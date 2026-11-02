// 학생: 내 화면을 LiveKit monitor 방으로 공유한다. 강사·조교만 볼 수 있다 (학생은 구독 권한이 없음).
'use strict';

const ScreenShare = {
  room: null,
  tracks: [],
  active: false,
  starting: false,

  init() {
    // STOMP 가 다시 연결되면 서버의 공유 상태가 지워졌으므로 다시 알린다
    Realtime.on('connected', (ok) => { if (ok && this.active) Realtime.send('sharing', { on: true }); });
  },

  onEnter() { this.render(); },
  async onLeave() { await this.stop(false); },

  toggle() { return this.active ? this.stop() : this.start(); },

  /** 버튼/수락 클릭에서 바로 불러야 한다 (화면 선택 창은 사용자 클릭 직후에만 열 수 있음). */
  async start() {
    if (this.active || this.starting || !App.current || App.current.myRole !== 'STUDENT') return;
    const cls = App.current;
    const L = LivekitClient;
    this.starting = true;
    this.render();
    let room = null;
    try {
      // 1) 화면 고르기 (클릭 직후에 해야 하므로 토큰보다 먼저)
      try {
        this.tracks = await L.createLocalScreenTracks({ audio: false, selfBrowserSurface: 'exclude' });
      } catch (e) {
        if (e.name !== 'NotAllowedError') toast(`화면 공유를 시작하지 못했습니다: ${e.message}`, 'error');
        return;
      }
      // 2) monitor 방에 들어가서 송출
      const t = await api('GET', `/api/classes/${cls.id}/livekit/monitor`);
      room = new L.Room({ dynacast: true });
      room.on(L.RoomEvent.Disconnected, () => {
        if (room !== this.room) return;
        toast('화면 공유 연결이 끊겼습니다.', 'error');
        this.stop();
      });
      await room.connect(t.url, t.token);
      if (App.current !== cls) throw new Error('클래스를 벗어났습니다.');
      for (const track of this.tracks) {
        // 브라우저의 "공유 중지" 버튼
        track.mediaStreamTrack.addEventListener('ended', () => this.stop());
        await room.localParticipant.publishTrack(track, {
          screenShareEncoding: { maxBitrate: 2_000_000, maxFramerate: 10 },
        });
      }
      this.room = room;
      this.active = true;
      Realtime.send('sharing', { on: true });
      toast('화면 공유를 시작했습니다. 강사·조교만 볼 수 있습니다.');
    } catch (e) {
      console.error(e);
      if (room) room.disconnect();
      for (const tr of this.tracks) tr.stop();
      this.tracks = [];
      if (App.current === cls) toast(`화면 공유를 시작하지 못했습니다: ${e.message}`, 'error');
    } finally {
      this.starting = false;
      this.render();
    }
  },

  async stop(notify = true) {
    if (!this.active) return;
    this.active = false;
    const room = this.room;
    this.room = null;
    for (const tr of this.tracks) tr.stop();
    this.tracks = [];
    if (notify && App.current) Realtime.send('sharing', { on: false });
    if (room) await room.disconnect();
    this.render();
  },

  render() {
    const b = document.getElementById('btn-share');
    b.textContent = this.starting ? '🖥️ 준비 중...' : this.active ? '🖥️ 공유 중지' : '🖥️ 화면 공유';
    b.disabled = this.starting;
    b.classList.toggle('on', this.active);
  },
};
ScreenShare.init();
App.register(ScreenShare);
