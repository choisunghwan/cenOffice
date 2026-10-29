package com.cenoffice.common.seed;

import com.cenoffice.common.config.AppProperties;
import com.cenoffice.common.domain.Device;
import com.cenoffice.common.domain.Enums.AdapterType;
import com.cenoffice.common.domain.Enums.DeviceType;
import com.cenoffice.common.domain.Enums.Role;
import com.cenoffice.common.domain.MeetingRoom;
import com.cenoffice.common.domain.UserAccount;
import com.cenoffice.common.repo.DeviceRepository;
import com.cenoffice.common.repo.MeetingRoomRepository;
import com.cenoffice.common.repo.UserRepository;
import com.cenoffice.office.chat.ChatEntities;
import com.cenoffice.office.chat.ChatRoom;
import com.cenoffice.office.chat.ChatRoomRepository;
import com.cenoffice.office.chat.Office;
import com.cenoffice.office.chat.OfficeRepository;
import com.cenoffice.office.agent.AiAgent;
import com.cenoffice.office.agent.AiAgentRepository;
import com.cenoffice.office.agent.WsEnums.AgentRole;
import com.cenoffice.office.agent.WsEnums.Environment;
import java.util.Set;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 최초 기동 시 기본 계정/장비/회의실을 만든다. 비밀번호는 환경변수, 없으면 임의 생성 후 로그에 한 번만 출력한다. */
@Component
public class DataSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository users;
    private final DeviceRepository devices;
    private final MeetingRoomRepository rooms;
    private final AiAgentRepository agents;
    private final OfficeRepository offices;
    private final ChatRoomRepository chatRooms;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public DataSeeder(UserRepository users, DeviceRepository devices, MeetingRoomRepository rooms, AiAgentRepository agents, OfficeRepository offices, ChatRoomRepository chatRooms, PasswordEncoder encoder, AppProperties props) {
        this.users = users;
        this.devices = devices;
        this.rooms = rooms;
        this.agents = agents;
        this.offices = offices;
        this.chatRooms = chatRooms;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        seedUser("admin", "관리자", Role.ADMIN, props.seed().adminPassword(), "경영지원팀");
        seedUser("user", "일반 사용자", Role.USER, props.seed().userPassword(), "개발팀");

        if (devices.findByCode("light-main").isEmpty())
            devices.save(new Device("light-main", "메인 오피스 조명", DeviceType.LIGHT, AdapterType.MOCK, "light"));
        if (devices.findByCode("ac-main").isEmpty())
            devices.save(new Device("ac-main", "메인 에어컨", DeviceType.AIR_CONDITIONER, AdapterType.MOCK, "ac"));

        // AI 에이전트: 운영(PROD) 환경 권한은 어떤 에이전트에도 부여하지 않는다.
        seedAgent("ari", "아리 (AI PM)", AgentRole.PM, Set.of(Environment.DEV, Environment.STG));
        seedAgent("dev-bot", "데브 (AI 개발자)", AgentRole.DEV, Set.of(Environment.DEV));
        seedAgent("qa-bot", "큐에이 (AI 테스터)", AgentRole.QA, Set.of(Environment.DEV, Environment.STG));

        Office office = offices.findAll().stream().findFirst().orElseGet(() -> offices.save(new Office("본사 오피스")));
        String officeKey = "office:" + office.getId();
        if (chatRooms.findByRoomKey(officeKey).isEmpty())
            chatRooms.save(new ChatRoom(ChatEntities.RoomType.OFFICE, office.getId(), officeKey, "전체 채팅", null));

        if (rooms.count() == 0) {
            rooms.save(new MeetingRoom("회의실 A", 8));
            rooms.save(new MeetingRoom("회의실 B", 4));
        }
    }

    private void seedAgent(String code, String name, AgentRole role, Set<Environment> envs) {
        if (agents.findByCode(code).isEmpty()) agents.save(new AiAgent(code, name, role, envs));
    }

    private void seedUser(String username, String display, Role role, String configured, String team) {
        var existing = users.findByUsername(username);
        if (existing.isPresent()) {
            // 개발용: OFFICE_SYNC_PASSWORDS=true 이면 설정된 비밀번호로 기존 계정을 맞춘다 (운영에서는 켜지 말 것)
            UserAccount u = existing.get();
            if (u.getTeam() == null) { u.setTeam(team); users.save(u); }
            if (props.seed().syncPasswords() && configured != null && !configured.isBlank() && !encoder.matches(configured, u.getPasswordHash())) {
                u.changePasswordHash(encoder.encode(configured));
                users.save(u);
                log.warn("계정 '{}' 의 비밀번호를 환경변수 값으로 동기화했습니다 (개발용 옵션).", username);
            }
            return;
        }
        String pw = configured;
        boolean generated = pw == null || pw.isBlank();
        if (generated) pw = randomPassword();
        UserAccount created = new UserAccount(username, encoder.encode(pw), display, role);
        created.setTeam(team);
        users.save(created);
        if (generated) log.warn("계정 '{}' 의 비밀번호가 설정되지 않아 임의 생성되었습니다 (이번 한 번만 표시): {}", username, pw);
    }

    private static String randomPassword() {
        String chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }
}
