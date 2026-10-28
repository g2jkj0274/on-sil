package kr.onsil.live;

/** 마이크 허용/회수를 미디어 서버에 반영한다 (3단계에서 LiveKit 으로 구현). */
public interface MicPermissionHandler {
    void apply(Long classId, String username, boolean allow);
}
