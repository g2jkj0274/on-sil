import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 3단계 테스트: LiveKit 토큰 발급, 시그널 접속, 강사의 마이크 허용/회수가 LiveKit 권한에 반영되는지. */
public class LiveKitProbe {
    static final String BASE = "http://127.0.0.1:8800";
    static final String LK = "http://127.0.0.1:7880";

    static void ok(boolean cond, String msg) { System.out.println((cond ? "PASS " : "FAIL ") + msg); }

    record Session(HttpClient http, String cookie) {}

    static Session login(String user) throws Exception {
        CookieManager cm = new CookieManager();
        HttpClient http = HttpClient.newBuilder().cookieHandler(cm).build();
        var res = http.send(HttpRequest.newBuilder(URI.create(BASE + "/api/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + user + "&password=1234")).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException(user + " login " + res.statusCode());
        String cookie = cm.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID"))
                .findFirst().orElseThrow().toString();
        return new Session(http, cookie);
    }

    static HttpResponse<String> req(Session s, String method, String path) throws Exception {
        return s.http.send(HttpRequest.newBuilder(URI.create(BASE + path)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static String field(String json, String name) {
        int i = json.indexOf("\"" + name + "\":\"");
        if (i < 0) return null;
        i += name.length() + 4;
        return json.substring(i, json.indexOf('"', i));
    }

    static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    static WebSocket stomp(Session s) throws Exception {
        WebSocket ws = s.http.newWebSocketBuilder().header("Cookie", s.cookie).header("Origin", BASE)
                .buildAsync(URI.create("ws://127.0.0.1:8800/ws"), new WebSocket.Listener() {}).get();
        ws.sendText("CONNECT\naccept-version:1.2\nhost:127.0.0.1\n\n\0", true).get();
        Thread.sleep(200);
        return ws;
    }

    static void app(WebSocket ws, String path, String json) throws Exception {
        ws.sendText("SEND\ndestination:/app/class/1/" + path + "\ncontent-type:application/json\n\n" + json + "\0", true).get();
    }

    /** LiveKit 관리 API 용 토큰 (devkey/secret, roomAdmin). */
    static String adminJwt(String room) throws Exception {
        long now = System.currentTimeMillis() / 1000;
        String h = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String p = b64("{\"iss\":\"devkey\",\"nbf\":" + now + ",\"exp\":" + (now + 600)
                + ",\"video\":{\"roomAdmin\":true,\"room\":\"" + room + "\"}}");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(), "HmacSHA256"));
        return h + "." + p + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((h + "." + p).getBytes()));
    }

    static String b64(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    static String participant(String room, String identity) throws Exception {
        var res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(LK + "/twirp/livekit.RoomService/GetParticipant"))
                .header("Authorization", "Bearer " + adminJwt(room)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"room\":\"" + room + "\",\"identity\":\"" + identity + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        return res.statusCode() + " " + res.body();
    }

    public static void main(String[] a) throws Exception {
        Session t = login("teacher1"), s1 = login("student1");

        // 토큰 내용
        String tt = req(t, "GET", "/api/classes/1/livekit/live").body();
        String st = req(s1, "GET", "/api/classes/1/livekit/live").body();
        String tc = claims(field(tt, "token")), sc = claims(field(st, "token"));
        ok(tc.contains("\"canPublish\":true") && tc.contains("screen_share") && tc.contains("\"room\":\"class-1-live\""), "teacher live token: " + tc);
        ok(sc.contains("\"canPublish\":false") && sc.contains("\"canSubscribe\":true"), "student live token: " + sc);
        String sm = claims(field(req(s1, "GET", "/api/classes/1/livekit/monitor").body(), "token"));
        ok(sm.contains("\"canSubscribe\":false") && sm.contains("screen_share") && !sm.contains("microphone"), "student monitor token: " + sm);
        String tm = claims(field(req(t, "GET", "/api/classes/1/livekit/monitor").body(), "token"));
        ok(tm.contains("\"hidden\":true") && tm.contains("\"canPublish\":false"), "teacher monitor token: " + tm);
        ok(req(t, "GET", "/api/classes/1/livekit/other").statusCode() == 404, "unknown room -> 404");
        Session out = login("teacher1");
        ok(req(out, "GET", "/api/classes/999/livekit/live").statusCode() == 403, "non-member class -> 403");

        // 학생이 LiveKit live 방에 시그널 접속
        BlockingQueue<String> lkEvents = new LinkedBlockingQueue<>();
        WebSocket lk = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:7880/rtc?access_token=" + field(st, "token") + "&protocol=15&auto_subscribe=1"),
                        new WebSocket.Listener() {
                            public CompletionStage<?> onBinary(WebSocket w, java.nio.ByteBuffer d, boolean last) { lkEvents.add("bin"); w.request(1); return null; }
                        }).get(5, TimeUnit.SECONDS);
        ok(lkEvents.poll(5, TimeUnit.SECONDS) != null, "student joined LiveKit signal (JoinResponse received)");
        Thread.sleep(300);
        String p0 = participant("class-1-live", "student1");
        ok(p0.startsWith("200") && p0.contains("\"can_publish\":false"), "before allow: " + p0);

        // 강사가 STOMP 로 마이크 허용
        WebSocket tws = stomp(t), sws = stomp(s1);
        app(tws, "join", "{}"); app(sws, "join", "{}");
        Thread.sleep(300);
        app(tws, "mic", "{\"userId\":" + userId(s1) + ",\"allow\":true}");
        Thread.sleep(800);
        String p1 = participant("class-1-live", "student1");
        ok(p1.contains("\"can_publish\":true") && p1.contains("MICROPHONE"), "after allow: " + p1);
        ok(claims(field(req(s1, "GET", "/api/classes/1/livekit/live").body(), "token")).contains("\"canPublish\":true"), "new token reflects allow");

        app(tws, "mic", "{\"userId\":" + userId(s1) + ",\"allow\":false}");
        Thread.sleep(800);
        String p2 = participant("class-1-live", "student1");
        ok(p2.startsWith("200") && p2.contains("\"can_publish\":false"), "after revoke: " + p2);

        // 학생은 마이크 허용을 할 수 없다
        app(sws, "mic", "{\"userId\":" + userId(s1) + ",\"allow\":true}");
        Thread.sleep(800);
        ok(participant("class-1-live", "student1").contains("\"can_publish\":false"), "student cannot self-allow");

        // monitor 방: 학생은 화면만 송출(구독 불가), 강사는 숨은 구독자
        ok(!sm.contains("screen_share_audio"), "student monitor token: screen only");
        WebSocket lkm = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:7880/rtc?access_token=" + field(req(s1, "GET", "/api/classes/1/livekit/monitor").body(), "token") + "&protocol=15"),
                        new WebSocket.Listener() {}).get(5, TimeUnit.SECONDS);
        WebSocket lkt = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:7880/rtc?access_token=" + field(req(t, "GET", "/api/classes/1/livekit/monitor").body(), "token") + "&protocol=15"),
                        new WebSocket.Listener() {}).get(5, TimeUnit.SECONDS);
        Thread.sleep(800);
        String ms = participant("class-1-monitor", "student1"), mt = participant("class-1-monitor", "teacher1");
        ok(ms.contains("\"can_subscribe\":false") && ms.contains("\"can_publish\":true") && ms.contains("SCREEN_SHARE") && !ms.contains("MICROPHONE"), "monitor student perms: " + ms);
        ok(mt.contains("\"hidden\":true") && mt.contains("\"can_subscribe\":true") && mt.contains("\"can_publish\":false"), "monitor teacher perms: " + mt);
        lkm.abort(); lkt.abort();

        lk.abort(); tws.abort(); sws.abort();
    }

    static String userId(Session s) throws Exception {
        String me = req(s, "GET", "/api/me").body();
        int i = me.indexOf("\"id\":") + 5;
        return me.substring(i, me.indexOf(',', i));
    }
}
