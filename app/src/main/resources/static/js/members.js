// 멤버 탭: 초대 코드(강사), 멤버 목록, 조교 지정/해제(강사)
'use strict';

const Members = {
  list: [],

  async onEnter() { await this.render(); },
  onTab(tab) { if (tab === 'members') this.render(); },

  async render() {
    const cls = App.current;
    const view = document.getElementById('view-members');
    this.list = await api('GET', `/api/classes/${cls.id}/members`);
    const roleName = { TEACHER: '강사', TA: '조교', STUDENT: '학생' };
    let html = '';
    if (App.isTeacher) {
      html += `<div class="panel"><h4>초대 코드</h4>
        <div class="row"><code class="invite">${esc(cls.inviteCode)}</code>
        <button class="secondary small" id="btn-regen-code">새 코드 발급</button>
        <span class="dim">학생에게 이 코드를 알려 주면 클래스에 가입할 수 있습니다.</span></div></div>`;
    }
    html += `<div class="panel"><h4>멤버 ${this.list.length}명</h4><table class="list">
      <tr><th>이름</th><th>아이디</th><th>역할</th>${App.isTeacher ? '<th></th>' : ''}</tr>`;
    for (const m of this.list) {
      let op = '';
      if (App.isTeacher && m.role !== 'TEACHER') {
        op = m.role === 'TA'
          ? `<button class="secondary small" data-role="STUDENT" data-user="${m.userId}">조교 해제</button>`
          : `<button class="small" data-role="TA" data-user="${m.userId}">조교로 지정</button>`;
      }
      html += `<tr><td>${esc(m.displayName)}</td><td class="dim">${esc(m.username)}</td>
        <td><span class="badge ${m.role}">${roleName[m.role]}</span></td>${App.isTeacher ? `<td>${op}</td>` : ''}</tr>`;
    }
    html += '</table></div>';
    view.innerHTML = html;

    const regen = view.querySelector('#btn-regen-code');
    if (regen) regen.onclick = async () => {
      const r = await api('POST', `/api/classes/${cls.id}/invite-code`);
      cls.inviteCode = r.inviteCode;
      this.render();
    };
    for (const b of view.querySelectorAll('button[data-role]')) {
      b.onclick = async () => {
        try {
          await api('PUT', `/api/classes/${cls.id}/members/${b.dataset.user}/role`, { role: b.dataset.role });
          toast(b.dataset.role === 'TA' ? '조교로 지정했습니다. 해당 사용자는 클래스를 다시 열면 적용됩니다.' : '조교를 해제했습니다.');
          this.render();
        } catch (e) { toast(e.message, 'error'); }
      };
    }
  },

  nameOf(userId) {
    const m = this.list.find((x) => x.userId === userId);
    return m ? m.displayName : '';
  },
};
App.register(Members);
