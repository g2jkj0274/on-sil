// 모니터링 탭(강사·조교): 학생들이 공유한 화면을 격자로 본다. "막혔어요" 학생이 먼저 나온다.
// LiveKit monitor 방에 숨은 참가자로 들어가 구독만 한다.
'use strict';

const Monitor = {
  room: null,
  gen: 0,
  tiles: new Map(),  // identity(username) → { el, video, track }
  focus: null,       // 크게 보는 학생 identity
  focusTrack: null,

  init() {
    Realtime.on('staffState', () => this.render());
    Realtime.on('state', () => this.render());
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && this.focus) this.setFocus(null); });
  },

  async onEnter(cls) {
    this.focus = null;
    this.renderView();
    this.updateCount();
    if (App.isStaff) await this.connect(cls);
  },

  async onLeave() {
    this.gen++;
    const room = this.room;
    this.room = null;
    for (const t of this.tiles.values()) if (t.track) t.track.detach(t.video);
    this.tiles.clear();
    if (this.focusTrack) this.focusTrack.detach();
    this.focusTrack = null;
    this.focus = null;
    if (room) await room.disconnect();
    document.getElementById('monitor-count').textContent = '';
  },

  onTab(tab) { if (tab === 'monitor') this.render(); },

  async connect(cls) {
    const gen = ++this.gen;
    const L = LivekitClient;
    let room;
    try {
      const t = await api('GET', `/api/classes/${cls.id}/livekit/monitor`);
      if (gen !== this.gen) return;
      // adaptiveStream: 보이는 타일만, 타일 크기에 맞는 화질로 받는다
      room = new L.Room({ adaptiveStream: true, dynacast: true });
      const rerender = () => { if (room === this.room) this.render(); };
      room
        .on(L.RoomEvent.TrackSubscribed, rerender)
        .on(L.RoomEvent.TrackUnsubscribed, (track) => { track.detach(); rerender(); })
        .on(L.RoomEvent.TrackMuted, rerender)
        .on(L.RoomEvent.TrackUnmuted, rerender)
        .on(L.RoomEvent.ParticipantDisconnected, rerender)
        .on(L.RoomEvent.Disconnected, () => {
          if (room !== this.room) return;
          this.room = null;
          this.setStatus('미디어 서버와 연결이 끊겼습니다.', true);
          this.render();
        });
      await room.connect(t.url, t.token);
      if (gen !== this.gen) { room.disconnect(); return; }
      this.room = room;
      this.setStatus('');
      this.render();
    } catch (e) {
      console.error(e);
      if (room) room.disconnect();
      if (gen === this.gen) this.setStatus(`미디어 서버에 연결하지 못했습니다: ${e.message}`, true);
    }
  },

  // ---------------- 데이터 ----------------

  /** 화면에 보여 줄 학생들: 공유 중이거나 막혔어요를 누른 학생. 막혔어요(먼저 누른 순) → 나머지(이름순). */
  entries() {
    const online = (Classroom.state && Classroom.state.online) || [];
    const staff = Classroom.staffState || { sharing: [], stuck: [] };
    const byId = new Map(online.map((u) => [u.userId, u]));
    const stuckOrder = new Map(staff.stuck.map((s, i) => [s.userId, i]));
    const ids = new Set([...staff.stuck.map((s) => s.userId), ...staff.sharing]);
    return [...ids]
      .map((id) => byId.get(id))
      .filter((u) => u && u.role === 'STUDENT')
      .map((u) => ({
        user: u,
        stuck: stuckOrder.has(u.userId),
        sharing: staff.sharing.includes(u.userId),
        track: this.trackOf(u.username),
      }))
      .sort((a, b) => {
        if (a.stuck !== b.stuck) return a.stuck ? -1 : 1;
        if (a.stuck) return stuckOrder.get(a.user.userId) - stuckOrder.get(b.user.userId);
        return a.user.displayName.localeCompare(b.user.displayName, 'ko');
      });
  },

  trackOf(identity) {
    const p = this.room && this.room.remoteParticipants.get(identity);
    const pub = p && p.getTrackPublication(LivekitClient.Track.Source.ScreenShare);
    return pub && pub.track && !pub.isMuted ? pub.track : null;
  },

  // ---------------- 화면 ----------------

  renderView() {
    this.tiles.clear();
    document.getElementById('view-monitor').innerHTML = `
      <div id="monitor-head" class="row">
        <span id="monitor-summary" class="dim"></span>
        <span class="spacer"></span>
        <button class="small" id="btn-share-all">전체 학생에게 공유 요청</button>
      </div>
      <div id="monitor-status" class="dim"></div>
      <div id="monitor-grid"></div>
      <div id="monitor-empty" class="empty-state"></div>
      <div id="monitor-focus" class="hidden">
        <div class="focus-head row">
          <b id="monitor-focus-name"></b><span class="spacer"></span>
          <button class="small secondary" id="btn-focus-close">닫기 (Esc)</button>
        </div>
        <video id="monitor-focus-video" autoplay playsinline muted></video>
        <div id="monitor-focus-empty" class="empty-state hidden">화면 공유가 끝났습니다.</div>
      </div>`;
    document.getElementById('btn-share-all').onclick = () => {
      Realtime.send('share-request', {});
      toast('접속한 모든 학생에게 화면 공유를 요청했습니다.');
    };
    document.getElementById('btn-focus-close').onclick = () => this.setFocus(null);
  },

  setStatus(msg, isError = false) {
    const el = document.getElementById('monitor-status');
    if (!el) return;
    el.textContent = msg;
    el.classList.toggle('error', isError);
    if (isError && App.current && App.isStaff) {
      const retry = document.createElement('button');
      retry.className = 'small secondary';
      retry.textContent = '다시 연결';
      retry.onclick = () => this.connect(App.current);
      el.append(' ', retry);
    }
  },

  /** 탭 옆 숫자: 공유 중 학생 수, 막혔어요가 있으면 🆘 개수. */
  updateCount() {
    const el = document.getElementById('monitor-count');
    const s = Classroom.staffState;
    if (!App.isStaff || !s) { el.textContent = ''; return; }
    const parts = [];
    if (s.sharing.length) parts.push(`🖥️${s.sharing.length}`);
    if (s.stuck.length) parts.push(`🆘${s.stuck.length}`);
    el.textContent = parts.join(' ');
  },

  render() {
    this.updateCount();
    const grid = document.getElementById('monitor-grid');
    if (!grid || !App.isStaff) return;
    const list = this.entries();

    const s = Classroom.staffState;
    document.getElementById('monitor-summary').textContent = s
      ? `화면 공유 ${s.sharing.length}명 · 막혔어요 ${s.stuck.length}명`
      : '';
    const empty = document.getElementById('monitor-empty');
    empty.classList.toggle('hidden', list.length > 0);
    empty.innerHTML = '<div>화면을 공유한 학생이 없습니다.<br><span class="dim">"전체 학생에게 공유 요청" 또는 접속자 목록의 "공유 요청"으로 요청할 수 있습니다.</span></div>';

    // 타일은 다시 만들지 않고 순서만 바꿔서 영상이 깜빡이지 않게 한다
    const seen = new Set();
    for (const e of list) {
      const id = e.user.username;
      seen.add(id);
      let tile = this.tiles.get(id);
      if (!tile) tile = this.createTile(id);
      this.updateTile(tile, e);
      grid.appendChild(tile.el);
    }
    for (const [id, tile] of this.tiles) {
      if (seen.has(id)) continue;
      if (tile.track) tile.track.detach(tile.video);
      tile.el.remove();
      this.tiles.delete(id);
    }
    this.renderFocus();
  },

  createTile(identity) {
    const el = document.createElement('div');
    el.className = 'mtile';
    el.innerHTML = `
      <div class="mscreen"><video autoplay playsinline muted></video><div class="mnone"></div></div>
      <div class="mbar"><span class="mname"></span><span class="micons"></span><span class="spacer"></span><span class="mops"></span></div>`;
    const tile = { el, video: el.querySelector('video'), track: null };
    el.querySelector('.mscreen').onclick = () => this.setFocus(identity);
    this.tiles.set(identity, tile);
    return tile;
  },

  updateTile(tile, e) {
    if (tile.track !== e.track) {
      if (tile.track) tile.track.detach(tile.video);
      if (e.track) e.track.attach(tile.video);
      tile.track = e.track;
    }
    const el = tile.el;
    el.classList.toggle('stuck', e.stuck);
    tile.video.classList.toggle('hidden', !e.track);
    const none = el.querySelector('.mnone');
    none.classList.toggle('hidden', !!e.track);
    none.textContent = e.sharing ? '연결 중...' : '화면 공유 안 함';
    el.querySelector('.mname').textContent = e.user.displayName;
    el.querySelector('.micons').textContent = (e.stuck ? '🆘' : '') + (e.user.hand ? '✋' : '');

    const ops = el.querySelector('.mops');
    ops.innerHTML = '';
    if (e.stuck) ops.appendChild(Classroom.opButton('확인', '"막혔어요" 해제', () => Realtime.send('stuck-clear', { userId: e.user.userId })));
    if (!e.sharing) ops.appendChild(Classroom.opButton('공유 요청', '화면 공유 요청 보내기', () => {
      Realtime.send('share-request', { userId: e.user.userId });
      toast(`${e.user.displayName}님에게 화면 공유를 요청했습니다.`);
    }));
  },

  setFocus(identity) {
    this.focus = identity;
    this.renderFocus();
  },

  renderFocus() {
    const box = document.getElementById('monitor-focus');
    if (!box) return;
    const video = document.getElementById('monitor-focus-video');
    const track = this.focus ? this.trackOf(this.focus) : null;
    if (this.focusTrack !== track) {
      if (this.focusTrack) this.focusTrack.detach(video);
      if (track) track.attach(video);
      this.focusTrack = track;
    }
    box.classList.toggle('hidden', !this.focus);
    const view = document.getElementById('view-monitor');
    if (this.focus && !view.classList.contains('focused')) view.scrollTop = 0;
    view.classList.toggle('focused', !!this.focus);
    if (!this.focus) return;
    const u = ((Classroom.state && Classroom.state.online) || []).find((x) => x.username === this.focus);
    document.getElementById('monitor-focus-name').textContent = u ? u.displayName : this.focus;
    video.classList.toggle('hidden', !track);
    document.getElementById('monitor-focus-empty').classList.toggle('hidden', !!track);
  },
};
Monitor.init();
App.register(Monitor);
