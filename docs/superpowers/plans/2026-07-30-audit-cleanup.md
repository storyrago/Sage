# 감사 후속 정리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 감사에서 드러난 거짓말하는 UI를 걷어내고, 죽어 있던 연출을 되살리고, 랜딩에서 프로필을 열 수 있게 한다.

**Architecture:** 프론트 전용. 가짜 저장(상태 메시지·알림 토글)을 UI째 제거하고, `warping`을 OAuth 핸드오프 구간에서 트리거하며, `ChannelLanding` 상단바에 설정 모달 진입점을 추가한다. `SettingsModal`은 이미 화면 분기 바깥에 렌더되어 있어 트리거만 연결하면 된다.

**Tech Stack:** React + TypeScript (Vite)

감사 문서: `docs/superpowers/specs/2026-07-30-ui-audit.md`

## Global Constraints

- 브랜치는 `chore/ui-audit-cleanup`(이미 존재, `origin/develop`에서 분기). PR 대상은 **develop**.
- **백엔드(`src/`)를 변경하지 않는다. 스키마 변경도 없다.**
- 프론트에 유닛 테스트 러너가 없다. 검증 명령은 `cd frontend && npm run lint && npm run build` 두 개뿐이다.
- **이번 범위는 "거짓말하는 UI 제거"다.** 실패 무음(메시지 전송·채널 생성·방 입장·참가자 목록·이미지 업로드·무한 스크롤)은 **손대지 않는다.** 별도 작업이다.
- **방 나가기 UI를 만들지 않는다.** 감사 문서 §6의 결정이다.
- 커밋 메시지·코드 주석은 변경의 목적만 쓴다. "누락/핫픽스/깨져 있었다" 같은 배경 서사 금지.
- 디자인 토큰은 각 화면의 기존 방식을 따른다(`ChannelLanding`·`SettingsModal`은 Tailwind 시맨틱 클래스, `Onboarding`은 인라인 스타일).

## File Structure

| 파일 | 책임 | 작업 |
|---|---|---|
| `frontend/src/components/ChannelLanding.tsx` | 상단바 프로필 진입점 | 수정 |
| `frontend/src/App.tsx` | `onOpenSettings` 전달, `warping` 트리거 | 수정 |
| `frontend/src/components/SettingsModal.tsx` | 가짜 저장 UI 제거 | 수정 |
| `frontend/src/components/Onboarding.tsx` | 건너뛰기 안내 문구 정정 | 수정 |

---

## Task 1: 랜딩에서 프로필 설정 열기

**Files:**
- Modify: `frontend/src/components/ChannelLanding.tsx`
- Modify: `frontend/src/App.tsx:507-518` (`ChannelLanding` 사용부)

**Interfaces:**
- Produces: `ChannelLanding`의 새 prop `currentUser: User`, `onOpenSettings: () => void`

- [ ] **Step 1: `ChannelLanding`에 prop 두 개를 받는다**

`ChannelLanding.tsx` 상단 import에 추가한다:

```tsx
import { User } from '../types';
import Avatar from './Avatar';
```

props 인터페이스에 두 줄을 추가한다(기존 prop은 그대로 둔다):

```tsx
  currentUser: User;
  onOpenSettings: () => void;
```

컴포넌트 시그니처의 구조분해에도 `currentUser`, `onOpenSettings`를 추가한다.

- [ ] **Step 2: 상단바에 프로필 버튼을 넣는다**

상단바에서 `채널 만들기`·`로그아웃` 버튼이 들어 있는 컨테이너를 찾아, **`채널 만들기` 버튼 앞**에 아래를 넣는다:

```tsx
              <button
                onClick={onOpenSettings}
                aria-label="프로필 설정"
                className="flex items-center gap-2 rounded-lg border border-border pl-1.5 pr-3 py-1.5 text-[13px] font-semibold text-text hover:border-accent transition-all cursor-pointer"
              >
                <Avatar
                  photoUrl={currentUser.photoUrl}
                  gradient={currentUser.avatar}
                  name={currentUser.displayName}
                  className="w-6 h-6 rounded-md text-[11px]"
                />
                {currentUser.displayName}
              </button>
```

기존 `채널 만들기`·`로그아웃` 버튼은 손대지 않는다. 최종 순서는 `[아바타+이름] [채널 만들기] [로그아웃]`이다.

- [ ] **Step 3: `App.tsx`에서 prop을 넘긴다**

`App.tsx`의 `<ChannelLanding ... />` 사용부에 두 줄을 추가한다(기존 prop은 그대로):

```tsx
            currentUser={user}
            onOpenSettings={() => setSettingsOpen(true)}
```

`SettingsModal`은 이미 화면 분기 바깥(`App.tsx:522`)에 렌더되어 있으므로 **추가 렌더는 필요 없다.** 새로 만들지 마라.

- [ ] **Step 4: 검증**

Run: `cd frontend && npm run lint`
Expected: 에러 없음.

Run: `cd frontend && npm run build`
Expected: 성공.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ChannelLanding.tsx frontend/src/App.tsx
git commit -m "feat(landing): 채널 랜딩에서 프로필 설정 열기"
```

---

## Task 2: 거짓말하는 UI 제거

**Files:**
- Modify: `frontend/src/components/SettingsModal.tsx`
- Modify: `frontend/src/components/Onboarding.tsx:162-166`

**Interfaces:**
- Consumes: 없음
- Produces: 없음

- [ ] **Step 1: 상태 메시지 입력을 제거한다**

`SettingsModal.tsx`에서 아래를 전부 지운다:

- `status` state 선언과 `localStorage.getItem('sage-status')` 초기화
- `handleSave` 안의 `localStorage.setItem('sage-status', status)`
- `상태 메시지` 라벨과 그 입력창 블록(`placeholder="예: 집중 모드"`가 있는 `div`)

`sage-status` 키는 이 파일 밖에서 참조되지 않으므로 다른 파일을 고칠 필요는 없다.

- [ ] **Step 2: 알림 토글 3개와 알림 섹션을 제거한다**

`SettingsModal.tsx`에서 아래를 전부 지운다:

- `Notif` 타입, `NOTIF_ROWS` 상수, `loadNotif` 함수
- `notif` state와 `toggleNotif` 함수
- `handleSave` 안의 `localStorage.setItem('sage-notif', ...)`
- `알림` 섹션 전체(`NOTIF_ROWS.map(...)`을 감싸는 블록)
- 위 제거로 쓰이지 않게 된 import

지우는 이유는 저장이 로컬이라서가 아니라 **이 토글이 제어할 알림 기능이 앱에 존재하지 않기 때문**이다. 로컬 저장을 백엔드 저장으로 바꾸는 식으로 "고치지" 마라.

`handleSave`는 남은 저장(사진 확정 → 닉네임)만 수행하게 되고, 그 뒤의 `setSaved(true)` → 700ms 후 `onClose()` 흐름은 그대로 둔다.

- [ ] **Step 3: 온보딩 안내 문구를 정정한다**

`Onboarding.tsx`의 건너뛰기 안내 문구를 아래로 바꾼다:

```tsx
              '나중에 하기'를 누르면 고른 사진은 저장되지 않아요
```

조건(`hasDraft`)과 위치는 그대로 둔다. 문구만 바꾼다. 지금 문장은 조건 없이 읽혀서, `시작하기`를 누르려는 사용자도 사진이 저장되지 않는다고 오해한다.

- [ ] **Step 4: 잔여 참조가 없는지 확인한다**

Run: `grep -rn "sage-status\|sage-notif\|NOTIF_ROWS\|toggleNotif" frontend/src`
Expected: 출력 없음.

- [ ] **Step 5: 검증**

Run: `cd frontend && npm run lint`
Expected: 에러 없음. 미사용 import·변수가 남아 있으면 여기서 잡힌다.

Run: `cd frontend && npm run build`
Expected: 성공.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/SettingsModal.tsx frontend/src/components/Onboarding.tsx
git commit -m "refactor(settings): 동작하지 않는 상태 메시지·알림 설정 제거"
```

---

## Task 3: 로그인 워프 연출 복원

**Files:**
- Modify: `frontend/src/App.tsx:111-139` (OAuth 해시 핸드오프 `useEffect`)

**Interfaces:**
- Consumes: 기존 `warping` state(`App.tsx:51`)와 `Welcome`의 `warping` prop(`App.tsx:445`) — 둘 다 이미 존재한다
- Produces: 없음

- [ ] **Step 1: 핸드오프 구간에서 워프를 켠다**

`App.tsx`의 OAuth 해시 처리 `useEffect` 안, `if (!oauthToken) return;` 다음의 즉시실행 async 블록을 아래로 교체한다:

```tsx
    (async () => {
      // 워프 연출이 눈에 보이도록 최소 노출 시간을 둔다.
      // 요청이 이미 그보다 오래 걸리면 추가로 기다리지 않는다.
      const WARP_MIN_MS = 900;
      const startedAt = Date.now();
      setWarping(true);
      try {
        const member = await getMe(oauthToken);
        const elapsed = Date.now() - startedAt;
        if (elapsed < WARP_MIN_MS) {
          await new Promise((resolve) => setTimeout(resolve, WARP_MIN_MS - elapsed));
        }
        persistSession(oauthToken, toUser(member));
      } catch (e) {
        console.error('[OAuth] 핸드오프 실패:', e);
        setWarping(false);
        setOauthError('로그인 처리에 실패했어요. 다시 시도해 주세요.');
      }
    })();
```

성공 시 `persistSession`이 `user`를 채우면 `Welcome`이 언마운트되므로 `setWarping(false)`가 필요 없다. **실패 시에는 `Welcome`이 그대로 남으므로 반드시 꺼야 한다** — 안 끄면 오라가 켜진 채 콘텐츠가 `opacity: 0`으로 숨어 화면이 빈 것처럼 보인다.

- [ ] **Step 2: 검증**

Run: `cd frontend && npm run lint`
Expected: 에러 없음. `setWarping`이 이제 사용되므로 미사용 경고가 사라진다.

Run: `cd frontend && npm run build`
Expected: 성공.

- [ ] **Step 3: 연출이 실제로 걸리는 경로를 코드로 확인한다**

`Welcome.tsx`에서 `warping` prop이 무엇을 하는지 읽고, 아래 세 가지가 모두 연결되는지 확인해 보고서에 적는다:
- `auraRef`에 `on` 클래스가 붙는지(`sage-warp-aura.on`, `index.css`)
- `contentRef`의 `opacity`가 `0`이 되는지
- 캔버스 파티클 쪽 `warpRef`가 쓰이는지

브라우저 실행은 하지 않는다(로컬에 백엔드·OAuth 자격증명이 없어 실제 로그인 왕복을 재현할 수 없다). 코드 경로만 확인하고, 확인하지 못한 것은 그대로 적는다.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "feat(login): OAuth 핸드오프 구간에 워프 연출 연결"
```

---

## Task 4: 검증 + PR

**Files:** 없음 (검증·문서 단계)

- [ ] **Step 1: 전체 검증**

Run: `cd frontend && npm run lint && npm run build`
Expected: tsc 에러 0, 빌드 성공.

- [ ] **Step 2: 범위를 벗어나지 않았는지 확인한다**

Run: `git diff origin/develop --stat -- src/ frontend/src/lib/`
Expected: 출력 없음. 이번 작업은 백엔드도 API 계층도 건드리지 않는다.

Run: `git diff origin/develop --stat`
Expected: `App.tsx`, `ChannelLanding.tsx`, `SettingsModal.tsx`, `Onboarding.tsx`와 문서 2개만.

- [ ] **Step 3: 브랜치를 푸시한다**

```bash
git push -u origin chore/ui-audit-cleanup
```

- [ ] **Step 4: PR을 만든다**

`.github/pull_request_template.md`의 5개 섹션을 그대로 채운다.

`## 검증`에는 실제로 실행한 것만 쓴다 — lint·build·잔여 참조 grep. **브라우저 확인은 하지 않았다**는 사실을 명시한다(로컬에 OAuth 자격증명이 없어 로그인 왕복 재현 불가).

`## 구현 노트 / 알려진 한계`에 담을 것:
- 이번 범위는 **거짓말하는 UI 제거**다. 감사에서 드러난 **실패 무음 6개**(메시지 전송·채널 생성·방 입장·참가자 목록·이미지 업로드·무한 스크롤)는 그대로 남아 있고 별도 작업이다. 감사 문서 §2 참고.
- 알림 토글을 지운 이유는 저장이 로컬이라서가 아니라 **알림 기능 자체가 없기 때문**이다. 백로그의 "멘션+알림"에서 기능부터 만든 뒤 설정을 다시 붙인다.
- 방 나가기 UI는 만들지 않았다. `GET /api/chatrooms`가 전체 방을 반환해 나가도 목록에 남고 재입장 시 자동 재가입되므로, 비공개방(인가) 작업과 함께 해야 의미가 있다.
- 워프 연출은 최소 노출 900ms를 둔다. `getMe`가 그보다 오래 걸리면 추가 대기는 없다.

```bash
gh pr create --base develop --head chore/ui-audit-cleanup --title "chore(ui): 감사 후속 — 동작하지 않는 설정 제거, 랜딩 프로필 진입점, 워프 복원" --body-file <작성한 본문 파일>
```

- [ ] **Step 5: 머지는 사용자가 한다 — 체크포인트**

머지 후 배포본에서 확인할 것:
- 랜딩 상단바에 아바타+닉네임 버튼이 있고 누르면 설정이 열린다
- 설정에 상태 메시지·알림 섹션이 없고, 이름·사진 저장은 그대로 동작한다
- 소셜 로그인 직후 워프 연출이 보인다
- 온보딩에서 사진을 고르면 `'나중에 하기'를 누르면...` 문구가 뜬다
