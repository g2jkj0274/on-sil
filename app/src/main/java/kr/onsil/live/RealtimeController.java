package kr.onsil.live;

import kr.onsil.domain.ChatMessage;
import kr.onsil.domain.ClassRole;
import kr.onsil.domain.Membership;
import kr.onsil.repo.ChatMessageRepository;
import kr.onsil.repo.UserRepository;
import kr.onsil.security.AppUser;
import kr.onsil.service.ClassService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;

/** 브라우저가 STOMP 로 보내는 실시간 요청(채팅, 손들기, 화면 공유 요청 등)을 처리한다. */
@Controller
public class RealtimeController {
    private static final Logger log = LoggerFactory.getLogger(RealtimeController.class);

    public record ChatDto(String type, Long id, Long userId, String displayName, ClassRole role, String text, Instant at) {}
    public record TextPayload(String text) {}
    public record OnPayload(boolean on) {}
    public record TargetPayload(Long userId) {}
    public record MicPayload(Long userId, boolean allow) {}

    private final ClassService classService;
    private final LiveStateService state;
    private final ChatMessageRepository chats;
    private final UserRepository users;
    private final SimpMessagingTemplate messaging;
    private final MicPermissionHandler micHandler;

    public RealtimeController(ClassService classService, LiveStateService state, ChatMessageRepository chats,
                              UserRepository users, SimpMessagingTemplate messaging, MicPermissionHandler micHandler) {
        this.classService = classService;
        this.state = state;
        this.chats = chats;
        this.users = users;
        this.messaging = messaging;
        this.micHandler = micHandler;
    }

    private static AppUser me(Principal p) { return StompAuthInterceptor.appUser(p); }

    @MessageMapping("/class/{classId}/join")
    public void join(@DestinationVariable Long classId, Principal principal, SimpMessageHeaderAccessor headers) {
        AppUser me = me(principal);
        Membership m = classService.requireMember(classId, me.getId());
        state.join(classId, headers.getSessionId(), me.getId(), me.getUsername(), me.getDisplayName(), m.getRole());
    }

    @MessageMapping("/class/{classId}/leave")
    public void leave(@DestinationVariable Long classId, SimpMessageHeaderAccessor headers) {
        state.leave(headers.getSessionId());
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent e) { state.leave(e.getSessionId()); }

    @MessageMapping("/class/{classId}/chat")
    public void chat(@DestinationVariable Long classId, @Payload TextPayload payload, Principal principal) {
        AppUser me = me(principal);
        Membership m = classService.requireMember(classId, me.getId());
        String text = payload.text() == null ? "" : payload.text().strip();
        if (text.isEmpty()) return;
        if (text.length() > 500) text = text.substring(0, 500);
        ChatMessage saved = chats.save(new ChatMessage(m.getClassroom(), users.getReferenceById(me.getId()), text));
        messaging.convertAndSend(LiveStateService.publicTopic(classId), new ChatDto("chat", saved.getId(),
                me.getId(), me.getDisplayName(), m.getRole(), saved.getText(), saved.getCreatedAt()));
    }

    /** 학생: 손들기 / 내리기 */
    @MessageMapping("/class/{classId}/hand")
    public void hand(@DestinationVariable Long classId, @Payload OnPayload payload, Principal principal) {
        AppUser me = me(principal);
        classService.requireMember(classId, me.getId());
        state.setHand(classId, me.getId(), payload.on());
    }

    /** 강사: 손든 학생의 마이크 허용 / 회수 */
    @MessageMapping("/class/{classId}/mic")
    public void mic(@DestinationVariable Long classId, @Payload MicPayload payload, Principal principal) {
        AppUser me = me(principal);
        classService.requireRole(classId, me.getId(), ClassRole.TEACHER);
        classService.requireMember(classId, payload.userId());
        state.setMicAllowed(classId, payload.userId(), payload.allow());
        String username = state.usernameOf(classId, payload.userId());
        if (username != null) {
            micHandler.apply(classId, username, payload.allow());
            sendEvent(username, Map.of("type", "mic", "classId", classId, "allow", payload.allow()));
        }
    }

    /** 강사·조교: 학생에게 화면 공유 요청 (userId 가 없으면 접속한 학생 전체) */
    @MessageMapping("/class/{classId}/share-request")
    public void shareRequest(@DestinationVariable Long classId, @Payload TargetPayload payload, Principal principal) {
        AppUser me = me(principal);
        classService.requireStaff(classId, me.getId());
        Map<String, Object> event = Map.of("type", "share-request", "classId", classId, "from", me.getDisplayName());
        if (payload.userId() == null) {
            for (String username : state.onlineStudents(classId)) sendEvent(username, event);
        } else {
            Membership target = classService.requireMember(classId, payload.userId());
            if (target.getRole() != ClassRole.STUDENT) return;
            String username = state.usernameOf(classId, payload.userId());
            if (username != null) sendEvent(username, event);
        }
    }

    /** 학생: 내 화면 공유 시작/중지를 알림 */
    @MessageMapping("/class/{classId}/sharing")
    public void sharing(@DestinationVariable Long classId, @Payload OnPayload payload, Principal principal) {
        AppUser me = me(principal);
        Membership m = classService.requireMember(classId, me.getId());
        if (m.getRole() != ClassRole.STUDENT) return;
        state.setSharing(classId, me.getId(), payload.on());
    }

    /** 학생: 막혔어요 켜기/끄기 */
    @MessageMapping("/class/{classId}/stuck")
    public void stuck(@DestinationVariable Long classId, @Payload OnPayload payload, Principal principal) {
        AppUser me = me(principal);
        Membership m = classService.requireMember(classId, me.getId());
        if (m.getRole() != ClassRole.STUDENT) return;
        state.setStuck(classId, me.getId(), payload.on());
    }

    /** 강사·조교: 학생의 "막혔어요" 해제 */
    @MessageMapping("/class/{classId}/stuck-clear")
    public void stuckClear(@DestinationVariable Long classId, @Payload TargetPayload payload, Principal principal) {
        AppUser me = me(principal);
        classService.requireStaff(classId, me.getId());
        state.setStuck(classId, payload.userId(), false);
        String username = state.usernameOf(classId, payload.userId());
        if (username != null) sendEvent(username, Map.of("type", "stuck-cleared", "classId", classId, "by", me.getDisplayName()));
    }

    /** 강사: 방송 시작/종료 알림 */
    @MessageMapping("/class/{classId}/broadcast")
    public void broadcast(@DestinationVariable Long classId, @Payload OnPayload payload, Principal principal) {
        AppUser me = me(principal);
        classService.requireRole(classId, me.getId(), ClassRole.TEACHER);
        state.setBroadcasting(classId, payload.on());
    }

    /** 강사: 녹화 시작/종료 알림 */
    @MessageMapping("/class/{classId}/recording")
    public void recording(@DestinationVariable Long classId, @Payload OnPayload payload, Principal principal) {
        AppUser me = me(principal);
        classService.requireRole(classId, me.getId(), ClassRole.TEACHER);
        state.setRecording(classId, payload.on());
    }

    private void sendEvent(String username, Object event) {
        messaging.convertAndSendToUser(username, "/queue/events", event);
    }

    @MessageExceptionHandler
    public void onError(Exception e, Principal principal) {
        String msg = e instanceof ResponseStatusException rse ? rse.getReason() : "요청을 처리하지 못했습니다.";
        if (!(e instanceof ResponseStatusException)) log.warn("STOMP 처리 오류", e);
        if (principal != null) {
            messaging.convertAndSendToUser(principal.getName(), "/queue/events", Map.of("type", "error", "message", String.valueOf(msg)));
        }
    }
}
