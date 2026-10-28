// 공통 도우미: 서버 API 호출, 토스트, 모달, HTML 이스케이프
'use strict';

async function api(method, url, body) {
  const opts = { method, headers: {} };
  if (body instanceof FormData || body instanceof Blob) {
    opts.body = body;
  } else if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(url, opts);
  if (res.status === 401) { location.href = '/login.html'; throw new Error('로그인이 필요합니다.'); }
  const text = await res.text();
  const data = text ? (() => { try { return JSON.parse(text); } catch { return text; } })() : null;
  if (!res.ok) throw new Error((data && data.message) || `요청 실패 (${res.status})`);
  return data;
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function toast(msg, type = 'info', ms = 3500) {
  const el = document.createElement('div');
  el.className = `toast ${type}`;
  el.textContent = msg;
  document.getElementById('toasts').appendChild(el);
  setTimeout(() => el.remove(), ms);
}

/**
 * 간단한 모달. buttons: [{label, value, className}] → 누른 버튼의 value 로 resolve.
 * body 는 HTML 문자열 또는 Element. onOpen(root) 로 입력칸에 포커스 등을 줄 수 있다.
 */
function modal({ title, body = '', buttons = [{ label: '확인', value: true }], onOpen }) {
  return new Promise((resolve) => {
    const bg = document.createElement('div');
    bg.id = 'modal-bg';
    bg.innerHTML = `<div id="modal"><h3>${esc(title)}</h3><div class="body"></div><div class="buttons"></div></div>`;
    const bodyEl = bg.querySelector('.body');
    if (body instanceof Element) bodyEl.appendChild(body); else bodyEl.innerHTML = body;
    const close = (v) => { bg.remove(); resolve(v); };
    for (const b of buttons) {
      const btn = document.createElement('button');
      btn.textContent = b.label;
      if (b.className) btn.className = b.className;
      btn.onclick = () => close(typeof b.value === 'function' ? b.value(bodyEl) : b.value);
      bg.querySelector('.buttons').appendChild(btn);
    }
    bg._close = close;
    document.body.appendChild(bg);
    if (onOpen) onOpen(bodyEl, close);
  });
}

/** 입력칸 하나짜리 모달. 취소하면 null. */
async function promptModal(title, placeholder = '') {
  const v = await modal({
    title,
    body: `<input id="prompt-input" placeholder="${esc(placeholder)}">`,
    buttons: [
      { label: '취소', value: null, className: 'secondary' },
      { label: '확인', value: (root) => root.querySelector('#prompt-input').value.trim() },
    ],
    onOpen: (root, close) => {
      const input = root.querySelector('#prompt-input');
      input.focus();
      input.onkeydown = (e) => { if (e.key === 'Enter') close(input.value.trim()); };
    },
  });
  return v || null;
}

function formatBytes(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function formatTime(iso) {
  const d = new Date(iso);
  return d.toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}
