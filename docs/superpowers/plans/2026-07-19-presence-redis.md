# Redis-backed Presence Implementation Plan

> **실행 방식:** hands-on 학습 프로젝트 — 재민 님이 태스크별로 직접 구현하거나 Claude가 구현 후 리뷰. 참고 코드는 "이 방향". 체크박스(`- [ ]`)로 추적.

**Goal:** in-memory presence를 Redis 기반으로 전환해, 여러 백엔드 인스턴스가 온라인 상태를 공유(메시지와 동일한 멀티서버 대응)하게 만든다.

**Architecture:** 온라인 상태를 Redis Hash `presence:sessions`(sessionId→memberId)에 저장(공유 SSOT). presence 변경 시 전체 스냅샷을 presence 전용 Redis 토픽 `"presence"`에 publish → 모든 인스턴스의 `PresenceRedisSubscriber`가 수신해 자기 서버 클라에 `convertAndSend("/sub/presence", …)`. 메시지 파이프라인(`ChatMessageController`→`RedisPublisher`→`RedisSubscriber`)과 동형.

**Tech Stack:** Spring Boot, Spring Data Redis(`StringRedisTemplate`, `RedisTemplate`, `RedisMessageListenerContainer`, `ChannelTopic`), STOMP, JUnit5 + Mockito + AssertJ, Node(WebSocket) E2E.

## Global Constraints

- **DB 스키마 변경 없음**(상태는 Redis). `ddl-auto: validate` 무관, 마이그레이션 불필요.
- 로스터 페이로드 불변: `{ "onlineMemberIds": [1, 5, 8] }`.
- presence 전용 Redis 토픽 `"presence"` (채팅 `"chatroom"`과 분리). presence subscriber는 `PresenceResponse`로만 역직렬화.
- 메시지 파이프라인·`RedisSubscriber`·`ChatMessageController`·프론트 전체 **무변경**.
- 죽은 세션 청소(하드 크래시 TTL)는 **범위 밖**(알려진 한계).
- CI엔 라이브 Redis가 없음 → 테스트는 **Redis 없이 통과**해야 함(Mockito 목킹). 실제 동작은 수동 멀티서버 E2E로.
- Redis Hash 값은 **문자열로 저장**(`StringRedisTemplate`) 후 `Long`으로 파싱 — JDK 직렬화 회피.

---

### Task 1: PresenceRegistry를 Redis Hash 기반으로 전환

인메모리 `ConcurrentHashMap`을 Redis Hash로 교체. 기존 유닛테스트(무인자 생성자 의존)는 Mockito 기반으로 **교체**.

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/presence/PresenceRegistry.java` (전체 교체)
- Test: `src/test/java/com/example/springboot_realtimechat/presence/PresenceRegistryTest.java` (전체 교체)

**Interfaces:**
- Consumes: `StringRedisTemplate` (Spring Boot 자동구성 빈).
- Produces (시그니처 불변):
  - `void connect(String sessionId, Long memberId)`
  - `Optional<Long> disconnect(String sessionId)`
  - `Set<Long> getOnlineMemberIds()`

- [ ] **Step 1: 기존 테스트를 Mockito 기반으로 교체 (실패 상태)**

`src/test/java/com/example/springboot_realtimechat/presence/PresenceRegistryTest.java` 전체 교체:
```java
package com.example.springboot_realtimechat.presence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PresenceRegistryTest {

    private static final String KEY = "presence:sessions";

    @Mock StringRedisTemplate redis;
    @Mock HashOperations<String, Object, Object> hashOps;
    @InjectMocks PresenceRegistry registry;

    @Test
    void 접속하면_해시에_세션과_멤버를_저장한다() {
        given(redis.opsForHash()).willReturn(hashOps);

        registry.connect("session-1", 10L);

        verify(hashOps).put(KEY, "session-1", "10");
    }

    @Test
    void 온라인_목록은_해시_값들의_distinct_Long_집합이다() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.values(KEY)).willReturn(List.of("10", "10", "20"));

        assertThat(registry.getOnlineMemberIds()).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void 끊기면_해당_세션을_삭제하고_memberId를_반환한다() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.get(KEY, "session-1")).willReturn("10");

        assertThat(registry.disconnect("session-1")).contains(10L);
        verify(hashOps).delete(KEY, "session-1");
    }

    @Test
    void 모르는_세션을_끊으면_empty를_반환한다() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.get(KEY, "nope")).willReturn(null);

        assertThat(registry.disconnect("nope")).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인 (아직 인메모리 구현이라 컴파일/동작 불일치)**

Run: `./gradlew test --tests "*PresenceRegistryTest"`
Expected: FAIL — `PresenceRegistry`가 아직 `StringRedisTemplate`을 안 받음(생성자 불일치 컴파일 에러).

- [ ] **Step 3: PresenceRegistry를 Redis Hash 구현으로 교체**

`src/main/java/com/example/springboot_realtimechat/presence/PresenceRegistry.java` 전체 교체:
```java
package com.example.springboot_realtimechat.presence;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PresenceRegistry {

    private static final String KEY = "presence:sessions";

    private final StringRedisTemplate redis;

    public void connect(String sessionId, Long memberId) {
        redis.opsForHash().put(KEY, sessionId, String.valueOf(memberId));
    }

    public Optional<Long> disconnect(String sessionId) {
        Object previous = redis.opsForHash().get(KEY, sessionId);
        redis.opsForHash().delete(KEY, sessionId);
        return previous == null ? Optional.empty() : Optional.of(Long.valueOf(previous.toString()));
    }

    public Set<Long> getOnlineMemberIds() {
        return redis.opsForHash().values(KEY).stream()
                .map(value -> Long.valueOf(value.toString()))
                .collect(Collectors.toSet());
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "*PresenceRegistryTest"`
Expected: PASS (4개).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/presence/PresenceRegistry.java \
        src/test/java/com/example/springboot_realtimechat/presence/PresenceRegistryTest.java
git commit -m "refactor(presence): PresenceRegistry를 Redis Hash 기반으로 전환"
```

---

### Task 2: presence Redis 방송 파이프라인 구축

presence 전용 토픽 + subscriber + publisher 메서드를 만들어, Redis를 통한 방송 경로를 완성한다(아직 이벤트 리스너는 이 경로를 안 씀 — Task 3에서 전환).

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/redis/PresenceRedisSubscriber.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/redis/RedisConfig.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/redis/RedisPublisher.java`

**Interfaces:**
- Consumes: `PresenceResponse`(기존 DTO), `SimpMessagingTemplate`, `tools.jackson.databind.ObjectMapper`, `RedisTemplate<String,Object>`, `ChannelTopic`.
- Produces:
  - `RedisPublisher.publishPresence(PresenceResponse presence)` — presence 토픽으로 publish.
  - Redis 토픽 `"presence"` 수신 시 `PresenceRedisSubscriber`가 `/sub/presence`로 재방송.
- 빈 배선 주의: `ChannelTopic` 빈이 2개(`channelTopic`, `presenceTopic`)가 되므로, 주입 필드/파라미터 **이름을 빈 이름과 일치**시켜 name-based 해석(스프링이 타입 모호 시 이름으로 해석). `-parameters`(Spring Boot 기본)로 파라미터명 보존됨.

- [ ] **Step 1: PresenceRedisSubscriber 작성 (RedisSubscriber와 대칭)**

`src/main/java/com/example/springboot_realtimechat/redis/PresenceRedisSubscriber.java`:
```java
package com.example.springboot_realtimechat.redis;

import com.example.springboot_realtimechat.dto.PresenceResponse;
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
public class PresenceRedisSubscriber implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            PresenceResponse presence = objectMapper.readValue(message.getBody(), PresenceResponse.class);
            messagingTemplate.convertAndSend("/sub/presence", presence);
        } catch (Exception e) {
            log.error("Redis presence 역직렬화 실패", e);
        }
    }
}
```

- [ ] **Step 2: RedisConfig에 presence 토픽 빈 + 리스너 등록**

`src/main/java/com/example/springboot_realtimechat/redis/RedisConfig.java`의 `messageListenerContainer`와 토픽 빈을 교체/추가:
```java
    @Bean
    public RedisMessageListenerContainer messageListenerContainer(RedisConnectionFactory redisConnectionFactory,
                                                                  RedisSubscriber redisSubscriber,
                                                                  PresenceRedisSubscriber presenceRedisSubscriber,
                                                                  ChannelTopic channelTopic,
                                                                  ChannelTopic presenceTopic){
        RedisMessageListenerContainer redisMessageListenerContainer = new RedisMessageListenerContainer();
        redisMessageListenerContainer.setConnectionFactory(redisConnectionFactory);
        redisMessageListenerContainer.addMessageListener(redisSubscriber, channelTopic);
        redisMessageListenerContainer.addMessageListener(presenceRedisSubscriber, presenceTopic);
        return redisMessageListenerContainer;
    }

    @Bean
    public ChannelTopic channelTopic(){
        return new ChannelTopic("chatroom");
    }

    @Bean
    public ChannelTopic presenceTopic(){
        return new ChannelTopic("presence");
    }
```

- [ ] **Step 3: RedisPublisher에 publishPresence 추가**

`src/main/java/com/example/springboot_realtimechat/redis/RedisPublisher.java` 전체 교체:
```java
package com.example.springboot_realtimechat.redis;

import com.example.springboot_realtimechat.dto.MessageResponse;
import com.example.springboot_realtimechat.dto.PresenceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedisPublisher {
    private final RedisTemplate<String, Object> redisTemplate;
    private final ChannelTopic channelTopic;    // "chatroom" (빈 이름으로 해석)
    private final ChannelTopic presenceTopic;   // "presence" (빈 이름으로 해석)

    public void publish(MessageResponse message){
        redisTemplate.convertAndSend(channelTopic.getTopic(), message);
    }

    public void publishPresence(PresenceResponse presence){
        redisTemplate.convertAndSend(presenceTopic.getTopic(), presence);
    }
}
```

- [ ] **Step 4: 컴파일 + 기존 테스트 통과 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. (컨텍스트 로딩 테스트는 기존 메시지 리스너와 동일하게 Redis 없이도 통과 — presence 리스너를 같은 컨테이너에 추가한 것뿐.)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/redis/PresenceRedisSubscriber.java \
        src/main/java/com/example/springboot_realtimechat/redis/RedisConfig.java \
        src/main/java/com/example/springboot_realtimechat/redis/RedisPublisher.java
git commit -m "feat(presence): presence 전용 Redis 토픽 + 구독자 + publishPresence"
```

---

### Task 3: WebSocketEventListener 방송을 Redis publish로 전환

이벤트 리스너가 `SimpMessagingTemplate` 직접 방송 → `RedisPublisher.publishPresence`로 전환. 이걸로 presence가 완전히 Redis 경유가 된다.

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/presence/WebSocketEventListener.java`

**Interfaces:**
- Consumes: `PresenceRegistry`(Task 1), `RedisPublisher.publishPresence`(Task 2).
- 변경: 의존성 `SimpMessagingTemplate` 제거 → `RedisPublisher` 주입.

- [ ] **Step 1: WebSocketEventListener 교체**

`src/main/java/com/example/springboot_realtimechat/presence/WebSocketEventListener.java` 전체 교체:
```java
package com.example.springboot_realtimechat.presence;

import com.example.springboot_realtimechat.dto.PresenceResponse;
import com.example.springboot_realtimechat.redis.RedisPublisher;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;
import java.util.ArrayList;

@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private static final String PRESENCE_DESTINATION = "/sub/presence";

    private final PresenceRegistry presenceRegistry;
    private final RedisPublisher redisPublisher;

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        Long memberId = extractMemberId(event.getUser());

        if (sessionId == null || memberId == null) {
            return;
        }

        presenceRegistry.connect(sessionId, memberId);
        broadcastRoster();
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        presenceRegistry.disconnect(event.getSessionId());
        broadcastRoster();
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        if (PRESENCE_DESTINATION.equals(accessor.getDestination())) {
            broadcastRoster();
        }
    }

    private Long extractMemberId(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof CustomUserDetails userDetails) {
            return userDetails.getMemberId();
        }
        return null;
    }

    private void broadcastRoster() {
        PresenceResponse roster =
                new PresenceResponse(new ArrayList<>(presenceRegistry.getOnlineMemberIds()));
        redisPublisher.publishPresence(roster);
    }
}
```

- [ ] **Step 2: 컴파일 + 기존 테스트 통과 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/presence/WebSocketEventListener.java
git commit -m "refactor(presence): 방송을 SimpMessagingTemplate 직접 → Redis publish로 전환"
```

---

### Task 4: 멀티서버 E2E 검증 (2 인스턴스 + 공유 Redis)

Redis 공유가 실제로 크로스-인스턴스 presence를 만드는지 증명한다.

**전제:** 로컬 MySQL(3306) + Redis(6379) 기동. 백엔드 두 인스턴스를 **같은 Redis+DB**에 물려 8080·8081로 띄움.

- [ ] **Step 1: 백엔드 2개 기동 (같은 Redis+DB, 다른 포트)**

```bash
# 인스턴스 A (8080)
JWT_SECRET="local-e2e-secret-0123456789abcdef0123456789abcdef" \
SPRING_JPA_HIBERNATE_DDL_AUTO=update \
./gradlew bootRun > /tmp/be-8080.log 2>&1 &

# 인스턴스 B (8081)
JWT_SECRET="local-e2e-secret-0123456789abcdef0123456789abcdef" \
SPRING_JPA_HIBERNATE_DDL_AUTO=update \
./gradlew bootRun --args='--server.port=8081' > /tmp/be-8081.log 2>&1 &
```
두 로그에서 `Started SpringbootRealtimechatApplication` 확인.

- [ ] **Step 2: 크로스-인스턴스 E2E 스크립트 작성**

`scratchpad/presence_multi_e2e.mjs`:
```js
// 인스턴스 A(8080)에 붙은 클라가, 인스턴스 B(8081)에 붙은 클라의 온라인을 보는지 검증.
const A_HTTP = 'http://localhost:8080';
const A_WS = 'ws://localhost:8080/ws';
const B_HTTP = 'http://localhost:8081';
const B_WS = 'ws://localhost:8081/ws';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function signupLogin(http, email, nick) {
  await fetch(`${http}/api/members`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: 'test1234', nickname: nick }) });
  const r = await fetch(`${http}/api/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: 'test1234' }) });
  const { accessToken } = await r.json();
  const me = await (await fetch(`${http}/api/members/me`, { headers: { Authorization: `Bearer ${accessToken}` } })).json();
  return { token: accessToken, id: me.id };
}
function parseFrames(raw) {
  return raw.split('\0').map(f => f.trim()).filter(Boolean).map(frame => {
    const [hb, ...bp] = frame.split('\n\n'); const [command, ...hl] = hb.split('\n');
    const headers = Object.fromEntries(hl.map(l => l.split(':')).filter(([k, v]) => k && v).map(([k, ...v]) => [k, v.join(':')]));
    return { command, headers, body: bp.join('\n\n') };
  });
}
class Client {
  constructor(name, ws, token) { this.name = name; this.ws = ws; this.token = token; this.rosters = []; }
  get latest() { return this.rosters.at(-1) ?? null; }
  connect() {
    return new Promise((resolve) => {
      this.sock = new WebSocket(this.ws);
      this.sock.onopen = () => this.sock.send(`CONNECT\naccept-version:1.2\nheart-beat:10000,10000\nAuthorization:Bearer ${this.token}\n\n\0`);
      this.sock.onmessage = (e) => { for (const f of parseFrames(String(e.data))) {
        if (f.command === 'CONNECTED') { this.sock.send('SUBSCRIBE\nid:sub-presence\ndestination:/sub/presence\nack:auto\n\n\0'); resolve(); }
        else if (f.command === 'MESSAGE' && f.body) { try { this.rosters.push(JSON.parse(f.body).onlineMemberIds.map(Number)); console.log(`[${this.name}] <-`, this.latest); } catch {} }
      } };
    });
  }
  close() { try { this.sock.send('DISCONNECT\n\n\0'); } catch {} this.sock.close(); }
}
const results = [];
const check = (d, c) => { results.push(c); console.log(`${c ? '✅' : '❌'} ${d}`); };

const stamp = Date.now();
const A = await signupLogin(A_HTTP, `multi-a-${stamp}@test.com`, 'mA');
const B = await signupLogin(B_HTTP, `multi-b-${stamp}@test.com`, 'mB');
console.log(`A.id=${A.id}(8080), B.id=${B.id}(8081)\n`);

const ca = new Client('A@8080', A_WS, A.token);
await ca.connect(); await sleep(600);
check('A가 자신 온라인 수신', ca.latest?.includes(A.id));

const cb = new Client('B@8081', B_WS, B.token);
await cb.connect(); await sleep(800);
check('★ A(8080)가 B(8081)의 온라인을 봄 = 크로스-인스턴스 공유', ca.latest?.includes(B.id));

cb.close(); await sleep(1000);
check('★ B 종료 후 A가 B 오프라인 반영', !ca.latest?.includes(B.id));

ca.close(); await sleep(200);
const passed = results.filter(Boolean).length;
console.log(`\n=== ${passed}/${results.length} 통과 ===`);
process.exit(passed === results.length ? 0 : 1);
```

- [ ] **Step 3: E2E 실행**

Run: `node scratchpad/presence_multi_e2e.mjs`
Expected: `3/3 통과` — 특히 "★ A(8080)가 B(8081)의 온라인을 봄"이 핵심(멀티서버 공유 증명).

- [ ] **Step 4: 두 인스턴스 종료(환경 원복)**

```bash
lsof -ti :8080 | xargs kill 2>/dev/null
lsof -ti :8081 | xargs kill 2>/dev/null
pkill -f bootRun 2>/dev/null
```

- [ ] **Step 5: 관찰 결과 기록**

3/3 통과면 완료. 실패 시(특히 역직렬화) `PresenceResponse` round-trip 확인 — 메시지(`MessageResponse`)와 동일하게 `GenericJacksonJsonRedisSerializer` + `tools.jackson`(Jackson3, unknown-props 무시)로 통해야 함. 필요 시 `PresenceResponse`에 `@Setter`/무인자 생성자 추가.

---

## Self-Review 결과

- **스펙 커버리지:** Redis Hash 상태(Task 1) / presence 토픽·subscriber·publishPresence(Task 2) / 리스너 Redis 전환(Task 3) / 멀티서버 E2E(Task 4) — 스펙 전 항목 매핑. 죽은세션청소·타이핑·방위치·프론트변경은 의도적 제외.
- **Placeholder:** 없음(모든 스텝 실제 코드/명령).
- **타입 일관성:** `PresenceRegistry`(connect/disconnect/getOnlineMemberIds 시그니처 불변) · `RedisPublisher.publishPresence(PresenceResponse)` · 토픽 `"presence"` · 키 `presence:sessions` · Hash 값 문자열 저장→`Long` 파싱 — 태스크 간 일치. `ChannelTopic` 2빈은 이름(`channelTopic`/`presenceTopic`)으로 해석.
