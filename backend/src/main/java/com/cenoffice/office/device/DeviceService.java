package com.cenoffice.office.device;

import com.cenoffice.common.api.ApiException;
import com.cenoffice.common.api.AuditService;
import com.cenoffice.common.config.AppProperties;
import com.cenoffice.office.device.DeviceTypes.*;
import com.cenoffice.common.domain.Device;
import com.cenoffice.common.domain.DeviceCommand;
import com.cenoffice.common.domain.DeviceState;
import com.cenoffice.common.domain.Enums.CommandStatus;
import com.cenoffice.common.domain.Enums.DeviceType;
import com.cenoffice.common.repo.DeviceCommandRepository;
import com.cenoffice.common.repo.DeviceRepository;
import com.cenoffice.common.repo.DeviceStateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장비 명령/상태 관리.
 * 상태(DeviceState)는 장비가 보고한 값(confirm / reportState)으로만 바뀐다 — 명령 접수·전송은 상태를 바꾸지 않는다.
 */
@Service
public class DeviceService {
    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepository devices;
    private final DeviceStateRepository states;
    private final DeviceCommandRepository commands;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final AppProperties props;

    public DeviceService(DeviceRepository devices, DeviceStateRepository states, DeviceCommandRepository commands,
                         AuditService audit, ApplicationEventPublisher events, AppProperties props) {
        this.devices = devices;
        this.states = states;
        this.commands = commands;
        this.audit = audit;
        this.events = events;
        this.props = props;
    }

    // ── 조회 ────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<DeviceView> list() {
        return devices.findAll().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public DeviceView get(Long id) { return view(find(id)); }

    @Transactional(readOnly = true)
    public List<CommandView> history(Long deviceId, int limit) {
        Device d = find(deviceId);
        return commands.findByDeviceIdOrderByRequestedAtDesc(deviceId, PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
                .stream().map(c -> view(c, d.getCode())).toList();
    }

    @Transactional(readOnly = true)
    public CommandView command(UUID id) {
        DeviceCommand c = commands.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "명령을 찾을 수 없습니다"));
        return view(c, find(c.getDeviceId()).getCode());
    }

    // ── 명령 접수 ────────────────────────────────────────────
    @Transactional
    public CommandView submit(Long deviceId, CommandRequest req, String actor) {
        Device d = find(deviceId);
        validate(d, req);
        // 같은 장비에 처리 중인 명령이 있으면 중복 실행을 막는다 (더블 클릭/재전송 방지)
        if (commands.existsByDeviceIdAndStatusIn(deviceId, Set.of(CommandStatus.REQUESTED, CommandStatus.SENT)))
            throw new ApiException(HttpStatus.CONFLICT, "이전 명령이 아직 처리 중입니다. 잠시 후 다시 시도하세요");
        DeviceCommand c = commands.save(new DeviceCommand(deviceId, req.power(), req.targetTemp(), req.mode(), actor));
        audit.log(actor, "DEVICE_COMMAND_REQUESTED", d.getCode(),
                "power=" + req.power() + ", targetTemp=" + req.targetTemp() + ", mode=" + req.mode() + ", cmd=" + c.getId());
        CommandView v = view(c, d.getCode());
        events.publishEvent(new OfficeEvent("command.update", v));
        return v;
    }

    private void validate(Device d, CommandRequest r) {
        if (d.getType() == DeviceType.LIGHT) {
            if (r.power() == null || r.targetTemp() != null || r.mode() != null)
                throw new ApiException(HttpStatus.BAD_REQUEST, "조명은 power 값만 제어할 수 있습니다");
        } else if (r.power() == null && r.targetTemp() == null && r.mode() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "변경할 값이 없습니다");
        }
    }

    // ── 어댑터 콜백 (비동기 스레드에서 호출됨) ─────────────────
    @Transactional
    public void markSent(UUID id) {
        commands.findById(id).filter(c -> c.getStatus() == CommandStatus.REQUESTED).ifPresent(c -> {
            c.markSent();
            publishCommand(c);
        });
    }

    /** 장비가 상태를 확인해 주었을 때만 CONFIRMED. 이미 TIMEOUT인 경우에도 장비의 실제 상태는 반영한다. */
    @Transactional
    public void confirm(UUID id, DeviceStateSnapshot reported) {
        DeviceCommand c = commands.findById(id).orElse(null);
        if (c == null) return;
        if (c.isOpen()) {
            c.finish(CommandStatus.CONFIRMED, null);
            audit.log(c.getRequestedBy(), "DEVICE_COMMAND_CONFIRMED", String.valueOf(c.getDeviceId()), "cmd=" + c.getId());
            publishCommand(c);
        } else {
            log.warn("명령 {} 은(는) 이미 {} 상태에서 장비 응답이 도착했습니다. 장비 상태만 반영합니다.", id, c.getStatus());
        }
        applyState(c.getDeviceId(), reported);
    }

    @Transactional
    public void fail(UUID id, String reason) {
        commands.findById(id).filter(DeviceCommand::isOpen).ifPresent(c -> {
            c.finish(CommandStatus.FAILED, reason);
            audit.log(c.getRequestedBy(), "DEVICE_COMMAND_FAILED", String.valueOf(c.getDeviceId()), reason);
            publishCommand(c);
        });
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void expireStale() {
        Instant limit = Instant.now().minusSeconds(props.devices().commandTimeoutSeconds());
        for (DeviceCommand c : commands.findByStatusInAndRequestedAtBefore(Set.of(CommandStatus.REQUESTED, CommandStatus.SENT), limit)) {
            c.finish(CommandStatus.TIMEOUT, "장비 응답 시간 초과");
            audit.log(c.getRequestedBy(), "DEVICE_COMMAND_TIMEOUT", String.valueOf(c.getDeviceId()), "cmd=" + c.getId());
            publishCommand(c);
        }
    }

    // ── 장비 상태 보고 ───────────────────────────────────────
    @EventListener
    @Transactional
    public void onReport(DeviceReport r) { applyState(r.deviceId(), r.state()); }

    @Transactional
    public void reportState(Long deviceId, DeviceStateSnapshot s) { applyState(deviceId, s); }

    private void applyState(Long deviceId, DeviceStateSnapshot s) {
        DeviceState st = states.findById(deviceId).orElseGet(() -> new DeviceState(deviceId));
        st.apply(s.power(), s.targetTemp(), s.currentTemp(), s.mode(), s.online());
        states.save(st);
        events.publishEvent(new OfficeEvent("device.state", view(find(deviceId))));
    }

    // ── 내부 ────────────────────────────────────────────────
    private void publishCommand(DeviceCommand c) {
        events.publishEvent(new OfficeEvent("command.update", view(c, find(c.getDeviceId()).getCode())));
    }

    private Device find(Long id) {
        return devices.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "장비를 찾을 수 없습니다"));
    }

    private DeviceView view(Device d) {
        DeviceState s = states.findById(d.getId()).orElse(null);
        StateView sv = s == null ? new StateView(false, null, null, null, false, null)
                : new StateView(s.isPower(), s.getTargetTemp(), s.getCurrentTemp(), s.getMode(), s.isOnline(), s.getUpdatedAt());
        return new DeviceView(d.getId(), d.getCode(), d.getName(), d.getType(), d.getAdapterType(), d.getObjectId(), sv);
    }

    private CommandView view(DeviceCommand c, String deviceCode) {
        return new CommandView(c.getId(), c.getDeviceId(), deviceCode, c.getStatus(), c.getPower(), c.getTargetTemp(),
                c.getMode(), c.getRequestedBy(), c.getRequestedAt(), c.getCompletedAt(), c.getError());
    }
}
