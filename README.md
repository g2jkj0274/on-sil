# on-sil — 수업용 온라인 교실 (MVP)

강사가 화면과 목소리로 수업을 방송하고, 학생은 채팅·손들기로 참여하며, 강사·조교는 학생들의 화면을 한눈에 모니터링하는 온라인 교실입니다.
**한 대의 Windows PC 안에서만** 동작하는 로컬 MVP이며, 설치한 모든 것이 이 폴더 안에만 남습니다.

## 주요 기능

| 기능 | 강사 | 조교 | 학생 |
|---|:-:|:-:|:-:|
| 클래스 만들기, 초대 코드 발급, 조교 지정 | ✅ | | |
| 초대 코드로 클래스 가입 | | | ✅ |
| 채팅, 접속자 목록 | ✅ | ✅ | ✅ |
| **라이브 방송** (화면 공유 + 마이크) | 송출 | 시청 | 시청 |
| 손들기 → 강사가 **마이크 허용/회수** | 허용·회수 | | 손들기·말하기 |
| **화면 공유 요청** → 학생 수락 시 공유 | 요청 | 요청 | 수락·공유 |
| **모니터링** (학생 화면 격자, 막혔어요 학생 우선) | ✅ | ✅ | |
| 🆘 막혔어요 신호 / 확인 | 확인 | 확인 | 보내기 |
| **녹화** (방송 화면 + 강사·학생 목소리) | ✅ | | |
| 다시보기 (재생·탐색·다운로드) | ✅ (+이름 바꾸기·삭제) | ✅ | ✅ |
| **자료실** (업로드·다운로드·열기) | 올리기·삭제 | 올리기·삭제 | 내려받기 |

학생의 화면은 **강사·조교만** 볼 수 있습니다. 학생끼리는 서로의 화면을 볼 수 없습니다.

## 준비물

- Windows 10/11 (64비트)
- 처음 설치할 때만 인터넷 연결 (JDK·LiveKit 서버·라이브러리를 내려받음, 약 350MB. 설치 후 폴더 크기 약 750MB)
- 브라우저: **Chrome 또는 Edge 권장** (녹화는 Chrome/Edge에서만 지원)
- 따로 설치할 것은 없습니다. Java·Node·Docker가 없어도 됩니다.

## 설치와 실행

폴더 안의 파일을 더블클릭합니다.

| 파일 | 하는 일 |
|---|---|
| `setup.cmd` | 처음 한 번. JDK 21과 LiveKit 서버를 `.tools\`에 받고 앱을 빌드합니다 (몇 분 걸림). |
| `start.cmd` | 서버 두 개(LiveKit, 앱)를 켭니다. 소스가 바뀌었으면 자동으로 다시 빌드합니다. |
| `stop.cmd` | 서버를 끕니다. |

실행 후 브라우저에서 **http://localhost:8800** 에 접속합니다.

### 기본 계정 (비밀번호는 모두 `1234`)

| 아이디 | 이름 | 역할 (데모 클래스) |
|---|---|---|
| `teacher1` | 김강사 | 강사 |
| `ta1` | 이조교 | 조교 |
| `student1` ~ `student4` | 박학생·최학생·정학생·강학생 | 학생 |

- 데모 클래스 초대 코드: `DEMO01`
- 로그인 화면에서 새 계정(강사/학생)을 만들 수도 있습니다.

> **한 브라우저에서는 한 계정만** 쓸 수 있습니다 (로그인 쿠키를 탭끼리 공유하기 때문).
> 여러 계정으로 동시에 테스트하려면 Chrome·Edge·시크릿 창(하나)·다른 브라우저 프로필처럼 **서로 다른 브라우저**를 쓰세요.
> 같은 브라우저에서 다른 계정으로 로그인하면 이전 탭에 안내가 뜨고 새로 고침됩니다.

## 사용 방법

### 강사
1. **라이브** 탭 → `● 방송 시작` → 공유할 화면 선택. 마이크도 함께 켜집니다.
   - `🖥️ 화면 다시 선택`, `🎤 마이크 끄기/켜기`, `■ 방송 종료`
   - 방송 중 `⏺ 녹화 시작` / `⏹ 녹화 종료`. 녹화 중에 화면을 바꿔도 녹화는 이어집니다.
2. 오른쪽 접속자 목록에서 학생별로
   - ✋ 손든 학생 → `🎤허용` (학생이 마이크를 켤 수 있게 됨) / `🎤회수`
   - `공유 요청` (학생 화면 공유 요청), 🆘 학생 → `확인`
3. **모니터링** 탭: 공유 중인 학생 화면이 격자로 보입니다. 🆘 학생은 빨간 테두리로 맨 앞에 옵니다. 타일을 누르면 크게 보기(Esc로 닫기). `전체 학생에게 공유 요청` 버튼.
4. **다시보기** 탭: 녹화 재생·다운로드·이름 바꾸기·삭제.
5. **자료실** 탭: `파일 올리기` 또는 화면에 끌어다 놓기 (파일당 500MB까지).
6. **멤버** 탭: 초대 코드 확인·새로 발급, 학생을 조교로 지정/해제 (지정된 사람은 클래스를 다시 열면 적용).

### 학생
- 라이브 탭에서 강사 화면을 봅니다. 소리가 안 나오면 `🔊 소리 켜기`를 누르세요 (브라우저 자동 재생 정책).
- `✋ 손들기` → 강사가 허용하면 라이브 탭에 `🎤 마이크 켜기`가 생깁니다.
- `🆘 막혔어요`: 강사·조교에게 도움 요청.
- `🖥️ 화면 공유` 또는 공유 요청 수락: 내 화면은 강사·조교만 봅니다. 언제든 `공유 중지`.

## 폴더 구조

```
on-sil\
├─ setup.cmd / start.cmd / stop.cmd   실행 파일 (실제 내용은 scripts\*.ps1)
├─ app\                               Spring Boot 앱 소스 (Java 21)
│  └─ src\main\resources\static\      브라우저 화면 (HTML/CSS/JS)
├─ scripts\                           설치·실행 스크립트, LiveKit 설정, 테스트(probe)
├─ docs\                              설계 문서
├─ .tools\    (setup이 만듦)          JDK, LiveKit 서버
├─ .m2\       (setup이 만듦)          Maven과 라이브러리
└─ data\      (실행하면 생김)          DB, 녹화, 자료, 로그, 임시 파일
   ├─ db\            사용자·클래스·채팅·목록 (H2 파일 DB)
   ├─ recordings\    녹화 파일 (클래스별 .webm)
   ├─ materials\     자료실 파일 (클래스별, 파일 번호로 저장)
   ├─ logs\          app.log, livekit.log
   ├─ samples\       자료실 테스트용 샘플 (선택)
   └─ tmp\, tomcat\, run\
```

- **백업**: 서버를 끄고 `data\` 폴더를 통째로 복사하면 됩니다.
- **초기화**: 서버를 끄고 `data\db`, `data\recordings`, `data\materials`를 지운 뒤 `start.cmd` → 기본 계정·데모 클래스가 다시 만들어집니다.
- **완전 삭제**: 서버를 끄고 `on-sil` 폴더를 지우면 끝입니다.

## 이 폴더 밖에 아무것도 남기지 않습니다

- 전역 설치·시스템/사용자 환경 변수·레지스트리를 바꾸지 않습니다. `JAVA_HOME`, Maven 경로, 임시 폴더(`TEMP`)는 **스크립트가 실행되는 동안 그 프로세스에서만** 폴더 안으로 지정됩니다.
- JVM은 `-XX:-UsePerfData`(사용자 임시 폴더에 성능 파일을 만들지 않음), 임시 파일은 `data\tmp`, Maven 저장소는 `.m2\`.
- 경로에 한글이나 공백이 있어도 동작합니다.

## 보안·네트워크

- 모든 서버는 **127.0.0.1(이 PC)에만** 열립니다: 앱 TCP 8800, LiveKit TCP 7880, LiveKit 미디어 UDP 7882. 다른 PC에서는 접속할 수 없습니다.
- 로컬 MVP 설정이므로 그대로 외부에 공개하면 안 됩니다: LiveKit 개발용 키(`devkey/secret`), CSRF 토큰 대신 SameSite=Strict 쿠키, HTTP(암호화 없음).
- 자료실의 "열기"는 PDF·이미지·영상·음성·텍스트 파일만 허용하고, HTML·SVG 등은 항상 내려받기만 됩니다 (업로드된 파일 속 스크립트가 실행되지 않도록).

## 알려진 제한

- **녹화는 강사 브라우저에서** 이루어집니다 (LiveKit의 서버 녹화 기능 Egress는 Docker/Linux가 필요해 쓰지 않음). 녹화 중 강사 브라우저를 닫으면 그때까지 받은 부분만 저장됩니다. 녹화 중 창을 닫으려 하면 경고가 뜹니다.
- 녹화 형식은 WebM(VP8+Opus)입니다. Chrome·Edge·Firefox·VLC에서 재생됩니다.
- 수업 중 상태(접속자, 손들기, 마이크 허용, 막혔어요, 방송 여부)는 메모리에만 있어 서버를 다시 켜면 사라집니다. 채팅·녹화·자료는 남습니다.
- 같은 계정은 한 브라우저 탭에서만 미디어(방송·화면 공유)를 쓸 수 있습니다.

## 문제 해결

| 증상 | 확인할 것 |
|---|---|
| `start.cmd`가 "시작되지 않았습니다"로 끝남 | `data\logs\app.log` 끝부분. "Port 8800 was already in use"면 다른 프로그램이 8800을 쓰는 중이거나 Windows가 그 포트를 예약한 것입니다 (`netsh interface ipv4 show excludedportrange protocol=tcp`로 확인). 이 경우 `app\src\main\resources\application.properties`의 `server.port`와 `scripts\start.ps1`의 8800을 함께 바꾸세요. |
| 학생에게 소리가 안 들림 | 학생 라이브 탭의 `🔊 소리 켜기` 버튼 |
| 화면 공유 창이 안 뜸 | 버튼을 직접 눌러야 뜹니다(브라우저 정책). 브라우저 주소창의 화면 공유 권한 확인 |
| "미디어 서버에 연결하지 못했습니다" | LiveKit이 꺼졌을 수 있음 → `stop.cmd` 후 `start.cmd`, `data\logs\livekit.log` 확인 |
| "다른 계정으로 로그인됨" 창 | 같은 브라우저 다른 탭에서 다른 계정으로 로그인한 것. 계정마다 다른 브라우저 사용 |

## 개발자용

- 버전: Temurin JDK 21.0.12.1, Spring Boot 3.5.16, LiveKit server 1.13.11 / livekit-server(Java) 0.16.0 / livekit-client 2.23.0, @stomp/stompjs 7.5.0, Maven 3.9.16.
- 설계와 API: [`docs/architecture.md`](docs/architecture.md)
- 자동 테스트(서버가 켜져 있을 때, on-sil 폴더에서 실행). 각 줄에 PASS/FAIL이 찍힙니다.
  ```
  .tools\jdk\bin\java -XX:-UsePerfData -Djava.io.tmpdir=data/tmp scripts\probe\StompProbe.java      (채팅·접속자·손들기·권한)
  .tools\jdk\bin\java -XX:-UsePerfData -Djava.io.tmpdir=data/tmp scripts\probe\LiveKitProbe.java    (토큰·마이크 권한·모니터 방)
  .tools\jdk\bin\java -XX:-UsePerfData -Djava.io.tmpdir=data/tmp scripts\probe\RecordingProbe.java  (녹화 업로드·길이·재생)
  .tools\jdk\bin\java -XX:-UsePerfData -Djava.io.tmpdir=data/tmp scripts\probe\MaterialProbe.java   (자료실)
  ```
  테스트는 기본 계정과 데모 클래스(id 1)를 사용하고, 채팅 메시지를 남깁니다.
- 자료실 샘플 파일 만들기: `.tools\jdk\bin\java -XX:-UsePerfData -Djava.io.tmpdir=data/tmp -Djava.awt.headless=true scripts\probe\MakeSamples.java` → `data\samples\`
