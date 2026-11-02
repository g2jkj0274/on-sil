// 강사: 방송 녹화. 강사 브라우저에서 MediaRecorder 로 녹화하고 5초마다 서버에 조각을 올린다.
// 영상 = 강사 화면 공유, 소리 = 강사 마이크 + 화면 소리 + 허용된 학생 마이크를 섞은 것.
'use strict';

const Recorder = {
  active: false,
  busy: false,       // 시작/종료 처리 중
  rec: null,         // MediaRecorder
  classId: null,
  recId: null,
  seq: 0,
  queue: Promise.resolve(),
  failed: false,
  startedAt: 0,
  timer: null,
  // 소리 섞기
  ctx: null,
  dest: null,
  sources: new Map(), // MediaStreamTrack.id → { track, node }
  // 화면 중계: 화면을 다시 골라도 녹화 트랙은 그대로 유지
  gen: null,
  writer: null,
  reader: null,
  relaySrc: null,
  relayClone: null,
  directVideo: null,  // 중계를 못 쓰는 브라우저에서 직접 녹화하는 트랙

  init() {
    window.addEventListener('beforeunload', (e) => {
      if (this.active) { e.preventDefault(); e.returnValue = ''; } // 녹화 중 창 닫기 경고
    });
  },

  get canRelay() { return 'MediaStreamTrackProcessor' in window && 'MediaStreamTrackGenerator' in window; },

  mimeType() {
    for (const t of ['video/webm;codecs=vp8,opus', 'video/webm;codecs=vp9,opus', 'video/webm']) {
      if (window.MediaRecorder && MediaRecorder.isTypeSupported(t)) return t;
    }
    return null;
  },

  screenTrack() {
    const room = Live.room;
    const pub = room && room.localParticipant.getTrackPublication(LivekitClient.Track.Source.ScreenShare);
    return pub && pub.track ? pub.track.mediaStreamTrack : null;
  },

  async start() {
    if (this.active || this.busy || !App.isTeacher || !Live.room || !Live.broadcasting) return;
    const mime = this.mimeType();
    if (!mime) return toast('이 브라우저는 녹화를 지원하지 않습니다. Chrome 또는 Edge 를 사용하세요.', 'error');
    const screen = this.screenTrack();
    if (!screen) return toast('화면을 공유하고 있을 때만 녹화할 수 있습니다.', 'error');

    this.busy = true;
    Live.renderAll();
    try {
      // 소리
      this.ctx = new AudioContext();
      this.dest = this.ctx.createMediaStreamDestination();
      // 영상
      let video;
      if (this.canRelay) {
        this.gen = new MediaStreamTrackGenerator({ kind: 'video' });
        this.writer = this.gen.writable.getWriter();
        video = this.gen;
      } else {
        video = this.directVideo = screen.clone();
        this.relaySrc = screen;
      }
      this.sync();

      const cls = App.current;
      const r = await api('POST', `/api/classes/${cls.id}/recordings`);
      this.classId = cls.id;
      this.recId = r.id;
      this.seq = 0;
      this.failed = false;
      this.queue = Promise.resolve();

      const stream = new MediaStream([video, ...this.dest.stream.getAudioTracks()]);
      this.rec = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: 2_500_000, audioBitsPerSecond: 128_000 });
      this.rec.ondataavailable = (e) => { if (e.data && e.data.size) this.enqueue(e.data); };
      this.rec.onerror = (e) => { toast(`녹화 오류: ${e.error ? e.error.message : ''}`, 'error'); this.stop(); };
      this.rec.start(5000);
      this.active = true;
      this.startedAt = Date.now();
      this.timer = setInterval(() => this.renderTime(), 1000);
      toast('녹화를 시작했습니다.');
    } catch (e) {
      console.error(e);
      toast(`녹화를 시작하지 못했습니다: ${e.message}`, 'error');
      this.cleanup();
    } finally {
      this.busy = false;
      Live.renderAll();
    }
  },

  async stop() {
    if (!this.active || this.busy) return;
    this.busy = true;
    this.active = false;
    clearInterval(this.timer);
    Live.renderAll();
    const { classId, recId } = this;
    const durationMs = Date.now() - this.startedAt;
    try {
      if (this.rec.state !== 'inactive') {
        const stopped = new Promise((r) => { this.rec.onstop = r; });
        this.rec.stop(); // 마지막 조각(dataavailable)이 나온 뒤 stop 이벤트가 온다
        await stopped;
      }
      await this.queue;
      await api('POST', `/api/classes/${classId}/recordings/${recId}/finish`, { durationMs });
      toast(this.failed ? '녹화를 끝냈지만 일부 조각을 저장하지 못했습니다.' : '녹화를 저장했습니다. 다시보기 탭에서 볼 수 있습니다.',
        this.failed ? 'error' : 'info');
    } catch (e) {
      console.error(e);
      toast(`녹화를 마무리하지 못했습니다: ${e.message}`, 'error');
    } finally {
      this.cleanup();
      this.busy = false;
      Live.renderAll();
    }
  },

  cleanup() {
    this.stopPump();
    if (this.writer) { this.writer.close().catch(() => {}); this.writer = null; }
    if (this.gen) { this.gen.stop(); this.gen = null; }
    if (this.directVideo) { this.directVideo.stop(); this.directVideo = null; }
    for (const s of this.sources.values()) s.node.disconnect();
    this.sources.clear();
    if (this.ctx) { this.ctx.close().catch(() => {}); this.ctx = null; }
    this.dest = null;
    this.rec = null;
  },

  // ---------------- 업로드 ----------------

  enqueue(blob) {
    const seq = this.seq++;
    const { classId, recId } = this;
    this.queue = this.queue.then(() => this.upload(classId, recId, seq, blob));
  },

  async upload(classId, recId, seq, blob) {
    if (this.failed) return;
    for (let attempt = 1; ; attempt++) {
      try {
        await api('POST', `/api/classes/${classId}/recordings/${recId}/chunks?seq=${seq}`, blob);
        return;
      } catch (e) {
        if (attempt >= 4) {
          this.failed = true;
          toast(`녹화 조각을 올리지 못해 녹화를 멈춥니다: ${e.message}`, 'error');
          setTimeout(() => this.stop());
          return;
        }
        await new Promise((r) => setTimeout(r, 1000 * attempt));
      }
    }
  },

  // ---------------- 트랙 맞추기 (Live 가 다시 그릴 때마다 호출) ----------------

  sync() {
    if (!this.ctx || !Live.room) return;
    const room = Live.room;
    const S = LivekitClient.Track.Source;
    // 소리: 내 마이크, 내 화면 소리, 학생 마이크
    const tracks = [];
    for (const src of [S.Microphone, S.ScreenShareAudio]) {
      const pub = room.localParticipant.getTrackPublication(src);
      if (pub && pub.track) tracks.push(pub.track.mediaStreamTrack);
    }
    for (const p of room.remoteParticipants.values()) {
      for (const pub of p.audioTrackPublications.values()) if (pub.track) tracks.push(pub.track.mediaStreamTrack);
    }
    const ids = new Set();
    for (const t of tracks) {
      if (!t || t.readyState === 'ended') continue;
      ids.add(t.id);
      if (this.sources.has(t.id)) continue;
      const node = this.ctx.createMediaStreamSource(new MediaStream([t]));
      node.connect(this.dest);
      this.sources.set(t.id, { track: t, node });
    }
    for (const [id, s] of this.sources) {
      if (!ids.has(id)) { s.node.disconnect(); this.sources.delete(id); }
    }

    // 영상: 화면이 바뀌었으면 중계 대상을 바꾼다
    const screen = this.screenTrack();
    if (this.gen) {
      if (screen !== this.relaySrc) {
        if (screen) this.pump(screen); else this.stopPump();
      }
    } else if (this.active && this.directVideo && screen !== this.relaySrc) {
      // 중계를 못 쓰는 브라우저: 처음 고른 화면만 녹화할 수 있다
      toast('이 브라우저에서는 화면을 바꾸면 녹화를 이어갈 수 없어 녹화를 끝냅니다.', 'error');
      this.stop();
    }
  },

  async pump(src) {
    this.stopPump();
    const clone = src.clone();
    this.relaySrc = src;
    this.relayClone = clone;
    const reader = new MediaStreamTrackProcessor({ track: clone }).readable.getReader();
    this.reader = reader;
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        if (this.reader !== reader || !this.writer) { value.close(); break; }
        await this.writer.write(value); // 쓰고 나면 프레임은 닫힌다
      }
    } catch { /* 화면이 바뀌거나 녹화가 끝나 읽기를 취소함 */ }
  },

  stopPump() {
    if (this.reader) { this.reader.cancel().catch(() => {}); this.reader = null; }
    if (this.relayClone) { this.relayClone.stop(); this.relayClone = null; }
    this.relaySrc = null;
  },

  // ---------------- 표시 ----------------

  elapsed() {
    const s = Math.floor((Date.now() - this.startedAt) / 1000);
    return formatDuration(s * 1000);
  },

  renderTime() {
    const el = document.getElementById('live-rec');
    if (el && this.active) el.textContent = `● REC ${this.elapsed()}`;
  },
};
Recorder.init();
