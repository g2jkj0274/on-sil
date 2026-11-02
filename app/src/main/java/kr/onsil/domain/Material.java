package kr.onsil.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 자료실 파일. 디스크에는 data/materials/{classId}/{id} 로 저장하고 원래 이름은 여기에만 둔다. */
@Entity
@Table(indexes = @Index(columnList = "classroom_id, id"))
public class Material {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Classroom classroom;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User uploader;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private Instant uploadedAt = Instant.now();

    protected Material() {}

    public Material(Classroom classroom, User uploader, String fileName, long sizeBytes) {
        this.classroom = classroom;
        this.uploader = uploader;
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
    }

    public Long getId() { return id; }
    public Classroom getClassroom() { return classroom; }
    public User getUploader() { return uploader; }
    public String getFileName() { return fileName; }
    public long getSizeBytes() { return sizeBytes; }
    public Instant getUploadedAt() { return uploadedAt; }
}
