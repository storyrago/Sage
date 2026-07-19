# 프론트 세이지 테마 + 라이트/다크 구현 플랜

> **For agentic workers:** 구현은 superpowers:executing-plans 또는 subagent-driven-development로 태스크별 진행. 각 스텝은 `- [ ]` 체크박스.

**Goal:** 프론트에 시맨틱 토큰 기반 세이지 테마 + 라이트/다크 토글을 도입하고, 채널 목록 여백을 줄이며 탭 제목을 정리한다.

**Architecture:** CSS 변수로 역할 기반 시맨틱 토큰을 `:root`(라이트)/`[data-theme=dark]`(다크)에 정의하고 Tailwind v4 `@theme inline`으로 유틸리티에 매핑. 컴포넌트의 하드코딩 색 클래스(`slate`/`indigo`/`emerald`)를 시맨틱 클래스로 치환. `useTheme` 훅이 `documentElement`의 `data-theme`를 관리(시스템 기본 + localStorage).

**Tech Stack:** React 19, Vite 6, Tailwind CSS v4, TypeScript.

## Global Constraints

- 유닛 테스트 없음 → 검증은 (1) `npm --prefix frontend run dev` 무오류 렌더, (2) 라이트/다크 토글 시각 확인(로그인 + 채팅 화면), (3) `grep`로 잔여 하드코딩 색 0개, (4) `npm --prefix frontend run lint`(`tsc --noEmit`) 통과.
- 색은 반드시 시맨틱 토큰 경유. 컴포넌트에 raw hex/`slate`/`indigo`/`emerald` 클래스 금지.
- 브랜치 `feat/frontend-theme`. 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- 토큰 값은 스펙(`docs/design/2026-07-18-frontend-sage-theme.md`)의 표를 그대로 사용.

---

### Task 1: 시맨틱 토큰 + Tailwind 매핑 + 탭 제목 + FOUC 방지

**Files:**
- Modify: `frontend/src/index.css`
- Modify: `frontend/index.html`

**Produces:** Tailwind 유틸리티 `bg-bg`, `bg-surface`, `bg-surface-2`, `border-border`, `text-text`, `text-muted`, `text-faint`, `bg-accent`, `hover:bg-accent-hover`, `text-accent-fg`, `bg-accent-subtle`, `text-accent-text`, `bg-bubble-other`, `bg-online` (그리고 `text-*`/`border-*` 대응).

- [ ] **Step 1: `index.css`에 토큰 정의 추가** (기존 `@theme` 폰트 블록 아래)

```css
:root {
  --bg: #F5F7F5; --surface: #FFFFFF; --surface-2: #EDF1EE; --border: #E2E8E3;
  --text: #212A25; --text-muted: #647469; --text-faint: #97A49B;
  --accent: #5E9079; --accent-hover: #517E69; --accent-fg: #FFFFFF;
  --accent-subtle: #E5EDE8; --accent-text: #3F6B54;
  --bubble-other: #EDEFEC; --online: #3FA779;
}
:root[data-theme="dark"] {
  --bg: #141917; --surface: #1C241F; --surface-2: #252E28; --border: #2D362F;
  --text: #E6ECE8; --text-muted: #9AA8A0; --text-faint: #6B7972;
  --accent: #7AAE92; --accent-hover: #8BBBA1; --accent-fg: #12241B;
  --accent-subtle: #29392F; --accent-text: #9CCBB2;
  --bubble-other: #262E29; --online: #57C596;
}
@theme inline {
  --color-bg: var(--bg); --color-surface: var(--surface); --color-surface-2: var(--surface-2);
  --color-border: var(--border); --color-text: var(--text);
  --color-text-muted: var(--text-muted); --color-text-faint: var(--text-faint);
  --color-accent: var(--accent); --color-accent-hover: var(--accent-hover);
  --color-accent-fg: var(--accent-fg); --color-accent-subtle: var(--accent-subtle);
  --color-accent-text: var(--accent-text); --color-bubble-other: var(--bubble-other);
  --color-online: var(--online);
}
```

(`@theme inline`이라야 유틸리티가 `var(--bg)`를 참조 → 런타임 토글 반영됨.)

- [ ] **Step 2: `index.html` `<head>`에 FOUC 방지 스크립트 + 탭 제목 변경**

`<title>My Google AI Studio App</title>` → `<title>Real-Time Chat</title>`.
그리고 `<head>` 안, CSS 로드 전에:

```html
<script>
  (function () {
    try {
      var t = localStorage.getItem('theme');
      if (t !== 'light' && t !== 'dark')
        t = matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
      document.documentElement.dataset.theme = t;
    } catch (e) {}
  })();
</script>
```

- [ ] **Step 3: 렌더 확인**

Run: `npm --prefix frontend run dev` (이미 떠 있으면 재사용)
Expected: 빌드 에러 없음. (아직 컴포넌트는 옛 클래스라 화면 변화는 부분적)

- [ ] **Step 4: 커밋**

```bash
git add frontend/src/index.css frontend/index.html
git commit -m "feat(theme): 세이지 시맨틱 토큰 + Tailwind 매핑, 탭 제목, FOUC 방지"
```

---

### Task 2: `useTheme` 훅 + App에서 적용

**Files:**
- Create: `frontend/src/lib/useTheme.ts`
- Modify: `frontend/src/App.tsx` (훅 호출 + Sidebar에 `theme`, `toggleTheme` 전달)

**Interfaces:**
- Produces: `useTheme(): { theme: 'light' | 'dark'; toggleTheme: () => void }`

- [ ] **Step 1: 훅 작성**

```ts
import { useEffect, useState } from 'react';

type Theme = 'light' | 'dark';

function getInitialTheme(): Theme {
  const stored = localStorage.getItem('theme');
  if (stored === 'light' || stored === 'dark') return stored;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export function useTheme() {
  const [theme, setTheme] = useState<Theme>(getInitialTheme);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('theme', theme);
  }, [theme]);
  const toggleTheme = () => setTheme((t) => (t === 'dark' ? 'light' : 'dark'));
  return { theme, toggleTheme };
}
```

- [ ] **Step 2: App.tsx에서 호출**

App 컴포넌트 상단에서 `const { theme, toggleTheme } = useTheme();` 호출(로그인 전에도 렌더되므로 로그인 화면에도 테마 적용됨). `<Sidebar ... theme={theme} onToggleTheme={toggleTheme} />`로 전달(다음 태스크에서 Sidebar가 props 수신).

- [ ] **Step 3: 확인**

Run: `npm --prefix frontend run lint`
Expected: 타입 에러 없음. dev 화면에서 브라우저 콘솔로 `document.documentElement.dataset.theme` 값이 시스템/저장값과 일치.

- [ ] **Step 4: 커밋**

```bash
git add frontend/src/lib/useTheme.ts frontend/src/App.tsx
git commit -m "feat(theme): useTheme 훅 + 앱 전역 테마 적용"
```

---

### Task 3: Sidebar — 클래스 치환 + 토글 버튼 + 채널 여백 축소

**Files:**
- Modify: `frontend/src/components/Sidebar.tsx`

**Interfaces:**
- Consumes: props `theme: 'light'|'dark'`, `onToggleTheme: () => void` (Task 2)

- [ ] **Step 1: 색 클래스 치환** — 아래 규칙 적용

| 현재 | → |
|---|---|
| `bg-slate-900/950` | `bg-bg` |
| `bg-slate-800/850` | `bg-surface` |
| `bg-slate-700` | `bg-surface-2` |
| `text-slate-100~300` / `400~500` / `600` | `text-text` / `text-muted` / `text-faint` |
| `border-slate-*` | `border-border` |
| `bg/text/border/ring-indigo-*` | `bg-accent`/`text-accent-text`/`border-accent`/`ring-accent` |
| `bg-emerald-*` (접속 점) | `bg-online` |
| 활성 채널 하이라이트(indigo 배경) | `bg-accent-subtle text-accent-text` |

- [ ] **Step 2: 채널 목록 여백 축소** — 채널 항목을 감싸는 컨테이너의 세로 간격을 줄인다(예: `space-y-1`→`space-y-0.5` 또는 각 항목 `py-2`→`py-1.5`). 현재 값 확인 후 한 단계 줄임.

- [ ] **Step 3: 테마 토글 버튼 추가** — 사이드바 하단 프로필 영역에, 기존 아이콘 버튼과 같은 스타일로:

```tsx
<button
  onClick={onToggleTheme}
  aria-label={theme === 'dark' ? '라이트 모드로 전환' : '다크 모드로 전환'}
  className="p-2 rounded-lg text-muted hover:bg-surface-2 hover:text-text transition-colors"
>
  {theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
</button>
```

(`lucide-react`에서 `Sun`, `Moon` import — 이미 의존성 있음.)

- [ ] **Step 4: 시각 확인** — dev 화면에서 로그인 후 사이드바가 세이지로 보이고, 토글 클릭 시 **라이트↔다크 즉시 전환**, 채널 간격이 촘촘해졌는지 확인.

- [ ] **Step 5: 잔여 색 확인**

Run: `grep -nE "(slate|indigo|emerald)-[0-9]" frontend/src/components/Sidebar.tsx`
Expected: 출력 없음.

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/components/Sidebar.tsx
git commit -m "feat(theme): Sidebar 세이지 치환 + 테마 토글 버튼 + 채널 여백 축소"
```

---

### Task 4: ChatArea — 클래스 치환

**Files:**
- Modify: `frontend/src/components/ChatArea.tsx`

- [ ] **Step 1: 색 클래스 치환** — Task 3 Step 1의 규칙 적용. 추가로:
  - 내 말풍선(현재 indigo 배경 + 흰 글자) → `bg-accent text-accent-fg`
  - 상대 말풍선(현재 slate 배경) → `bg-bubble-other text-text`
  - 헤더/입력창 배경 → `bg-surface` / `bg-surface-2`, 테두리 `border-border`
  - placeholder 텍스트 → `text-faint` (또는 `placeholder-*`는 `placeholder-[var(--text-faint)]`로)

- [ ] **Step 2: 시각 확인** — 채팅 화면에서 내/상대 말풍선 색·정렬 정상, 라이트/다크 둘 다 확인.

- [ ] **Step 3: 잔여 색 확인**

Run: `grep -nE "(slate|indigo|emerald)-[0-9]" frontend/src/components/ChatArea.tsx`
Expected: 출력 없음.

- [ ] **Step 4: 커밋**

```bash
git add frontend/src/components/ChatArea.tsx
git commit -m "feat(theme): ChatArea 세이지 치환"
```

---

### Task 5: UserSetup(로그인) — 클래스 치환

**Files:**
- Modify: `frontend/src/components/UserSetup.tsx`

- [ ] **Step 1: 색 클래스 치환** — Task 3 Step 1 규칙. 로그인/회원가입 탭 활성 상태(indigo) → `bg-accent text-accent-fg`, 카드 배경 `bg-surface`, 입력창 `bg-surface-2 border-border`, 로그인 버튼 `bg-accent hover:bg-accent-hover text-accent-fg`.

- [ ] **Step 2: 시각 확인** — 로그인 화면 라이트/다크 둘 다 확인.

- [ ] **Step 3: 잔여 색 확인**

Run: `grep -nE "(slate|indigo|emerald)-[0-9]" frontend/src/components/UserSetup.tsx`
Expected: 출력 없음.

- [ ] **Step 4: 커밋**

```bash
git add frontend/src/components/UserSetup.tsx
git commit -m "feat(theme): UserSetup 세이지 치환"
```

---

### Task 6: App.tsx + EmojiPicker 잔여 치환

**Files:**
- Modify: `frontend/src/App.tsx`, `frontend/src/components/EmojiPicker.tsx`

- [ ] **Step 1: 두 파일의 잔여 색 클래스 치환** — Task 3 Step 1 규칙.

- [ ] **Step 2: 잔여 색 확인**

Run: `grep -rnE "(slate|indigo|emerald)-[0-9]" frontend/src/App.tsx frontend/src/components/EmojiPicker.tsx`
Expected: 출력 없음.

- [ ] **Step 3: 커밋**

```bash
git add frontend/src/App.tsx frontend/src/components/EmojiPicker.tsx
git commit -m "feat(theme): App·EmojiPicker 세이지 치환"
```

---

### Task 7: 최종 검증 스윕

- [ ] **Step 1: 전역 잔여 하드코딩 색 0개 확인**

Run: `grep -rnE "(bg|text|border|ring)-(slate|indigo|violet|purple|zinc|gray|neutral)-[0-9]" frontend/src`
Expected: 출력 없음. (남으면 해당 파일로 돌아가 치환)

- [ ] **Step 2: 타입/빌드 확인**

Run: `npm --prefix frontend run lint`
Expected: PASS.

- [ ] **Step 3: 시각 QA** — dev 화면에서 로그인·채팅 두 화면 각각 **라이트/다크** 확인. 토글이 즉시 반영되고 새로고침 후 선택 유지되는지, 대비(텍스트 가독성) 이상 없는지 확인.

- [ ] **Step 4: PR 준비** — `feat/frontend-theme` push 후 develop 대상 PR 생성.
