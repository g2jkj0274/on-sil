// 다시보기 탭: 녹화 목록과 재생. 강사는 제목 바꾸기·삭제도 할 수 있다.
'use strict';

const Recordings = {
  list: [],
  playingId: null,
  wasRecording: false,

  init() {
    // 녹화가 끝나면 목록을 새로 고친다
    Realtime.on('state', (s) => {
      const was = this.wasRecording;
      this.wasRecording = s.recording;
      if (was !== s.recording && App.tab === 'recordings') setTimeout(() => this.load(), 1500);
    });
  },

  onEnter() {
    this.list = [];
    this.playingId = null;
    this.wasRecording = false;
    document.getElementById('view-recordings').innerHTML = '';
  },

  onLeave() { document.getElementById('view-recordings').innerHTML = ''; },

  onTab(tab) { if (tab === 'recordings') this.load(); },

  base() { return `/api/classes/${App.current.id}/recordings`; },

  async load() {
    if (!App.current) return;
    try {
      this.list = await api('GET', this.base());
    } catch (e) { toast(e.message, 'error'); return; }
    if (this.playingId && !this.list.some((r) => r.id === this.playingId && r.status === 'READY')) this.playingId = null;
    this.render();
  },

  render() {
    const view = document.getElementById('view-recordings');
    const playing = this.list.find((r) => r.id === this.playingId);
    let html = '';
    if (playing) {
      html += `<div class="panel" id="rec-player">
        <div class="row"><h4>${esc(playing.title)}</h4><span class="spacer"></span>
          <button class="small secondary" id="rec-close">닫기</button></div>
        <video controls autoplay playsinline src="${this.base()}/${playing.id}/file"></video></div>`;
    }
    html += `<div class="panel"><h4>녹화 ${this.list.length}개</h4>`;
    if (!this.list.length) {
      html += `<div class="dim">아직 녹화가 없습니다.${App.isTeacher ? ' 라이브 탭에서 방송 중에 "⏺ 녹화 시작"을 누르면 녹화됩니다.' : ''}</div>`;
    } else {
      html += `<table class="list"><tr><th>제목</th><th>날짜</th><th>길이</th><th>크기</th><th></th></tr>`;
      for (const r of this.list) {
        const ready = r.status === 'READY';
        const ops = [];
        if (ready) {
          ops.push(`<button class="small" data-play="${r.id}">▶ 보기</button>`);
          ops.push(`<a class="button small secondary" href="${this.base()}/${r.id}/file?download=true">다운로드</a>`);
        }
        if (App.isTeacher && ready) {
          ops.push(`<button class="small secondary" data-rename="${r.id}">이름 바꾸기</button>`);
          ops.push(`<button class="small danger" data-delete="${r.id}">삭제</button>`);
        }
        html += `<tr class="${r.id === this.playingId ? 'active' : ''}">
          <td>${esc(r.title)}${ready ? '' : ' <span class="badge live">녹화 중</span>'}</td>
          <td class="dim">${esc(formatTime(r.startedAt))}</td>
          <td>${ready ? formatDuration(r.durationMs || 0) : '-'}</td>
          <td class="dim">${formatBytes(r.sizeBytes)}</td>
          <td><div class="row">${ops.join('')}</div></td></tr>`;
      }
      html += '</table>';
    }
    html += '</div>';
    view.innerHTML = html;

    const close = view.querySelector('#rec-close');
    if (close) close.onclick = () => { this.playingId = null; this.render(); };
    for (const b of view.querySelectorAll('[data-play]')) {
      b.onclick = () => { this.playingId = Number(b.dataset.play); this.render(); view.scrollTop = 0; };
    }
    for (const b of view.querySelectorAll('[data-rename]')) b.onclick = () => this.rename(Number(b.dataset.rename));
    for (const b of view.querySelectorAll('[data-delete]')) b.onclick = () => this.remove(Number(b.dataset.delete));
  },

  async rename(id) {
    const r = this.list.find((x) => x.id === id);
    const title = await promptModal('녹화 제목 바꾸기', r ? r.title : '');
    if (!title) return;
    try {
      await api('PUT', `${this.base()}/${id}/title`, { title });
      await this.load();
    } catch (e) { toast(e.message, 'error'); }
  },

  async remove(id) {
    const r = this.list.find((x) => x.id === id);
    const ok = await modal({
      title: '녹화 삭제',
      body: `<div><b>${esc(r ? r.title : '')}</b> 녹화를 삭제할까요? 되돌릴 수 없습니다.</div>`,
      buttons: [{ label: '취소', value: false, className: 'secondary' }, { label: '삭제', value: true, className: 'danger' }],
    });
    if (!ok) return;
    try {
      await api('DELETE', `${this.base()}/${id}`);
      if (this.playingId === id) this.playingId = null;
      await this.load();
    } catch (e) { toast(e.message, 'error'); }
  },
};
Recordings.init();
App.register(Recordings);
