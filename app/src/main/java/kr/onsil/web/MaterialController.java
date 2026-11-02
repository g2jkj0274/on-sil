package kr.onsil.web;

import kr.onsil.domain.Material;
import kr.onsil.security.AppUser;
import kr.onsil.service.MaterialService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 자료실 API. */
@RestController
@RequestMapping("/api/classes/{classId}/materials")
public class MaterialController {
    /**
     * 브라우저에서 바로 열어도 안전한 형식만 "열기"를 허용한다.
     * HTML·SVG 같은 파일을 같은 사이트에서 열면 그 안의 스크립트가 로그인한 사용자 권한으로 실행될 수 있어서 항상 내려받게 한다.
     */
    private static final Map<String, String> VIEWABLE = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"), Map.entry("m4a", "audio/mp4"),
            Map.entry("txt", "text/plain;charset=UTF-8"), Map.entry("md", "text/plain;charset=UTF-8"),
            Map.entry("java", "text/plain;charset=UTF-8"), Map.entry("py", "text/plain;charset=UTF-8"),
            Map.entry("c", "text/plain;charset=UTF-8"), Map.entry("cpp", "text/plain;charset=UTF-8"),
            Map.entry("csv", "text/plain;charset=UTF-8"), Map.entry("json", "text/plain;charset=UTF-8"),
            Map.entry("sql", "text/plain;charset=UTF-8"), Map.entry("log", "text/plain;charset=UTF-8"));

    private final MaterialService service;

    public MaterialController(MaterialService service) { this.service = service; }

    public record MaterialDto(Long id, String fileName, long sizeBytes, String uploader, Instant uploadedAt, boolean viewable) {
        static MaterialDto of(Material m) {
            return new MaterialDto(m.getId(), m.getFileName(), m.getSizeBytes(), m.getUploader().getDisplayName(),
                    m.getUploadedAt(), viewableType(m.getFileName()) != null);
        }
    }

    static String viewableType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? null : VIEWABLE.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    @GetMapping
    public List<MaterialDto> list(@AuthenticationPrincipal AppUser me, @PathVariable Long classId) {
        return service.list(classId, me.getId()).stream().map(MaterialDto::of).toList();
    }

    /** multipart 필드 이름: files (여러 개 가능). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<Long> upload(@AuthenticationPrincipal AppUser me, @PathVariable Long classId,
                             @RequestParam("files") List<MultipartFile> files) throws IOException {
        return service.upload(classId, me.getId(), files).stream().map(Material::getId).toList();
    }

    @DeleteMapping("/{materialId}")
    public void delete(@AuthenticationPrincipal AppUser me, @PathVariable Long classId, @PathVariable Long materialId) throws IOException {
        service.delete(classId, materialId, me.getId());
    }

    /** 기본은 내려받기. view=true 이고 안전한 형식이면 브라우저에서 연다. */
    @GetMapping("/{materialId}/file")
    public ResponseEntity<Resource> file(@AuthenticationPrincipal AppUser me, @PathVariable Long classId,
                                         @PathVariable Long materialId, @RequestParam(defaultValue = "false") boolean view) {
        Material m = service.requireViewable(classId, materialId, me.getId());
        Path file = service.fileOf(m);
        if (!Files.exists(file)) return ResponseEntity.notFound().build();
        String type = viewableType(m.getFileName());
        boolean inline = view && type != null;
        ContentDisposition cd = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(m.getFileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(inline ? type : MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file));
    }
}
