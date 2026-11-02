# on-sil — 작업 메모 (Claude용)

수업용 온라인 교실 MVP. 원래 요구사항 전문은 대화 첫 메시지에 있었고, 핵심 규칙은 아래와 같다.

## 절대 규칙
- on-sil 폴더 밖에 아무것도 남기지 않는다 (전역 설치·환경변수·레지스트리 금지, Docker/Node 금지).
- 도구는 `.tools/`, Maven 저장소/래퍼는 `.m2/`, 런타임 데이터는 `data/`.
- 모든 서버는 127.0.0.1에만 바인드 (앱 8800, LiveKit 7880/UDP 7882). 경로에 공백/한글이 있어도 동작해야 함.
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
- 앱 포트 8800: 2026-11-02 Windows(WinNAT)가 8080~8279를 예약해 8080 바인드 실패 → 변경. 시스템 설정(winnat)은 건드리지 않음.
- LiveKit identity = username (같은 계정으로 두 탭 접속 시 먼저 들어온 쪽이 끊김). 강사·조교는 monitor 방에서 hidden.
- 마이크 허용 순간 학생이 live 방에 없으면 updateParticipant 404 → 무시(토큰에 반영). 그래도 어긋나면 클라이언트가 `POST /api/classes/{id}/livekit/mic-sync`.
- JS 모듈은 top-level `const`라 `window.X`로 접근 불가 → 직접 참조. 학생 화면 공유는 클릭 직후 createLocalScreenTracks 먼저(사용자 활성화), 그다음 토큰·접속.
- 모니터링 타일 = staffState(sharing ∪ stuck) 기준, 영상은 monitor 방 구독(adaptiveStream). 막혔어요 먼저(누른 순), 나머지 이름순.
- 한 브라우저 = 한 계정(쿠키 공유). 탭이 아는 사용자를 `X-Onsil-User` 헤더로 보내고 SessionUserCheckFilter가 다르면 409 → 새로 고침 안내. STOMP 재연결 시 CONNECTED user-name도 비교.
- 녹화: LiveKit Egress는 Docker/Linux 필요 → 불가. 강사 브라우저 MediaRecorder(webm vp8/opus, 5초 조각) → `POST /recordings/{id}/chunks?seq=n` → data/recordings/{classId}/{id}.webm.
  - 영상은 MediaStreamTrackGenerator 중계(화면 다시 선택해도 녹화 유지, Chrome/Edge). 없으면 화면 바뀔 때 녹화 종료.
  - 소리는 AudioContext로 강사 마이크+화면 소리+학생 마이크 믹스 (Live.renderAll → Recorder.sync).
  - WebM에 Duration이 없어 탐색 불가 문제 → 첫 조각 저장 시 Info에 Duration(0.0) 삽입, finish 때 덮어씀 (WebmDuration).
  - 끝 신호 없는 녹화: 서버 시작 시 / 목록 조회 시 60초 이상 조각 없으면 받은 데까지로 READY.
  - 녹화 상태는 REST(start/finish)로 바뀜(STOMP /recording 제거). STOMP 재연결 시 강사는 broadcast 재전송, 녹화는 조각 도착 시 복구.
- 자료실: 강사·조교만 업로드/삭제, 멤버 전원 다운로드. 디스크 data/materials/{classId}/{id}(원래 이름은 DB만). 열기(inline)는 PDF·이미지·영상·음성·텍스트 화이트리스트만, 나머지(HTML/SVG 등)는 항상 attachment+nosniff (같은 출처 XSS 방지). 변경 시 public 토픽 {"type":"materials"}.
- 폴더 밖 점검 시 혼동 주의(모두 on-sil과 무관, 2026-11-02 확인): C:	emp 의 GUID.tmp(0바이트)·dd_BackgroundDownload·TCD*.tmp 등, C:	emp\hsperfdata_User\{JetBrains PID}, 시스템 JAVA_HOME(C:\Program Files\Java), HKCU\Software\JavaSoft(JetBrains Prefs).
- start.ps1이 앱 시작 전 data	mp	omcat* 임시 폴더를 정리.
- CSRF 끔, 세션 쿠키 SameSite=Strict (로컬 MVP).
- 기준 시각(폴더 밖 점검용): 2026-10-28T20:46:31+09:00. 시작 전 이미 있던 것: ~/.m2(.lemminx-maven만), %TEMP%(c:\temp)\hsperfdata_User\2704(19:40).

## 진행 상황
- [x] 0단계 환경 (setup/start/stop 동작, 바인드 확인, 폴더 밖 흔적 없음 확인)
- [x] 1단계 회원·클래스·시드 (API curl 검증 완료, 브라우저 UI 확인은 사용자 확인 대기)
- [x] 2단계 강의실 화면 + 채팅·접속자·손들기 (STOMP 다중 사용자 테스트 scripts/probe/StompProbe.java 통과, UI는 사용자 확인 대기)
- [x] 3단계 LiveKit + 강사 방송 (토큰·마이크 권한 scripts/probe/LiveKitProbe.java 통과, 사용자 브라우저 확인 완료)
- [x] 4단계 학생 화면 모니터링 (monitor 방 권한 LiveKitProbe 통과, 사용자 브라우저 확인 완료)
- [x] 5단계 녹화 (scripts/probe/RecordingProbe.java 27개 통과, 사용자 브라우저 확인 완료)
- [x] 6단계 자료실 (scripts/probe/MaterialProbe.java 21개 통과, 사용자 브라우저 확인 완료)
- [x] 7단계 문서·최종 점검 (README.md, docs/architecture.md, 한글·공백 경로 복사본에서 setup→start→테스트 75/75, 바인드 127.0.0.1만, 폴더 밖 흔적 없음, data 초기화·시드 재생성)

## 메모
- 브라우저 확장(claude-in-chrome)으로는 localhost 페이지가 오류 페이지로 떠서 직접 UI 확인 불가 → UI 확인은 사용자에게 요청.
- 자료실 샘플: data/samples (생성기 scripts/probe/MakeSamples.java, -Djava.awt.headless=true). 510MB 파일 포함 → 최종 정리 때 삭제 여부 확인.
- 자동 테스트: `.tools/jdk/bin/java -XX:-UsePerfData -Djava.io.tmpdir=data/tmp scripts/probe/{Stomp,LiveKit,Recording,Material}Probe.java` (on-sil 루트에서, Bash로 실행. PowerShell은 -D 인자를 깨뜨림). 채팅을 남기므로 끝나면 필요 시 data 초기화.
- STOMP: preserveReceiveOrder/PublishOrder 켬. 상태 스냅샷은 synchronized 안에서 전송(순서 역전 방지). 순서 보존 모드에서는 구독 거부 시 ERROR 프레임 대신 서버 로그만 남음(구독은 막힘).
- GitHub: origin = https://github.com/g2jkj0274/on-sil.git (master). 단계마다 커밋 후 push 한다 (사용자 요청, 2026-10-28).

## 다음에 이어서 할 일 (2026-11-02)
- 7단계까지 완료. 사용자 최종 확인 대기. data/samples 의 510MB 파일 삭제 여부 사용자에게 물어봄.
