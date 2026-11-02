package kr.onsil.live;

import io.livekit.server.*;
import kr.onsil.domain.ClassRole;
import livekit.LivekitModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * LiveKit 토큰 발급과 참가자 권한 변경.
 * 클래스마다 방이 2개다.
 * - live: 강사가 화면·마이크를 송출하고 모두가 구독한다. 학생 마이크는 강사가 허용했을 때만.
 * - monitor: 학생은 화면 공유만 송출(구독 불가), 강사·조교는 구독만 한다.
 */
@Service
public class LiveKitService implements MicPermissionHandler {
    private static final Logger log = LoggerFactory.getLogger(LiveKitService.class);

    public enum RoomKind { LIVE, MONITOR }

    public record Ticket(String url, String room, String identity, String token) {}

    private final String wsUrl;
    private final String apiKey;
    private final String apiSecret;
    private final RoomServiceClient rooms;

    public LiveKitService(@Value("${onsil.livekit.ws-url}") String wsUrl,
                          @Value("${onsil.livekit.api-url}") String apiUrl,
                          @Value("${onsil.livekit.api-key}") String apiKey,
                          @Value("${onsil.livekit.api-secret}") String apiSecret) {
        this.wsUrl = wsUrl;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.rooms = RoomServiceClient.createClient(apiUrl, apiKey, apiSecret);
    }

    public static String roomName(Long classId, RoomKind kind) {
        return "class-" + classId + "-" + kind.name().toLowerCase();
    }

    /** 이 사용자가 해당 방에 들어갈 토큰. identity 는 username (로그인 아이디). */
    public Ticket ticket(Long classId, RoomKind kind, String username, String displayName, ClassRole role, boolean micAllowed) {
        String room = roomName(classId, kind);
        AccessToken token = new AccessToken(apiKey, apiSecret);
        token.setIdentity(username);
        token.setName(displayName);
        token.setMetadata("{\"role\":\"" + role.name() + "\"}");

        List<VideoGrant> grants = new ArrayList<>(List.of(new RoomJoin(true), new RoomName(room), new CanPublishData(false)));
        if (kind == RoomKind.LIVE) {
            grants.add(new CanSubscribe(true));
            if (role == ClassRole.TEACHER) {
                grants.add(new CanPublish(true));
                grants.add(new CanPublishSources(List.of("camera", "microphone", "screen_share", "screen_share_audio")));
            } else {
                grants.add(new CanPublish(micAllowed));
                grants.add(new CanPublishSources(List.of("microphone")));
            }
        } else if (role == ClassRole.STUDENT) {
            grants.add(new CanSubscribe(false));
            grants.add(new CanPublish(true));
            grants.add(new CanPublishSources(List.of("screen_share")));
        } else {
            // 강사·조교는 학생에게 보이지 않게 구독만
            grants.add(new CanSubscribe(true));
            grants.add(new CanPublish(false));
            grants.add(new Hidden(true));
        }
        token.addGrants(grants);
        return new Ticket(wsUrl, room, username, token.toJwt());
    }

    /** 강사의 마이크 허용/회수를 live 방에 이미 들어와 있는 학생에게 바로 반영한다. */
    @Override
    public void apply(Long classId, String username, boolean allow) {
        LivekitModels.ParticipantPermission perm = LivekitModels.ParticipantPermission.newBuilder()
                .setCanSubscribe(true)
                .setCanPublish(allow)
                .setCanPublishData(false)
                .addCanPublishSources(LivekitModels.TrackSource.MICROPHONE)
                .build();
        String room = roomName(classId, RoomKind.LIVE);
        rooms.updateParticipant(room, username, null, null, perm).enqueue(new Callback<>() {
            @Override public void onResponse(Call<LivekitModels.ParticipantInfo> call, Response<LivekitModels.ParticipantInfo> res) {
                // 아직 방에 안 들어왔으면 404: 들어올 때 받는 토큰에 권한이 들어가므로 괜찮다
                if (!res.isSuccessful() && res.code() != 404) log.warn("마이크 권한 변경 실패 {} {}: HTTP {}", room, username, res.code());
            }
            @Override public void onFailure(Call<LivekitModels.ParticipantInfo> call, Throwable t) {
                log.warn("마이크 권한 변경 실패 {} {}: {}", room, username, t.toString());
            }
        });
    }
}
