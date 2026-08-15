# 타이핑 인디케이터 Implementation Plan

> **실행 방식:** hands-on 학습 프로젝트 — 재민 님이 직접 구현하거나 Claude가 구현 후 리뷰. 참고 코드는 "이 방향". 체크박스(`- [ ]`)로 추적.

**Goal:** 채팅방에서 상대가 입력 중이면 "OO님이 입력 중... ✍️"를 실시간 표시(여러 서버에서도 동작).

**Architecture:** 타이핑은 stateless 이벤트 → 메시지 파이프라인과 동형으로 처리. 클라 `SEND /pub/chatrooms/{id}/typing` → 컨트롤러 → `RedisPublisher.publishTyping`(토픽 `typing`) → `TypingRedisSubscriber` → `convertAndSend("/sub/chatrooms/{id}/typing", …)`. 프론트는 기존 `typingUsers` UI를 재사용하고 배선만 추가.

**Tech Stack:** Spring Boot(STOMP, Spring Data Redis), JUnit(빌드 검증), React + TypeScript, Node(WebSocket) E2E.

## Global Constraints

- **DB 스키마 변경 없음**. Redis 토픽/키만 추가. 배포 안전.
- **stateless** — 서버는 타이핑 상태를 저장하지 않고 릴레이만.
- 와이어 필드명은 **`typing`**(boolean). 이유: Lombok `@Getter`가 `boolean typing` → `isTyping()` 생성 → Jackson이 "is" 접두사를 떼어 JSON 키를 **"typing"** 으로 만듦. `isTyping`으로 이름 지으면 round-trip에서 키 불일치. 그래서 프론트도 `{ typing: … }`로 주고받음.
- typing 전용 Redis 토픽 `"typing"`(채팅 `"chatroom"`·presence `"presence"`와 분리). subscriber는 `TypingResponse`로만 역직렬화.
- true는 **3초 하트비트 스로틀**, 수신측 **5초 안전 만료**(하트비트 < 만료라 정상 타이핑 중 안 꺼짐).
- 메시지·presence 파이프라인·`ChatArea`의 typingUsers UI·DB 무변경.

---

### Task 1: 백엔드 — 타이핑 방송 파이프라인 + STOMP 핸들러

DTO 2개, Redis 토픽·subscriber, publisher 메서드, 컨트롤러 핸들러를 한 번에. 순수 로직이 없어(릴레이) 빌드+E2E로 검증.

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/dto/TypingRequest.java`
- Create: `src/main/java/com/example/springboot_realtimechat/dto/TypingResponse.java`
- Create: `src/main/java/com/example/springboot_realtimechat/redis/TypingRedisSubscriber.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/redis/RedisPublisher.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/config/RedisConfig.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/ChatMessageController.java`

**Interfaces:**
- Produces: `SEND /pub/chatrooms/{id}/typing {typing}` → `/sub/chatrooms/{id}/typing`로 `TypingResponse{chatroomId, memberId, nickname, typing}` JSON 방송.
- Consumes: `MemberService.getMemberById(Long)`(nickname), `RedisTemplate`, `ChannelTopic`, `SimpMessagingTemplate`, `tools.jackson.databind.ObjectMapper`.

- [ ] **Step 1: DTO 2개 작성**

`dto/TypingRequest.java`:
```java
package com.example.springboot_realtimechat.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TypingRequest {
    private boolean typing;
}
```

`dto/TypingResponse.java`:
```java
package com.example.springboot_realtimechat.dto;

import lombok.Getter;

@Getter
public class TypingResponse {
    private final Long chatroomId;
    private final Long memberId;
    private final String nickname;
    private final boolean typing;

    public TypingResponse(Long chatroomId, Long memberId, String nickname, boolean typing) {
        this.chatroomId = chatroomId;
        this.memberId = memberId;
        this.nickname = nickname;
        this.typing = typing;
    }
}
```

- [ ] **Step 2: TypingRedisSubscriber 작성 (RedisSubscriber/PresenceRedisSubscriber와 대칭)**

`redis/TypingRedisSubscriber.java`:
```java
package com.example.springboot_realtimechat.redis;

import com.example.springboot_realtimechat.dto.TypingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class TypingRedisSubscriber implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            TypingResponse typing = objectMapper.readValue(message.getBody(), TypingResponse.class);
            messagingTemplate.convertAndSend(
                    "/sub/chatrooms/" + typing.getChatroomId() + "/typing", typing);
        } catch (Exception e) {
            log.error("Redis typing 역직렬화 실패", e);
        }
    }
}
```

- [ ] **Step 3: RedisPublisher에 publishTyping 추가**

`redis/RedisPublisher.java` — import·필드·메서드 추가:
```java
import com.example.springboot_realtimechat.dto.TypingResponse;
// ...
    private final ChannelTopic typingTopic;   // "typing" (빈 이름으로 해석)

    public void publishTyping(TypingResponse typing){
        redisTemplate.convertAndSend(typingTopic.getTopic(), typing);
    }
```
(기존 `publish(MessageResponse)`·`publishPresence(PresenceResponse)`·필드 `channelTopic`·`presenceTopic`는 그대로 두고 추가만.)

- [ ] **Step 4: RedisConfig에 typing 토픽 빈 + 리스너 등록**

`config/RedisConfig.java` — import `TypingRedisSubscriber`, `messageListenerContainer` 파라미터·등록 추가, 토픽 빈 추가:
```java
    @Bean
    public RedisMessageListenerContainer messageListenerContainer(RedisConnectionFactory redisConnectionFactory,
                                                                  RedisSubscriber redisSubscriber,
                                                                  PresenceRedisSubscriber presenceRedisSubscriber,
                                                                  TypingRedisSubscriber typingRedisSubscriber,
                                                                  ChannelTopic channelTopic,
                                                                  ChannelTopic presenceTopic,
                                                                  ChannelTopic typingTopic){
        RedisMessageListenerContainer redisMessageListenerContainer = new RedisMessageListenerContainer();
        redisMessageListenerContainer.setConnectionFactory(redisConnectionFactory);
        redisMessageListenerContainer.addMessageListener(redisSubscriber, channelTopic);
        redisMessageListenerContainer.addMessageListener(presenceRedisSubscriber, presenceTopic);
        redisMessageListenerContainer.addMessageListener(typingRedisSubscriber, typingTopic);
        return redisMessageListenerContainer;
    }

    @Bean
    public ChannelTopic typingTopic(){
        return new ChannelTopic("typing");
    }
```
(import: `import com.example.springboot_realtimechat.redis.TypingRedisSubscriber;`)

- [ ] **Step 5: ChatMessageController에 타이핑 핸들러 추가**

`controller/ChatMessageController.java` — `MemberService` 주입 + 핸들러 추가. import 추가:
```java
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.TypingRequest;
import com.example.springboot_realtimechat.dto.TypingResponse;
import com.example.springboot_realtimechat.service.MemberService;
```
필드에 `private final MemberService memberService;` 추가하고, 핸들러:
```java
    @MessageMapping("/chatrooms/{chatroomId}/typing")
    public void typing(
            @DestinationVariable Long chatroomId,
            TypingRequest typingRequest,
            Principal principal) {
        CustomUserDetails customUserDetails = (CustomUserDetails) ((Authentication) principal).getPrincipal();
        Member member = memberService.getMemberById(customUserDetails.getMemberId());
        TypingResponse response = new TypingResponse(
                chatroomId, member.getId(), member.getNickname(), typingRequest.isTyping());
        redisPublisher.publishTyping(response);
    }
```

- [ ] **Step 6: 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. (ChannelTopic 빈 3개 이름 해석, 리스너 3개 등록, 컨텍스트 로딩 통과.)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/dto/TypingRequest.java \
        src/main/java/com/example/springboot_realtimechat/dto/TypingResponse.java \
        src/main/java/com/example/springboot_realtimechat/redis/TypingRedisSubscriber.java \
        src/main/java/com/example/springboot_realtimechat/redis/RedisPublisher.java \
        src/main/java/com/example/springboot_realtimechat/config/RedisConfig.java \
        src/main/java/com/example/springboot_realtimechat/controller/ChatMessageController.java
git commit -m "feat(typing): STOMP 타이핑 핸들러 + Redis 방송 파이프라인"
```

---

### Task 2: 프론트 stomp.ts — sendTyping + 타이핑 구독 라우팅

`onTyping`은 선택 옵션으로 추가해 단독 tsc 통과(App 배선은 Task 3).

**Files:**
- Modify: `frontend/src/lib/stomp.ts`

**Interfaces:**
- Produces: `StompClientOptions.onTyping?: (p: { chatroomId: string; memberId: string; nickname: string; typing: boolean }) => void`. `sendTyping(chatroomId, isTyping)`. 방 구독 시 `/sub/chatrooms/{id}/typing` 함께 구독, `subscription` 헤더로 `'typing'` 라우팅.

- [ ] **Step 1: stomp.ts 수정 (구독 종류에 typing 추가)**

`subscribe(chatroomId)`를 아래로 교체(채팅+타이핑 함께 구독/해제):
```ts
  subscribe(chatroomId: string) {
    if (!this.connected) return;
    if (this.currentChatSubscription) {
      this.write('UNSUBSCRIBE', { id: this.currentChatSubscription });
      this.subscriptionKinds.delete(this.currentChatSubscription);
    }
    if (this.currentTypingSubscription) {
      this.write('UNSUBSCRIBE', { id: this.currentTypingSubscription });
      this.subscriptionKinds.delete(this.currentTypingSubscription);
    }

    this.currentChatSubscription = `sub-${++this.subscriptionId}`;
    this.subscriptionKinds.set(this.currentChatSubscription, 'chat');
    this.write('SUBSCRIBE', {
      id: this.currentChatSubscription,
      destination: `/sub/chatrooms/${chatroomId}`,
      ack: 'auto',
    });

    this.currentTypingSubscription = `sub-${++this.subscriptionId}`;
    this.subscriptionKinds.set(this.currentTypingSubscription, 'typing');
    this.write('SUBSCRIBE', {
      id: this.currentTypingSubscription,
      destination: `/sub/chatrooms/${chatroomId}/typing`,
      ack: 'auto',
    });
  }
```

필드 선언에 `currentTypingSubscription` 추가하고 `subscriptionKinds` 타입에 `'typing'` 추가:
```ts
  private currentChatSubscription?: string;
  private currentTypingSubscription?: string;
  private presenceSubscription?: string;
  private subscriptionKinds = new Map<string, 'chat' | 'presence' | 'typing'>();
```

`StompClientOptions`에 옵션 추가:
```ts
  onPresence?: (onlineMemberIds: string[]) => void;
  onTyping?: (p: { chatroomId: string; memberId: string; nickname: string; typing: boolean }) => void;
```

`disconnect()`에 typing 구독 초기화 추가:
```ts
    this.currentTypingSubscription = undefined;
```

`sendTyping` 메서드 추가(`send` 아래):
```ts
  sendTyping(chatroomId: string, isTyping: boolean) {
    if (!this.connected) return;
    this.write('SEND', {
      destination: `/pub/chatrooms/${chatroomId}/typing`,
      'content-type': 'application/json',
    }, JSON.stringify({ typing: isTyping }));
  }
```

`handleRawMessage`의 MESSAGE 분기에 typing 라우팅 추가:
```ts
      if (frame.command === 'MESSAGE' && frame.body) {
        const kind = this.subscriptionKinds.get(frame.headers.subscription);
        if (kind === 'presence') {
          const payload = JSON.parse(frame.body) as { onlineMemberIds: Array<number | string> };
          this.options.onPresence?.(payload.onlineMemberIds.map(String));
        } else if (kind === 'typing') {
          const p = JSON.parse(frame.body) as { chatroomId: number | string; memberId: number | string; nickname: string; typing: boolean };
          this.options.onTyping?.({
            chatroomId: String(p.chatroomId),
            memberId: String(p.memberId),
            nickname: p.nickname,
            typing: p.typing,
          });
        } else {
          this.options.onMessage(JSON.parse(frame.body) as BackendMessage);
        }
        return;
      }
```

- [ ] **Step 2: tsc + build 확인**

Run: `npm --prefix frontend run lint && npm --prefix frontend run build`
Expected: tsc 에러 없음, build 성공. (채팅 회귀 없음.)

- [ ] **Step 3: 커밋**

```bash
git add frontend/src/lib/stomp.ts
git commit -m "feat(typing): stomp sendTyping + 방별 타이핑 구독 라우팅"
```

---

### Task 3: 프론트 App.tsx — 타이핑 송수신 배선

`handleTypeStateChange`를 STOMP 전송(3초 하트비트)으로 바꾸고, 수신 타이핑을 `presences`에 upsert(5초 만료). 채널 전환 시 presences 초기화.

**Files:**
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `SpringStompClient` `sendTyping`·`onTyping`(Task 2). `ChatArea`의 `typingUsers` 필터(기존).

- [ ] **Step 1: 타이핑용 ref 3개 추가**

`stompRef` 선언 근처(다른 useRef 옆)에 추가:
```ts
  const typingSentAtRef = useRef<number>(0);
  const typingActiveRef = useRef<boolean>(false);
  const typingExpiryRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());
```

- [ ] **Step 2: 채널 전환 시 presences를 self가 아닌 빈 배열로**

기존 이펙트(presences를 `[{ self, isTyping:false }]`로 세팅하던 것)의 세팅부를 교체:
```ts
  useEffect(() => {
    setPresences([]);
  }, [user, selectedChannelId]);
```
(self presence는 typingUsers 필터에서 어차피 제외되므로 불필요. presences는 이제 "남의 타이핑"만 담는다.)

- [ ] **Step 3: handleTypeStateChange를 STOMP 하트비트 전송으로 교체**

```ts
  const handleTypeStateChange = (isTyping: boolean) => {
    const client = stompRef.current;
    if (!client || !selectedChannelId) return;

    if (isTyping) {
      const now = Date.now();
      if (now - typingSentAtRef.current >= 3000) {   // 3초 하트비트 스로틀
        client.sendTyping(selectedChannelId, true);
        typingSentAtRef.current = now;
        typingActiveRef.current = true;
      }
    } else if (typingActiveRef.current) {
      client.sendTyping(selectedChannelId, false);
      typingActiveRef.current = false;
      typingSentAtRef.current = 0;
    }
  };
```

- [ ] **Step 4: STOMP 옵션에 onTyping 추가 (수신 → presences upsert + 5초 만료)**

STOMP 이펙트의 `new SpringStompClient({ … })`에서 `onPresence` 뒤에 추가:
```ts
        onTyping: ({ chatroomId, memberId, nickname, typing }) => {
          setPresences((prev) => {
            const others = prev.filter((p) => p.userId !== memberId);
            return typing
              ? [...others, { userId: memberId, userName: nickname, userAvatar: '', isTyping: true, channelId: chatroomId, lastSeen: Date.now() }]
              : others;
          });
          const timers = typingExpiryRef.current;
          const existing = timers.get(memberId);
          if (existing) clearTimeout(existing);
          if (typing) {
            timers.set(memberId, setTimeout(() => {
              setPresences((prev) => prev.filter((p) => p.userId !== memberId));
              timers.delete(memberId);
            }, 5000));   // 하트비트(3초)보다 길게
          } else {
            timers.delete(memberId);
          }
        },
```

- [ ] **Step 5: clearSession에서 타이핑 타이머 정리**

`clearSession`의 `setPresences([]);` 근처에 추가:
```ts
    typingExpiryRef.current.forEach((t) => clearTimeout(t));
    typingExpiryRef.current.clear();
    typingActiveRef.current = false;
    typingSentAtRef.current = 0;
```

- [ ] **Step 6: tsc + build 확인**

Run: `npm --prefix frontend run lint && npm --prefix frontend run build`
Expected: tsc 에러 없음, build 성공.

- [ ] **Step 7: 커밋**

```bash
git add frontend/src/App.tsx
git commit -m "feat(typing): 타이핑 송신(3초 하트비트)+수신 표시(5초 만료) 배선"
```

---

### Task 4: 멀티서버 E2E 검증

Redis 경유라 인스턴스 2개로 크로스-인스턴스 타이핑을 검증한다.

**전제:** 로컬 MySQL(3306) + Redis(6379). 백엔드 2개(8080·8081) 같은 Redis+DB.

- [ ] **Step 1: 백엔드 2개 기동**

```bash
JWT_SECRET="local-e2e-secret-0123456789abcdef0123456789abcdef" SPRING_JPA_HIBERNATE_DDL_AUTO=update \
  ./gradlew bootRun > /tmp/be-8080.log 2>&1 &
JWT_SECRET="local-e2e-secret-0123456789abcdef0123456789abcdef" SPRING_JPA_HIBERNATE_DDL_AUTO=update \
  ./gradlew bootRun --args='--server.port=8081' > /tmp/be-8081.log 2>&1 &
```
두 로그에 `Started SpringbootRealtimechatApplication` 확인.

- [ ] **Step 2: 크로스-인스턴스 타이핑 E2E 스크립트 작성**

`scratchpad/typing_multi_e2e.mjs`:
```js
// A(8080)가 typing 전송 → B(8081)가 같은 방 /sub/.../typing 으로 수신하는지 검증.
const A = { http: 'http://localhost:8080', ws: 'ws://localhost:8080/ws' };
const B = { http: 'http://localhost:8081', ws: 'ws://localhost:8081/ws' };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function jf(base, path, opts = {}, token) {
  const headers = { 'Content-Type': 'application/json', ...(opts.headers || {}) };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(base + path, { ...opts, headers });
  if (!res.ok) throw new Error(`${opts.method || 'GET'} ${path} -> ${res.status}: ${await res.text()}`);
  return res.status === 204 ? null : res.json();
}
async function signupLogin(base, email, nick) {
  await fetch(base + '/api/members', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: 'test1234', nickname: nick }) });
  const { accessToken } = await jf(base, '/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password: 'test1234' }) });
  const me = await jf(base, '/api/members/me', {}, accessToken);
  return { token: accessToken, id: me.id };
}
function parseFrames(raw) {
  return raw.split('\0').map(f => f.trim()).filter(Boolean).map(frame => {
    const [hb, ...bp] = frame.split('\n\n'); const [command, ...hl] = hb.split('\n');
    const headers = Object.fromEntries(hl.map(l => l.split(':')).filter(([k, v]) => k && v).map(([k, ...v]) => [k, v.join(':')]));
    return { command, headers, body: bp.join('\n\n') };
  });
}
function connect(wsUrl, token, roomId, onTyping) {
  return new Promise((resolve) => {
    const sock = new WebSocket(wsUrl);
    sock.onopen = () => sock.send(`CONNECT\naccept-version:1.2\nheart-beat:10000,10000\nAuthorization:Bearer ${token}\n\n\0`);
    sock.onmessage = (e) => { for (const f of parseFrames(String(e.data))) {
      if (f.command === 'CONNECTED') {
        sock.send(`SUBSCRIBE\nid:t\ndestination:/sub/chatrooms/${roomId}/typing\nack:auto\n\n\0`);
        resolve(sock);
      } else if (f.command === 'MESSAGE' && f.body && f.headers.subscription === 't') {
        try { const p = JSON.parse(f.body); onTyping(p); console.log('  [B] typing <-', p); } catch {}
      }
    } };
  });
}

const stamp = Date.now();
const ua = await signupLogin(A.http, `type-a-${stamp}@test.com`, 'tA');
const ub = await signupLogin(B.http, `type-b-${stamp}@test.com`, 'tB');
const room = await jf(A.http, '/api/chatrooms', { method: 'POST', body: JSON.stringify({ name: `typing-${stamp % 10000}` }) }, ua.token);
const roomId = room.id;
await jf(A.http, `/api/chatrooms/${roomId}/members`, { method: 'POST' }, ua.token);
await jf(B.http, `/api/chatrooms/${roomId}/members`, { method: 'POST' }, ub.token);
console.log(`A.id=${ua.id}(8080), B.id=${ub.id}(8081), room=${roomId}\n`);

let last = null;
const sa = await connect(A.ws, ua.token, roomId, () => {});          // A: 전송자
const sb = await connect(B.ws, ub.token, roomId, (p) => { last = p; }); // B: 수신자
await sleep(400);

const results = [];
const check = (d, c) => { results.push(c); console.log(`${c ? '✅' : '❌'} ${d}`); };

sa.send(`SEND\ndestination:/pub/chatrooms/${roomId}/typing\ncontent-type:application/json\n\n${JSON.stringify({ typing: true })}\0`);
await sleep(600);
check('★ A(8080) 입력 → B(8081)가 typing:true 수신 (크로스-인스턴스)', last && String(last.memberId) === String(ua.id) && last.typing === true);
check('닉네임 포함', last && last.nickname === 'tA');

sa.send(`SEND\ndestination:/pub/chatrooms/${roomId}/typing\ncontent-type:application/json\n\n${JSON.stringify({ typing: false })}\0`);
await sleep(600);
check('A 멈춤 → B가 typing:false 수신', last && last.typing === false);

sa.close(); sb.close(); await sleep(200);
const passed = results.filter(Boolean).length;
console.log(`\n=== ${passed}/${results.length} 통과 ===`);
process.exit(passed === results.length ? 0 : 1);
```

- [ ] **Step 3: E2E 실행**

Run: `node scratchpad/typing_multi_e2e.mjs`
Expected: `3/3 통과` — 특히 "★ A(8080) 입력 → B(8081)가 수신"이 핵심(멀티서버 타이핑).

- [ ] **Step 4: 두 인스턴스 종료**

```bash
lsof -ti :8080 | xargs kill 2>/dev/null; lsof -ti :8081 | xargs kill 2>/dev/null; pkill -f bootRun 2>/dev/null
```

- [ ] **Step 5: 관찰 결과 기록**

3/3 통과면 완료. 실패 시(역직렬화) `TypingResponse` round-trip 확인 — `typing` 필드명·생성자 파라미터명 일치(`-parameters`), presence/message와 동일 직렬화 경로인지.

---

## Self-Review 결과

- **스펙 커버리지:** Redis 릴레이 파이프라인+핸들러(Task 1) / stomp 송수신·구독(Task 2) / App 하트비트·만료 배선(Task 3) / 멀티서버 E2E(Task 4) — 스펙 전 항목 매핑. stateless·닉네임 payload·true 하트비트·5초 만료 모두 반영.
- **Placeholder:** 없음(모든 스텝 실제 코드/명령).
- **타입 일관성:** 와이어 필드 `typing`(boolean) 전 구간 통일 · `TypingResponse{chatroomId,memberId,nickname,typing}` · 토픽 `"typing"` · 목적지 `/sub/chatrooms/{id}/typing` · `onTyping({chatroomId,memberId,nickname,typing})` 문자열화 · presences upsert 시 `userId=memberId` — 태스크 간 일치. `ChannelTopic` 3빈은 이름으로 해석.
