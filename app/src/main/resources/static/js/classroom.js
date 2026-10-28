// 오른쪽 패널: 접속자 목록, 채팅, 학생용 손들기/막혔어요 버튼
'use strict';

const Classroom = {
  state: null,        // 서버의 PublicState
  staffState: null,   // 서버의 StaffState (강사·조교만)
  myHand: false,
  myStuck: false,
  chatIds: new Set(),

  init() {
    Realtime.on('state', (s) => { this.state = s; this.syncMine(); this.renderOnline(); });
    Realtime.on('staffState', (s) => { this.staffState = s; this.renderOnline(); });
    Realtime.on('chat', (m) => this.addChat(m));
    Realtime.on('error', (e) => toast(e.message, 'error'));
    Realtime.on('stuck-cleared', (e) => {
      this.myStuck = false; this.renderMyBar();
      toast(`${e.by}님이 "막혔어요"를 확인했습니다.`);
    });
    Realtime.on('share-request', (e) => this.onShareRequest(e));
    Realtime.on('mic', (e) => toast(e.allow ? '강사가 마이크 사용을 허용했습니다. 라이브 탭에서 마이크를 켜세요.' : '마이크 사용 권한이 회수되었습니다.'));
    Realtime.on('connected', (ok) => {
      document.getElementById('chat-input').disabled = !ok || !App.current;
      document.getElementById('chat-send').disabled = !ok || !App.current;
    });

    document.getElementById('chat-form').onsubmit = (e) => {
      e.preventDefault();
      const input = document.getElementById('chat-input');
      const text = input.value.trim();
      if (!text) return;
      Realtime.send('chat', { text });
      input.value = '';
    };
  },

  async onEnter(cls) {
    this.state = null; this.staffState = null; this.myHand = false; this.myStuck = false;
    this.chatIds.clear();
    document.getElementById('chat-log').innerHTML = '';
    const history = await api('GET', `/api/classes/${cls.id}/chat`);
    for (const m of history) this.addChat(m);
    Realtime.enter(cls);
    const ok = Realtime.connected;
    document.getElementById('chat-input').disabled = !ok;
    document.getElementById('chat-send').disabled = !ok;
    this.renderMyBar();
  },

  onLeave() {
    Realtime.leave();
    document.getElementById('online-list').innerHTML = '';
    document.getElementById('online-count').textContent = '';
    document.getElementById('my-bar').classList.add('hidden');
  },

  /** 서버 상태에서 내 손들기 여부를 맞춘다 (다른 탭에서 바꿨을 수도 있으므로). */
  syncMine() {
    const mine = this.state.online.find((u) => u.userId === App.me.id);
    if (mine) this.myHand = mine.hand;
    this.renderMyBar();
  },

  get myMicAllowed() {
    const mine = this.state && this.state.online.find((u) => u.userId === App.me.id);
    return !!(mine && mine.micAllowed);
  },

  isSharing(userId) { return !!(this.staffState && this.staffState.sharing.includes(userId)); },
  isStuck(userId) { return !!(this.staffState && this.staffState.stuck.some((s) => s.userId === userId)); },

  renderMyBar() {
    const bar = document.getElementById('my-bar');
    const isStudent = App.current && App.current.myRole === 'STUDENT';
    bar.classList.toggle('hidden', !isStudent);
    if (!isStudent) return;
    const hand = document.getElementById('btn-hand');
    hand.textContent = this.myHand ? '✋ 손 내리기' : '✋ 손들기';
    hand.classList.toggle('on', this.myHand);
    const stuck = document.getElementById('btn-stuck');
    stuck.textContent = this.myStuck ? '🆘 막혔어요 해제' : '🆘 막혔어요';
    stuck.classList.toggle('on', this.myStuck);
  },

  /** 강사·조교의 화면 공유 요청. 수락하면 화면 공유 모듈(ScreenShare)이 있으면 공유를 시작한다. */
  async onShareRequest(e) {
    if (this.shareRequestOpen) return; // 이미 요청 창이 떠 있으면 무시
    if (window.ScreenShare && ScreenShare.active) return; // 이미 공유 중
    this.shareRequestOpen = true;
    const accepted = await modal({
      title: '화면 공유 요청',
      body: `<div><b>${esc(e.from)}</b>님이 화면 공유를 요청했습니다.</div>
        <div class="dim">수락하면 공유할 화면을 고르는 창이 뜹니다. 내 화면은 강사·조교만 볼 수 있고, 언제든 중지할 수 있습니다.</div>`,
      buttons: [{ label: '거절', value: false, className: 'secondary' }, { label: '수락', value: true }],
    });
    this.shareRequestOpen = false;
    if (!accepted) return;
    if (window.ScreenShare) await ScreenShare.start();
    else toast('화면 공유 기능은 다음 단계에서 연결됩니다.');
  },

  toggleHand() {
    this.myHand = !this.myHand;
    Realtime.send('hand', { on: this.myHand });
    this.renderMyBar();
  },

  toggleStuck() {
    this.myStuck = !this.myStuck;
    Realtime.send('stuck', { on: this.myStuck });
    this.renderMyBar();
  },

  renderOnline() {
    if (!this.state) return;
    const ul = document.getElementById('online-list');
    document.getElementById('online-count').textContent = `— ${this.state.online.length}`;
    ul.innerHTML = '';
    for (const u of this.state.online) {
      const li = document.createElement('li');
      const icons = [];
      if (u.hand) icons.push('<span title="손들기">✋</span>');
      if (u.micAllowed) icons.push('<span title="마이크 허용됨">🎤</span>');
      if (this.isSharing(u.userId)) icons.push('<span title="화면 공유 중">🖥️</span>');
      if (this.isStuck(u.userId)) icons.push('<span title="막혔어요">🆘</span>');
      const roleBadge = u.role === 'STUDENT' ? '' : `<span class="badge ${u.role}">${u.role === 'TEACHER' ? '강사' : '조교'}</span>`;
      li.innerHTML = `<span class="dot"></span><span class="uname">${esc(u.displayName)}${u.userId === App.me.id ? ' <span class="dim">(나)</span>' : ''}</span>
        ${roleBadge}<span class="icons">${icons.join('')}</span><span class="ops"></span>`;
      const ops = li.querySelector('.ops');
      if (App.isStaff && u.role === 'STUDENT') {
        if (!this.isSharing(u.userId)) ops.appendChild(this.opButton('공유 요청', '화면 공유 요청 보내기', () => {
          Realtime.send('share-request', { userId: u.userId });
          toast(`${u.displayName}님에게 화면 공유를 요청했습니다.`);
        }));
        if (this.isStuck(u.userId)) ops.appendChild(this.opButton('확인', '"막혔어요" 해제', () => Realtime.send('stuck-clear', { userId: u.userId })));
        if (App.isTeacher && u.hand && !u.micAllowed) ops.appendChild(this.opButton('🎤허용', '마이크 사용 허용', () => Realtime.send('mic', { userId: u.userId, allow: true })));
        if (App.isTeacher && u.micAllowed) ops.appendChild(this.opButton('🎤회수', '마이크 권한 회수', () => Realtime.send('mic', { userId: u.userId, allow: false }), 'danger'));
      }
      ul.appendChild(li);
    }
  },

  opButton(label, title, onclick, cls = 'secondary') {
    const b = document.createElement('button');
    b.className = `small ${cls}`;
    b.textContent = label;
    b.title = title;
    b.onclick = onclick;
    return b;
  },

  addChat(m) {
    if (m.id && this.chatIds.has(m.id)) return;
    if (m.id) this.chatIds.add(m.id);
    const log = document.getElementById('chat-log');
    const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
    const div = document.createElement('div');
    div.className = 'msg';
    const role = m.role === 'TEACHER' ? ' <span class="badge TEACHER">강사</span>' : m.role === 'TA' ? ' <span class="badge TA">조교</span>' : '';
    div.innerHTML = `<div class="meta"><b>${esc(m.displayName)}</b>${role} ${esc(formatTime(m.at))}</div><div>${esc(m.text)}</div>`;
    log.appendChild(div);
    if (atBottom) log.scrollTop = log.scrollHeight;
  },
};
Classroom.init();
App.register(Classroom);
