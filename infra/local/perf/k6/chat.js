// 메시지 전파 경로 부하 측정. 보내는 쪽은 REST로 보내고(본문에 보낸 시각), 받는 쪽은 서버 2대에 STOMP로 붙어
// 받은 시각과의 차이(종단 지연)를 잰다. 송·수신이 한 k6 프로세스라 시계가 같다.
// 부하 형태: hot = 방 1개에 모두(방 행 잠금 경합), spread = 방 10개(보내는 사람 1 + 받는 사람 2씩).
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { WebSocket } from 'k6/experimental/websockets';
import { setTimeout } from 'k6/timers';

const NODES = ['http://app-1:8080', 'http://app-2:8080'];
const WS_NODES = ['ws://app-1:8080/ws', 'ws://app-2:8080/ws'];
const SHAPE = __ENV.SHAPE || 'hot';
const RATE = Number(__ENV.RATE || 50);
const WARMUP_S = Number(__ENV.WARMUP_S || 60);
const DURATION_S = Number(__ENV.DURATION_S || 120);
const RUN_NAME = __ENV.RUN_NAME || 'run';
// 측정 구간 종단 지연 p99 합격선. kafka 모드 관측 최댓값(약 69ms, 커넥터 poll 50ms)의 약 2배 — 500ms 폴링 같은 퇴행을 실패로 잡는다.
const P99_LIMIT_MS = Number(__ENV.P99_LIMIT_MS || 150);
const SENDERS = 10;
const RECEIVERS = 20;
const PASSWORD = 'demo1234';
// 받는 쪽이 접속·구독을 마칠 시간. 보내기는 이 뒤에 시작한다.
const CONNECT_GRACE_S = 15;
const JSON_HEADERS = { 'Content-Type': 'application/json' };

const e2eLatency = new Trend('e2e_latency', true);
const e2eReceived = new Counter('e2e_received');
const stompErrors = new Counter('stomp_errors');

export const options = {
  setupTimeout: '180s',
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
  scenarios: {
    receivers: {
      executor: 'per-vu-iterations',
      exec: 'receive',
      vus: RECEIVERS,
      iterations: 1,
      maxDuration: `${CONNECT_GRACE_S + WARMUP_S + DURATION_S + 30}s`,
    },
    warmup: {
      executor: 'constant-arrival-rate',
      exec: 'send',
      rate: RATE,
      timeUnit: '1s',
      duration: `${WARMUP_S}s`,
      startTime: `${CONNECT_GRACE_S}s`,
      preAllocatedVUs: 20,
      maxVUs: 200,
      tags: { phase: 'warmup' },
    },
    measure: {
      executor: 'constant-arrival-rate',
      exec: 'send',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION_S}s`,
      startTime: `${CONNECT_GRACE_S + WARMUP_S}s`,
      preAllocatedVUs: 20,
      maxVUs: 200,
      tags: { phase: 'measure' },
    },
  },
  thresholds: {
    'e2e_latency{phase:measure}': [`p(99)<${P99_LIMIT_MS}`],
    'e2e_received{phase:measure}': ['count>0'],
    'http_req_failed{phase:measure}': ['rate<0.01'],
    'http_req_duration{phase:measure}': ['p(99)<5000'],
    stomp_errors: ['count==0'],
  },
};

function auth(token) {
  return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } };
}

function login(n) {
  const res = http.post(`${NODES[0]}/api/auth/login`,
    JSON.stringify({ email: `perf-${RUN_NAME}-${n}@perf.local`, password: PASSWORD }), { headers: JSON_HEADERS });
  if (res.status !== 200) throw new Error(`로그인 실패 #${n}: ${res.status} ${res.body}`);
  return res.json('accessToken');
}

function createRoom(token, name) {
  const res = http.post(`${NODES[0]}/api/chatrooms`, JSON.stringify({ name, private: false }), auth(token));
  if (res.status !== 200) throw new Error(`방 생성 실패: ${res.status} ${res.body}`);
  return res.json('id');
}

function join(token, roomId) {
  const res = http.post(`${NODES[0]}/api/chatrooms/${roomId}/members`, null, auth(token));
  if (res.status !== 200) throw new Error(`방 가입 실패(${roomId}): ${res.status} ${res.body}`);
}

export function setup() {
  const senderTokens = [];
  for (let i = 1; i <= SENDERS; i++) senderTokens.push(login(i));
  const receiverTokens = [];
  for (let i = 1; i <= RECEIVERS; i++) receiverTokens.push(login(SENDERS + i));

  const rooms = [];
  if (SHAPE === 'hot') {
    // 만든 사람(보내는 사람 1)은 자동 가입된다.
    rooms.push(createRoom(senderTokens[0], `perf-${RUN_NAME}`));
    senderTokens.slice(1).forEach((t) => join(t, rooms[0]));
    receiverTokens.forEach((t) => join(t, rooms[0]));
  } else {
    for (let i = 0; i < SENDERS; i++) {
      rooms.push(createRoom(senderTokens[i], `perf-${RUN_NAME}-${i}`));
      join(receiverTokens[2 * i], rooms[i]);
      join(receiverTokens[2 * i + 1], rooms[i]);
    }
  }

  const senders = senderTokens.map((token, i) => ({ token, roomId: rooms[SHAPE === 'hot' ? 0 : i] }));
  // 받는 사람 j는 서버 j % 2에 붙는다. spread에서 방 i의 두 사람(2i, 2i+1)은 서로 다른 서버에 붙는다.
  const receivers = receiverTokens.map((token, j) => ({
    token,
    roomId: rooms[SHAPE === 'hot' ? 0 : Math.floor(j / 2)],
    wsUrl: WS_NODES[j % 2],
  }));
  return { senders, receivers };
}

function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function send(data) {
  const i = exec.scenario.iterationInTest;
  const sender = data.senders[i % data.senders.length];
  const phase = exec.scenario.name; // warmup | measure
  const body = JSON.stringify({ content: `perf|${phase}|${Date.now()}`, clientMessageId: uuidv4() });
  const res = http.post(`${NODES[i % 2]}/api/chatrooms/${sender.roomId}/messages`, body,
    { ...auth(sender.token), tags: { name: 'send' } });
  check(res, { '전송 200': (r) => r.status === 200 });
}

function frame(command, headers, body = '') {
  const lines = Object.entries(headers).map(([k, v]) => `${k}:${v}`);
  return `${command}\n${lines.join('\n')}\n\n${body}\0`;
}

function onFrame(raw, ws, receiver) {
  const headerEnd = raw.indexOf('\n\n');
  const command = raw.slice(0, raw.indexOf('\n')).trim();
  if (command === 'CONNECTED') {
    ws.send(frame('SUBSCRIBE', { id: 'sub-0', destination: `/sub/chatrooms/${receiver.roomId}` }));
  } else if (command === 'MESSAGE') {
    const message = JSON.parse(raw.slice(headerEnd + 2));
    const parts = String(message.content || '').split('|');
    if (parts[0] === 'perf') {
      const phase = parts[1];
      e2eLatency.add(Date.now() - Number(parts[2]), { phase });
      e2eReceived.add(1, { phase });
    }
  } else if (command === 'ERROR') {
    stompErrors.add(1);
    console.error(`STOMP ERROR: ${raw}`);
  }
}

export function receive(data) {
  const receiver = data.receivers[exec.scenario.iterationInTest];
  const ws = new WebSocket(receiver.wsUrl);
  ws.onopen = () => {
    ws.send(frame('CONNECT', {
      'accept-version': '1.2',
      host: 'sage',
      'heart-beat': '0,0',
      Authorization: `Bearer ${receiver.token}`,
    }));
  };
  ws.onmessage = (event) => {
    // 한 웹소켓 메시지에 프레임이 여러 개 올 수 있다(NUL로 구분).
    String(event.data).split('\0').filter((f) => f.trim().length > 0).forEach((f) => onFrame(f.replace(/^\n+/, ''), ws, receiver));
  };
  ws.onerror = (e) => {
    stompErrors.add(1);
    console.error(`웹소켓 오류: ${e.error}`);
  };
  // 보내기가 끝나고 늦게 오는 메시지까지 받은 뒤 닫는다.
  setTimeout(() => ws.close(), (CONNECT_GRACE_S + WARMUP_S + DURATION_S + 15) * 1000);
}

export function handleSummary(data) {
  const pick = (name, stat) => (data.metrics[name] ? data.metrics[name].values[stat] : undefined);
  const line = [
    `[perf] ${RUN_NAME}`,
    `e2e p50=${pick('e2e_latency{phase:measure}', 'med')}ms`,
    `p95=${pick('e2e_latency{phase:measure}', 'p(95)')}ms`,
    `p99=${pick('e2e_latency{phase:measure}', 'p(99)')}ms`,
    `received=${pick('e2e_received{phase:measure}', 'count')}`,
    `send p99=${pick('http_req_duration{phase:measure}', 'p(99)')}ms`,
    `failed=${pick('http_req_failed{phase:measure}', 'rate')}`,
  ].join(' ');
  return {
    [`/results/${RUN_NAME}.json`]: JSON.stringify(data, null, 2),
    stdout: `${line}\n`,
  };
}
