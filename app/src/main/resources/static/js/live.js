// 라이브 탭: LiveKit live 방. 강사는 화면+마이크를 송출하고, 모두가 구독한다.
// 학생은 강사가 마이크를 허용했을 때만 마이크를 켤 수 있다.
'use strict';

const Live = {
  room: null,
  gen: 0,              // 클래스를 빠르게 바꿀 때 늦게 끝난 연결을 버리기 위한 번호
  broadcasting: false, // 서버 상태(강사가 방송 중인지)
  stateClassId: null,  // 마지막으로 상태를 받은 클래스
  stageTrack: null,    // 무대 <video> 에 붙어 있는 트랙
  speakers: new Set(), // 지금 말하고 있는 participant identity

  init() {
    Realtime.on('state', (s) => this.onState(s));
    // 실시간 연결이 끊겼다 붙으면 서버의 방송 상태가 초기화되므로, 아직 송출 중이면 다시 알린다
    Realtime.on('connected', (ok) => {
      const lp = ok && App.isTeacher && this.room && this.room.localParticipant;
      if (lp && (lp.isScreenShareEnabled || lp.isMicrophoneEnabled)) Realtime.send('broadcast', { on: true });
    });
    Realtime.on('mic', (e) => { if (!e.allow) this.setMic(false); });
  },

  async onEnter(cls) {
    // 앞 모듈(Classroom)이 연결하는 사이 상태가 먼저 도착했을 수 있다
    this.broadcasting = !!(Classroom.state && Classroom.state.broadcasting);
    this.speakers.clear();
    this.renderView();
    await this.connect(cls);
  },

  async onLeave() {
    this.gen++;
    await Recorder.stop();
    if (this.broadcasting && App.isTeacher) Realtime.send('broadcast', { on: false });
    this.broadcasting = false;
    this.stateClassId = null;
    await this.disconnect();
    this.setLiveBadge(false);
  },

  onTab(tab) {
    // adaptiveStream 이 보이지 않는 영상의 수신을 줄이므로, 탭에 돌아오면 다시 붙여 준다
    if (tab === 'live') this.renderStage();
  },

  // ---------------- 연결 ----------------

  async connect(cls) {
    const gen = ++this.gen;
    this.setStatus('미디어 서버에 연결 중...');
    let room;
    try {
      const t = await api('GET', `/api/classes/${cls.id}/livekit/live`);
      if (gen !== this.gen) return;
      const L = LivekitClient;
      room = new L.Room({
        adaptiveStream: true,
        dynacast: true,
        publishDefaults: { screenShareEncoding: L.ScreenSharePresets.h1080fps15.encoding },
      });
      this.bindRoom(room);
      await room.connect(t.url, t.token);
      if (gen !== this.gen) { room.disconnect(); return; }
      this.room = room;
      this.setStatus('');
      this.renderAll();
    } catch (e) {
      console.error(e);
      if (room) room.disconnect();
      if (gen !== this.gen) return;
      this.setStatus(`미디어 서버에 연결하지 못했습니다: ${e.message}`, true);
    }
  },

  async disconnect() {
    const room = this.room;
    this.room = null;
    this.detachStage();
    document.getElementById('live-audio')?.replaceChildren();
    if (room) await room.disconnect();
  },

  bindRoom(room) {
    const E = LivekitClient.RoomEvent;
    const rerender = () => { if (room === this.room) this.renderAll(); };
    room
      .on(E.TrackSubscribed, (track) => {
        if (track.kind === 'audio') document.getElementById('live-audio')?.appendChild(track.attach());
        rerender();
      })
      .on(E.TrackUnsubscribed, (track) => { track.detach().forEach((el) => el.remove()); rerender(); })
      .on(E.LocalTrackPublished, rerender)
      .on(E.LocalTrackUnpublished, (pub) => {
        // 강사가 브라우저의 "공유 중지" 버튼으로 화면 공유를 끈 경우
        if (pub.source === LivekitClient.Track.Source.ScreenShare && this.broadcasting && App.isTeacher) {
          toast('화면 공유가 중지되었습니다. "화면 다시 선택"으로 다시 공유하거나 방송을 종료하세요.');
        }
        rerender();
      })
      .on(E.TrackMuted, rerender)
      .on(E.TrackUnmuted, rerender)
      .on(E.ParticipantConnected, rerender)
      .on(E.ParticipantDisconnected, rerender)
      .on(E.ParticipantPermissionsChanged, rerender)
      .on(E.AudioPlaybackStatusChanged, rerender)
      .on(E.ActiveSpeakersChanged, (speakers) => {
        this.speakers = new Set(speakers.map((p) => p.identity));
        if (room === this.room) this.renderVoices();
      })
      .on(E.Reconnecting, () => { if (room === this.room) this.setStatus('미디어 서버와 다시 연결하는 중...'); })
      .on(E.Reconnected, () => { if (room === this.room) this.setStatus(''); })
      .on(E.Disconnected, () => {
        if (room !== this.room) return;
        this.room = null;
        this.setStatus('미디어 서버와 연결이 끊겼습니다.', true);
        this.renderAll();
      });
  },

  // ---------------- 서버 상태 ----------------

  onState(s) {
    const was = this.broadcasting;
    const first = this.stateClassId !== s.classId; // 들어오자마자 받은 상태면 알림 생략
    this.stateClassId = s.classId;
    this.broadcasting = s.broadcasting;
    this.setLiveBadge(s.broadcasting);
    if (!App.isTeacher && s.broadcasting && !was && !first) toast('강사가 방송을 시작했습니다.');
    this.renderAll();
  },

  setLiveBadge(on) {
    if (!App.current) return;
    const li = document.querySelector(`#class-list li[data-id="${App.current.id}"] [data-live]`);
    if (li) li.classList.toggle('hidden', !on);
  },

  // ---------------- 강사: 방송 ----------------

  async startBroadcast() {
    if (!this.room) return toast('미디어 서버에 연결되지 않았습니다.', 'error');
    const lp = this.room.localParticipant;
    try {
      await lp.setScreenShareEnabled(true, { audio: true, selfBrowserSurface: 'exclude' });
    } catch (e) {
      if (e.name !== 'NotAllowedError') toast(`화면 공유를 시작하지 못했습니다: ${e.message}`, 'error');
      return; // 화면 선택 창에서 취소함
    }
    try {
      await lp.setMicrophoneEnabled(true);
    } catch (e) {
      toast(`마이크를 켜지 못했습니다(화면만 송출합니다): ${e.message}`, 'error');
    }
    Realtime.send('broadcast', { on: true });
  },

  async stopBroadcast() {
    await Recorder.stop();
    if (this.room) {
      const lp = this.room.localParticipant;
      try { await lp.setScreenShareEnabled(false); } catch (e) { console.error(e); }
      try { await lp.setMicrophoneEnabled(false); } catch (e) { console.error(e); }
    }
    Realtime.send('broadcast', { on: false });
  },

  async reselectScreen() {
    const lp = this.room && this.room.localParticipant;
    if (!lp) return;
    try {
      // 새 화면을 먼저 고르게 한 뒤 바꾼다 (취소하면 기존 화면 유지)
      const tracks = await lp.createScreenTracks({ audio: true, selfBrowserSurface: 'exclude' });
      await lp.setScreenShareEnabled(false);
      for (const t of tracks) await lp.publishTrack(t);
    } catch (e) {
      if (e.name !== 'NotAllowedError') toast(`화면을 바꾸지 못했습니다: ${e.message}`, 'error');
    }
  },

  // ---------------- 마이크 (강사 / 허용된 학생) ----------------

  get micOn() { return !!(this.room && this.room.localParticipant.isMicrophoneEnabled); },

  async setMic(on) {
    if (!this.room) return;
    const lp = this.room.localParticipant;
    try {
      if (on && !App.isTeacher && !(lp.permissions && lp.permissions.canPublish)) {
        // 허용 순간에 방에 없었으면 권한이 아직 반영되지 않았을 수 있다
        await api('POST', `/api/classes/${App.current.id}/livekit/mic-sync`);
        await new Promise((r) => setTimeout(r, 700));
        if (!(lp.permissions && lp.permissions.canPublish)) throw new Error('마이크 권한이 아직 없습니다.');
      }
      await lp.setMicrophoneEnabled(on);
    } catch (e) {
      if (on) toast(`마이크를 켜지 못했습니다: ${e.message}`, 'error');
    }
    this.renderAll();
  },

  // ---------------- 화면 ----------------

  renderView() {
    document.getElementById('view-live').innerHTML = `
      <div id="live-wrap">
        <div id="live-stage">
          <video id="live-video" autoplay playsinline muted></video>
          <div id="live-placeholder" class="empty-state"></div>
        </div>
        <div id="live-bar">
          <span id="live-state" class="badge"></span>
          <span id="live-rec" class="badge live hidden"></span>
          <span id="live-voices" class="dim"></span>
          <span class="spacer"></span>
          <span id="live-controls" class="row"></span>
        </div>
        <div id="live-status" class="dim"></div>
        <div id="live-audio" class="hidden"></div>
      </div>`;
  },

  setStatus(msg, isError = false) {
    const el = document.getElementById('live-status');
    if (!el) return;
    el.textContent = msg;
    el.classList.toggle('error', isError);
    if (isError && App.current) {
      const retry = document.createElement('button');
      retry.className = 'small secondary';
      retry.textContent = '다시 연결';
      retry.onclick = () => this.connect(App.current);
      el.append(' ', retry);
    }
  },

  renderAll() {
    if (!document.getElementById('live-wrap')) return;
    this.renderStage();
    this.renderControls();
    this.renderVoices();
    Recorder.sync();
  },

  /** 무대에 띄울 화면 공유 트랙 (강사 본인은 내 화면 미리보기). */
  stageCandidate() {
    if (!this.room) return null;
    const S = LivekitClient.Track.Source.ScreenShare;
    const local = this.room.localParticipant.getTrackPublication(S);
    if (local && local.track) return local.track;
    for (const p of this.room.remoteParticipants.values()) {
      const pub = p.getTrackPublication(S);
      if (pub && pub.track && !pub.isMuted) return pub.track;
    }
    return null;
  },

  detachStage() {
    const video = document.getElementById('live-video');
    if (this.stageTrack && video) this.stageTrack.detach(video);
    this.stageTrack = null;
  },

  renderStage() {
    const video = document.getElementById('live-video');
    const ph = document.getElementById('live-placeholder');
    if (!video) return;
    const track = this.stageCandidate();
    if (track !== this.stageTrack) {
      this.detachStage();
      if (track) track.attach(video);
      this.stageTrack = track;
    }
    video.classList.toggle('hidden', !track);
    ph.classList.toggle('hidden', !!track);
    if (!track) {
      ph.innerHTML = this.broadcasting
        ? '<div>강사가 화면을 다시 고르는 중입니다.<br><span class="dim">음성은 계속 들립니다.</span></div>'
        : App.isTeacher
          ? '<div>아직 방송 전입니다.<br><span class="dim">아래 "방송 시작"을 누르면 공유할 화면을 고를 수 있습니다.</span></div>'
          : '<div>아직 방송 전입니다.<br><span class="dim">강사가 방송을 시작하면 여기에 화면이 나옵니다.</span></div>';
    }
  },

  renderControls() {
    const state = document.getElementById('live-state');
    state.textContent = this.broadcasting ? 'LIVE' : '방송 전';
    state.classList.toggle('live', this.broadcasting);
    // 녹화 표시: 강사는 경과 시간, 다른 사람은 서버 상태 기준
    const rec = document.getElementById('live-rec');
    const recording = App.isTeacher ? Recorder.active : !!(Classroom.state && Classroom.state.recording);
    rec.classList.toggle('hidden', !recording);
    rec.textContent = App.isTeacher && Recorder.active ? `● REC ${Recorder.elapsed()}` : '● 녹화 중';

    const box = document.getElementById('live-controls');
    box.innerHTML = '';
    const add = (label, onclick, cls = '') => {
      const b = document.createElement('button');
      b.textContent = label;
      if (cls) b.className = cls;
      b.onclick = onclick;
      b.disabled = !this.room;
      box.appendChild(b);
    };
    if (this.room && !this.room.canPlaybackAudio) {
      add('🔊 소리 켜기', () => this.room.startAudio().then(() => this.renderAll()), 'success');
    }
    if (App.isTeacher) {
      if (this.broadcasting) {
        add(this.micOn ? '🎤 마이크 끄기' : '🎤 마이크 켜기', () => this.setMic(!this.micOn), 'secondary');
        add('🖥️ 화면 다시 선택', () => this.reselectScreen(), 'secondary');
        if (Recorder.active) add('⏹ 녹화 종료', () => Recorder.stop(), 'secondary');
        else add('⏺ 녹화 시작', () => Recorder.start(), 'secondary');
        if (Recorder.busy) box.lastChild.disabled = true;
        add('■ 방송 종료', () => this.stopBroadcast(), 'danger');
      } else {
        add('● 방송 시작', () => this.startBroadcast());
      }
    } else if (Classroom.myMicAllowed) {
      add(this.micOn ? '🎤 마이크 끄기' : '🎤 마이크 켜기', () => this.setMic(!this.micOn), this.micOn ? 'danger' : 'success');
    }
  },

  /** 마이크를 켠 사람들 (말하는 사람은 강조). */
  renderVoices() {
    const el = document.getElementById('live-voices');
    if (!el) return;
    if (!this.room) { el.innerHTML = ''; return; }
    const people = [this.room.localParticipant, ...this.room.remoteParticipants.values()];
    const names = people
      .filter((p) => p.isMicrophoneEnabled)
      .map((p) => {
        const name = esc(p.name || p.identity) + (p === this.room.localParticipant ? ' (나)' : '');
        return this.speakers.has(p.identity) ? `<b class="speaking">🎤 ${name}</b>` : `🎤 ${name}`;
      });
    el.innerHTML = names.join(' · ');
  },
};
Live.init();
App.register(Live);
