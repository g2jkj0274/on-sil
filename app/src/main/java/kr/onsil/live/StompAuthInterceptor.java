package kr.onsil.live;

import kr.onsil.security.AppUser;
import kr.onsil.service.ClassService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로그인하지 않은 연결을 막고, 구독(SUBSCRIBE)할 때 그 클래스 멤버인지·강사/조교인지 검사한다.
 * 학생이 /topic/class/{id}/staff 를 구독하려 하면 거부된다.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {
    private static final Pattern CLASS_TOPIC = Pattern.compile("^/topic/class/(\\d+)/(public|staff)$");

    private final ClassService classService;

    public StompAuthInterceptor(ClassService classService) { this.classService = classService; }

    public static AppUser appUser(Principal principal) {
        if (principal instanceof Authentication a && a.getPrincipal() instanceof AppUser u) return u;
        return null;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (acc == null || acc.getCommand() == null) return message;
        AppUser user = appUser(acc.getUser());
        StompCommand cmd = acc.getCommand();
        if ((cmd == StompCommand.CONNECT || cmd == StompCommand.SUBSCRIBE || cmd == StompCommand.SEND) && user == null) {
            throw new MessageDeliveryException("로그인이 필요합니다.");
        }
        if (cmd == StompCommand.SUBSCRIBE) {
            String dest = acc.getDestination() == null ? "" : acc.getDestination();
            if (dest.startsWith("/user/queue/")) return message;
            Matcher m = CLASS_TOPIC.matcher(dest);
            if (!m.matches()) throw new MessageDeliveryException("구독할 수 없는 주소입니다: " + dest);
            Long classId = Long.valueOf(m.group(1));
            if ("staff".equals(m.group(2))) classService.requireStaff(classId, user.getId());
            else classService.requireMember(classId, user.getId());
        }
        return message;
    }
}
