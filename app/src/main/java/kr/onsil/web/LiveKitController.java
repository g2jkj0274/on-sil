package kr.onsil.web;

import kr.onsil.domain.Membership;
import kr.onsil.live.LiveKitService;
import kr.onsil.live.LiveKitService.RoomKind;
import kr.onsil.live.LiveStateService;
import kr.onsil.security.AppUser;
import kr.onsil.service.ClassService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 브라우저가 LiveKit 방에 들어갈 때 쓰는 토큰. */
@RestController
@RequestMapping("/api/classes/{classId}/livekit")
public class LiveKitController {
    private final ClassService classService;
    private final LiveStateService state;
    private final LiveKitService liveKit;

    public LiveKitController(ClassService classService, LiveStateService state, LiveKitService liveKit) {
        this.classService = classService;
        this.state = state;
        this.liveKit = liveKit;
    }

    /** room: live | monitor */
    @GetMapping("/{room}")
    public LiveKitService.Ticket ticket(@AuthenticationPrincipal AppUser me, @PathVariable Long classId, @PathVariable String room) {
        RoomKind kind = switch (room) {
            case "live" -> RoomKind.LIVE;
            case "monitor" -> RoomKind.MONITOR;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "알 수 없는 방입니다.");
        };
        Membership m = classService.requireMember(classId, me.getId());
        return liveKit.ticket(classId, kind, me.getUsername(), me.getDisplayName(), m.getRole(),
                state.isMicAllowed(classId, me.getId()));
    }

    /** 마이크가 허용됐는데 LiveKit 쪽 권한이 아직 없을 때(허용 순간에 방에 없었던 경우) 다시 반영한다. */
    @PostMapping("/mic-sync")
    public void micSync(@AuthenticationPrincipal AppUser me, @PathVariable Long classId) {
        classService.requireMember(classId, me.getId());
        if (state.isMicAllowed(classId, me.getId())) liveKit.apply(classId, me.getUsername(), true);
    }
}
