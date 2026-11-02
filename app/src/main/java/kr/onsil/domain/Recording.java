package kr.onsil.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 강사 브라우저가 녹화해 조각(chunk)으로 올린 방송 녹화본. 파일은 data/recordings/{classId}/{id}.webm */
@Entity
@Table(indexes = @Index(columnList = "classroom_id, id"))
public class Recording {
    public enum Status { RECORDING, READY }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Classroom classroom;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User createdBy;

    @Column(nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.RECORDING;

    @Column(nullable = false)
    private Instant startedAt = Instant.now();

    /** 마지막 조각을 받은 시각 (강사 브라우저가 갑자기 꺼졌을 때 길이를 추정하는 데 씀). */
    private Instant lastChunkAt;

    private Long durationMs;

    @Column(nullable = false)
    private long sizeBytes;

    /** 다음에 받을 조각 번호. */
    @Column(nullable = false)
    private int nextSeq;

    /** 파일 안 Duration 값(float) 위치와 크기. 녹화가 끝나면 그 자리에 실제 길이를 써 넣는다. -1 이면 없음. */
    @Column(nullable = false)
    private long durationOffset = -1;
    @Column(nullable = false)
    private int durationSize;
    @Column(nullable = false)
    private long timecodeScale = 1_000_000;

    protected Recording() {}

    public Recording(Classroom classroom, User createdBy, String title) {
        this.classroom = classroom;
        this.createdBy = createdBy;
        this.title = title;
    }

    public Long getId() { return id; }
    public Classroom getClassroom() { return classroom; }
    public User getCreatedBy() { return createdBy; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Status getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getLastChunkAt() { return lastChunkAt; }
    public Long getDurationMs() { return durationMs; }
    public long getSizeBytes() { return sizeBytes; }
    public int getNextSeq() { return nextSeq; }
    public long getDurationOffset() { return durationOffset; }
    public int getDurationSize() { return durationSize; }
    public long getTimecodeScale() { return timecodeScale; }

    public void chunkAppended(long bytes) {
        this.sizeBytes += bytes;
        this.nextSeq++;
        this.lastChunkAt = Instant.now();
    }

    public void setDurationSlot(long offset, int size, long timecodeScale) {
        this.durationOffset = offset;
        this.durationSize = size;
        this.timecodeScale = timecodeScale;
    }

    public void finish(long durationMs) {
        this.status = Status.READY;
        this.durationMs = durationMs;
    }
}
