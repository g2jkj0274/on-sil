import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.*;

/** 5단계 테스트: 녹화 시작 → 조각 업로드 → 끝 → WebM Duration 기록, 권한, 재생(Range), 삭제. */
public class RecordingProbe {
    static final String BASE = "http://127.0.0.1:8800";

    static void ok(boolean cond, String msg) { System.out.println((cond ? "PASS " : "FAIL ") + msg); }

    static HttpClient login(String user) throws Exception {
        HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var res = http.send(HttpRequest.newBuilder(URI.create(BASE + "/api/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + user + "&password=1234")).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException(user + " login " + res.statusCode());
        return http;
    }

    static HttpResponse<String> send(HttpClient h, String method, String path, byte[] body, String type) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(BASE + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (type != null) b.header("Content-Type", type);
        return h.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---- 간단한 EBML 만들기 (Chrome MediaRecorder 와 같은 모양) ----
    static byte[] el(int id, byte[] data) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] idb = BigInteger(id);
        o.writeBytes(idb);
        if (data.length < 127) o.write(0x80 | data.length);
        else { o.write(0x01); for (int i = 6; i >= 0; i--) o.write((int) ((long) data.length >> (8 * i)) & 0xFF); }
        o.writeBytes(data);
        return o.toByteArray();
    }
    static byte[] BigInteger(int id) {
        if ((id & 0xFF000000) != 0) return new byte[]{(byte) (id >> 24), (byte) (id >> 16), (byte) (id >> 8), (byte) id};
        if ((id & 0xFF0000) != 0) return new byte[]{(byte) (id >> 16), (byte) (id >> 8), (byte) id};
        if ((id & 0xFF00) != 0) return new byte[]{(byte) (id >> 8), (byte) id};
        return new byte[]{(byte) id};
    }
    static byte[] cat(byte[]... parts) { ByteArrayOutputStream o = new ByteArrayOutputStream(); for (byte[] p : parts) o.writeBytes(p); return o.toByteArray(); }
    static byte[] str(String s) { return s.getBytes(); }

    static byte[] header() {
        byte[] ebml = el(0x1A45DFA3, cat(el(0x4286, new byte[]{1}), el(0x42F7, new byte[]{1}), el(0x42F2, new byte[]{4}),
                el(0x42F3, new byte[]{8}), el(0x4282, str("webm")), el(0x4287, new byte[]{4}), el(0x4285, new byte[]{2})));
        byte[] segStart = {0x18, 0x53, (byte) 0x80, 0x67, 0x01, -1, -1, -1, -1, -1, -1, -1};
        byte[] info = el(0x1549A966, cat(el(0x2AD7B1, new byte[]{0x0F, 0x42, 0x40}), el(0x4D80, str("Chrome")), el(0x5741, str("Chrome"))));
        byte[] tracks = el(0x1654AE6B, el(0xAE, cat(el(0xD7, new byte[]{1}), el(0x83, new byte[]{1}), el(0x86, str("V_VP8")))));
        byte[] cluster = cat(new byte[]{0x1F, 0x43, (byte) 0xB6, 0x75, 0x01, -1, -1, -1, -1, -1, -1, -1}, el(0xE7, new byte[]{0}),
                el(0xA3, new byte[]{(byte) 0x81, 0, 0, (byte) 0x80, 1, 2, 3}));
        return cat(ebml, segStart, info, tracks, cluster);
    }

    public static void main(String[] a) throws Exception {
        HttpClient t = login("teacher1"), s = login("student1");
        String base = "/api/classes/1/recordings";

        ok(send(s, "POST", base, null, null).statusCode() == 403, "student cannot start recording");
        var started = send(t, "POST", base, null, null);
        ok(started.statusCode() == 200, "teacher starts: " + started.body());
        String id = started.body().replaceAll(".*\"id\":(\\d+).*", "$1");

        byte[] c0 = header(), c1 = new byte[300_000], c2 = new byte[1234];
        new Random(1).nextBytes(c1); new Random(2).nextBytes(c2);
        String oct = "application/octet-stream";
        ok(send(s, "POST", base + "/" + id + "/chunks?seq=0", c0, oct).statusCode() == 403, "student cannot upload");
        ok(send(t, "POST", base + "/" + id + "/chunks?seq=0", c0, oct).statusCode() == 200, "chunk 0");
        ok(send(t, "POST", base + "/" + id + "/chunks?seq=2", c2, oct).statusCode() == 409, "out-of-order chunk -> 409");
        ok(send(t, "POST", base + "/" + id + "/chunks?seq=1", c1, "video/webm").statusCode() == 200, "chunk 1");
        ok(send(t, "POST", base + "/" + id + "/chunks?seq=1", c1, oct).statusCode() == 200, "duplicate chunk 1 ignored");
        ok(send(t, "POST", base + "/" + id + "/chunks?seq=2", c2, oct).statusCode() == 200, "chunk 2");

        String listDuring = send(s, "GET", base, null, null).body();
        ok(listDuring.contains("\"status\":\"RECORDING\""), "student sees RECORDING in list");
        ok(send(t, "DELETE", base + "/" + id, null, null).statusCode() == 409, "cannot delete while recording");

        ok(send(t, "POST", base + "/" + id + "/finish", "{\"durationMs\":12345}".getBytes(), "application/json").statusCode() == 200, "finish");
        String list = send(s, "GET", base, null, null).body();
        ok(list.contains("\"status\":\"READY\"") && list.contains("\"durationMs\":12345"), "list after finish: " + list);

        // 파일 내용 확인
        var file = s.send(HttpRequest.newBuilder(URI.create(BASE + base + "/" + id + "/file")).build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] f = file.body();
        ok(file.statusCode() == 200 && "video/webm".equals(file.headers().firstValue("Content-Type").orElse("")), "student can play: " + file.statusCode());
        ok(f.length == c0.length + 18 + c1.length + c2.length, "file size = chunks + 18 bytes (Duration + longer Info size): " + f.length);
        int at = -1;
        for (int i = 0; i + 2 < f.length; i++) if (f[i] == 0x44 && f[i + 1] == (byte) 0x89 && f[i + 2] == (byte) 0x88) { at = i + 3; break; }
        double dur = at < 0 ? -1 : ByteBuffer.wrap(f, at, 8).getDouble();
        ok(dur == 12345.0, "WebM Duration = " + dur);
        ok(at > 0 && at < c0.length + 18, "Duration inside header");
        ok(Arrays.equals(Arrays.copyOfRange(f, c0.length + 18, c0.length + 18 + c1.length), c1), "chunk 1 bytes intact");

        // 구간 요청 (영상 탐색)
        var range = s.send(HttpRequest.newBuilder(URI.create(BASE + base + "/" + id + "/file")).header("Range", "bytes=100-199").build(),
                HttpResponse.BodyHandlers.ofByteArray());
        ok(range.statusCode() == 206 && range.body().length == 100, "Range request -> " + range.statusCode() + " " + range.body().length);
        var dl = s.send(HttpRequest.newBuilder(URI.create(BASE + base + "/" + id + "/file?download=true")).build(), HttpResponse.BodyHandlers.discarding());
        ok(dl.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"), "download header: " + dl.headers().firstValue("Content-Disposition").orElse(""));

        // 제목 바꾸기·삭제
        ok(send(s, "PUT", base + "/" + id + "/title", "{\"title\":\"x\"}".getBytes(), "application/json").statusCode() == 403, "student cannot rename");
        ok(send(t, "PUT", base + "/" + id + "/title", "{\"title\":\"1주차 수업\"}".getBytes(), "application/json").statusCode() == 200, "teacher renames");
        ok(send(t, "GET", base, null, null).body().contains("1주차 수업"), "renamed in list");
        ok(send(t, "GET", "/api/classes/999/recordings", null, null).statusCode() == 403, "other class -> 403");
        ok(send(s, "DELETE", base + "/" + id, null, null).statusCode() == 403, "student cannot delete");
        ok(send(t, "DELETE", base + "/" + id, null, null).statusCode() == 200, "teacher deletes");
        ok(!send(t, "GET", base, null, null).body().contains("\"id\":" + id + ","), "gone from list");
        ok(!new java.io.File("data/recordings/1/" + id + ".webm").exists(), "file deleted");
    }
}
