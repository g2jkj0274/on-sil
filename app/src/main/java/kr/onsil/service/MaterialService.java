package kr.onsil.service;

import kr.onsil.domain.Material;
import kr.onsil.domain.Membership;
import kr.onsil.live.LiveStateService;
import kr.onsil.repo.MaterialRepository;
import kr.onsil.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 자료실: 강사·조교가 올리고, 클래스 멤버 모두가 내려받는다. */
@Service
@Transactional
public class MaterialService {
    private static final Logger log = LoggerFactory.getLogger(MaterialService.class);

    private final MaterialRepository materials;
    private final UserRepository users;
    private final ClassService classService;
    private final SimpMessagingTemplate messaging;
    private final Path baseDir;

    public MaterialService(MaterialRepository materials, UserRepository users, ClassService classService,
                           SimpMessagingTemplate messaging, @Value("${onsil.data-dir}") String dataDir) {
        this.materials = materials;
        this.users = users;
        this.classService = classService;
        this.messaging = messaging;
        this.baseDir = Path.of(dataDir, "materials");
    }

    public Path fileOf(Material m) {
        return baseDir.resolve(String.valueOf(m.getClassroom().getId())).resolve(String.valueOf(m.getId()));
    }

    @Transactional(readOnly = true)
    public List<Material> list(Long classId, Long userId) {
        classService.requireMember(classId, userId);
        return materials.findByClass(classId);
    }

    public List<Material> upload(Long classId, Long userId, List<MultipartFile> files) throws IOException {
        Membership m = classService.requireStaff(classId, userId);
        if (files == null || files.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올릴 파일을 고르세요.");
        List<Material> saved = new ArrayList<>();
        List<Path> written = new ArrayList<>();
        try {
            for (MultipartFile f : files) {
                if (f.isEmpty() && (f.getOriginalFilename() == null || f.getOriginalFilename().isBlank())) continue;
                Material mat = materials.save(new Material(m.getClassroom(), users.getReferenceById(userId),
                        cleanName(f.getOriginalFilename()), f.getSize()));
                Path target = fileOf(mat);
                Files.createDirectories(target.getParent());
                try (InputStream in = f.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                written.add(target);
                saved.add(mat);
            }
        } catch (IOException | RuntimeException e) {
            // DB 는 롤백되므로 이미 쓴 파일도 지운다
            for (Path p : written) Files.deleteIfExists(p);
            throw e;
        }
        notifyAfterCommit(classId);
        return saved;
    }

    public void delete(Long classId, Long materialId, Long userId) throws IOException {
        classService.requireStaff(classId, userId);
        Material m = require(classId, materialId);
        Path file = fileOf(m);
        materials.delete(m);
        notifyAfterCommit(classId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { Files.deleteIfExists(file); } catch (IOException e) { log.warn("자료 파일 삭제 실패 {}: {}", file, e.toString()); }
            }
        });
    }

    @Transactional(readOnly = true)
    public Material requireViewable(Long classId, Long materialId, Long userId) {
        classService.requireMember(classId, userId);
        return require(classId, materialId);
    }

    private Material require(Long classId, Long materialId) {
        Material m = materials.findById(materialId).orElse(null);
        if (m == null || !m.getClassroom().getId().equals(classId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "자료를 찾을 수 없습니다.");
        return m;
    }

    /** 자료실이 바뀌었다고 클래스 전체에 알린다 (목록을 다시 불러가도록). */
    private void notifyAfterCommit(Long classId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                messaging.convertAndSend(LiveStateService.publicTopic(classId), Map.of("type", "materials", "classId", classId));
            }
        });
    }

    /** 브라우저가 보낸 이름에서 경로와 제어 문자를 없앤다. */
    static String cleanName(String name) {
        if (name == null) name = "";
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "이름 없는 파일";
        if (name.length() > 255) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
            name = name.substring(0, 255 - ext.length()) + ext;
        }
        return name;
    }
}
