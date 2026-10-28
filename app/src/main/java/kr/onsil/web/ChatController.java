package kr.onsil.web;

import kr.onsil.live.RealtimeController.ChatDto;
import kr.onsil.repo.ChatMessageRepository;
import kr.onsil.repo.MembershipRepository;
import kr.onsil.security.AppUser;
import kr.onsil.service.ClassService;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 강의실에 들어올 때 지난 채팅 50개를 불러온다. */
@RestController
public class ChatController {
    private final ClassService classService;
    private final ChatMessageRepository chats;
    private final MembershipRepository memberships;

    public ChatController(ClassService classService, ChatMessageRepository chats, MembershipRepository memberships) {
        this.classService = classService;
        this.chats = chats;
        this.memberships = memberships;
    }

    @GetMapping("/api/classes/{classId}/chat")
    @Transactional(readOnly = true)
    public List<ChatDto> recent(@AuthenticationPrincipal AppUser me, @PathVariable Long classId) {
        classService.requireMember(classId, me.getId());
        List<ChatDto> list = new ArrayList<>();
        for (var m : chats.findRecent(classId, PageRequest.of(0, 50))) {
            var role = memberships.findByClassroomIdAndUserId(classId, m.getUser().getId()).map(x -> x.getRole()).orElse(null);
            list.add(new ChatDto("chat", m.getId(), m.getUser().getId(), m.getUser().getDisplayName(), role, m.getText(), m.getCreatedAt()));
        }
        Collections.reverse(list);
        return list;
    }
}
