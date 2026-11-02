# on-sil 설계

## 구성

```
 브라우저 (index.html + js 모듈)
   │  HTTP  REST /api/...          ┌────────────────────────────┐
   │  WebSocket STOMP /ws  ───────▶│ Spring Boot (127.0.0.1:8800)│── H2 파일 DB (data/db)
   │                               │  - 로그인/세션               │── data/recordings, data/materials
   │                               │  - 실시간 상태(메모리)       │
   │                               │  - LiveKit 토큰·권한 변경 ───┼──▶ LiveKit RoomService API
   │                               └────────────────────────────┘
   │  WebRTC (시그널 ws://127.0.0.1:7880, 미디어 UDP 127.0.0.1:7882)
   └──────────────────────────────▶ LiveKit server (SFU)
```

- **Spring Boot**: 회원·클래스·멤버십, 채팅 저장, 녹화·자료 파일, LiveKit 토큰 발급과 참가자 권한 변경.
- **STOMP**(Spring 내장 simple broker): 채팅, 접속자/손들기/마이크/공유/막혔어요 상태, 각종 알림.
- **LiveKit**: 영상·음성 중계(SFU). 앱이 발급한 토큰의 권한대로만 송출·구독할 수 있다.

## LiveKit 방과 권한

클래스마다 방이 2개다. identity = 로그인 아이디(username).

| 방 | 강사 | 조교 | 학생 |
|---|---|---|---|
| `class-{id}-live` | 구독 + 송출(camera, microphone, screen_share, screen_share_audio) | 구독 | 구독. 송출은 microphone만, **강사가 허용했을 때만** (`canPublish`) |
| `class-{id}-monitor` | 구독만, **hidden** | 구독만, **hidden** | 송출(screen_share)만, **구독 불가** |

- 마이크 허용/회수: 강사 STOMP `mic` → `LiveStateService` 상태 변경 → `LiveKitService.apply()`가 `RoomService.UpdateParticipant`로 권한 변경 (권한을 빼면 LiveKit이 해당 트랙을 내림). 학생이 아직 방에 없으면 404 → 무시하고, 나중에 받는 토큰에 반영된다. 어긋나면 브라우저가 `POST /livekit/mic-sync`.
- monitor 방에서 학생은 구독 권한이 없으므로 다른 학생 화면을 받을 방법이 없다. 강사·조교는 hidden이라 학생 쪽 참가자 목록에도 보이지 않는다.

## 실시간 상태 (메모리, `LiveStateService`)

클래스별: 접속자(세션 묶음), 손들기, 마이크 허용, 화면 공유 중, 막혔어요(누른 시각), 방송 중, 녹화 중.
상태가 바뀌면 스냅샷 전체를 다시 보낸다 (잠금 안에서 전송해 순서 역전 방지, STOMP 순서 보존 모드).

| 토픽 | 받는 사람 | 내용 |
|---|---|---|
| `/topic/class/{id}/public` | 멤버 전원 | `state`(접속자·손들기·마이크·방송·녹화), `chat`, `materials` |
| `/topic/class/{id}/staff` | 강사·조교 (구독 시 서버가 검사) | `staffState`(공유 중 학생, 막혔어요 순서) |
| `/user/queue/events` | 본인 | `share-request`, `mic`, `stuck-cleared`, `error` |

브라우저 → 서버 (`/app/class/{id}/...`): `join`, `leave`, `chat`, `hand`, `mic`(강사), `share-request`(강사·조교), `sharing`(학생), `stuck`(학생), `stuck-clear`(강사·조교), `broadcast`(강사).

모든 연결이 끊긴 사용자는 손들기·공유·막혔어요·마이크 허용이 정리되고, 강사가 나가면 방송·녹화 표시가 꺼진다.
STOMP가 재연결되면 강사는 송출 중일 때 `broadcast`를 다시 보내고, 녹화 표시는 다음 녹화 조각이 도착할 때 복구된다.

## REST API

모두 로그인 필요(세션 쿠키). 브라우저는 `X-Onsil-User: {내 id}`를 붙이고, 세션 사용자와 다르면 409 `SESSION_USER_CHANGED`.

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | `/api/signup`, `/api/login`, `/api/logout` | 누구나 | 가입·로그인(form)·로그아웃 |
| GET | `/api/me` | 로그인 | 내 정보 |
| GET/POST | `/api/classes` | 로그인 / 강사 계정 | 내 클래스 목록 / 만들기 |
| POST | `/api/classes/join` | 로그인 | 초대 코드로 가입 |
| GET | `/api/classes/{id}` | 멤버 | 클래스 정보 (강사에게만 초대 코드) |
| POST | `/api/classes/{id}/invite-code` | 강사 | 초대 코드 새로 발급 |
| GET | `/api/classes/{id}/members` | 멤버 | 멤버 목록 |
| PUT | `/api/classes/{id}/members/{userId}/role` | 강사 | 조교 지정/해제 |
| GET | `/api/classes/{id}/chat` | 멤버 | 최근 채팅 50개 |
| GET | `/api/classes/{id}/livekit/{live\|monitor}` | 멤버 | LiveKit 접속 정보·토큰 |
| POST | `/api/classes/{id}/livekit/mic-sync` | 멤버 | 허용된 마이크 권한 다시 반영 |
| GET/POST | `/api/classes/{id}/recordings` | 멤버 / 강사 | 녹화 목록 / 녹화 시작 |
| POST | `.../recordings/{rid}/chunks?seq=n` | 강사 | 녹화 조각(WebM) 이어 붙이기 |
| POST | `.../recordings/{rid}/finish` | 강사 | 녹화 끝 `{durationMs}` |
| PUT / DELETE | `.../recordings/{rid}/title`, `.../recordings/{rid}` | 강사 | 이름 바꾸기 / 삭제 |
| GET | `.../recordings/{rid}/file[?download=true]` | 멤버 | 재생(Range 지원)·다운로드 |
| GET/POST | `/api/classes/{id}/materials` | 멤버 / 강사·조교 | 자료 목록 / 업로드(multipart `files`) |
| DELETE | `.../materials/{mid}` | 강사·조교 | 삭제 |
| GET | `.../materials/{mid}/file[?view=true]` | 멤버 | 다운로드 / 안전한 형식만 브라우저에서 열기 |

## 녹화

LiveKit Egress는 Docker/Linux가 필요해 쓰지 않고, 강사 브라우저에서 녹화한다 (`recorder.js`).

1. `POST /recordings` → 녹화 id. `MediaRecorder`(webm vp8/opus, 2.5Mbps)를 `start(5000)`.
2. 영상: 강사 화면 트랙을 `MediaStreamTrackProcessor` → `MediaStreamTrackGenerator`로 중계한다. 화면을 다시 골라도 녹화 트랙이 바뀌지 않아 녹화가 이어진다 (Chrome/Edge). 지원하지 않는 브라우저는 화면이 바뀌면 녹화를 끝낸다.
3. 소리: `AudioContext`로 강사 마이크 + 화면 소리 + 구독 중인 학생 마이크를 섞는다. 트랙이 생기거나 없어질 때마다(`Live.renderAll → Recorder.sync`) 다시 연결.
4. 5초마다 나오는 조각을 순서대로 `POST chunks?seq=n` (실패 시 재시도, 중복 seq는 무시, 순서가 어긋나면 409).
5. `finish`에서 실제 길이를 기록.

MediaRecorder가 만든 WebM에는 길이(Duration)가 없어 재생 막대 탐색이 잘 안 된다. 서버(`WebmDuration`)가 첫 조각을 받을 때 Segment/Info에 `Duration = 0.0`(float64) 자리를 끼워 넣고, 끝날 때 그 위치에 실제 길이를 덮어쓴다 (Segment 크기가 "알 수 없음"이라 바이트를 끼워 넣어도 구조가 깨지지 않음).

끝 신호 없이 끊긴 녹화는 서버 시작 시, 그리고 목록 조회 시 마지막 조각 후 60초가 지났으면 "받은 데까지"로 마무리한다.

## 자료실

- 강사·조교만 업로드/삭제. 디스크에는 `data/materials/{classId}/{id}`로만 저장하고 원래 이름은 DB에 둔다 (이름의 경로·제어 문자는 제거).
- "열기"(inline)는 PDF·이미지·영상·음성·텍스트 화이트리스트만. HTML/SVG 등은 같은 출처에서 열리면 로그인 사용자 권한으로 스크립트가 실행될 수 있어 항상 `attachment` + `X-Content-Type-Options: nosniff`.
- 바뀌면 public 토픽에 `{"type":"materials"}` → 보고 있던 사람의 목록이 새로 고쳐진다.

## 데이터 모델 (H2, `ddl-auto=update`)

`User`(아이디, 비밀번호 해시, 이름, 계정 유형) · `Classroom`(이름, 초대 코드, 강사) · `Membership`(클래스, 사용자, 역할 TEACHER/TA/STUDENT) · `ChatMessage` · `Recording`(상태, 길이, 크기, 다음 조각 번호, Duration 위치) · `Material`(파일 이름, 크기, 올린 사람)

## 브라우저 모듈 (`static/js`)

| 파일 | 역할 |
|---|---|
| `api.js` | fetch 도우미, 토스트, 모달, 계정 바뀜 감지 |
| `app.js` | 로그인 사용자, 클래스 목록, 탭, 모듈 등록(`onEnter/onLeave/onTab`) |
| `realtime.js` | STOMP 연결·구독, 메시지를 type별로 전달 |
| `classroom.js` | 접속자·채팅·손들기·막혔어요·공유 요청 창 |
| `live.js` | 라이브 탭, live 방 (방송·시청·마이크) |
| `recorder.js` | 녹화 |
| `recordings.js` | 다시보기 탭 |
| `screenshare.js` | 학생 화면 공유 (monitor 방 송출) |
| `monitor.js` | 모니터링 탭 (monitor 방 구독) |
| `materials.js` | 자료실 탭 |
| `members.js` | 멤버 탭 |

## 폴더 밖에 흔적을 남기지 않는 방법

- `scripts/common.ps1`: 그 프로세스에서만 `JAVA_HOME=.tools/jdk`, `MAVEN_USER_HOME=.m2`, `TEMP/TMP=data/tmp`.
- `app/.mvn/maven.config`: `-Dmaven.repo.local=../.m2/repository`. `.mvn/jvm.config`·`MAVEN_OPTS`: `-XX:-UsePerfData`.
- 앱 JVM: `-XX:-UsePerfData -Djava.io.tmpdir=data\tmp`, `server.tomcat.basedir=data/tomcat`, 작업 폴더 = on-sil 루트(경로는 `data/...` 상대 경로라 한글·공백 경로를 JVM 인자로 넘기지 않음).
- LiveKit: `scripts/livekit.yaml` — 127.0.0.1 바인드, ICE/TCP 끔(0.0.0.0에 열리기 때문), UDP 7882는 루프백 후보만.
