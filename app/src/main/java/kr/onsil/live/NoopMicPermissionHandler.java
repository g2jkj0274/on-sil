package kr.onsil.live;

import org.springframework.stereotype.Component;

/** 임시: LiveKit 연동 전까지 아무것도 하지 않는다. */
@Component
public class NoopMicPermissionHandler implements MicPermissionHandler {
    @Override public void apply(Long classId, String username, boolean allow) {}
}
