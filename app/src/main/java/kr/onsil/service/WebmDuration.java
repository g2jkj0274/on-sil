package kr.onsil.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * 브라우저 MediaRecorder 가 만든 WebM 에는 길이(Duration)가 없어서 다시보기에서 탐색(seek)이 잘 안 된다.
 * 첫 조각(헤더 포함)을 저장할 때 Segment/Info 안에 Duration 자리(0.0)를 끼워 두고,
 * 녹화가 끝나면 그 자리에 실제 길이를 덮어쓴다.
 */
public final class WebmDuration {
    private static final long EBML = 0x1A45DFA3L, SEGMENT = 0x18538067L, INFO = 0x1549A966L,
            TIMECODE_SCALE = 0x2AD7B1L, DURATION = 0x4489L, TRACKS = 0x1654AE6BL, CLUSTER = 0x1F43B675L;

    /** bytes: 바뀐(또는 그대로인) 첫 조각. offset: Duration 값의 파일 내 위치(-1 이면 못 찾음). */
    public record Result(byte[] bytes, long offset, int size, long timecodeScale) {}

    private WebmDuration() {}

    public static Result prepare(byte[] b) {
        try {
            return prepareOrThrow(b);
        } catch (RuntimeException e) {
            return new Result(b, -1, 0, 1_000_000); // 형식이 예상과 다르면 손대지 않는다
        }
    }

    private static Result prepareOrThrow(byte[] b) {
        int[] pos = {0};
        if (readId(b, pos) != EBML) return none(b);
        long hs = readSize(b, pos);
        pos[0] += (int) hs;
        if (readId(b, pos) != SEGMENT) return none(b);
        long segSize = readSize(b, pos);
        if (segSize != -1) return none(b); // 크기가 정해진 Segment 에는 바이트를 끼워 넣을 수 없다

        while (pos[0] < b.length) {
            int elemStart = pos[0];
            long id = readId(b, pos);
            long size = readSize(b, pos);
            if (id == TRACKS || id == CLUSTER || size < 0) return none(b);
            int dataStart = pos[0];
            if (id != INFO) { pos[0] = dataStart + (int) size; continue; }

            // Info 안에서 TimecodeScale, Duration 찾기
            long scale = 1_000_000;
            int end = dataStart + (int) size;
            int[] p = {dataStart};
            while (p[0] < end) {
                long cid = readId(b, p);
                long csz = readSize(b, p);
                if (cid == TIMECODE_SCALE) scale = readUInt(b, p[0], (int) csz);
                if (cid == DURATION) return new Result(b, p[0], (int) csz, scale); // 이미 있음
                p[0] += (int) csz;
            }
            // Duration(float64 0.0)을 Info 끝에 추가. Info 크기는 8바이트 vint 로 다시 쓴다.
            ByteArrayOutputStream out = new ByteArrayOutputStream(b.length + 16);
            out.write(b, 0, elemStart);
            out.write(new byte[]{0x15, 0x49, (byte) 0xA9, 0x66}, 0, 4);
            out.write(sizeVint8(size + 11), 0, 8);
            out.write(b, dataStart, (int) size);
            out.write(new byte[]{0x44, (byte) 0x89, (byte) 0x88}, 0, 3);
            long offset = out.size();
            out.write(new byte[8], 0, 8);
            out.write(b, end, b.length - end);
            return new Result(out.toByteArray(), offset, 8, scale);
        }
        return none(b);
    }

    /** 녹화가 끝난 파일에 실제 길이를 써 넣는다. */
    public static void write(Path file, long offset, int size, long timecodeScale, long durationMs) throws IOException {
        if (offset < 0) return;
        double value = durationMs * 1_000_000.0 / timecodeScale;
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
            f.seek(offset);
            if (size == 8) f.write(ByteBuffer.allocate(8).putDouble(value).array());
            else if (size == 4) f.write(ByteBuffer.allocate(4).putFloat((float) value).array());
        }
    }

    private static Result none(byte[] b) { return new Result(b, -1, 0, 1_000_000); }

    /** EBML ID (앞의 표시 비트 포함). */
    private static long readId(byte[] b, int[] pos) {
        int first = b[pos[0]] & 0xFF;
        int len = Integer.numberOfLeadingZeros(first) - 23;
        if (len < 1 || len > 4) throw new IllegalArgumentException("bad id");
        long v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (b[pos[0] + i] & 0xFF);
        pos[0] += len;
        return v;
    }

    /** EBML 크기. 모든 비트가 1이면(알 수 없음) -1. */
    private static long readSize(byte[] b, int[] pos) {
        int first = b[pos[0]] & 0xFF;
        int len = Integer.numberOfLeadingZeros(first) - 23;
        if (len < 1 || len > 8) throw new IllegalArgumentException("bad size");
        long v = first & (0xFF >> len);
        boolean allOnes = v == (0xFF >> len);
        for (int i = 1; i < len; i++) {
            int x = b[pos[0] + i] & 0xFF;
            v = (v << 8) | x;
            allOnes &= x == 0xFF;
        }
        pos[0] += len;
        return allOnes ? -1 : v;
    }

    private static long readUInt(byte[] b, int at, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (b[at + i] & 0xFF);
        return v;
    }

    private static byte[] sizeVint8(long size) {
        byte[] r = new byte[8];
        r[0] = 0x01;
        for (int i = 7; i >= 1; i--) { r[i] = (byte) (size & 0xFF); size >>= 8; }
        return r;
    }
}
