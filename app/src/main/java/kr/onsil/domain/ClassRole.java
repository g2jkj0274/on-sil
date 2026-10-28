package kr.onsil.domain;

/** 클래스 안에서의 역할. */
public enum ClassRole {
    TEACHER, TA, STUDENT;

    /** 학생 화면을 모니터링할 수 있는 역할(강사·조교). */
    public boolean isStaff() { return this == TEACHER || this == TA; }
}
