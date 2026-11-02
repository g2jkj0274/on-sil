import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 2단계 테스트: 여러 사용자가 STOMP 로 접속해 채팅/손들기/공유요청/막혔어요/권한을 확인. */
public class StompProbe {
    static final String BASE = "http://127.0.0.1:8800";

    static class U implements WebSocket.Listener {
        final String name; WebSocket ws; final List<String> frames = new CopyOnWriteArrayList<>();
        final StringBuilder buf = new StringBuilder();
        U(String name) { this.name = name; }
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            buf.append(data);
            if (last) { String f = buf.toString(); buf.setLength(0); if (!f.isBlank()) frames.add(f); }
            w.request(1); return null;
        }
        void send(String f) throws Exception { ws.sendText(f + "\0", true).get(); }
        void sub(String id, String dest) throws Exception { send("SUBSCRIBE\nid:" + id + "\ndestination:" + dest + "\n\n"); }
        void app(String path, String json) throws Exception {
            send("SEND\ndestination:/app/class/1/" + path + "\ncontent-type:application/json\n\n" + json);
        }
        long count(String needle) { return frames.stream().filter(f -> f.contains(needle)).count(); }
        String last(String needle) {
            String r = null; for (String f : frames) if (f.contains(needle)) r = f; return r == null ? "(none)" : r.substring(r.indexOf("\n\n") + 2).trim();
        }
    }

    static U login(String user) throws Exception {
        CookieManager cm = new CookieManager();
        HttpClient http = HttpClient.newBuilder().cookieHandler(cm).build();
        var res = http.send(HttpRequest.newBuilder(URI.create(BASE + "/api/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + user + "&password=1234")).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException(user + " login " + res.statusCode());
        String cookie = cm.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID"))
                .findFirst().orElseThrow().toString();
        U u = new U(user);
        u.ws = http.newWebSocketBuilder().header("Cookie", cookie).header("Origin", BASE)
                .buildAsync(URI.create("ws://127.0.0.1:8800/ws"), u).get();
        u.send("CONNECT\naccept-version:1.2\nhost:127.0.0.1\n\n");
        Thread.sleep(200);
        u.sub("q", "/user/queue/events");
        return u;
    }

    static void ok(boolean cond, String msg) { System.out.println((cond ? "PASS " : "FAIL ") + msg); }

    public static void main(String[] a) throws Exception {
        U t = login("teacher1"), ta = login("ta1"), s1 = login("student1"), s2 = login("student2");
        for (U u : List.of(t, ta, s1, s2)) u.sub("p", "/topic/class/1/public");
        t.sub("s", "/topic/class/1/staff"); ta.sub("s", "/topic/class/1/staff");
        for (U u : List.of(t, ta, s1, s2)) u.app("join", "{}");
        Thread.sleep(500);
        ok(t.last("\"type\":\"state\"").contains("student2") , "teacher sees 4 online: " + t.last("\"type\":\"state\"").replaceAll("\"username\":\"[^\"]*\",", ""));

        // 학생이 staff 토픽 구독 → 거부
        U s3 = login("student3");
        s3.sub("bad", "/topic/class/1/staff"); Thread.sleep(400);
        System.out.println("s3 frames: " + s3.frames.stream().map(f -> f.replace('\n', '|')).toList());
        s3.ws.abort(); s3 = login("student3"); s3.sub("bad", "/topic/class/1/staff"); s3.sub("p", "/topic/class/1/public");

        s2.app("chat", "{\"text\":\"안녕하세요 강사님\"}"); Thread.sleep(400);
        ok(t.count("안녕하세요 강사님") == 1 && s1.count("안녕하세요 강사님") == 1, "chat delivered to all");

        s2.app("hand", "{\"on\":true}"); Thread.sleep(300);
        ok(t.last("\"type\":\"state\"").contains("\"hand\":true"), "hand raised visible");

        s2.app("mic", "{\"userId\":4,\"allow\":true}"); Thread.sleep(300);
        ok(s2.count("\"type\":\"error\"") == 1, "student cannot grant mic");
        t.app("mic", "{\"userId\":4,\"allow\":true}"); Thread.sleep(300);
        ok(s2.count("\"type\":\"mic\"") == 1 && t.last("\"type\":\"state\"").contains("\"micAllowed\":true"), "teacher grants mic, student notified");

        System.out.println("TA frames: " + ta.frames.stream().map(f -> f.replace('\n', '|')).toList());
        ta.app("share-request", "{\"userId\":3}"); Thread.sleep(300);
        ok(s1.count("share-request") == 1 && s2.count("share-request") == 0, "share request only to student1");
        t.app("share-request", "{}"); Thread.sleep(300);
        ok(s1.count("share-request") == 2 && s2.count("share-request") == 1 && ta.count("share-request") == 0, "share request to all students");

        s1.app("stuck", "{\"on\":true}"); s1.app("sharing", "{\"on\":true}"); Thread.sleep(300);
        ok(ta.last("staffState").contains("\"userId\":3") && ta.last("staffState").contains("\"sharing\":[3]"), "staff sees stuck+sharing: " + ta.last("staffState"));
        ok(s2.count("staffState") == 0, "student never receives staffState");
        ok(s3.count("staffState") == 0, "student who tried staff-subscribe receives no staffState");

        ta.app("stuck-clear", "{\"userId\":3}"); Thread.sleep(300);
        ok(s1.count("stuck-cleared") == 1 && ta.last("staffState").contains("\"stuck\":[]"), "TA clears stuck");

        s1.ws.abort(); Thread.sleep(800);
        ok(!t.last("\"type\":\"state\"").contains("student1") && t.last("staffState").contains("\"sharing\":[]"), "disconnect removes student1");

        for (U u : List.of(t, ta, s2, s3)) u.ws.abort();
    }
}
