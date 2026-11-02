import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 자료실 테스트용 샘플 파일을 data/samples 에 만든다. */
public class MakeSamples {
    static final Path OUT = Path.of("data", "samples");
    static final String FONT = "Malgun Gothic";

    public static void main(String[] a) throws Exception {
        Files.createDirectories(OUT);

        // 1. PDF: 슬라이드 3쪽 (한글은 그림으로 넣어서 글꼴 없이도 보이게)
        List<byte[]> pages = new ArrayList<>();
        String[][] slides = {
                {"1주차: 프로그래밍 시작하기", "• 수업 진행 방식 안내", "• 개발 환경 설치", "• 첫 번째 프로그램: Hello, World!"},
                {"변수와 자료형", "• int, double, boolean, String", "• 변수 선언과 대입", "• 형 변환 (casting)"},
                {"오늘의 과제", "• HelloWorld.java 를 실행해 보세요", "• 결과 화면을 캡처해 제출", "• 막히면 '막혔어요' 버튼을 누르세요"}};
        for (int i = 0; i < slides.length; i++) pages.add(jpeg(slide(slides[i], i + 1), 0.9f));
        Files.write(OUT.resolve("1주차_강의슬라이드.pdf"), pdf(pages, 1280, 720));

        // 2. PNG 다이어그램
        ImageIO.write(diagram(), "png", OUT.resolve("다이어그램 (구조도).png").toFile());

        // 3. JPG 사진 느낌 (그라데이션 풍경)
        Files.write(OUT.resolve("사진 예시.jpg"), jpeg(landscape(), 0.85f));

        // 4. 텍스트들
        text("과제 안내.txt", """
                [1주차 과제 안내]

                1. 자료실의 HelloWorld.java 를 내려받아 실행해 보세요.
                2. 실행 결과를 캡처해서 제출합니다.
                3. 제출 기한: 다음 수업 전날 밤 11시 59분

                질문은 수업 중 채팅이나 손들기로 해 주세요.
                """);
        text("HelloWorld.java", """
                public class HelloWorld {
                    public static void main(String[] args) {
                        String name = "on-sil";
                        System.out.println("Hello, World! 안녕하세요, " + name + "입니다.");
                        for (int i = 1; i <= 3; i++) {
                            System.out.println(i + "번째 줄");
                        }
                    }
                }
                """);
        // 엑셀에서 한글이 깨지지 않도록 BOM
        Files.write(OUT.resolve("성적표_샘플.csv"), ("﻿" + """
                학번,이름,과제1,과제2,중간고사,합계
                2026001,박학생,10,9,85,104
                2026002,최학생,8,10,78,96
                2026003,정학생,9,7,92,108
                2026004,강학생,10,10,88,108
                """).getBytes(StandardCharsets.UTF_8));
        text("README 예제.md", """
                # 실습 환경 설정

                ## 준비물
                - JDK 21
                - 텍스트 편집기 또는 IDE

                ## 실행 방법
                ```
                javac HelloWorld.java
                java HelloWorld
                ```
                """);
        text("특수문자 & 공백 [테스트] #1 (복사본).txt", "파일 이름에 특수문자·공백·괄호가 있어도 올리고 내려받을 수 있는지 확인용입니다.\n");

        // 5. WAV: 440Hz 소리 3초
        Files.write(OUT.resolve("소리 테스트 (440Hz).wav"), wav(3, 440));

        // 6. 열면 안 되는 형식 (항상 내려받기여야 함)
        text("보안 테스트 (열리면 안 됨).html", """
                <!doctype html><meta charset="utf-8">
                <h1>이 파일이 브라우저에서 열리면 안 됩니다</h1>
                <script>document.body.insertAdjacentHTML('beforeend', '<p>스크립트가 실행되었습니다: ' + document.cookie + '</p>');</script>
                """);
        text("아이콘 (열리면 안 됨).svg", """
                <svg xmlns="http://www.w3.org/2000/svg" width="120" height="120" viewBox="0 0 120 120">
                  <circle cx="60" cy="60" r="50" fill="#5865f2"/>
                  <text x="60" y="70" font-size="28" text-anchor="middle" fill="#fff">on-sil</text>
                  <script>alert('svg script')</script>
                </svg>
                """);

        // 7. ZIP
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(OUT.resolve("실습 파일 모음.zip")), StandardCharsets.UTF_8)) {
            for (String name : List.of("HelloWorld.java", "과제 안내.txt", "README 예제.md")) {
                zip.putNextEntry(new ZipEntry("실습/" + name));
                zip.write(Files.readAllBytes(OUT.resolve(name)));
                zip.closeEntry();
            }
        }

        // 8. 큰 파일: 진행률 확인(120MB), 용량 제한 확인(510MB)
        bigFile("대용량 테스트 (120MB).bin", 120L * 1024 * 1024);
        bigFile("용량 초과 테스트 (510MB).bin", 510L * 1024 * 1024);

        try (var s = Files.list(OUT)) {
            s.sorted().forEach(p -> {
                try { System.out.printf("%,15d  %s%n", Files.size(p), p.getFileName()); } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
    }

    static void text(String name, String body) throws IOException {
        Files.writeString(OUT.resolve(name), body, StandardCharsets.UTF_8);
    }

    static void bigFile(String name, long size) throws IOException {
        byte[] block = new byte[1024 * 1024];
        new java.util.Random(7).nextBytes(block); // 압축으로 줄어들지 않게
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(OUT.resolve(name)))) {
            for (long w = 0; w < size; w += block.length) out.write(block, 0, (int) Math.min(block.length, size - w));
        }
    }

    static Graphics2D g(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    static BufferedImage slide(String[] lines, int no) {
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = g(img);
        g.setColor(new Color(0x2b2d31)); g.fillRect(0, 0, 1280, 720);
        g.setColor(new Color(0x5865f2)); g.fillRect(0, 0, 1280, 12);
        g.setColor(Color.WHITE); g.setFont(new Font(FONT, Font.BOLD, 60)); g.drawString(lines[0], 80, 160);
        g.setColor(new Color(0xdbdee1)); g.setFont(new Font(FONT, Font.PLAIN, 40));
        for (int i = 1; i < lines.length; i++) g.drawString(lines[i], 100, 260 + (i - 1) * 80);
        g.setColor(new Color(0x949ba4)); g.setFont(new Font(FONT, Font.PLAIN, 24));
        g.drawString("on-sil 샘플 강의 · " + no + " / 3", 80, 680);
        g.dispose();
        return img;
    }

    static BufferedImage diagram() {
        BufferedImage img = new BufferedImage(900, 420, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        g.setColor(Color.WHITE); g.fillRect(0, 0, 900, 420);
        String[] boxes = {"브라우저", "Spring Boot", "LiveKit"};
        Color[] colors = {new Color(0x5865f2), new Color(0x23a55a), new Color(0xf57731)};
        g.setFont(new Font(FONT, Font.BOLD, 28));
        for (int i = 0; i < 3; i++) {
            int x = 60 + i * 290;
            g.setColor(colors[i]); g.fillRoundRect(x, 150, 200, 110, 24, 24);
            g.setColor(Color.WHITE);
            int w = g.getFontMetrics().stringWidth(boxes[i]);
            g.drawString(boxes[i], x + 100 - w / 2, 215);
            if (i < 2) { g.setColor(Color.DARK_GRAY); g.setStroke(new BasicStroke(4)); g.drawLine(x + 205, 205, x + 285, 205); g.fillPolygon(new int[]{x + 285, x + 270, x + 270}, new int[]{205, 195, 215}, 3); }
        }
        g.setColor(Color.BLACK); g.setFont(new Font(FONT, Font.BOLD, 32)); g.drawString("on-sil 구조도", 60, 80);
        g.setFont(new Font(FONT, Font.PLAIN, 20)); g.setColor(Color.GRAY);
        g.drawString("채팅·상태: STOMP / 영상·음성: WebRTC", 60, 330);
        g.dispose();
        return img;
    }

    static BufferedImage landscape() {
        BufferedImage img = new BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = g(img);
        g.setPaint(new GradientPaint(0, 0, new Color(0x87ceeb), 0, 600, new Color(0xffd59e))); g.fillRect(0, 0, 1600, 650);
        g.setColor(new Color(0xffe066)); g.fillOval(1150, 180, 160, 160);
        g.setColor(new Color(0x4a6b3a)); g.fillPolygon(new int[]{0, 400, 800, 1200, 1600, 1600, 0}, new int[]{650, 380, 600, 330, 620, 1000, 1000}, 7);
        g.setColor(new Color(0x2f4a24)); g.fillRect(0, 780, 1600, 220);
        g.setColor(Color.WHITE); g.setFont(new Font(FONT, Font.BOLD, 48)); g.drawString("사진 예시 (JPG)", 60, 940);
        g.dispose();
        return img;
    }

    static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);
        try (var ios = new MemoryCacheImageOutputStream(out)) {
            w.setOutput(ios);
            w.write(null, new IIOImage(img, null, null), p);
        }
        w.dispose();
        return out.toByteArray();
    }

    /** JPEG 쪽들을 한 장씩 꽉 채운 PDF. */
    static byte[] pdf(List<byte[]> jpegs, int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        int n = jpegs.size();
        // 객체 번호: 1 Catalog, 2 Pages, 그다음 쪽마다 Page/Content/Image 3개
        out.write("%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1));
        offsets.add(out.size()); out.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".getBytes());
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(3 + i * 3).append(" 0 R ");
        offsets.add(out.size()); out.write(("2 0 obj\n<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>\nendobj\n").getBytes());
        for (int i = 0; i < n; i++) {
            int page = 3 + i * 3, content = page + 1, image = page + 2;
            offsets.add(out.size());
            out.write((page + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + w + " " + h + "] /Resources << /XObject << /Im"
                    + i + " " + image + " 0 R >> >> /Contents " + content + " 0 R >>\nendobj\n").getBytes());
            String draw = "q " + w + " 0 0 " + h + " 0 0 cm /Im" + i + " Do Q";
            offsets.add(out.size());
            out.write((content + " 0 obj\n<< /Length " + draw.length() + " >>\nstream\n" + draw + "\nendstream\nendobj\n").getBytes());
            byte[] jpg = jpegs.get(i);
            offsets.add(out.size());
            out.write((image + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + w + " /Height " + h
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + jpg.length + " >>\nstream\n").getBytes());
            out.write(jpg);
            out.write("\nendstream\nendobj\n".getBytes());
        }
        int xref = out.size();
        StringBuilder sb = new StringBuilder("xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
        for (int off : offsets) sb.append(String.format("%010d 00000 n \n", off));
        sb.append("trailer\n<< /Size ").append(offsets.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        out.write(sb.toString().getBytes());
        return out.toByteArray();
    }

    static byte[] wav(int seconds, int hz) throws IOException {
        int rate = 44100, samples = rate * seconds;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (int i = 0; i < samples; i++) {
            double fade = Math.min(1, Math.min(i, samples - i) / (rate * 0.05)); // 딸깍 소리 방지
            short v = (short) (Math.sin(2 * Math.PI * hz * i / rate) * 12000 * fade);
            pcm.write(v & 0xFF); pcm.write((v >> 8) & 0xFF);
        }
        byte[] data = pcm.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeBytes("RIFF"); d.writeInt(Integer.reverseBytes(36 + data.length)); d.writeBytes("WAVEfmt ");
        d.writeInt(Integer.reverseBytes(16)); d.writeShort(Short.reverseBytes((short) 1)); d.writeShort(Short.reverseBytes((short) 1));
        d.writeInt(Integer.reverseBytes(rate)); d.writeInt(Integer.reverseBytes(rate * 2));
        d.writeShort(Short.reverseBytes((short) 2)); d.writeShort(Short.reverseBytes((short) 16));
        d.writeBytes("data"); d.writeInt(Integer.reverseBytes(data.length)); d.write(data);
        return out.toByteArray();
    }
}
