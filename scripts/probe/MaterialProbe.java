import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 6단계 테스트: 자료실 업로드/목록/다운로드/열기/삭제, 권한, 파일 이름 정리, 실시간 알림. */
public class MaterialProbe {
    static final String BASE = "http://127.0.0.1:8800";

    static void ok(boolean cond, String msg) { System.out.println((cond ? "PASS " : "FAIL ") + msg); }

    record S(HttpClient http, String cookie) {}

    static S login(String user) throws Exception {
        CookieManager cm = new CookieManager();
        HttpClient http = HttpClient.newBuilder().cookieHandler(cm).build();
        var res = http.send(HttpRequest.newBuilder(URI.create(BASE + "/api/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + user + "&password=1234")).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException(user + " login " + res.statusCode());
        return new S(http, cm.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().toString());
    }

    static HttpResponse<String> upload(S s, Map<String, byte[]> files) throws Exception {
        String boundary = "----probe" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (var e : files.entrySet()) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + e.getKey()
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(e.getValue());
            body.writeBytes("\r\n".getBytes());
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes());
        return s.http.send(HttpRequest.newBuilder(URI.create(BASE + "/api/classes/1/materials"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<byte[]> get(S s, String path) throws Exception {
        return s.http.send(HttpRequest.newBuilder(URI.create(BASE + path)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static int del(S s, String path) throws Exception {
        return s.http.send(HttpRequest.newBuilder(URI.create(BASE + path)).DELETE().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    static String list(S s) throws Exception { return new String(get(s, "/api/classes/1/materials").body(), StandardCharsets.UTF_8); }

    static String idOf(String list, String name) {
        int i = list.indexOf("\"fileName\":\"" + name + "\"");
        if (i < 0) return null;
        int j = list.lastIndexOf("\"id\":", i);
        return list.substring(j + 5, list.indexOf(',', j));
    }

    public static void main(String[] a) throws Exception {
        S t = login("teacher1"), s = login("student1");

        // 학생이 실시간 알림을 받는지
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        WebSocket ws = s.http.newWebSocketBuilder().header("Cookie", s.cookie).header("Origin", BASE)
                .buildAsync(URI.create("ws://127.0.0.1:8800/ws"), new WebSocket.Listener() {
                    StringBuilder buf = new StringBuilder();
                    public CompletionStage<?> onText(WebSocket w, CharSequence d, boolean last) {
                        buf.append(d); if (last) { events.add(buf.toString()); buf.setLength(0); } w.request(1); return null;
                    }
                }).get();
        ws.sendText("CONNECT\naccept-version:1.2\nhost:127.0.0.1\n\n\0", true).get();
        Thread.sleep(200);
        ws.sendText("SUBSCRIBE\nid:p\ndestination:/topic/class/1/public\n\n\0", true).get();
        Thread.sleep(200);
        events.clear();

        byte[] pdf = "%PDF-1.4 probe".getBytes(), html = "<script>alert(1)</script>".getBytes();
        byte[] big = new byte[3_000_000]; new Random(3).nextBytes(big);
        ok(upload(s, Map.of("x.txt", "no".getBytes())).statusCode() == 403, "student cannot upload");

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("1주차 강의 자료.pdf", pdf);
        files.put("evil.html", html);
        files.put("..\\..\\escape.bin", big);
        var up = upload(t, files);
        ok(up.statusCode() == 200, "teacher uploads 3 files: " + up.statusCode() + " " + up.body());

        String ev = events.poll(3, TimeUnit.SECONDS);
        ok(ev != null && ev.contains("\"type\":\"materials\""), "student notified: " + (ev == null ? "none" : ev.substring(ev.indexOf("\n\n") + 2).trim()));

        String l = list(s);
        String pdfId = idOf(l, "1주차 강의 자료.pdf"), htmlId = idOf(l, "evil.html"), binId = idOf(l, "escape.bin");
        ok(pdfId != null && htmlId != null, "korean name kept, list: " + l.length() + " chars");
        ok(binId != null && !l.contains(".."), "path stripped from name -> escape.bin");
        ok(l.contains("\"viewable\":true") && l.contains("\"viewable\":false"), "viewable flags");
        ok(!new java.io.File("data/escape.bin").exists() && new java.io.File("data/materials/1/" + binId).exists(), "stored as data/materials/1/{id}");

        var dl = get(s, "/api/classes/1/materials/" + binId + "/file");
        ok(dl.statusCode() == 200 && Arrays.equals(dl.body(), big), "student downloads, bytes match (" + dl.body().length + ")");
        ok(dl.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"), "download is attachment");

        var vp = get(s, "/api/classes/1/materials/" + pdfId + "/file?view=true");
        ok(vp.headers().firstValue("Content-Type").orElse("").equals("application/pdf")
                && vp.headers().firstValue("Content-Disposition").orElse("").startsWith("inline"), "pdf opens inline");
        var vh = get(s, "/api/classes/1/materials/" + htmlId + "/file?view=true");
        ok(vh.headers().firstValue("Content-Type").orElse("").equals("application/octet-stream")
                && vh.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment")
                && vh.headers().firstValue("X-Content-Type-Options").orElse("").equals("nosniff"), "html never inline: "
                + vh.headers().firstValue("Content-Type").orElse(""));
        var range = s.http.send(HttpRequest.newBuilder(URI.create(BASE + "/api/classes/1/materials/" + binId + "/file"))
                .header("Range", "bytes=0-9").build(), HttpResponse.BodyHandlers.ofByteArray());
        ok(range.statusCode() == 206 && range.body().length == 10, "range download (resume) -> " + range.statusCode());

        S out = login("teacher1");
        ok(get(out, "/api/classes/999/materials").statusCode() == 403, "other class -> 403");
        ok(get(s, "/api/classes/1/materials/999999/file").statusCode() == 404, "missing -> 404");

        events.clear();
        ok(del(s, "/api/classes/1/materials/" + pdfId) == 403, "student cannot delete");
        for (String id : List.of(pdfId, htmlId, binId)) ok(del(t, "/api/classes/1/materials/" + id) == 200, "teacher deletes " + id);
        ok(events.poll(3, TimeUnit.SECONDS) != null, "student notified of delete");
        Thread.sleep(200);
        ok(!new java.io.File("data/materials/1/" + binId).exists(), "file removed from disk");
        ok(idOf(list(s), "escape.bin") == null, "gone from list");
        ws.abort();
    }
}
