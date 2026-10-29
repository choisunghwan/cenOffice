package com.cenoffice.office.chat;

import com.cenoffice.common.api.ApiException;
import com.cenoffice.common.api.AuditService;
import com.cenoffice.office.chat.ChatEntities.RoomType;
import com.cenoffice.office.chat.ChatEntities.SenderType;
import com.cenoffice.common.domain.UserAccount;
import com.cenoffice.common.repo.UserRepository;
import com.cenoffice.office.agent.AiAgent;
import com.cenoffice.office.agent.AiAgentRepository;
import com.cenoffice.office.ws.PresenceService;
import com.cenoffice.office.ws.WsEvents.UserEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 채팅방/메시지. 접근 권한(멤버십)은 매 요청마다 서버가 확인한다. */
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    public static final int MAX_LENGTH = 500;
    private static final int RATE_COUNT = 5;
    private static final Duration RATE_WINDOW = Duration.ofSeconds(5);

    public record RoomView(Long id, RoomType type, String name, String peerUsername, String peerDisplayName, String peerTeam,
                           boolean peerOnline, String agentCode) {}

    public record MessageView(Long id, Long roomId, SenderType senderType, String sender, String senderName, String content, Instant createdAt) {}

    public record PersonView(String username, String displayName, String team, boolean online) {}

    private final ChatRoomRepository rooms;
    private final ChatRoomMemberRepository members;
    private final ChatMessageRepository messages;
    private final OfficeRepository offices;
    private final UserRepository users;
    private final AiAgentRepository agents;
    private final AiChatResponder responder;
    private final PresenceService presence;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    private final Map<String, Deque<Instant>> sendLog = new ConcurrentHashMap<>();
    private final ExecutorService aiExec = Executors.newVirtualThreadPerTaskExecutor();

    public ChatService(ChatRoomRepository rooms, ChatRoomMemberRepository members, ChatMessageRepository messages, OfficeRepository offices,
                       UserRepository users, AiAgentRepository agents, AiChatResponder responder, PresenceService presence,
                       AuditService audit, ApplicationEventPublisher events) {
        this.rooms = rooms;
        this.members = members;
        this.messages = messages;
        this.offices = offices;
        this.users = users;
        this.agents = agents;
        this.responder = responder;
        this.presence = presence;
        this.audit = audit;
        this.events = events;
    }

    // ───────── 조회 ─────────
    @Transactional(readOnly = true)
    public List<PersonView> people() {
        return users.findAll().stream()
                .map(u -> new PersonView(u.getUsername(), u.getDisplayName(), u.getTeam() == null ? "" : u.getTeam(), presence.isOnline(u.getUsername())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoomView> myRooms(String me) {
        List<RoomView> out = new ArrayList<>();
        Long officeId = offices.findAll().stream().findFirst().map(Office::getId).orElse(null);
        if (officeId != null) rooms.findByRoomKey("office:" + officeId).ifPresent(r -> out.add(view(r, me)));
        for (ChatRoomMember m : members.findByUsername(me)) rooms.findById(m.getRoomId()).ifPresent(r -> out.add(view(r, me)));
        return out;
    }

    @Transactional(readOnly = true)
    public List<MessageView> history(String me, Long roomId, Long beforeId, int limit) {
        requireAccess(me, roomId);
        List<ChatMessage> page = new ArrayList<>(messages.page(roomId, beforeId, PageRequest.of(0, Math.min(Math.max(limit, 1), 100))));
        Collections.reverse(page);
        return page.stream().map(ChatService::view).toList();
    }

    // ───────── 방 생성 ─────────
    @Transactional
    public RoomView directRoom(String me, String other) {
        if (me.equals(other)) throw new ApiException(HttpStatus.BAD_REQUEST, "자기 자신과는 대화할 수 없습니다");
        users.findByUsername(other).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "대화 상대를 찾을 수 없습니다"));
        String a = me.compareTo(other) < 0 ? me : other, b = me.compareTo(other) < 0 ? other : me;
        String key = "dm:" + a + "|" + b;
        ChatRoom room = rooms.findByRoomKey(key).orElse(null);
        if (room == null) {
            room = rooms.save(new ChatRoom(RoomType.DIRECT, officeId(), key, null, null));
            members.save(new ChatRoomMember(room.getId(), me));
            members.save(new ChatRoomMember(room.getId(), other));
            audit.log(me, "CHAT_ROOM_CREATED", "room:" + room.getId(), "direct with " + other);
        }
        return view(room, me);
    }

    @Transactional
    public RoomView aiRoom(String me, String agentCode) {
        AiAgent agent = agents.findByCode(agentCode).filter(AiAgent::isEnabled)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AI 직원을 찾을 수 없습니다"));
        String key = "ai:" + agent.getCode() + "|" + me;
        ChatRoom room = rooms.findByRoomKey(key).orElse(null);
        if (room == null) {
            room = rooms.save(new ChatRoom(RoomType.AI, officeId(), key, agent.getName(), agent.getCode()));
            members.save(new ChatRoomMember(room.getId(), me));
            audit.log(me, "CHAT_ROOM_CREATED", "room:" + room.getId(), "ai " + agent.getCode());
        }
        return view(room, me);
    }

    // ───────── 전송 ─────────
    /** 메시지 저장 후 방 멤버에게만 실시간 전달한다. 발신자는 항상 인증된 사용자(me). */
    @Transactional
    public MessageView send(String me, Long roomId, String raw) {
        ChatRoom room = requireAccess(me, roomId);
        String content = sanitize(raw);
        throttle(me);
        UserAccount u = users.findByUsername(me).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다"));
        ChatMessage saved = messages.save(new ChatMessage(roomId, SenderType.USER, me, u.getDisplayName(), content));
        MessageView v = view(saved);
        events.publishEvent(UserEvent.to(recipients(room), "chat.message", v));
        if (room.getType() == RoomType.AI) {
            String agentCode = room.getAgentCode();
            aiExec.submit(() -> replyAsAgent(room.getId(), me, agentCode, content));
        }
        return v;
    }

    private void replyAsAgent(Long roomId, String me, String agentCode, String userText) {
        try {
            Thread.sleep(700);
            AiAgent agent = agents.findByCode(agentCode).orElse(null);
            if (agent == null) return;
            String reply = responder.reply(agent, userText);
            saveAgentMessage(roomId, me, agent, reply);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("AI 채팅 응답 실패: {}", e.getMessage());
        }
    }

    @Transactional
    public void saveAgentMessage(Long roomId, String toUser, AiAgent agent, String text) {
        ChatMessage saved = messages.save(new ChatMessage(roomId, SenderType.AI, agent.getCode(), agent.getName(), clip(text)));
        events.publishEvent(UserEvent.to(Set.of(toUser), "chat.message", view(saved)));
    }

    // ───────── 내부 ─────────
    private ChatRoom requireAccess(String me, Long roomId) {
        ChatRoom room = rooms.findById(roomId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다"));
        if (room.getType() == RoomType.OFFICE) return room; // 직원 전원 (인증은 API 레벨에서 보장)
        if (!members.existsByRoomIdAndUsername(roomId, me)) {
            audit.log(me, "CHAT_ACCESS_DENIED", "room:" + roomId, null);
            throw new ApiException(HttpStatus.FORBIDDEN, "이 대화에 참여한 사용자만 접근할 수 있습니다");
        }
        return room;
    }

    private Set<String> recipients(ChatRoom room) {
        if (room.getType() == RoomType.OFFICE) return null; // 접속한 전원
        return members.findByRoomId(room.getId()).stream().map(ChatRoomMember::getUsername).collect(java.util.stream.Collectors.toSet());
    }

    /** 제어 문자 제거, 공백 정리, 길이 검증. HTML 은 변환하지 않고 텍스트로만 렌더링한다. */
    static String sanitize(String raw) {
        if (raw == null) throw new ApiException(HttpStatus.BAD_REQUEST, "메시지를 입력하세요");
        String s = raw.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
        if (s.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "메시지를 입력하세요");
        if (s.codePointCount(0, s.length()) > MAX_LENGTH) throw new ApiException(HttpStatus.BAD_REQUEST, "메시지는 " + MAX_LENGTH + "자 이하여야 합니다");
        return s;
    }

    private static String clip(String s) { return s.length() > 1000 ? s.substring(0, 1000) : s; }

    private void throttle(String me) {
        Deque<Instant> q = sendLog.computeIfAbsent(me, k -> new ArrayDeque<>());
        synchronized (q) {
            Instant now = Instant.now(), cutoff = now.minus(RATE_WINDOW);
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
            if (q.size() >= RATE_COUNT) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "메시지를 너무 빠르게 보내고 있습니다. 잠시 후 다시 시도하세요");
            q.addLast(now);
        }
    }

    private Long officeId() { return offices.findAll().stream().findFirst().map(Office::getId).orElseThrow(); }

    private RoomView view(ChatRoom r, String me) {
        String peer = null, peerName = null, peerTeam = null;
        boolean online = false;
        if (r.getType() == RoomType.DIRECT) {
            peer = members.findByRoomId(r.getId()).stream().map(ChatRoomMember::getUsername).filter(x -> !x.equals(me)).findFirst().orElse(null);
            UserAccount p = peer == null ? null : users.findByUsername(peer).orElse(null);
            if (p != null) { peerName = p.getDisplayName(); peerTeam = p.getTeam(); online = presence.isOnline(peer); }
        }
        return new RoomView(r.getId(), r.getType(), r.getName(), peer, peerName, peerTeam, online, r.getAgentCode());
    }

    private static MessageView view(ChatMessage m) {
        return new MessageView(m.getId(), m.getRoomId(), m.getSenderType(), m.getSender(), m.getSenderName(), m.getContent(), m.getCreatedAt());
    }
}
