package kr.onsil.live;

import kr.onsil.domain.ClassRole;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 수업 중의 실시간 상태(메모리에만 보관, 서버를 끄면 사라짐).
 * 상태가 바뀔 때마다 클래스 전체용(public)과 강사·조교용(staff) 요약을 STOMP 로 다시 보낸다.
 */
@Service
public class LiveStateService {

    public record OnlineUser(Long userId, String username, String displayName, ClassRole role,
                             boolean hand, boolean micAllowed) {}

    /** 클래스 멤버 모두에게 보내는 상태. */
    public record PublicState(String type, Long classId, List<OnlineUser> online,
                              boolean broadcasting, boolean recording) {}

    /** 강사·조교만 받는 상태: 화면 공유 중인 학생과 "막혔어요" 학생(먼저 누른 순). */
    public record StaffState(String type, Long classId, List<Long> sharing, List<StuckEntry> stuck) {}
    public record StuckEntry(Long userId, Instant since) {}

    private static final class Presence {
        final Long userId; final String username; final String displayName; final ClassRole role;
        final Set<String> sessions = new HashSet<>();
        Presence(Long userId, String username, String displayName, ClassRole role) {
            this.userId = userId; this.username = username; this.displayName = displayName; this.role = role;
        }
    }

    private static final class ClassLive {
        final Map<Long, Presence> online = new LinkedHashMap<>();
        final Set<Long> hands = new LinkedHashSet<>();
        final Set<Long> micAllowed = new HashSet<>();
        final Set<Long> sharing = new LinkedHashSet<>();
        final Map<Long, Instant> stuck = new LinkedHashMap<>();
        boolean broadcasting;
        boolean recording;
    }

    private final Map<Long, ClassLive> classes = new ConcurrentHashMap<>();
    /** STOMP 세션 → 들어가 있는 클래스 (연결이 끊기면 정리하기 위해). */
    private final Map<String, Long> sessionClass = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messaging;

    public LiveStateService(SimpMessagingTemplate messaging) { this.messaging = messaging; }

    private ClassLive live(Long classId) { return classes.computeIfAbsent(classId, k -> new ClassLive()); }

    public void join(Long classId, String sessionId, Long userId, String username, String displayName, ClassRole role) {
        Long prev = sessionClass.put(sessionId, classId);
        if (prev != null && !prev.equals(classId)) removeSession(prev, sessionId);
        ClassLive c = live(classId);
        synchronized (c) {
            Presence p = c.online.get(userId);
            if (p == null || p.role != role) {
                Presence np = new Presence(userId, username, displayName, role);
                if (p != null) np.sessions.addAll(p.sessions);
                c.online.put(userId, np);
                p = np;
            }
            p.sessions.add(sessionId);
        }
        publish(classId);
    }

    public void leave(String sessionId) {
        Long classId = sessionClass.remove(sessionId);
        if (classId != null) removeSession(classId, sessionId);
    }

    private void removeSession(Long classId, String sessionId) {
        ClassLive c = live(classId);
        synchronized (c) {
            Iterator<Presence> it = c.online.values().iterator();
            while (it.hasNext()) {
                Presence p = it.next();
                if (p.sessions.remove(sessionId) && p.sessions.isEmpty()) {
                    it.remove();
                    // 완전히 나가면 손들기·공유·막혔어요도 정리 (마이크 허용은 유지하지 않음)
                    c.hands.remove(p.userId);
                    c.sharing.remove(p.userId);
                    c.stuck.remove(p.userId);
                    c.micAllowed.remove(p.userId);
                    if (p.role == ClassRole.TEACHER) { c.broadcasting = false; c.recording = false; }
                }
            }
        }
        publish(classId);
    }

    public boolean isOnline(Long classId, Long userId) {
        ClassLive c = live(classId);
        synchronized (c) { return c.online.containsKey(userId); }
    }

    public String usernameOf(Long classId, Long userId) {
        ClassLive c = live(classId);
        synchronized (c) { Presence p = c.online.get(userId); return p == null ? null : p.username; }
    }

    /** 접속 중인 학생들의 username. */
    public List<String> onlineStudents(Long classId) {
        ClassLive c = live(classId);
        synchronized (c) {
            return c.online.values().stream().filter(p -> p.role == ClassRole.STUDENT).map(p -> p.username).toList();
        }
    }

    public void setHand(Long classId, Long userId, boolean up) {
        ClassLive c = live(classId);
        synchronized (c) { if (up) c.hands.add(userId); else c.hands.remove(userId); }
        publish(classId);
    }

    public void setMicAllowed(Long classId, Long userId, boolean allowed) {
        ClassLive c = live(classId);
        synchronized (c) {
            if (allowed) { c.micAllowed.add(userId); c.hands.remove(userId); } else c.micAllowed.remove(userId);
        }
        publish(classId);
    }

    public boolean isMicAllowed(Long classId, Long userId) {
        ClassLive c = live(classId);
        synchronized (c) { return c.micAllowed.contains(userId); }
    }

    public void setSharing(Long classId, Long userId, boolean on) {
        ClassLive c = live(classId);
        synchronized (c) { if (on) c.sharing.add(userId); else c.sharing.remove(userId); }
        publish(classId);
    }

    public void setStuck(Long classId, Long userId, boolean on) {
        ClassLive c = live(classId);
        synchronized (c) { if (on) c.stuck.putIfAbsent(userId, Instant.now()); else c.stuck.remove(userId); }
        publish(classId);
    }

    public void setBroadcasting(Long classId, boolean on) {
        ClassLive c = live(classId);
        synchronized (c) { c.broadcasting = on; if (!on) c.recording = false; }
        publish(classId);
    }

    public void setRecording(Long classId, boolean on) {
        ClassLive c = live(classId);
        synchronized (c) { c.recording = on; }
        publish(classId);
    }

    /** 현재 상태를 구독자에게 다시 보낸다. */
    public void publish(Long classId) {
        PublicState pub;
        StaffState staff;
        ClassLive c = live(classId);
        synchronized (c) {
            List<OnlineUser> online = c.online.values().stream()
                    .sorted(Comparator.comparing((Presence p) -> p.role.ordinal()).thenComparing(p -> p.displayName))
                    .map(p -> new OnlineUser(p.userId, p.username, p.displayName, p.role,
                            c.hands.contains(p.userId), c.micAllowed.contains(p.userId)))
                    .toList();
            pub = new PublicState("state", classId, online, c.broadcasting, c.recording);
            staff = new StaffState("staffState", classId, List.copyOf(c.sharing),
                    c.stuck.entrySet().stream().map(e -> new StuckEntry(e.getKey(), e.getValue())).toList());
            // 잠금 안에서 보내야 오래된 스냅샷이 새 스냅샷보다 늦게 도착하지 않는다
            messaging.convertAndSend(publicTopic(classId), pub);
            messaging.convertAndSend(staffTopic(classId), staff);
        }
    }

    public static String publicTopic(Long classId) { return "/topic/class/" + classId + "/public"; }
    public static String staffTopic(Long classId) { return "/topic/class/" + classId + "/staff"; }
}
