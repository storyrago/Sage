# 프론트 디자인 패스 — 세이지 테마 + 라이트/다크 모드

- 날짜: 2026-07-18
- 범위: `frontend/` (React + Vite + Tailwind v4)
- 상태: 설계 승인됨 → 구현 플랜 대기

## 목표 (Why)

현재 UI가 보라/다크 단일 톤이라 "칙칙"하다는 피드백. 두 가지를 해결한다.

1. **라이트 모드 추가** — 다크 전용에서 라이트/다크 둘 다 지원.
2. **세이지(파스텔 그린) 리컬러** — 액센트를 보라(indigo)에서 세이지로 교체하고, 전체 톤을 덜 칙칙하게.

부수적으로 브라우저 탭 제목(스캐폴딩 잔재)을 정리한다.

## 범위

### 포함
- 시맨틱 토큰 도입 + 라이트/다크 값 정의 (세이지)
- 테마 토글(시스템 기본 + 수동 + localStorage 저장)
- 컴포넌트 색상 클래스 → 시맨틱 클래스로 치환
- 탭 제목 변경
- 사이드바 채널 목록 항목 간 세로 여백 축소

### 제외 (별도 작업)
- **실시간 접속자 기능 (per-room presence)** — 백엔드 STOMP 세션 추적 + Redis presence + 프론트. 성격이 완전히 다른 백엔드 기능이라 별도 스펙/플랜으로 진행한다.

## 접근법

**시맨틱 토큰 (CSS 변수 → Tailwind `@theme` 매핑).** 색 정의를 한 곳(`index.css`)에 모으고, 컴포넌트는 역할 기반 클래스(`bg-surface`, `text-muted`, `bg-accent` 등)만 쓴다. 라이트/다크는 `:root` vs `[data-theme=dark]`에서 같은 토큰의 값만 스왑. (대안인 Tailwind `dark:` 변형은 클래스가 2배로 불어나고 리컬러가 어려워 기각.)

## 토큰 세트 (세이지, 라이트 / 다크)

| 토큰 | 역할 | 라이트 | 다크 |
|---|---|---|---|
| `--bg` | 페이지 배경 | `#F5F7F5` | `#141917` |
| `--surface` | 사이드바·패널·카드 | `#FFFFFF` | `#1C241F` |
| `--surface-2` | 입력창·호버·raised | `#EDF1EE` | `#252E28` |
| `--border` | 헤어라인 | `#E2E8E3` | `#2D362F` |
| `--text` | 본문 | `#212A25` | `#E6ECE8` |
| `--text-muted` | 보조 | `#647469` | `#9AA8A0` |
| `--text-faint` | 힌트·placeholder | `#97A49B` | `#6B7972` |
| `--accent` | 버튼·내 말풍선·활성 | `#5E9079` | `#7AAE92` |
| `--accent-hover` | 호버 | `#517E69` | `#8BBBA1` |
| `--accent-fg` | 액센트 위 글자/아이콘 | `#FFFFFF` | `#12241B` |
| `--accent-subtle` | 활성 채널 배경·연한 강조 | `#E5EDE8` | `#29392F` |
| `--accent-text` | 일반 배경 위 세이지 글자 | `#3F6B54` | `#9CCBB2` |
| `--bubble-other` | 상대 말풍선 | `#EDEFEC` | `#262E29` |
| `--online` | 접속 표시 점 | `#3FA779` | `#57C596` |

- 내 말풍선/버튼 = `--accent` + `--accent-fg`. 상대 말풍선 = `--bubble-other` + `--text`.
- 접근성: `--accent`를 원 세이지(`#6FA287`)보다 살짝 깊게 잡아 흰 글자 대비 확보.

## 테마 토글

- `useTheme` 훅: `localStorage.theme` 있으면 사용, 없으면 `matchMedia('(prefers-color-scheme: dark)')`.
- 적용: `document.documentElement.dataset.theme = 'light' | 'dark'`.
- 토글 버튼(해/달 아이콘): 사이드바 하단 프로필 근처. 클릭 시 localStorage 저장.

## 클래스 치환 규칙

| 현재 | → |
|---|---|
| `bg-slate-900/950` | `bg-bg` |
| `bg-slate-800/850` | `bg-surface` |
| `bg-slate-700` | `bg-surface-2` |
| `text-slate-100~300` / `400~500` / `600` | `text-text` / `text-muted` / `text-faint` |
| `border-slate-*` | `border-border` |
| `bg/text/border/ring-indigo-*` | `…-accent` (글자는 `text-accent-text`) |
| `bg-emerald-*` (접속) | `bg-online` |

## 바뀌는 파일

- `frontend/src/index.css` — 토큰 정의(`:root` + `[data-theme=dark]`) + Tailwind `@theme` 매핑
- `frontend/index.html` — 탭 제목 `My Google AI Studio App` → `Real-Time Chat`
- `frontend/src/lib/useTheme.ts` — 신규(테마 훅)
- `frontend/src/components/Sidebar.tsx` — 토글 버튼 + 클래스 치환 + 채널 목록 항목 간 세로 여백 축소
- `frontend/src/components/ChatArea.tsx` · `UserSetup.tsx` · `EmojiPicker.tsx`, `frontend/src/App.tsx` — 클래스 치환

## 검증

- dev 서버(`npm --prefix frontend run dev`)에서 라이트/다크 토글 → 로그인·채팅 두 화면 확인
- 잔여 `slate-`/`indigo-` 하드코딩 `grep` 0개 확인
- 주요 텍스트 대비 눈으로 체크
- `tsc --noEmit`(lint) 통과

## 브랜치/배포

- 브랜치 `feat/frontend-theme` → develop PR. 머지 시 CD로 자동 배포(프론트는 별도 배포 경로 확인 필요 — Vercel).
