# PR 0 — 프론트 선행 수정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 서버를 변경하지 않은 채, WS 인가(PR 1)를 켤 수 있는 상태로 프론트를 정비한다.

**Architecture:** 순수 함수로 분리할 수 있는 판단(재연결 지연, 세션 만료 여부)을 `lib/`의 작은 모듈로 빼서 단위 테스트로 고정하고, 배선(`App.tsx`·`stomp.ts`)은 그 함수를 호출하도록 바꾼다. 프론트에 테스트 러너와 CI 검증이 없으므로 먼저 그것부터 세운다.

**Tech Stack:** React 19, TypeScript 5.8, Vite 6, Vitest(신규), 직접 구현한 STOMP 클라이언트(라이브러리 아님)

## Global Constraints

- 설계 문서: `docs/superpowers/specs/2026-07-31-room-authorization-design.md` §6 (F1~F5)
- **서버 코드(`src/main/java/**`)를 변경하지 않는다.** PR 0은 프론트 전용이다
- 프론트 검증 명령: `cd frontend && npm run lint && npm run build` (`lint`는 `tsc --noEmit`)
- 브랜치: `feat/room-authorization` (develop에서 분기). PR 대상은 **develop**
- 커밋 메시지·주석은 변경의 목적만 쓴다. 배경 서사("누락됐다", "그래서 깨져 있었다")를 넣지 않는다
- 테스트는 vitest 전역이 아니라 `import { describe, it, expect } from 'vitest'`로 명시 import 한다 — `tsconfig.json`에 `types` 설정을 추가하지 않기 위해서다
- 새 STOMP 목적지 문자열은 `/user/queue/errors` 하나뿐이다. 서버는 아직 여기로 보내지 않으며, 인메모리 SimpleBroker라 발신 없는 목적지 구독은 무해하다

## File Structure

| 파일 | 책임 |
|---|---|
| `frontend/src/lib/reconnect.ts` (신규) | 재연결 지연·소진 판정. 순수 함수만 |
| `frontend/src/lib/reconnect.test.ts` (신규) | 위 단위 테스트 |
| `frontend/src/lib/errors.ts` (수정) | 사용자 메시지 변환 + **세션 만료 오류 판별** 추가 |
| `frontend/src/lib/errors.test.ts` (신규) | 위 단위 테스트 |
| `frontend/src/lib/stomp.ts` (수정) | `/user/queue/errors` 구독과 인가 오류 콜백 |
| `frontend/src/App.tsx` (수정) | join 성공 후 구독, 백오프 배선, 부트스트랩 catch 기준 |
| `frontend/package.json` (수정) | vitest devDependency + `test` 스크립트 |
| `.github/workflows/ci.yml` (수정) | 프론트 잡 추가 (lint + test + build) |

---

### Task 1: 프론트 테스트 인프라와 CI 잡

**Files:**
- Modify: `frontend/package.json`
- Create: `frontend/src/lib/reconnect.ts`
- Create: `frontend/src/lib/reconnect.test.ts`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `npm test` 명령, CI의 `frontend` 잡. 이후 모든 태스크가 `npm test`로 검증한다

**배경:** 현재 프론트에는 테스트 러너가 없고(`package.json` scripts: dev/build/start/preview/clean/lint), CI는 gradle만 돌린다(`.github/workflows/ci.yml`에 node 스텝 없음). PR 0이 바꾸는 것은 조용히 깨지는 비동기 순서 로직이므로 회귀를 잡을 수단이 먼저 필요하다.

- [ ] **Step 1: vitest 설치**

```bash
cd frontend && npm install -D vitest
```

기대: `package.json`의 `devDependencies`에 `vitest`가 추가되고 `package-lock.json`이 갱신된다.

- [ ] **Step 2: `test` 스크립트 추가**

`frontend/package.json`의 `scripts`에 한 줄 추가한다. 기존 스크립트는 그대로 둔다.

```json
"test": "vitest run"
```

`vitest run`은 watch 없이 1회 실행하고 종료 코드를 남긴다. CI에서 쓰려면 `vitest`(watch 모드)가 아니라 이것이어야 한다.

- [ ] **Step 3: 실패하는 테스트 작성**

`frontend/src/lib/reconnect.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { reconnectDelayMs, reconnectExhausted, RECONNECT_MAX_MS, RECONNECT_MAX_ATTEMPTS } from './reconnect';

describe('reconnectDelayMs', () => {
  it('첫 시도는 기본 지연의 절반에서 시작한다', () => {
    expect(reconnectDelayMs(0, () => 0)).toBe(500);
    expect(reconnectDelayMs(0, () => 1)).toBe(1000);
  });

  it('시도마다 상한이 2배로 늘어난다', () => {
    expect(reconnectDelayMs(1, () => 1)).toBe(2000);
    expect(reconnectDelayMs(2, () => 1)).toBe(4000);
    expect(reconnectDelayMs(3, () => 1)).toBe(8000);
  });

  it('지터가 최대여도 상한을 넘지 않는다', () => {
    expect(reconnectDelayMs(5, () => 1)).toBe(RECONNECT_MAX_MS);
    expect(reconnectDelayMs(20, () => 1)).toBe(RECONNECT_MAX_MS);
  });

  it('지터가 최소여도 상한의 절반 아래로 내려가지 않는다', () => {
    expect(reconnectDelayMs(20, () => 0)).toBe(RECONNECT_MAX_MS / 2);
  });

  it('음수 시도는 0으로 취급한다', () => {
    expect(reconnectDelayMs(-3, () => 1)).toBe(1000);
  });
});

describe('reconnectExhausted', () => {
  it('상한 미만이면 계속 시도한다', () => {
    expect(reconnectExhausted(RECONNECT_MAX_ATTEMPTS - 1)).toBe(false);
  });

  it('상한에 도달하면 중단한다', () => {
    expect(reconnectExhausted(RECONNECT_MAX_ATTEMPTS)).toBe(true);
  });
});
```

- [ ] **Step 4: 테스트가 실패하는 것 확인**

```bash
cd frontend && npm test
```

기대: FAIL — `Failed to resolve import "./reconnect"`

- [ ] **Step 5: 구현**

`frontend/src/lib/reconnect.ts`:

```ts
// 재연결 간격은 지수적으로 늘리고 상한을 둔다.
// 지터는 서버가 되살아날 때 모든 클라이언트가 같은 시점에 몰려 다시 과부하를 주는 것을 막는다.
// 지연은 [상한의 절반, 상한]에서 뽑는다 — 상한을 실제로 넘지 않으면서 분산을 유지한다.
// 상한에 곱셈으로 지터를 주면 상한을 초과하고, 곱한 뒤 자르면 상한 근처에서 분산이 뭉개진다.
export const RECONNECT_BASE_MS = 1000;
export const RECONNECT_MAX_MS = 30000;
export const RECONNECT_MAX_ATTEMPTS = 8;

/** attempt는 0부터. random은 테스트에서 고정하기 위한 주입점이다. */
export function reconnectDelayMs(attempt: number, random: () => number = Math.random): number {
  const steps = Math.max(0, attempt);
  const ceiling = Math.min(RECONNECT_BASE_MS * 2 ** steps, RECONNECT_MAX_MS);
  return Math.round(ceiling / 2 + random() * (ceiling / 2));
}

/** 자동 재시도를 멈추고 사용자에게 알려야 하는 시점 */
export function reconnectExhausted(attempt: number): boolean {
  return attempt >= RECONNECT_MAX_ATTEMPTS;
}
```

- [ ] **Step 6: 테스트 통과 확인**

```bash
cd frontend && npm test
```

기대: PASS — 7 tests

- [ ] **Step 7: CI에 프론트 잡 추가**

`.github/workflows/ci.yml`의 `jobs:` 아래에 잡을 하나 추가한다. 기존 백엔드 잡은 건드리지 않는다.

```yaml
  frontend:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
          cache-dependency-path: frontend/package-lock.json

      - name: Install
        run: npm ci

      - name: Lint (tsc)
        run: npm run lint

      - name: Test
        run: npm test

      - name: Build
        run: npm run build
```

- [ ] **Step 8: 로컬에서 CI가 돌릴 것을 그대로 확인**

```bash
cd frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0

- [ ] **Step 9: 커밋**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/lib/reconnect.ts frontend/src/lib/reconnect.test.ts .github/workflows/ci.yml
git commit -m "test(frontend): 테스트 러너와 CI 검증 도입, 재연결 지연 계산 분리"
```

---

### Task 2: 세션 만료 오류 판별

**Files:**
- Modify: `frontend/src/lib/errors.ts`
- Create: `frontend/src/lib/errors.test.ts`

**Interfaces:**
- Consumes: `ApiError`(`frontend/src/lib/api.ts`, `status: number`, `code?: string`)
- Produces: `isSessionExpiredError(err: unknown): boolean`

**배경:** `App.tsx`의 부트스트랩 catch가 오류 종류를 가리지 않고 세션을 지운다. 기준이 필요한데, `status === 401`로는 안 된다 — `INVALID_PASSWORD`·`SOCIAL_LOGIN_ONLY`도 401이다. `api.ts:73`이 이미 쓰는 기준(`status === 401 && (code === undefined || code === 'UNAUTHORIZED')`)과 같아야 한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/src/lib/errors.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { isSessionExpiredError } from './errors';
import { ApiError } from './api';

describe('isSessionExpiredError', () => {
  it('401 UNAUTHORIZED는 세션 만료다', () => {
    expect(isSessionExpiredError(new ApiError('만료', 401, 'UNAUTHORIZED'))).toBe(true);
  });

  it('코드 없는 401도 세션 만료로 본다', () => {
    expect(isSessionExpiredError(new ApiError('실패', 401))).toBe(true);
  });

  it('같은 401이라도 다른 코드는 세션 만료가 아니다', () => {
    expect(isSessionExpiredError(new ApiError('비밀번호 틀림', 401, 'INVALID_PASSWORD'))).toBe(false);
    expect(isSessionExpiredError(new ApiError('소셜 전용', 401, 'SOCIAL_LOGIN_ONLY'))).toBe(false);
  });

  it('403·500·502는 세션 만료가 아니다', () => {
    expect(isSessionExpiredError(new ApiError('권한 없음', 403, 'NOT_JOINED_ROOM'))).toBe(false);
    expect(isSessionExpiredError(new ApiError('서버 오류', 500))).toBe(false);
    expect(isSessionExpiredError(new ApiError('게이트웨이', 502))).toBe(false);
  });

  it('네트워크 실패(TypeError)는 세션 만료가 아니다', () => {
    expect(isSessionExpiredError(new TypeError('Failed to fetch'))).toBe(false);
  });

  it('ApiError가 아닌 값은 세션 만료가 아니다', () => {
    expect(isSessionExpiredError(new Error('그냥 오류'))).toBe(false);
    expect(isSessionExpiredError(undefined)).toBe(false);
    expect(isSessionExpiredError('문자열')).toBe(false);
  });
});
```

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
cd frontend && npm test
```

기대: FAIL — `isSessionExpiredError is not a function` 또는 import 오류

- [ ] **Step 3: 구현**

`frontend/src/lib/errors.ts` 끝에 추가한다. 기존 `toUserMessage`는 그대로 둔다.

```ts
import { ApiError } from './api';

// 세션이 만료된 경우에만 로그인 화면으로 돌린다.
// 기준은 api.ts의 401 처리기와 같다 — 같은 401이라도 INVALID_PASSWORD 같은 코드는 세션을 지우면 안 된다.
export function isSessionExpiredError(err: unknown): boolean {
  if (!(err instanceof ApiError)) return false;
  if (err.status !== 401) return false;
  return err.code === undefined || err.code === 'UNAUTHORIZED';
}
```

`import`는 파일 맨 위로 옮긴다(첫 줄 주석 다음).

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd frontend && npm test
```

기대: PASS — reconnect 7건 + errors 6건

- [ ] **Step 5: 순환 import가 없는지 확인**

`errors.ts`가 `api.ts`를 import 하는데, `api.ts`가 `errors.ts`를 import 하지 않는지 본다.

```bash
cd frontend && grep -n "from './errors'" src/lib/api.ts || echo "순환 없음"
```

기대: `순환 없음`

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/lib/errors.ts frontend/src/lib/errors.test.ts
git commit -m "feat(frontend): 세션 만료 오류 판별을 코드 기준으로 분리"
```

---

### Task 3: 부트스트랩이 오류 종류를 가려 처리하도록 수정

**Files:**
- Modify: `frontend/src/App.tsx:214-217`

**Interfaces:**
- Consumes: `isSessionExpiredError`(Task 2), 기존 `notify`, `toUserMessage`
- Produces: 없음 (동작 수정)

**배경:** 현재 catch가 무조건 `clearSession()`을 한다. `api.ts:76`은 상태코드와 무관하게 던지므로 403·500·502·네트워크 오류가 모두 여기로 오고, 배포 중 502 한 번에도 세션이 지워진다.

또한 진짜 401일 때 `api.ts:74`의 `unauthorizedHandler`가 **이미** `clearSession()` + 안내 문구 설정을 마친 상태다. 그 뒤 catch가 `clearSession()`을 한 번 더 하면 `clearSession` 마지막 줄의 `setNotice(null)`(`App.tsx:111`)이 안내를 덮는다. **catch에서 세션을 지우지 않는 것만으로 두 문제가 함께 해결된다.**

- [ ] **Step 1: 현재 동작 확인**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && sed -n '214,218p' frontend/src/App.tsx
```

기대 출력:

```
      } catch (error) {
        console.error('[Auth] Saved token is invalid:', error);
        if (!cancelled) clearSession();
      }
    }
```

- [ ] **Step 2: 수정**

`App.tsx:214-217`을 아래로 교체한다.

```tsx
      } catch (error) {
        console.error('[Auth] 부트스트랩 실패:', error);
        if (cancelled) return;
        // 세션 만료는 api.ts의 401 처리기가 이미 세션 정리와 안내를 마쳤다.
        // 여기서 clearSession()을 다시 부르면 그 안내를 지운다.
        if (!isSessionExpiredError(error)) {
          notify(toUserMessage(error, '계정 정보를 불러오지 못했어요.'));
        }
      }
```

- [ ] **Step 3: import 추가**

`App.tsx`의 `./lib/errors` import에 `isSessionExpiredError`를 더한다. `toUserMessage`는 이미 import 되어 있다.

```tsx
import { toUserMessage, isSessionExpiredError } from './lib/errors';
```

- [ ] **Step 4: 의존성 배열 확인**

이 `useEffect`의 의존성 배열(`App.tsx:224`)에서 `clearSession`이 더 이상 본문에서 쓰이지 않는다. **배열에서 빼지 않는다** — 같은 이펙트의 다른 위치에서 쓰일 수 있고, 불필요한 항목이 있어도 동작은 정확하다. `notify`가 배열에 없으면 추가한다.

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && sed -n '224p' frontend/src/App.tsx
```

`notify`가 목록에 없으면 추가한다.

- [ ] **Step 5: 타입 검사와 빌드**

```bash
cd frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0

- [ ] **Step 6: 수동 확인 — 서버가 죽어도 로그아웃되지 않는다**

백엔드 컨테이너를 정지한 상태에서 프론트를 새로고침한다.

기대: 로그인 화면으로 튕기지 않고 오류 안내만 표시된다. 수정 전에는 로그인 화면으로 돌아간다.

- [ ] **Step 7: 커밋**

```bash
git add frontend/src/App.tsx
git commit -m "fix(frontend): 세션 만료가 아닌 오류에서 세션을 유지하고 안내를 보존"
```

---

### Task 4: STOMP 오류 채널 구독

**Files:**
- Modify: `frontend/src/lib/stomp.ts`

**Interfaces:**
- Consumes: 없음
- Produces: `StompClientOptions.onAuthzError?: (err: WsAuthzError) => void`, `export interface WsAuthzError { code: string; message: string; destination?: string }`

**배경:** 설계 §6 F2·F3. 인가 거부 사유를 받을 통로를 만든다. STOMP ERROR 프레임의 헤더로는 401/403을 구분할 수 없으므로(래핑된 예외 문자열), 사유는 개인 큐로만 받는다. 기존 `onError`는 **연결 수준 실패** 전용으로 남긴다 — 별도 콜백을 두는 편이 하나의 콜백에 종류 인자를 넣는 것보다 호출부의 분기가 분명해진다.

PR 0 시점에는 서버가 이 목적지로 보내지 않으므로 콜백이 호출되지 않는다. 프론트가 먼저 배포되어 있어야 PR 1 적용 직후 사용자가 사유를 볼 수 있다.

- [ ] **Step 1: 타입과 옵션 추가**

`stomp.ts:1-18`의 인터페이스 블록을 수정한다.

```ts
import { BackendMessage } from './api';

interface StompFrame {
  command: string;
  headers: Record<string, string>;
  body: string;
}

/** 서버가 인가 거부 사유를 개인 큐로 보낼 때의 페이로드 */
export interface WsAuthzError {
  code: string;
  message: string;
  destination?: string;
}

interface StompClientOptions {
  token: string;
  onConnect: () => void;
  onMessage: (message: BackendMessage) => void;
  onPresence?: (roomId: string, onlineMemberIds: string[]) => void;
  onTyping?: (p: { chatroomId: string; memberId: string; nickname: string; typing: boolean }) => void;
  onUnread?: (evt: { chatroomId: number; messageId: number }) => void;
  /** 특정 목적지의 인가 거부. 세션은 살아있으므로 재연결하지 않는다. */
  onAuthzError?: (err: WsAuthzError) => void;
  onDisconnect: () => void;
  /** 연결 수준 실패(ERROR 프레임·소켓 오류). 세션이 끊겼으므로 재연결 대상이다. */
  onError: () => void;
}
```

- [ ] **Step 2: 구독 상태 필드 추가**

`stomp.ts:27-29`을 수정한다.

```ts
  private unreadSubscription?: string;
  private authzErrorSubscription?: string;
  // subscription id -> 종류: 들어온 MESSAGE 프레임을 알맞은 핸들러로 라우팅
  private subscriptionKinds = new Map<string, 'chat' | 'typing' | 'roompresence' | 'unread' | 'authzerror'>();
```

- [ ] **Step 3: CONNECTED 시 구독 추가**

`stomp.ts:136-146`의 `CONNECTED` 블록에서 `this.options.onConnect();` 바로 앞에 추가한다.

```ts
        this.authzErrorSubscription = `sub-${++this.subscriptionId}`;
        this.subscriptionKinds.set(this.authzErrorSubscription, 'authzerror');
        this.write('SUBSCRIBE', {
          id: this.authzErrorSubscription,
          destination: '/user/queue/errors',
          ack: 'auto',
        });
```

- [ ] **Step 4: MESSAGE 라우팅에 분기 추가**

`stomp.ts:162-164`의 `unread` 분기 다음에 추가한다.

```ts
        } else if (kind === 'authzerror') {
          this.options.onAuthzError?.(JSON.parse(frame.body) as WsAuthzError);
```

- [ ] **Step 5: disconnect에서 정리**

`stomp.ts:68` 다음 줄에 추가한다.

```ts
    this.authzErrorSubscription = undefined;
```

- [ ] **Step 6: 타입 검사와 빌드**

```bash
cd frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0

- [ ] **Step 7: 수동 확인 — 구독 프레임이 나가는지**

앱을 띄우고 개발자도구 Network → WS → Messages에서 연결 직후 프레임을 본다.

기대: `SUBSCRIBE` 프레임 두 개(`/user/queue/unread`, `/user/queue/errors`)가 나가고, 연결과 채팅이 평소대로 동작한다.

- [ ] **Step 8: 커밋**

```bash
git add frontend/src/lib/stomp.ts
git commit -m "feat(frontend): 인가 거부 사유를 받는 개인 오류 채널 구독"
```

---

### Task 5: join 성공 이후에 구독하도록 배선

**Files:**
- Modify: `frontend/src/App.tsx:226-290`

**Interfaces:**
- Consumes: 기존 `joinChatRoom`, `selectedChannelRef`, `stompRef`
- Produces: 없음 (동작 수정)

**배경:** 설계 §6 F1. 현재 `loadMessages()`를 `await` 없이 호출하고 다음 줄에서 동기적으로 구독한다(`App.tsx:284-285`). `loadMessages`의 첫 await가 `joinChatRoom`의 fetch이므로 제어가 즉시 반환되어 **첫 입장에서 SUBSCRIBE가 join 커밋보다 먼저 도착한다.**

`await loadMessages()`로 바꾸는 것으로는 부족하다 — 메시지 로드까지 기다린 뒤 구독하면 그 사이 도착한 메시지를 놓친다. **join 성공 직후, 메시지 로드 전에 구독**해야 순서와 무손실을 함께 만족한다. 중복 수신은 기존 `onMessage`의 id 대조(`App.tsx:327`)가 흡수한다.

- [ ] **Step 1: 이펙트 본문 교체**

`App.tsx:226-290`의 이펙트를 아래로 교체한다. 함수명을 `loadMessages`에서 `enterRoom`으로 바꾼다 — 이제 입장·구독·로드를 순서대로 수행하기 때문이다.

```tsx
  useEffect(() => {
    if (!token || !selectedChannelId) return;

    let cancelled = false;

    async function enterRoom() {
      setLoadingMessage('메시지를 불러오는 중입니다.');

      try {
        await joinChatRoom(token, selectedChannelId);
      } catch (error) {
        // 방에 못 들어갔으므로 채팅 화면에 남을 이유가 없다. 랜딩으로 되돌린다.
        if (!cancelled) {
          notify(toUserMessage(error, '채널에 입장하지 못했어요.'));
          setSelectedChannelId('');
        }
        return;
      }

      // 방 전환이 겹치면 이전 방을 구독하지 않는다.
      if (cancelled || selectedChannelRef.current !== selectedChannelId) return;

      // 입장이 확정된 뒤 구독한다. 메시지 로드보다 먼저 해야 그 사이 도착한 메시지를 놓치지 않는다.
      stompRef.current?.subscribe(selectedChannelId);

      try {
        const page = await getMessages(token, selectedChannelId);
        if (!cancelled) {
          const mapped = page.messages.map(toMessage);
          setMessages((prev) => {
            const otherRooms = prev.filter((message) => message.channelId !== selectedChannelId);
            return [...otherRooms, ...mapped];
          });
          setPageState((prev) => ({
            ...prev,
            [selectedChannelId]: {
              oldestId: page.messages.length ? page.messages[0].messageId : null,
              hasMore: page.hasMore,
              loading: false,
            },
          }));
          // 입장 시 읽음 처리 → 배지 0. (구분선용 lastRead 스냅샷은 App의 roomLastRead를 갱신하지 않아 세션 동안 고정)
          // 읽음 처리 실패는 사용자가 조치할 수 없고 다음 입장에서 회복되므로 알리지 않는다.
          // 여기서 새어나가면 "메시지를 불러오지 못했어요"로 잘못 표시된다.
          try {
            await markRoomRead(token, selectedChannelId);
            setUnread((prev) => ({ ...prev, [selectedChannelId]: 0 }));
          } catch (readError) {
            console.error('[Unread] 입장 시 읽음 처리 실패(무시하고 계속):', readError);
          }
          // 다음 입장 때 낡은 구분선이 뜨지 않도록 경계를 전진 (현재 화면은 ChatArea가 입장 시점 값으로 고정)
          const newestId = page.messages.length ? page.messages[page.messages.length - 1].messageId : null;
          if (newestId != null) {
            setRoomLastRead((prev) => ({ ...prev, [selectedChannelId]: newestId }));
          }
        }
      } catch (error) {
        // 입장은 성공했으므로 채팅 화면에 남는다.
        if (!cancelled) {
          notify(toUserMessage(error, '메시지를 불러오지 못했어요.'));
        }
      }
    }

    enterRoom();

    return () => {
      cancelled = true;
    };
  }, [token, selectedChannelId, notify]);
```

`stompRef.current?.subscribe(selectedChannelId);`가 이펙트 본문 끝(`App.tsx:285`)에서 사라지고 `enterRoom` 안으로 들어간 것이 이 태스크의 핵심이다.

- [ ] **Step 2: 재연결 경로도 같은 규칙을 따르는지 확인**

`App.tsx:314-316`의 `onConnect`가 `selectedChannelRef.current`를 구독한다. 이 경로는 이미 join이 끝난 방에만 해당하므로 그대로 둔다. 변경하지 않는다.

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && sed -n '312,318p' frontend/src/App.tsx
```

기대: `if (selectedChannelRef.current) { client.subscribe(selectedChannelRef.current); }` 가 그대로 있다.

- [ ] **Step 3: 타입 검사와 빌드**

```bash
cd frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0

- [ ] **Step 4: 수동 확인 — 순서 역전**

개발자도구를 열고 Network 탭과 WS → Messages 탭을 함께 본다. **아직 들어가 본 적 없는 방**을 처음 선택한다.

기대 순서:
1. `POST /api/chatrooms/{id}/members` 요청과 응답
2. 그 다음 `SUBSCRIBE destination:/sub/chatrooms/{id}/presence` 등 프레임 3개
3. `GET /api/chatrooms/{id}/messages`

수정 전에는 2번이 1번의 응답보다 먼저 나간다.

- [ ] **Step 5: 수동 확인 — 방 빠른 전환**

방 A와 방 B를 빠르게 번갈아 클릭한다.

기대: 마지막에 선택한 방만 구독된다. WS 프레임에서 이전 방 목적지로의 `SUBSCRIBE`가 뒤늦게 나가지 않는다.

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/App.tsx
git commit -m "fix(frontend): 채팅방 입장이 확정된 뒤 구독하도록 순서 정리"
```

---

### Task 6: 재연결 백오프와 상한 배선

**Files:**
- Modify: `frontend/src/App.tsx:292-404`

**Interfaces:**
- Consumes: `reconnectDelayMs`, `reconnectExhausted`(Task 1)
- Produces: 없음 (동작 수정)

**배경:** 설계 §6 F4. 현재 `scheduleReconnect`가 고정 3초에 상한이 없고(`App.tsx:298-306`), 재연결마다 `getUnreadCounts` REST를 동반한다(`App.tsx:318`). PR 1에서 인증 실패는 세션 종료로 이어지므로 이 경로가 실제로 돌게 된다.

- [ ] **Step 1: import 추가**

```tsx
import { reconnectDelayMs, reconnectExhausted } from './lib/reconnect';
```

- [ ] **Step 2: 상한 도달 상태 추가**

다른 `useState` 선언들 옆에 추가한다.

```tsx
  const [reconnectGaveUp, setReconnectGaveUp] = useState(false);
```

- [ ] **Step 3: `scheduleReconnect` 교체**

`App.tsx:298-306`을 아래로 교체한다. `attempt`는 이펙트 안의 지역 변수로 둔다 — 이 이펙트가 재실행되면 연결도 새로 만들어지므로 함께 초기화되는 것이 맞다.

```tsx
    let attempt = 0;

    const scheduleReconnect = () => {
      if (disposed || reconnectTimer) return;
      setConnected(false);

      if (reconnectExhausted(attempt)) {
        setReconnectGaveUp(true);   // 조용한 무한 재시도 대신 사용자에게 알린다
        return;
      }

      const delay = reconnectDelayMs(attempt);
      attempt += 1;
      setReconnectCount(attempt);
      reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        connect();
      }, delay);
    };
```

- [ ] **Step 4: 연결 성공 시 초기화**

`App.tsx:311-313`의 `onConnect` 시작 부분을 수정한다.

```tsx
        onConnect: () => {
          setConnected(true);
          attempt = 0;
          setReconnectCount(0);
          setReconnectGaveUp(false);
```

- [ ] **Step 5: 인가 오류는 재연결하지 않도록 연결**

`App.tsx:386-387`의 콜백 배선에 `onAuthzError`를 추가한다. `onDisconnect`·`onError`는 그대로 `scheduleReconnect`다.

```tsx
        onAuthzError: ({ message }) => {
          // 세션은 살아있고 특정 목적지만 거부된 것이므로 재연결하지 않는다.
          notify(message || '이 채널에 접근할 수 없어요.');
        },
        onDisconnect: scheduleReconnect,
        onError: scheduleReconnect,
```

- [ ] **Step 6: 상한 도달을 기존 연결 끊김 배너에 반영**

`notice`는 쓰지 않는다. `notice`는 `Welcome`(로그인 전 화면)에만 전달되므로 재연결이 도는 상황에서는 화면에 나타나지 않는다.

로그인 후 화면에는 이미 `!connected`일 때 뜨는 배너가 있다(`App.tsx:553`). 상한에 도달하면 같은 배너를 종료 상태로 바꾼다 — 문구를 조치 안내로 바꾸고, 재시도 중을 뜻하는 회전 아이콘을 제거한다.

```tsx
            <WifiOff className="w-4 h-4 animate-pulse flex-shrink-0" />
            <span>
              {reconnectGaveUp
                ? '실시간 채팅에 연결할 수 없습니다. 페이지를 새로고침해 주세요. REST API는 계속 사용할 수 있습니다.'
                : `실시간 채팅 연결 대기 중입니다. REST API는 계속 사용할 수 있습니다. (${reconnectCount}회)`}
            </span>
            {!reconnectGaveUp && <RefreshCw className="w-3.5 h-3.5 animate-spin ml-2 flex-shrink-0" />}
```

회전 아이콘을 남기면 자동 재시도가 계속되는 것처럼 보여 사용자가 기다리게 된다.

- [ ] **Step 7: 타입 검사와 빌드**

```bash
cd frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0

- [ ] **Step 8: 수동 확인 — 백오프**

앱을 띄운 상태에서 백엔드 컨테이너를 정지한다.

```bash
docker compose stop app
```

개발자도구 Network → WS에서 재연결 시도 간격을 관찰한다.

기대: 간격이 약 1초 → 2초 → 4초 → 8초로 벌어진다(±20% 지터). 수정 전에는 계속 3초다.

- [ ] **Step 9: 수동 확인 — 상한**

정지한 채로 두고 8회 시도가 끝날 때까지 기다린다(총 약 1분).

기대: 자동 재시도가 멈추고 "서버에 연결할 수 없어요. 페이지를 새로고침해 주세요." 안내가 뜬다.

- [ ] **Step 10: 수동 확인 — 복구**

```bash
docker compose start app
```

새로고침 후 정상 연결되고 채팅이 동작하는지 확인한다.

- [ ] **Step 11: 커밋**

```bash
git add frontend/src/App.tsx
git commit -m "feat(frontend): 재연결에 지수 백오프와 시도 상한 적용"
```

---

### Task 7: 최종 검증과 PR

**Files:** 없음 (검증만)

**Interfaces:**
- Consumes: Task 1~6 전부
- Produces: PR

- [ ] **Step 1: 전체 검증**

```bash
cd frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0

- [ ] **Step 2: 백엔드 회귀 없음 확인**

PR 0은 서버를 건드리지 않지만 CI 잡을 추가했으므로 확인한다.

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && ./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 3: 서버 변경이 없는지 확인**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && git diff develop --stat -- src/
```

기대: 출력 없음

- [ ] **Step 4: 설계 §10의 수동 검증 4항목 실행**

| 확인 | 방법 | 기대 |
|---|---|---|
| join이 구독보다 먼저 | Network + WS 프레임 탭에서 새 방 첫 입장 | `POST .../members` 응답 후 `SUBSCRIBE` |
| 백오프 | 백엔드 컨테이너 정지 후 콘솔 관찰 | 재연결 간격 1→2→4→8초 |
| 로그아웃 오작동 수정 | 백엔드 정지 상태로 새로고침 | 로그인 화면으로 튕기지 않고 오류 배너만 |
| 세션 만료 안내 | 만료 토큰으로 접속 | 안내 문구가 남아있음 |

- [ ] **Step 5: PR 생성**

본문은 `.github/pull_request_template.md`의 섹션을 그대로, 같은 순서·같은 제목으로 채운다. 해당 없는 섹션은 "없음"이라고 적는다. `## 검증`에는 실제로 실행한 것만 쓰고, 안 한 검증은 안 했다고 명시한다.

```bash
git push -u origin feat/room-authorization
```

PR 대상 브랜치는 **develop**이다. 머지는 사용자가 한다.

---

## Self-Review

**스펙 커버리지 (설계 §6):**

| 요구 | 태스크 |
|---|---|
| F1 join 성공 후 구독 | Task 5 |
| F2 `/user/queue/errors` 구독 | Task 4 |
| F3 연결 오류와 인가 오류 구분 | Task 4(통로) + Task 6 Step 5(배선) |
| F4 재연결 백오프·상한 | Task 1(계산) + Task 6(배선) |
| F5 오류 종류를 가려 세션 정리 + 안내 보존 | Task 2(판별) + Task 3(적용) |

**추가된 범위:** Task 1의 테스트 러너와 CI 잡은 설계 §6에 없다. 프론트에 자동 검증 수단이 전혀 없는 상태에서 조용히 깨지는 비동기 순서 로직을 고치기 때문에 포함했다. 빼려면 Task 1에서 vitest·CI 스텝을 제거하고 `reconnect.ts`만 남긴 뒤, 이후 태스크의 `npm test`를 `npm run lint`로 바꾸면 된다.

**타입 일관성:** `WsAuthzError`(Task 4 정의) → Task 6 Step 5에서 `{ message }` 구조분해로 소비. `reconnectDelayMs(attempt, random?)`·`reconnectExhausted(attempt)`(Task 1 정의) → Task 6 Step 3에서 호출. `isSessionExpiredError(err)`(Task 2 정의) → Task 3 Step 2에서 호출. 이름과 시그니처가 일치한다.

**설계에서 벗어난 점:** §6 F3은 "`onError`가 사유를 받도록 시그니처를 바꾼다"고 했으나, 이 계획은 `onError`를 연결 수준 전용으로 두고 `onAuthzError`를 새로 추가한다. 두 경로의 반응이 완전히 다르므로(재연결 대 미재연결) 콜백을 나누는 편이 호출부의 분기가 분명하다. 결과는 같다.
