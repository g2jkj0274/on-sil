// 강의실 화면의 뼈대: 로그인 사용자, 클래스 목록, 탭 전환, 기능 모듈 연결
'use strict';

const App = {
  me: null,            // { id, username, displayName, accountType }
  classes: [],
  current: null,       // 선택한 클래스 { id, name, myRole, teacherName, inviteCode }
  tab: 'live',
  modules: [],         // { onEnter(cls), onLeave(), onTab(tab) }

  get isStaff() { return this.current && (this.current.myRole === 'TEACHER' || this.current.myRole === 'TA'); },
  get isTeacher() { return this.current && this.current.myRole === 'TEACHER'; },

  register(mod) { this.modules.push(mod); },

  async init() {
    this.me = await api('GET', '/api/me');
    document.getElementById('me-name').textContent = this.me.displayName;
    document.getElementById('me-type').textContent =
      `${this.me.username} · ${this.me.accountType === 'TEACHER' ? '강사' : '학생'}`;
    const createBtn = document.getElementById('btn-create-class');
    createBtn.classList.toggle('hidden', this.me.accountType !== 'TEACHER');
    createBtn.onclick = () => this.createClass();
    document.getElementById('btn-join-class').onclick = () => this.joinClass();
    document.getElementById('btn-logout').onclick = async () => {
      await this.leaveClass();
      await api('POST', '/api/logout');
      location.href = '/login.html';
    };
    for (const b of document.querySelectorAll('#tabs button')) b.onclick = () => this.showTab(b.dataset.tab);

    await this.loadClasses();
    const saved = Number(sessionStorage.getItem('onsil.class'));
    const first = this.classes.find((c) => c.id === saved) || this.classes[0];
    if (first) await this.selectClass(first.id);
  },

  async loadClasses() {
    this.classes = await api('GET', '/api/classes');
    const ul = document.getElementById('class-list');
    ul.innerHTML = '';
    for (const c of this.classes) {
      const li = document.createElement('li');
      li.dataset.id = c.id;
      li.innerHTML = `<span class="name"># ${esc(c.name)}</span><span class="badge live hidden" data-live>LIVE</span>`;
      li.classList.toggle('active', this.current && this.current.id === c.id);
      li.onclick = () => this.selectClass(c.id);
      ul.appendChild(li);
    }
  },

  async createClass() {
    const name = await promptModal('클래스 만들기', '클래스 이름');
    if (!name) return;
    try {
      const c = await api('POST', '/api/classes', { name });
      await this.loadClasses();
      await this.selectClass(c.id);
      toast(`클래스를 만들었습니다. 초대 코드: ${c.inviteCode}`);
    } catch (e) { toast(e.message, 'error'); }
  },

  async joinClass() {
    const code = await promptModal('초대 코드로 가입', '예: DEMO01');
    if (!code) return;
    try {
      const c = await api('POST', '/api/classes/join', { code });
      await this.loadClasses();
      await this.selectClass(c.id);
      toast(`'${c.name}' 클래스에 가입했습니다.`);
    } catch (e) { toast(e.message, 'error'); }
  },

  async leaveClass() {
    if (!this.current) return;
    for (const m of this.modules) { try { await m.onLeave?.(); } catch (e) { console.error(e); } }
    this.current = null;
  },

  async selectClass(id) {
    if (this.current && this.current.id === id) return;
    await this.leaveClass();
    this.current = await api('GET', `/api/classes/${id}`);
    sessionStorage.setItem('onsil.class', id);
    for (const li of document.querySelectorAll('#class-list li')) li.classList.toggle('active', Number(li.dataset.id) === id);

    document.getElementById('no-class').classList.add('hidden');
    document.getElementById('class-header').classList.remove('hidden');
    document.getElementById('class-name').textContent = this.current.name;
    const role = document.getElementById('class-role');
    role.className = `badge ${this.current.myRole}`;
    role.textContent = { TEACHER: '강사', TA: '조교', STUDENT: '학생' }[this.current.myRole];
    for (const b of document.querySelectorAll('#tabs button[data-staff]')) b.classList.toggle('hidden', !this.isStaff);

    for (const m of this.modules) { try { await m.onEnter?.(this.current); } catch (e) { console.error(e); toast(e.message, 'error'); } }
    this.showTab(this.tab === 'monitor' && !this.isStaff ? 'live' : this.tab);
  },

  showTab(tab) {
    this.tab = tab;
    for (const b of document.querySelectorAll('#tabs button')) b.classList.toggle('active', b.dataset.tab === tab);
    for (const v of document.querySelectorAll('.view')) v.classList.toggle('hidden', v.id !== `view-${tab}`);
    for (const m of this.modules) { try { m.onTab?.(tab); } catch (e) { console.error(e); } }
  },
};
