// 자료실 탭: 강사·조교는 파일을 올리고 지울 수 있고, 모두가 내려받을 수 있다.
'use strict';

const Materials = {
  list: [],
  filter: '',
  uploads: [],  // { name, loaded, total, error }

  init() {
    // 다른 사람이 올리거나 지우면 목록을 새로 고친다
    Realtime.on('materials', () => { if (App.tab === 'materials') this.load(); });
  },

  onEnter() {
    this.list = [];
    this.filter = '';
    this.renderView();
  },

  onTab(tab) { if (tab === 'materials') this.load(); },

  base() { return `/api/classes/${App.current.id}/materials`; },

  async load() {
    if (!App.current) return;
    try { this.list = await api('GET', this.base()); } catch (e) { toast(e.message, 'error'); return; }
    this.renderList();
  },

  renderView() {
    const view = document.getElementById('view-materials');
    view.innerHTML = `
      ${App.isStaff ? `<div class="panel" id="mat-drop">
        <div class="row">
          <button id="mat-pick">파일 올리기</button>
          <span class="dim">또는 이 화면에 파일을 끌어다 놓으세요. 한 번에 500MB까지.</span>
          <input type="file" id="mat-input" multiple class="hidden">
        </div>
        <div id="mat-uploads"></div>
      </div>` : ''}
      <div class="panel">
        <div class="row"><h4 id="mat-title">자료</h4><span class="spacer"></span>
          <input id="mat-filter" placeholder="파일 이름 검색" autocomplete="off"></div>
        <div id="mat-list"></div>
      </div>`;
    const filter = view.querySelector('#mat-filter');
    filter.oninput = () => { this.filter = filter.value.trim().toLowerCase(); this.renderList(); };
    if (!App.isStaff) return;

    const input = view.querySelector('#mat-input');
    view.querySelector('#mat-pick').onclick = () => input.click();
    input.onchange = () => { this.upload([...input.files]); input.value = ''; };
    // 끌어다 놓기: 탭 전체를 받는 곳으로
    view.ondragover = (e) => {
      if (!e.dataTransfer.types.includes('Files')) return;
      e.preventDefault();
      view.classList.add('dragging');
    };
    view.ondragleave = (e) => { if (e.target === view) view.classList.remove('dragging'); };
    view.ondrop = (e) => {
      e.preventDefault();
      view.classList.remove('dragging');
      const files = [...e.dataTransfer.files];
      if (files.length) this.upload(files);
    };
  },

  renderList() {
    const box = document.getElementById('mat-list');
    if (!box) return;
    const shown = this.list.filter((m) => !this.filter || m.fileName.toLowerCase().includes(this.filter));
    document.getElementById('mat-title').textContent = `자료 ${this.list.length}개`;
    if (!this.list.length) {
      box.innerHTML = `<div class="dim">아직 올린 자료가 없습니다.${App.isStaff ? '' : ' 강사·조교가 올리면 여기에 나옵니다.'}</div>`;
      return;
    }
    if (!shown.length) { box.innerHTML = '<div class="dim">검색 결과가 없습니다.</div>'; return; }
    let html = '<table class="list"><tr><th>파일</th><th>크기</th><th>올린 사람</th><th>날짜</th><th></th></tr>';
    for (const m of shown) {
      const url = `${this.base()}/${m.id}/file`;
      const ops = [];
      if (m.viewable) ops.push(`<a class="button small secondary" href="${url}?view=true" target="_blank" rel="noopener">열기</a>`);
      ops.push(`<a class="button small" href="${url}">다운로드</a>`);
      if (App.isStaff) ops.push(`<button class="small danger" data-delete="${m.id}">삭제</button>`);
      html += `<tr><td class="mat-name">${esc(m.fileName)}</td><td class="dim">${formatBytes(m.sizeBytes)}</td>
        <td>${esc(m.uploader)}</td><td class="dim">${esc(formatTime(m.uploadedAt))}</td>
        <td><div class="row">${ops.join('')}</div></td></tr>`;
    }
    box.innerHTML = html + '</table>';
    for (const b of box.querySelectorAll('[data-delete]')) b.onclick = () => this.remove(Number(b.dataset.delete));
  },

  /** 파일마다 따로 올려서 진행률을 보여 준다. */
  async upload(files) {
    const limit = 500 * 1024 * 1024;
    for (const f of files) {
      const item = { name: f.name, loaded: 0, total: f.size, error: null, done: false };
      this.uploads.push(item);
      if (f.size > limit) { item.error = '500MB를 넘어서 올릴 수 없습니다.'; continue; }
      this.renderUploads();
      try {
        await this.send(f, item);
        item.done = true;
      } catch (e) {
        item.error = e.message;
      }
      this.renderUploads();
    }
    this.renderUploads();
    await this.load();
    // 끝난 항목은 잠시 뒤 지운다 (실패는 남김)
    setTimeout(() => { this.uploads = this.uploads.filter((u) => !u.done); this.renderUploads(); }, 3000);
  },

  send(file, item) {
    const cls = App.current;
    return new Promise((resolve, reject) => {
      const form = new FormData();
      form.append('files', file);
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `/api/classes/${cls.id}/materials`);
      xhr.setRequestHeader('X-Onsil-User', String(App.me.id));
      xhr.upload.onprogress = (e) => { item.loaded = e.loaded; item.total = e.total; this.renderUploads(); };
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) return resolve();
        let msg = `업로드 실패 (${xhr.status})`;
        try {
          const d = JSON.parse(xhr.responseText);
          if (d.code === 'SESSION_USER_CHANGED') sessionUserChanged();
          if (d.message) msg = d.message;
        } catch { /* JSON 아님 */ }
        reject(new Error(msg));
      };
      xhr.onerror = () => reject(new Error('서버에 연결하지 못했습니다.'));
      xhr.send(form);
    });
  },

  renderUploads() {
    const box = document.getElementById('mat-uploads');
    if (!box) return;
    box.innerHTML = this.uploads.map((u, i) => {
      const pct = u.total ? Math.floor((u.loaded / u.total) * 100) : 0;
      const status = u.error ? `<span class="error-text">${esc(u.error)}</span>`
        : u.done ? '<span class="ok-text">완료</span>' : `${pct}%`;
      return `<div class="mat-upload"><span class="mat-name">${esc(u.name)}</span>
        <div class="bar"><div style="width:${u.error ? 0 : u.done ? 100 : pct}%"></div></div>
        <span class="mat-status">${status}</span>
        ${u.error ? `<button class="small secondary" data-dismiss="${i}">닫기</button>` : ''}</div>`;
    }).join('');
    for (const b of box.querySelectorAll('[data-dismiss]')) {
      b.onclick = () => { this.uploads.splice(Number(b.dataset.dismiss), 1); this.renderUploads(); };
    }
  },

  async remove(id) {
    const m = this.list.find((x) => x.id === id);
    const ok = await modal({
      title: '자료 삭제',
      body: `<div><b>${esc(m ? m.fileName : '')}</b> 파일을 삭제할까요? 되돌릴 수 없습니다.</div>`,
      buttons: [{ label: '취소', value: false, className: 'secondary' }, { label: '삭제', value: true, className: 'danger' }],
    });
    if (!ok) return;
    try {
      await api('DELETE', `${this.base()}/${id}`);
      await this.load();
    } catch (e) { toast(e.message, 'error'); }
  },
};
Materials.init();
App.register(Materials);
