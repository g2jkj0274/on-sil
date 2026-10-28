// 서버와의 실시간 연결(STOMP over WebSocket). 받은 메시지는 type 별로 다른 모듈에 전달한다.
'use strict';

const Realtime = {
  client: null,
  classSubs: [],
  handlers: {},     // type → [fn]
  joinedClassId: null,

  /** type: 'state' | 'staffState' | 'chat' | 'share-request' | 'mic' | 'stuck-cleared' | 'error' | 'connected' */
  on(type, fn) { (this.handlers[type] ||= []).push(fn); },
  emit(type, data) { for (const fn of this.handlers[type] || []) { try { fn(data); } catch (e) { console.error(e); } } },

  start() {
    this.client = new StompJs.Client({
      brokerURL: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
    });
    this.client.onConnect = () => {
      this.client.subscribe('/user/queue/events', (msg) => {
        const data = JSON.parse(msg.body);
        if (data.classId && App.current && data.classId !== App.current.id) return;
        this.emit(data.type, data);
      });
      this.joinedClassId = null;
      if (App.current) this.enter(App.current);
      this.emit('connected', true);
    };
    this.client.onWebSocketClose = () => this.emit('connected', false);
    this.client.onStompError = (frame) => console.warn('STOMP 오류', frame.headers.message);
    this.client.activate();
  },

  get connected() { return this.client && this.client.connected; },

  enter(cls) {
    if (!this.connected) return; // 연결되면 onConnect 에서 다시 호출됨
    this.unsubscribeClass();
    const handle = (msg) => { const d = JSON.parse(msg.body); this.emit(d.type, d); };
    this.classSubs.push(this.client.subscribe(`/topic/class/${cls.id}/public`, handle));
    if (cls.myRole === 'TEACHER' || cls.myRole === 'TA') {
      this.classSubs.push(this.client.subscribe(`/topic/class/${cls.id}/staff`, handle));
    }
    this.joinedClassId = cls.id;
    this.send('join');
  },

  leave() {
    if (this.connected && this.joinedClassId) this.send('leave');
    this.unsubscribeClass();
    this.joinedClassId = null;
  },

  unsubscribeClass() {
    for (const s of this.classSubs) { try { s.unsubscribe(); } catch { /* 이미 끊김 */ } }
    this.classSubs = [];
  },

  /** /app/class/{현재 클래스}/{path} 로 보낸다. */
  send(path, body = {}) {
    if (!this.connected || !App.current) { toast('서버와 연결되지 않았습니다. 잠시 후 다시 시도하세요.', 'error'); return; }
    this.client.publish({ destination: `/app/class/${App.current.id}/${path}`, body: JSON.stringify(body) });
  },
};
