package kr.onsil.service;

import kr.onsil.domain.ClassRole;
import kr.onsil.domain.Membership;
import kr.onsil.domain.Recording;
import kr.onsil.live.LiveStateService;
import kr.onsil.repo.RecordingRepository;
import kr.onsil.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 방송 녹화. 강사 브라우저가 MediaRecorder 로 녹화하며 몇 초마다 조각을 올리면 파일 뒤에 이어 붙인다.
 * 강사 브라우저가 갑자기 꺼져 "끝" 신호가 오지 않은 녹화는 일정 시간 뒤 받은 데까지로 마무리한다.
 */
@Service
@Transactional
public class RecordingService {
    private static final Logger log = LoggerFactory.getLogger(RecordingService.class);
    private static final Duration ABANDONED_AFTER = Duration.ofSeconds(60);
    private static final long MAX_CHUNK_BYTES = 64L * 1024 * 1024;
    private static final DateTimeFormatter TITLE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final RecordingRepository recordings;
    private final UserRepository users;
    private final ClassService classService;
    private final LiveStateService state;
    private final Path baseDir;

    public RecordingService(RecordingRepository recordings, UserRepository users, ClassService classService,
                            LiveStateService state, @Value("${onsil.data-dir}") String dataDir) {
        this.recordings = recordings;
        this.users = users;
        this.classService = classService;
        this.state = state;
        this.baseDir = Path.of(dataDir, "recordings");
    }

    public Path fileOf(Recording r) {
        return baseDir.resolve(String.valueOf(r.getClassroom().getId())).resolve(r.getId() + ".webm");
    }

    @Transactional(readOnly = true)
    public List<Recording> list(Long classId, Long userId) {
        classService.requireMember(classId, userId);
        return recordings.findByClass(classId);
    }

    public Recording start(Long classId, Long userId) throws IOException {
        Membership m = classService.requireRole(classId, userId, ClassRole.TEACHER);
        // 이 클래스에 끝나지 않은 녹화가 남아 있으면 먼저 마무리
        for (Recording r : recordings.findByClass(classId)) if (r.getStatus() == Recording.Status.RECORDING) finishAbandoned(r);
        Recording r = recordings.save(new Recording(m.getClassroom(), users.getReferenceById(userId),
                TITLE_FMT.format(Instant.now()) + " 수업"));
        Path file = fileOf(r);
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        Files.createFile(file);
        state.setRecording(classId, true);
        return r;
    }

    /** seq 번째 조각을 붙인다. 이미 받은 조각(재전송)은 무시한다. */
    public void appendChunk(Long classId, Long recId, Long userId, int seq, InputStream body) throws IOException {
        classService.requireRole(classId, userId, ClassRole.TEACHER);
        Recording r = require(classId, recId);
        if (r.getStatus() != Recording.Status.RECORDING) throw conflict("이미 끝난 녹화입니다.");
        if (seq < r.getNextSeq()) return;
        if (seq > r.getNextSeq()) throw conflict("녹화 조각 순서가 맞지 않습니다. (기대 " + r.getNextSeq() + ", 받음 " + seq + ")");

        byte[] bytes = body.readNBytes((int) MAX_CHUNK_BYTES + 1);
        if (bytes.length > MAX_CHUNK_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "녹화 조각이 너무 큽니다.");
        if (seq == 0) {
            WebmDuration.Result prepared = WebmDuration.prepare(bytes);
            bytes = prepared.bytes();
            r.setDurationSlot(prepared.offset(), prepared.size(), prepared.timecodeScale());
            if (prepared.offset() < 0) log.warn("녹화 {}: WebM 길이 자리를 만들지 못했습니다(탐색이 느릴 수 있음).", recId);
        }
        try (OutputStream out = Files.newOutputStream(fileOf(r), StandardOpenOption.APPEND)) {
            out.write(bytes);
        }
        r.chunkAppended(bytes.length);
        // 강사의 실시간 연결이 끊겼다 붙으면 서버 상태가 초기화되므로 조각이 올 때 되살린다
        if (!state.isRecording(classId)) state.setRecording(classId, true);
    }

    public Recording finish(Long classId, Long recId, Long userId, Long durationMs) throws IOException {
        classService.requireRole(classId, userId, ClassRole.TEACHER);
        Recording r = require(classId, recId);
        if (r.getStatus() == Recording.Status.RECORDING) {
            long ms = durationMs != null && durationMs > 0 ? durationMs : estimateMs(r);
            complete(r, ms);
        }
        state.setRecording(classId, false);
        return r;
    }

    public void rename(Long classId, Long recId, Long userId, String title) {
        classService.requireRole(classId, userId, ClassRole.TEACHER);
        require(classId, recId).setTitle(title);
    }

    public void delete(Long classId, Long recId, Long userId) throws IOException {
        classService.requireRole(classId, userId, ClassRole.TEACHER);
        Recording r = require(classId, recId);
        if (r.getStatus() == Recording.Status.RECORDING) throw conflict("녹화 중에는 지울 수 없습니다. 먼저 녹화를 끝내세요.");
        Files.deleteIfExists(fileOf(r));
        recordings.delete(r);
    }

    @Transactional(readOnly = true)
    public Recording requireViewable(Long classId, Long recId, Long userId) {
        classService.requireMember(classId, userId);
        return require(classId, recId);
    }

    /** 서버가 켜질 때: 지난번에 끝나지 않은 녹화를 받은 데까지로 마무리. */
    @EventListener(ApplicationReadyEvent.class)
    public void finishLeftovers() {
        for (Recording r : recordings.findByStatus(Recording.Status.RECORDING)) finishAbandoned(r);
    }

    /** 오래 조각이 오지 않은 녹화(강사 브라우저가 꺼짐 등) 정리. 목록을 볼 때 호출한다. */
    public void finishStale(Long classId) {
        Instant limit = Instant.now().minus(ABANDONED_AFTER);
        for (Recording r : recordings.findByClass(classId)) {
            if (r.getStatus() != Recording.Status.RECORDING) continue;
            Instant last = r.getLastChunkAt() != null ? r.getLastChunkAt() : r.getStartedAt();
            if (last.isBefore(limit)) finishAbandoned(r);
        }
    }

    private void finishAbandoned(Recording r) {
        log.info("녹화 {}: 끝 신호 없이 중단되어 받은 데까지로 마무리합니다.", r.getId());
        complete(r, estimateMs(r));
    }

    private void complete(Recording r, long ms) {
        r.finish(ms);
        try {
            WebmDuration.write(fileOf(r), r.getDurationOffset(), r.getDurationSize(), r.getTimecodeScale(), ms);
        } catch (IOException e) {
            log.warn("녹화 {}: 길이를 기록하지 못했습니다: {}", r.getId(), e.toString());
        }
    }

    private static long estimateMs(Recording r) {
        Instant last = r.getLastChunkAt() != null ? r.getLastChunkAt() : r.getStartedAt();
        return Math.max(0, Duration.between(r.getStartedAt(), last).toMillis());
    }

    private Recording require(Long classId, Long recId) {
        Recording r = recordings.findById(recId).orElse(null);
        if (r == null || !r.getClassroom().getId().equals(classId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "녹화를 찾을 수 없습니다.");
        return r;
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }
}
