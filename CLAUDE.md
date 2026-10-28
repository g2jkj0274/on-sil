# on-sil — 작업 메모 (Claude용)

수업용 온라인 교실 MVP. 원래 요구사항 전문은 대화 첫 메시지에 있었고, 핵심 규칙은 아래와 같다.

## 절대 규칙
- on-sil 폴더 밖에 아무것도 남기지 않는다 (전역 설치·환경변수·레지스트리 금지, Docker/Node 금지).
- 도구는 `.tools/`, Maven 저장소/래퍼는 `.m2/`, 런타임 데이터는 `data/`.
- 모든 서버는 127.0.0.1에만 바인드. 경로에 공백/한글이 있어도 동작해야 함.
- 규칙을 지킬 수 없으면 진행 전에 사용자에게 묻는다.

## 실행
- `setup.cmd` → `.tools`에 JDK/LiveKit 다운로드 + 빌드. `start.cmd` / `stop.cmd`.
- 실제 로직은 `scripts/*.ps1` (UTF-8 BOM 유지! PowerShell 5.1이 한글을 읽기 위해 필요). `common.ps1`이 이 프로세스에서만 JAVA_HOME, MAVEN_USER_HOME, TEMP/TMP를 폴더 안으로 지정.
- 테스트할 때 `ONSIL_NOPAUSE=1`로 cmd의 pause를 건너뛴다.
- 앱은 작업 폴더 = on-sil 루트로 `java -jar app\target\on-sil.jar` 실행 → `data/...` 상대경로 사용 (한글 경로를 JVM 인자로 넘기지 않기 위함).
- start.ps1은 src/pom이 jar보다 새로우면 자동 재빌드 (앱이 꺼져 있을 때만).
- 로그: `data/logs/app.log`, `data/logs/livekit.log`. PID: `data/run/*.pid`.

## 결정 사항
- 버전: Temurin JDK 21.0.12.1, LiveKit server 1.13.11, Spring Boot 3.5.16, livekit-server(Java) 0.16.0, livekit-client 2.23.0, @stomp/stompjs 7.5.0, Maven 3.9.16 / wrapper 3.3.4.
- 폴더 밖 흔적 방지: JVM `-XX:-UsePerfData` (hsperfdata 방지), java.io.tmpdir·tomcat basedir = data/, `.mvn/maven.config`에 `-Dmaven.repo.local=../.m2/repository`, `.mvn/jvm.config`.
- LiveKit(`scripts/livekit.yaml`): ICE/TCP는 0.0.0.0에 바인드되므로 끔(tcp_port 0). UDP 7882는 `enable_loopback_candidate: true` + `ips.includes 127.0.0.1/32`로 루프백에만 바인드.
- 학생 화면 격리: 클래스마다 LiveKit 방 2개.
  - `class-{id}-live`: 강사 송출(화면+마이크), 모두 구독. 학생 마이크는 강사 허용 시 서버가 updateParticipant로 권한 부여.
  - `class-{id}-monitor`: 학생은 화면공유 송출만(canSubscribe=false), 강사·조교는 구독만.
- CSRF 끔, 세션 쿠키 SameSite=Strict (로컬 MVP).
- 기준 시각(폴더 밖 점검용): 2026-10-28T20:46:31+09:00. 시작 전 이미 있던 것: ~/.m2(.lemminx-maven만), %TEMP%(c:\temp)\hsperfdata_User\2704(19:40).

## 진행 상황
- [x] 0단계 환경 (setup/start/stop 동작, 바인드 확인, 폴더 밖 흔적 없음 확인)
- [x] 1단계 회원·클래스·시드 (API curl 검증 완료, 브라우저 UI 확인은 사용자 확인 대기)
- [x] 2단계 강의실 화면 + 채팅·접속자·손들기 (STOMP 다중 사용자 테스트 data/tmp/probe/StompProbe.java 통과, UI는 사용자 확인 대기)
- [ ] 3단계 LiveKit + 강사 방송
- [ ] 4단계 학생 화면 모니터링
- [ ] 5단계 녹화
- [ ] 6단계 자료실
- [ ] 7단계 문서·최종 점검

## 메모
- 브라우저 확장(claude-in-chrome)으로는 localhost 페이지가 오류 페이지로 떠서 직접 UI 확인 불가 → UI 확인은 사용자에게 요청.
- STOMP 테스트: `.tools/jdk/bin/java -XX:-UsePerfData -Djava.io.tmpdir=data/tmp data/tmp/probe/StompProbe.java` (Bash에서 실행. PowerShell은 -D 인자를 깨뜨림)
- STOMP: preserveReceiveOrder/PublishOrder 켬. 상태 스냅샷은 synchronized 안에서 전송(순서 역전 방지). 순서 보존 모드에서는 구독 거부 시 ERROR 프레임 대신 서버 로그만 남음(구독은 막힘).
- 사용자가 테스트 중 ta1을 조교 해제한 적 있음 → 최종 점검 전 data/db 초기화해서 시드 재생성할 것.
- GitHub: origin = https://github.com/g2jkj0274/on-sil.git (master). 단계마다 커밋 후 push 한다 (사용자 요청, 2026-10-28).
