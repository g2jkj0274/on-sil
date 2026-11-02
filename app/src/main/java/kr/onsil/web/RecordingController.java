package kr.onsil.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.onsil.domain.Recording;
import kr.onsil.security.AppUser;
import kr.onsil.service.RecordingService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** 녹화 업로드(강사)와 다시보기(클래스 멤버). */
@RestController
@RequestMapping("/api/classes/{classId}/recordings")
public class RecordingController {
    private final RecordingService service;

    public RecordingController(RecordingService service) { this.service = service; }

    public record RecordingDto(Long id, String title, String status, Instant startedAt, Long durationMs,
                               long sizeBytes, String createdBy) {
        static RecordingDto of(Recording r) {
            return new RecordingDto(r.getId(), r.getTitle(), r.getStatus().name(), r.getStartedAt(), r.getDurationMs(),
                    r.getSizeBytes(), r.getCreatedBy().getDisplayName());
        }
    }

    public record FinishRequest(Long durationMs) {}
    public record RenameRequest(@NotBlank(message = "제목을 입력하세요.") @Size(max = 100) String title) {}

    @GetMapping
    public List<RecordingDto> list(@AuthenticationPrincipal AppUser me, @PathVariable Long classId) {
        service.finishStale(classId);
        return service.list(classId, me.getId()).stream().map(RecordingDto::of).toList();
    }

    @PostMapping
    public RecordingDto start(@AuthenticationPrincipal AppUser me, @PathVariable Long classId) throws IOException {
        Recording r = service.start(classId, me.getId());
        return new RecordingDto(r.getId(), r.getTitle(), r.getStatus().name(), r.getStartedAt(), null, 0, me.getDisplayName());
    }

    /** 본문: MediaRecorder 가 만든 WebM 조각 그대로 (application/octet-stream). */
    @PostMapping("/{recId}/chunks")
    public void chunk(@AuthenticationPrincipal AppUser me, @PathVariable Long classId, @PathVariable Long recId,
                      @RequestParam int seq, HttpServletRequest req) throws IOException {
        service.appendChunk(classId, recId, me.getId(), seq, req.getInputStream());
    }

    @PostMapping("/{recId}/finish")
    public void finish(@AuthenticationPrincipal AppUser me, @PathVariable Long classId, @PathVariable Long recId,
                       @RequestBody(required = false) FinishRequest req) throws IOException {
        service.finish(classId, recId, me.getId(), req == null ? null : req.durationMs());
    }

    @PutMapping("/{recId}/title")
    public void rename(@AuthenticationPrincipal AppUser me, @PathVariable Long classId, @PathVariable Long recId,
                       @Valid @RequestBody RenameRequest req) {
        service.rename(classId, recId, me.getId(), req.title().strip());
    }

    @DeleteMapping("/{recId}")
    public void delete(@AuthenticationPrincipal AppUser me, @PathVariable Long classId, @PathVariable Long recId) throws IOException {
        service.delete(classId, recId, me.getId());
    }

    /** 재생/다운로드. Resource 를 돌려주면 Spring 이 Range 요청(구간 재생)을 처리한다. */
    @GetMapping("/{recId}/file")
    public ResponseEntity<Resource> file(@AuthenticationPrincipal AppUser me, @PathVariable Long classId, @PathVariable Long recId,
                                         @RequestParam(defaultValue = "false") boolean download) {
        Recording r = service.requireViewable(classId, recId, me.getId());
        Path file = service.fileOf(r);
        if (!Files.exists(file)) return ResponseEntity.notFound().build();
        String name = r.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_") + ".webm";
        ContentDisposition cd = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(name, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/webm"))
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(new FileSystemResource(file));
    }
}
