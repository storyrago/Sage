# 소셜 로그인 후 프로필 온보딩 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 신규 소셜 회원이 첫 로그인 직후 닉네임을 직접 정하게 하고, 닉네임 수정 경로를 만든다.

**Architecture:** `members.onboarded_at`(nullable)으로 온보딩 경험 여부를 표시한다. 책임이 하나씩인 API 두 개(`PATCH /api/members/me` = 닉네임 수정, `POST /api/members/me/onboarding` = 온보딩 기록)를 만들고, 프론트는 `getMe`의 `onboarded`가 `false`일 때 온보딩 화면을 렌더한다. 온보딩은 건너뛸 수 있으며, 같은 `PATCH`를 설정 화면에서도 재사용한다.

**Tech Stack:** Spring Boot / JPA(`ddl-auto: validate`) / Flyway / MySQL·H2(test) / React + TypeScript(Vite)

설계 문서: `docs/superpowers/specs/2026-07-29-onboarding-design.md`

## Global Constraints

- DB 스키마 변경은 **Flyway 마이그레이션 파일로만**(`src/main/resources/db/migration/V*.sql`). 수동 ALTER 금지. `ddl-auto: validate` 유지.
- 테스트는 H2 create-drop이라 Flyway 비활성(`src/test/resources/application.yaml`).
- 닉네임 최대 길이는 **20자**. 앞뒤 공백을 제거한 뒤 검증한다.
- 온보딩은 **건너뛸 수 있다**. 백엔드는 온보딩 미완료 회원의 API를 막지 않는다.
- 커밋 메시지·코드 주석은 변경의 목적만 쓴다. "누락/핫픽스/깨져 있었다" 같은 배경 서사 금지.
- 검증 명령: 백엔드 `./gradlew test`, 프론트 `cd frontend && npm run lint && npm run build`.
- 프론트 신규 컴포넌트는 기존 로그인 카드의 디자인 토큰을 따른다: 카드 `#1C241F` / `1px solid #2D362F` / radius 22, 입력 `#252E28` / radius 12, 기본 버튼 `#7AAE92` 배경 + `#12241B` 글자 / radius 13, 본문 텍스트 `#E6ECE8`, 보조 텍스트 `#9AA8A0`, 흐린 텍스트 `#6B7972`.

## File Structure

| 파일 | 책임 | 작업 |
|---|---|---|
| `db/migration/V6__add_onboarding.sql` | 스키마 | 생성 |
| `domain/Member.java` | 온보딩 상태·닉네임 변경 | 수정 |
| `dto/MemberResponse.java` | `onboarded` 노출 | 수정 |
| `service/OAuthService.java` | 닉네임 절단 20자 | 수정 |
| `global/exception/ErrorCode.java` | 닉네임 검증 실패 코드 | 수정 |
| `dto/NicknameRequest.java` | 닉네임 요청 본문 | 생성 |
| `service/MemberService.java` | 닉네임 수정·온보딩 기록 | 수정 |
| `controller/MemberController.java` | 엔드포인트 2개 | 수정 |
| `frontend/src/lib/api.ts` | API 함수·타입 | 수정 |
| `frontend/src/types.ts` | `User.onboarded` | 수정 |
| `frontend/src/components/Onboarding.tsx` | 온보딩 화면 | 생성 |
| `frontend/src/App.tsx` | 온보딩 분기·닉네임 저장 배선 | 수정 |
| `frontend/src/components/SettingsModal.tsx` | 닉네임 20자 | 수정 |

---

## Task 1: V6 마이그레이션 + 엔티티 + 응답 DTO

**Files:**
- Create: `src/main/resources/db/migration/V6__add_onboarding.sql`
- Modify: `src/main/java/com/example/springboot_realtimechat/domain/Member.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/dto/MemberResponse.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/OAuthService.java`
- Test: `src/test/java/com/example/springboot_realtimechat/onboarding/OnboardingEntityTest.java`

**Interfaces:**
- Produces:
  - `Member.getOnboardedAt(): LocalDateTime` (Lombok `@Getter`)
  - `Member.isOnboarded(): boolean`
  - `Member.updateNickname(String nickname): void`
  - `Member.markOnboarded(): void` — 이미 기록돼 있으면 아무것도 하지 않는다
  - `MemberResponse`에 `onboarded` 필드 추가. 생성자 시그니처가 `(Long id, String email, String nickname, String profileImageUrl, LocalDateTime createdAt, boolean onboarded)`로 바뀐다

- [ ] **Step 1: V6 마이그레이션 작성**

`src/main/resources/db/migration/V6__add_onboarding.sql`:

```sql
-- 온보딩 경험 여부. NULL이면 아직 온보딩 화면을 지나지 않은 회원
ALTER TABLE members ADD COLUMN onboarded_at DATETIME(6) NULL;

-- 사용자가 직접 정하는 값이므로 10자에서 20자로 늘린다
ALTER TABLE members MODIFY nickname VARCHAR(20);

-- 마이그레이션 시점의 기존 회원은 온보딩 대상이 아니다
UPDATE members SET onboarded_at = NOW(6);
```

- [ ] **Step 2: Member 엔티티 수정**

`domain/Member.java`에서 `nickname` 필드의 length를 20으로 바꾸고, `createdAt` 필드 아래에 `onboardedAt`을 추가한다.

`nickname` 필드를 아래로 교체:

```java
    @Column(length = 20)
    private String nickname;
```

`createdAt` 필드 선언 바로 다음 줄에 추가:

```java
    @Column(name = "onboarded_at")
    private LocalDateTime onboardedAt;
```

그리고 `updateProfileImageUrl` 메서드 바로 아래에 두 메서드를 추가:

```java
    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    public void markOnboarded() {
        if (this.onboardedAt == null) {
            this.onboardedAt = LocalDateTime.now();
        }
    }

    public boolean isOnboarded() {
        return this.onboardedAt != null;
    }
```

- [ ] **Step 3: MemberResponse에 onboarded 추가**

`dto/MemberResponse.java` 전체를 아래로 교체:

```java
package com.example.springboot_realtimechat.dto;

import com.example.springboot_realtimechat.domain.Member;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class MemberResponse {
    Long id;
    String email;
    String nickname;
    String profileImageUrl;
    LocalDateTime createdAt;
    boolean onboarded;

    public MemberResponse(Long id, String email, String nickname, String profileImageUrl,
                          LocalDateTime createdAt, boolean onboarded) {
        this.id = id;
        this.email = email;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.createdAt = createdAt;
        this.onboarded = onboarded;
    }

    public static MemberResponse from(Member member){
        return new MemberResponse(
                member.getId(),
                member.getEmail(),
                member.getNickname(),
                member.getProfileImageUrl(),
                member.getCreatedAt(),
                member.isOnboarded()
        );
    }
}
```

- [ ] **Step 4: OAuthService의 닉네임 절단 길이를 20으로**

`service/OAuthService.java`의 `toNickname` 메서드에서 절단 부분을 교체한다.

변경 전:

```java
        String cut = base.length() > 10 ? base.substring(0, 10) : base;
        String trimmed = cut.trim();
        return trimmed.isEmpty() ? "user" : trimmed;   // nickname 컬럼은 10자 제한
```

변경 후:

```java
        String cut = base.length() > 20 ? base.substring(0, 20) : base;
        String trimmed = cut.trim();
        return trimmed.isEmpty() ? "user" : trimmed;   // nickname 컬럼은 20자 제한
```

- [ ] **Step 5: 엔티티 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/onboarding/OnboardingEntityTest.java`:

```java
package com.example.springboot_realtimechat.onboarding;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.MemberResponse;
import com.example.springboot_realtimechat.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class OnboardingEntityTest {
    @Autowired MemberRepository memberRepository;

    @Test
    void 새로_만든_소셜회원은_온보딩_전이다() {
        Member m = memberRepository.save(
                Member.ofSocial("GOOGLE", "onboarding-sub-1", "a@x.com", "닉", null));

        assertThat(m.getOnboardedAt()).isNull();
        assertThat(m.isOnboarded()).isFalse();
        assertThat(MemberResponse.from(m).isOnboarded()).isFalse();
    }

    @Test
    void 온보딩을_기록하면_onboarded가_true가_된다() {
        Member m = memberRepository.save(
                Member.ofSocial("GOOGLE", "onboarding-sub-2", "b@x.com", "닉", null));

        m.markOnboarded();

        assertThat(m.getOnboardedAt()).isNotNull();
        assertThat(m.isOnboarded()).isTrue();
        assertThat(MemberResponse.from(m).isOnboarded()).isTrue();
    }

    @Test
    void 온보딩_기록은_멱등이다() {
        Member m = memberRepository.save(
                Member.ofSocial("GOOGLE", "onboarding-sub-3", "c@x.com", "닉", null));

        m.markOnboarded();
        var first = m.getOnboardedAt();
        m.markOnboarded();

        assertThat(m.getOnboardedAt()).isEqualTo(first);
    }

    @Test
    void 닉네임을_바꿀_수_있다() {
        Member m = memberRepository.save(
                Member.ofSocial("GOOGLE", "onboarding-sub-4", "d@x.com", "이전", null));

        m.updateNickname("바뀐닉네임");

        assertThat(m.getNickname()).isEqualTo("바뀐닉네임");
    }
}
```

- [ ] **Step 6: 테스트 실행**

Run: `./gradlew test --tests "*OnboardingEntityTest"`
Expected: PASS (4/4).

- [ ] **Step 7: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. `MemberResponse` 생성자를 직접 호출하는 곳이 있으면 컴파일 에러가 난다. 그 경우 `MemberResponse.from(member)`를 쓰도록 고친다.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V6__add_onboarding.sql \
        src/main/java/com/example/springboot_realtimechat/domain/Member.java \
        src/main/java/com/example/springboot_realtimechat/dto/MemberResponse.java \
        src/main/java/com/example/springboot_realtimechat/service/OAuthService.java \
        src/test/java/com/example/springboot_realtimechat/onboarding/OnboardingEntityTest.java
git commit -m "feat(onboarding): onboarded_at 컬럼과 닉네임 20자 확장 (Flyway V6)"
```

---

## Task 2: 닉네임 수정·온보딩 기록 API (TDD)

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/global/exception/ErrorCode.java`
- Create: `src/main/java/com/example/springboot_realtimechat/dto/NicknameRequest.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/MemberService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/MemberController.java`
- Test: `src/test/java/com/example/springboot_realtimechat/onboarding/OnboardingServiceTest.java`

**Interfaces:**
- Consumes: `Member.updateNickname(String)`, `Member.markOnboarded()`, `Member.isOnboarded()` (Task 1)
- Produces:
  - `MemberService.updateNickname(Long memberId, String nickname): Member` — 앞뒤 공백 제거 후 저장. 빈 값이거나 20자 초과면 `CustomException(ErrorCode.INVALID_NICKNAME)`
  - `MemberService.completeOnboarding(Long memberId): Member` — 멱등
  - `PATCH /api/members/me` — 본문 `{ "nickname": "..." }` → `MemberResponse`
  - `POST /api/members/me/onboarding` — 본문 없음 → `MemberResponse`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/onboarding/OnboardingServiceTest.java`:

```java
package com.example.springboot_realtimechat.onboarding;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class OnboardingServiceTest {
    @Autowired MemberService memberService;
    @Autowired MemberRepository memberRepository;

    private Member socialMember(String providerId) {
        return memberRepository.save(
                Member.ofSocial("GOOGLE", providerId, providerId + "@x.com", "처음닉", null));
    }

    @Test
    void 닉네임을_수정한다() {
        Member m = socialMember("svc-1");

        Member updated = memberService.updateNickname(m.getId(), "새로운닉네임");

        assertThat(updated.getNickname()).isEqualTo("새로운닉네임");
    }

    @Test
    void 닉네임_앞뒤_공백은_제거한다() {
        Member m = socialMember("svc-2");

        Member updated = memberService.updateNickname(m.getId(), "  공백닉  ");

        assertThat(updated.getNickname()).isEqualTo("공백닉");
    }

    @Test
    void 공백만_있는_닉네임은_거부한다() {
        Member m = socialMember("svc-3");

        assertThatThrownBy(() -> memberService.updateNickname(m.getId(), "   "))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_NICKNAME);
    }

    @Test
    void 스무자를_넘는_닉네임은_거부한다() {
        Member m = socialMember("svc-4");
        String tooLong = "가".repeat(21);

        assertThatThrownBy(() -> memberService.updateNickname(m.getId(), tooLong))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_NICKNAME);
    }

    @Test
    void 정확히_스무자는_허용한다() {
        Member m = socialMember("svc-5");
        String exact = "가".repeat(20);

        Member updated = memberService.updateNickname(m.getId(), exact);

        assertThat(updated.getNickname()).isEqualTo(exact);
    }

    @Test
    void 온보딩을_완료로_기록한다() {
        Member m = socialMember("svc-6");
        assertThat(m.isOnboarded()).isFalse();

        Member done = memberService.completeOnboarding(m.getId());

        assertThat(done.isOnboarded()).isTrue();
    }

    @Test
    void 온보딩_완료_호출은_멱등이다() {
        Member m = socialMember("svc-7");

        var first = memberService.completeOnboarding(m.getId()).getOnboardedAt();
        var second = memberService.completeOnboarding(m.getId()).getOnboardedAt();

        assertThat(second).isEqualTo(first);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*OnboardingServiceTest"`
Expected: FAIL — 컴파일 에러(`ErrorCode.INVALID_NICKNAME`, `memberService.updateNickname`, `memberService.completeOnboarding` 없음).

- [ ] **Step 3: ErrorCode 추가**

`global/exception/ErrorCode.java`의 `EMAIL_ALREADY_REGISTERED` 줄 다음에 추가:

```java
    INVALID_NICKNAME(400, "닉네임은 공백을 제외하고 1자 이상 20자 이하여야 합니다."),
```

- [ ] **Step 4: NicknameRequest 생성**

`src/main/java/com/example/springboot_realtimechat/dto/NicknameRequest.java`:

```java
package com.example.springboot_realtimechat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NicknameRequest {
    @NotBlank
    private String nickname;
}
```

길이 검증은 앞뒤 공백을 제거한 뒤 판단해야 하므로 서비스에서 처리한다.

- [ ] **Step 5: MemberService에 메서드 추가**

`service/MemberService.java`의 `updateProfileImage` 메서드 바로 아래에 추가:

```java
    @Transactional
    public Member updateNickname(Long memberId, String nickname) {
        Member member = getMemberById(memberId);
        String trimmed = (nickname == null) ? "" : nickname.trim();
        if (trimmed.isEmpty() || trimmed.length() > 20) {
            throw new CustomException(ErrorCode.INVALID_NICKNAME);
        }
        member.updateNickname(trimmed);
        return member;
    }

    @Transactional
    public Member completeOnboarding(Long memberId) {
        Member member = getMemberById(memberId);
        member.markOnboarded();
        return member;
    }
```

- [ ] **Step 6: MemberController에 엔드포인트 추가**

`controller/MemberController.java`의 상단 import에 추가:

```java
import com.example.springboot_realtimechat.dto.NicknameRequest;
```

그리고 `updateProfileImage` 메서드 바로 위에 두 핸들러를 추가:

```java
    @PatchMapping("/me")
    public MemberResponse updateMe(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @Valid @RequestBody NicknameRequest request) {

        Member member = memberService.updateNickname(
                customUserDetails.getMemberId(),
                request.getNickname());

        return MemberResponse.from(member);
    }

    @PostMapping("/me/onboarding")
    public MemberResponse completeOnboarding(
            @AuthenticationPrincipal CustomUserDetails customUserDetails) {

        Member member = memberService.completeOnboarding(customUserDetails.getMemberId());
        return MemberResponse.from(member);
    }
```

`SecurityConfig`는 수정하지 않는다. `anyRequest().authenticated()`가 이 두 경로를 이미 덮는다.

- [ ] **Step 7: 통과 확인**

Run: `./gradlew test --tests "*OnboardingServiceTest"`
Expected: PASS (7/7).

- [ ] **Step 8: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/example/springboot_realtimechat/global/exception/ErrorCode.java \
        src/main/java/com/example/springboot_realtimechat/dto/NicknameRequest.java \
        src/main/java/com/example/springboot_realtimechat/service/MemberService.java \
        src/main/java/com/example/springboot_realtimechat/controller/MemberController.java \
        src/test/java/com/example/springboot_realtimechat/onboarding/OnboardingServiceTest.java
git commit -m "feat(onboarding): 닉네임 수정·온보딩 기록 API 추가"
```

---

## Task 3: 프론트 API 함수와 타입

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/types.ts`

**Interfaces:**
- Consumes: `PATCH /api/members/me`, `POST /api/members/me/onboarding` (Task 2)
- Produces:
  - `BackendMember.onboarded: boolean`
  - `User.onboarded: boolean`
  - `updateNickname(token: string, nickname: string): Promise<BackendMember>`
  - `completeOnboarding(token: string): Promise<BackendMember>`

- [ ] **Step 1: BackendMember에 onboarded 추가**

`frontend/src/lib/api.ts`의 `BackendMember` 인터페이스에 필드를 추가한다. 기존 필드는 그대로 두고 마지막에 한 줄 넣는다:

```ts
  onboarded: boolean;
```

- [ ] **Step 2: API 함수 두 개 추가**

`frontend/src/lib/api.ts`의 `updateProfileImage` 함수 바로 아래에 추가:

```ts
export async function updateNickname(token: string, nickname: string) {
  return request<BackendMember>('/api/members/me', {
    method: 'PATCH',
    body: JSON.stringify({ nickname }),
  }, token);
}

export async function completeOnboarding(token: string) {
  return request<BackendMember>('/api/members/me/onboarding', {
    method: 'POST',
  }, token);
}
```

- [ ] **Step 3: toUser에 onboarded 전달**

`frontend/src/lib/api.ts`의 `toUser` 함수를 아래로 교체:

```ts
export function toUser(member: BackendMember): User {
  return {
    id: String(member.id),
    email: member.email,
    displayName: member.nickname,
    avatar: avatarForId(member.id),
    photoUrl: member.profileImageUrl ?? undefined,
    onboarded: member.onboarded,
  };
}
```

- [ ] **Step 4: User 타입에 onboarded 추가**

`frontend/src/types.ts`의 `User` 인터페이스를 아래로 교체:

```ts
export interface User {
  id: string;
  email: string | null;   // 소셜 제공자가 이메일을 주지 않을 수 있다
  displayName: string;
  avatar: string; // Tailored color index, gradient, or icon abbreviation
  photoUrl?: string;
  onboarded: boolean;
}
```

- [ ] **Step 5: 검증**

Run: `cd frontend && npm run lint`
Expected: `User`를 만드는 다른 지점이 있으면 `onboarded` 누락으로 타입 에러가 난다. 에러가 나면 해당 지점을 확인해 값을 채운다. 에러가 없으면 통과.

Run: `cd frontend && npm run build`
Expected: 성공.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/api.ts frontend/src/types.ts
git commit -m "feat(onboarding): 닉네임 수정·온보딩 완료 API 클라이언트 추가"
```

---

## Task 4: 온보딩 화면과 App 분기

**Files:**
- Create: `frontend/src/components/Onboarding.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `updateNickname`, `completeOnboarding` (Task 3), `User.onboarded` (Task 3)
- Produces: `Onboarding` 컴포넌트 — props `{ user: User; token: string; onDone: (updated: User) => void }`

- [ ] **Step 1: 온보딩 컴포넌트 생성**

`frontend/src/components/Onboarding.tsx`:

```tsx
import { useState, CSSProperties } from 'react';
import { User } from '../types';
import { MessagesSquare } from 'lucide-react';
import { updateNickname, completeOnboarding, toUser } from '../lib/api';

interface OnboardingProps {
  user: User;
  token: string;
  onDone: (updated: User) => void;
}

const MAX_NICKNAME = 20;

export default function Onboarding({ user, token, onDone }: OnboardingProps) {
  const [nickname, setNickname] = useState(user.displayName ?? '');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const handleStart = async () => {
    const trimmed = nickname.trim();
    if (!trimmed) {
      setError('닉네임을 입력해 주세요.');
      return;
    }
    setError('');
    setBusy(true);
    try {
      await updateNickname(token, trimmed);
      const member = await completeOnboarding(token);
      onDone(toUser(member));
    } catch (e) {
      setError(e instanceof Error ? e.message : '저장에 실패했어요. 다시 시도해 주세요.');
    } finally {
      setBusy(false);
    }
  };

  const handleSkip = async () => {
    setError('');
    setBusy(true);
    try {
      const member = await completeOnboarding(token);
      onDone(toUser(member));
    } catch (e) {
      setError(e instanceof Error ? e.message : '처리에 실패했어요. 다시 시도해 주세요.');
    } finally {
      setBusy(false);
    }
  };

  const inputStyle: CSSProperties = {
    background: '#252E28', border: '1px solid #2D362F', borderRadius: 12,
    padding: '12px 14px', fontSize: 13, color: '#E6ECE8', outline: 'none', width: '100%',
  };

  return (
    <div className="min-h-screen flex items-center justify-center px-6 font-sans"
      style={{ background: '#141917', color: '#E6ECE8' }}>
      <div style={{ width: '100%', maxWidth: 400 }}>

        <div className="text-center" style={{ marginBottom: 16 }}>
          <div style={{ fontSize: 11, letterSpacing: '.16em', color: '#6B7972', fontWeight: 600 }}>ALMOST THERE</div>
          <div style={{ fontSize: 14, color: '#9AA8A0', marginTop: 4 }}>프로필 설정</div>
        </div>

        <div style={{ background: '#1C241F', border: '1px solid #2D362F', borderRadius: 22, padding: '30px 28px' }}>

          <div className="flex items-center justify-center"
            style={{ width: 64, height: 64, borderRadius: '50%', background: '#29392F', color: '#9CCBB2', margin: '0 auto 14px', overflow: 'hidden' }}>
            {user.photoUrl
              ? <img src={user.photoUrl} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
              : <MessagesSquare className="w-6 h-6" />}
          </div>

          <h2 className="text-center" style={{ fontWeight: 700, fontSize: 21, color: '#E6ECE8', margin: '0 0 6px' }}>
            어떤 이름으로 부를까요?
          </h2>
          <p className="text-center" style={{ fontSize: 13, color: '#9AA8A0', lineHeight: 1.6, margin: '0 0 24px' }}>
            채팅에서 이 이름으로 표시돼요.<br />나중에 설정에서 바꿀 수 있어요.
          </p>

          <div className="flex items-baseline justify-between" style={{ marginBottom: 5 }}>
            <label style={{ fontSize: 12, fontWeight: 600, color: '#C7D2CB' }}>닉네임</label>
            <span style={{ fontSize: 11, color: '#6B7972' }}>{nickname.trim().length} / {MAX_NICKNAME}</span>
          </div>
          <input
            style={inputStyle}
            value={nickname}
            maxLength={MAX_NICKNAME}
            onChange={(e) => setNickname(e.target.value)}
            id="onboarding-nickname"
          />

          {error && (
            <p style={{ fontSize: 12, color: '#f0a5a5', textAlign: 'center', margin: '12px 0 0' }}>{error}</p>
          )}

          <button type="button" onClick={handleStart} disabled={busy} id="onboarding-start"
            style={{ width: '100%', marginTop: 22, background: '#7AAE92', color: '#12241B', borderRadius: 13, padding: 14, fontWeight: 600, fontSize: 14, border: '1px solid transparent', cursor: 'pointer', opacity: busy ? 0.6 : 1 }}>
            {busy ? '처리 중...' : '시작하기'}
          </button>

          <button type="button" onClick={handleSkip} disabled={busy} id="onboarding-skip"
            style={{ width: '100%', marginTop: 14, background: 'transparent', color: '#6B7972', border: 'none', fontSize: 13, cursor: 'pointer' }}>
            나중에 하기
          </button>

        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: App.tsx에 온보딩 분기 추가**

`frontend/src/App.tsx`의 상단 import에 추가:

```tsx
import Onboarding from './components/Onboarding';
```

그리고 `if (!user) { ... }` 블록 **바로 다음**에 아래를 삽입한다(기존 `if (!user)` 블록은 그대로 둔다):

```tsx
  if (!user.onboarded) {
    return (
      <Onboarding
        user={user}
        token={token ?? ''}
        onDone={(updated) => {
          if (token) persistSession(token, updated);
        }}
      />
    );
  }
```

`persistSession`은 `localStorage`와 `user` 상태를 함께 갱신하므로, 온보딩이 끝나면 `user.onboarded`가 `true`가 되어 채팅 화면으로 넘어간다.

- [ ] **Step 3: 검증**

Run: `cd frontend && npm run lint && npm run build`
Expected: tsc 에러 0, vite build 성공.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/Onboarding.tsx frontend/src/App.tsx
git commit -m "feat(onboarding): 온보딩 화면 추가와 진입 분기"
```

---

## Task 5: 설정 화면의 닉네임 편집을 백엔드에 연결

**Files:**
- Modify: `frontend/src/App.tsx:513`
- Modify: `frontend/src/components/SettingsModal.tsx:134`

**Interfaces:**
- Consumes: `updateNickname` (Task 3)

- [ ] **Step 1: 설정 모달의 닉네임 입력 길이를 20으로**

`frontend/src/components/SettingsModal.tsx`의 닉네임 입력에서 `maxLength={10}`을 `maxLength={20}`으로 바꾼다.

변경 전:

```tsx
            <input className={inputCls} value={name} maxLength={10} onChange={(e) => setName(e.target.value)} />
```

변경 후:

```tsx
            <input className={inputCls} value={name} maxLength={20} onChange={(e) => setName(e.target.value)} />
```

- [ ] **Step 2: App.tsx의 onUpdateName을 API 호출로 교체**

`frontend/src/App.tsx`의 `SettingsModal`에 넘기는 `onUpdateName`을 교체한다.

변경 전:

```tsx
        onUpdateName={(displayName) => setUser((u) => (u ? { ...u, displayName } : u))}
```

변경 후:

```tsx
        onUpdateName={async (displayName) => {
          if (!token) return;
          const member = await updateNickname(token, displayName);
          persistSession(token, toUser(member));
        }}
```

`frontend/src/App.tsx`의 `./lib/api` import 목록에 `updateNickname`을 추가한다(`toUser`, `persistSession`은 이미 있다).

- [ ] **Step 3: SettingsModal의 onUpdateName 타입을 비동기로**

`frontend/src/components/SettingsModal.tsx`의 props 타입에서 `onUpdateName`을 교체:

```tsx
  onUpdateName: (name: string) => void | Promise<void>;
```

- [ ] **Step 4: 검증**

Run: `cd frontend && npm run lint && npm run build`
Expected: tsc 에러 0, vite build 성공.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/App.tsx frontend/src/components/SettingsModal.tsx
git commit -m "feat(onboarding): 설정 화면 닉네임 편집을 서버에 반영"
```

---

## Task 6: 통합 검증 + PR

**Files:** 없음 (검증·문서 단계)

- [ ] **Step 1: 전체 자동 검증**

Run:
```bash
./gradlew test
cd frontend && npm run lint && npm run build
```
Expected: 백엔드 BUILD SUCCESSFUL, 프론트 tsc 0 에러 + build 성공.

- [ ] **Step 2: 마이그레이션 검증**

기존 회원이 있는 DB에 V6가 어떻게 적용되는지 확인한다.

```bash
mysql -h 127.0.0.1 -uroot -p1111 -e "DROP DATABASE IF EXISTS onboarding_check; CREATE DATABASE onboarding_check CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

`mysql`이 PATH에 없으면 `/usr/local/mysql/bin/mysql`을 쓴다. 그다음 백엔드를 이 DB로 띄운다.

```bash
JWT_SECRET=local-onboarding-secret-0123456789abcdef0123456789abcdef \
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/onboarding_check' \
./gradlew bootRun > /tmp/onboarding-boot.log 2>&1 &
```

부팅 후 확인:

```bash
grep -E "Successfully applied 6 migrations|Started SpringbootRealtimechatApplication" /tmp/onboarding-boot.log
mysql -h 127.0.0.1 -uroot -p1111 -e "SHOW COLUMNS FROM members LIKE 'onboarded_at'; SHOW COLUMNS FROM members LIKE 'nickname'; SELECT id, nickname, onboarded_at FROM members;" onboarding_check
```

Expected:
- `Successfully applied 6 migrations`
- `onboarded_at` 컬럼 존재, `nickname`의 Type이 `varchar(20)`
- V4 시드로 들어간 demo/guest 두 행의 `onboarded_at`이 **채워져 있음**(기존 회원은 온보딩 대상이 아니다)

확인 후 서버를 종료하고 포트가 비었는지 확인한다.

```bash
pkill -f "bootRun" || true
```

- [ ] **Step 3: 로컬 E2E — 신규 회원 온보딩**

백엔드(위 DB)와 프론트 dev를 띄우고 브라우저에서 소셜 로그인한다. 이 DB에는 그 소셜 계정이 없으므로 신규 회원으로 생성된다.

Expected: 채팅이 아니라 **온보딩 화면**이 뜬다. 닉네임 입력에 provider가 준 이름이 미리 채워져 있고, 카운터가 `n / 20`으로 표시된다.

닉네임을 바꾸고 `시작하기`를 누른다.

Expected: 채팅 화면으로 넘어간다. DB 확인:

```bash
mysql -h 127.0.0.1 -uroot -p1111 -e "SELECT id, nickname, onboarded_at FROM members WHERE provider <> 'LOCAL';" onboarding_check
```

Expected: 입력한 닉네임이 저장되고 `onboarded_at`이 채워져 있다.

- [ ] **Step 4: 로컬 E2E — 재로그인 시 온보딩이 뜨지 않음**

로그아웃 후 같은 계정으로 다시 로그인한다.

Expected: 온보딩 없이 바로 채팅 화면. 브라우저를 새로고침해도 마찬가지다.

- [ ] **Step 5: 로컬 E2E — 건너뛰기**

DB를 다시 만들어(`Step 2`의 DROP/CREATE) 다른 소셜 계정으로 로그인한 뒤 `나중에 하기`를 누른다.

Expected: 채팅 화면으로 넘어가고, `onboarded_at`이 채워지며, 닉네임은 provider가 준 값 그대로다. 재로그인해도 온보딩이 뜨지 않는다.

- [ ] **Step 6: 로컬 E2E — 설정 화면 닉네임 변경**

채팅 화면에서 설정을 열어 닉네임을 바꾸고 저장한다.

Expected: **새로고침 후에도 유지**된다(기존에는 로컬 상태만 바뀌어 원복됐다). 메시지를 보내면 작성자 이름에 새 닉네임이 표시된다.

- [ ] **Step 7: PR 생성**

`.github/pull_request_template.md`의 5개 섹션(개요/변경 내용/검증/배포 영향/구현 노트·알려진 한계)을 그대로 채운다. 스키마 변경이 있으므로 `## 리뷰어가 꼭 봐야 할 변경`을 `## 검증` 바로 앞에 추가해 V6의 `UPDATE members SET onboarded_at = NOW(6)`(기존 회원 제외 처리)를 명시한다. `## 검증`에는 실제로 실행한 것만 쓴다.

```bash
git push -u origin feat/onboarding
gh pr create --base develop --title "feat(onboarding): 소셜 로그인 후 프로필 온보딩 추가"
```

---

## Self-Review 결과

**Spec coverage**: §2 D1(건너뛰기·백엔드 미강제)→T4 `handleSkip`·SecurityConfig 무변경, D2(닉네임만)→T4 화면, D3(20자)→T1 Step 1·2·4·T2 Step 5·T5 Step 1, D4(기존 회원 제외)→T1 Step 1 `UPDATE`·T6 Step 2. §3 스키마→T1. §4 API 2개·분리 이유→T2. §5 `onboarded` 노출→T1 Step 3. §6 진입 판별·화면·설정 연결→T3·T4·T5. §7 검증→각 태스크+T6. §8 배포 영향→T6 Step 7. 커버 확인.

**타입 일관성**: `MemberService.updateNickname(Long, String): Member`와 `completeOnboarding(Long): Member`가 T2 정의·T2 컨트롤러 사용처에서 동일. `Member.updateNickname(String)`·`markOnboarded()`·`isOnboarded()`가 T1 정의·T2 사용처에서 동일. `MemberResponse` 생성자 6인자가 T1에서 정의되고 `from()`만 외부에서 쓰인다. 프론트 `updateNickname(token, nickname)`·`completeOnboarding(token)`이 T3 정의·T4·T5 사용처에서 동일. `User.onboarded`가 T3 정의·T4 분기에서 동일.

**주의**: T1 Step 7에서 `MemberResponse` 생성자를 직접 호출하는 코드가 있으면 컴파일이 깨진다. 해당 지점은 `MemberResponse.from(member)`로 바꾼다.
