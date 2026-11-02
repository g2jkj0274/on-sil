package kr.onsil.live;

/** 마이크 허용/회수를 미디어 서버에 반영한다 (LiveKitService). */
public interface MicPermissionHandler {
    void apply(Long classId, String username, boolean allow);
}
