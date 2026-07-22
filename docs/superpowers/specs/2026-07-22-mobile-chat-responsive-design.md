# 모바일 채팅 반응형 — 가로잘림 근본수정 설계

- 날짜: 2026-07-22
- 브랜치: `fix/mobile-chat-overflow` (develop 분기)
- 범위: **채팅 화면 집중** (A안) — 가로잘림 근본수정 + 헤더/입력창/타이핑바 좁은 폭 정합
- 제외(이번 배치 아님): 우표 랜딩 재배치, 설정/프로필/채널생성 모달, Welcome 인트로

## 문제 (실측으로 재현·확정)

로컬(백엔드 8080 + Vite 5173)에서 모바일 뷰포트 375px로 재현.

- 뷰포트 375px인데 메시지 스크롤 영역 콘텐츠 폭이 **722px**로 넘침.
- 최상위 `div.w-screen.overflow-hidden`이 **문서 가로 스크롤바만 숨겨서**, 스크롤은 안 되는데 메시지가 화면 밖으로 잘려 보이는 상태.
- 측정: `document.documentElement.scrollWidth === 375`(정상)이지만 `.overflow-y-auto` 컨테이너는 `scrollWidth === clientWidth === 722`. 즉 **컨테이너 내부가 뷰포트를 넘김**.
- 데스크톱 1280px에서는 `scrollerSW === clientWidth === 1274`, 버블 최대 651px(`max-w-3xl` = 768px 이내) → **정상, 회귀 없음**. 이 버그는 **모바일 전용**.

### 근본 원인 (3가지가 겹침)

1. **긴 무공백 문자열이 안 깨짐 (핵심).** 버블 텍스트에 `break-words`(= `overflow-wrap: break-word`)만 걸려 있음. 이 값은 **min-content 크기 계산에 반영되지 않음** — 브라우저는 여전히 "버블은 긴 URL 한 덩어리만큼 넓어야 한다"고 보고 부모를 밀어냄. 실측 버블 폭 591px.
2. **`max-w-[85%]`가 부모 기준 %.** 부모(메시지 행)가 넘친 폭의 85%를 다시 자식 max로 잡아, shrink가 전파되지 않는 악순환.
3. **`min-w-0` 체인.** 대부분 걸려 있으나, 텍스트가 안 깨지면(1번) min-content가 커서 `min-w-0`도 무력화됨.

→ **1번이 해결되면 2·3번은 자연히 정상화된다** (min-content가 줄면 flex shrink·`max-w-[85%]`가 제대로 작동). 그러므로 1번을 정확히 타격하는 것이 설계의 중심.

## 접근 결정

**접근 1 — 정밀 타격 (채택).** 실제 원인(overflow-wrap)만 교체 + 최소 방어선. diff 최소, 데스크톱 무영향.

- 접근 2(레이아웃 grid 리팩터 + `clamp()` max-width): 과함, 데스크톱 회귀 검증 부담 → 기각.
- 접근 3(전역 `overflow-x-hidden`만): 증상만 가림, 긴 URL은 여전히 잘림(근본 미해결) → 기각.

## 변경 설계

파일: `frontend/src/components/ChatArea.tsx` (주), `frontend/src/index.css`(선택).

### 1. 버블 텍스트 줄바꿈 — 근본수정

- 위치: 메시지 본문 `div` (현재 `px-4 py-2.5 rounded-2xl ... break-words whitespace-pre-wrap`, 약 396행).
- 변경: `break-words` → `[overflow-wrap:anywhere]`.
  - `overflow-wrap: anywhere`는 `break-word`와 달리 **min-content를 실제로 축소**시켜 flex/max-width 계산이 정상 작동.
  - 일반 한글/공백 텍스트는 단어 경계로 자연 줄바꿈, 긴 URL·연속문자는 넘칠 때만 강제 분리.
  - `whitespace-pre-wrap`은 사용자 개행 보존을 위해 **유지**.
  - `word-break: break-all`은 채택 안 함(영어 단어 중간까지 항상 부숨 → 가독성 저하).

### 2. 스크롤 컨테이너 방어선

- 위치: 메시지 뷰피드 스크롤 `div` (현재 `flex-1 overflow-y-auto px-3 md:px-6 ...`, 약 338행).
- 변경: `overflow-x-clip` 추가. 미래에 어떤 자식이 넘쳐도 문서/레이아웃이 가로로 밀리지 않게 하는 안전망. `clip`은 스크롤 발생 없이 잘라내며 스크롤 앵커링에 영향 적음.

### 3. 답장 인용박스 정합 (부수)

- 위치: 부모 메시지 인용 `div` (약 388행). 이미 내용에 `truncate min-w-0`가 있어 대체로 안전하나, 버블 폭이 정상화되면 함께 정합됨. 별도 변경 없이 1·2번 효과에 포함. **구현 후 실측으로 확인**.

### 4. 헤더 / 입력창 / 타이핑바 — 좁은 폭 미세조정 (과잉수정 금지)

실측상 375px에서 헤더 아이콘 4개·입력창은 크게 터지지 않았음. 여백 정합 수준만 손봄:

- **타이핑바**(약 464행 `h-5 px-6`): `px-6` → `px-4 md:px-6` — 메시지 영역(`px-3 md:px-6`)과 좌우 여백 정합.
- **입력창 placeholder**(약 535행): 모바일에서 매우 긴 문자열이 잘리므로 짧게. 예: 기본은 짧은 안내("메시지를 입력하세요…"), 넓은 화면에서만 URL 안내 포함 — 또는 단순히 placeholder 자체를 짧게 교체. **구현 시 최소 변경으로**.
- 헤더·`+`/전송 버튼: 변경 없음(현행 유지). 미심쩍은 확장 지양.

## 성공 기준 (검증 계획 — 실제 E2E)

로컬 375px 뷰포트에서 3케이스 메시지를 전송 후:

- (a) 80자 무공백 영숫자, (b) 116자+ 긴 URL, (c) 긴 한글(공백 포함).
- **합격 조건**:
  - `document.documentElement.scrollWidth === 375`
  - 메시지 스크롤 컨테이너 `scrollWidth === clientWidth`
  - 모든 버블이 뷰포트 폭 안에서 줄바꿈되어 렌더 (화면 밖 잘림 없음)
- **데스크톱 1280px 회귀 확인**: `scrollerSW === clientWidth`, 버블 `max-w-3xl` 이내 유지, 레이아웃 육안 동일.
- 프론트 정적검증: `npm --prefix frontend run lint`(tsc) + `vite build` 통과.

## 구현/통합 방침

- 구현은 **Sonnet 서브에이전트**에 위임(설계·리뷰·검증은 상위 모델). 최소 변경범위·정합성·엣지케이스 준수.
- 브랜치 `fix/mobile-chat-overflow` → **develop PR**(실질 버그수정이므로 PR 유지).
- DB/백엔드 무변경(프론트 CSS만) → 배포 안전.

## 검증 결과 — 설계 정정 (2026-07-22, Opus)

**설계의 핵심 가정이 실측에서 틀렸다.** 실제 `ChatArea`를 프로덕션과 동일한 3중 flex 중첩
(`root(flex w-screen overflow-hidden) > div.flex.w-full > div.flex.w-full > ChatArea.flex-1`)
안에서 격리 하니스로 렌더해 E2E 재현·검증한 결과:

1. **`overflow-wrap:anywhere`는 필요하지만 충분하지 않다.** 이 변경만으로는 버블 텍스트가
   버블 내부에서 줄바꿈될 뿐, **메시지 영역 전체는 여전히 798px**로 뷰포트(375px)를 넘겨 잘렸다.
   설계가 예상한 "1번을 고치면 2·3번이 자연 정상화"는 이 레이아웃에서 성립하지 않았다.
2. **실제 원인은 flexbox `min-width:auto` 트랩.** `ChatArea` 루트가 바깥 flex-row의 `flex-1`
   아이템인데 `min-w-0`이 없어, 콘텐츠 min-content(798px) 아래로 축소되지 못했다.
   라이브 실험으로 **`ChatArea` 루트에 `min-w-0` 하나만** 추가하면 798→375로 붕괴됨을 확인
   (스크롤러에는 불필요). `min-w-0` 체인이 메시지 행·버블에는 있었으나 **최상위 아이템엔 빠져 있었다.**
3. 따라서 실제 수정 = **`overflow-wrap:anywhere`(텍스트 줄바꿈) + `ChatArea` 루트 `min-w-0`(축소 허용)**.
   `overflow-x-clip`은 무해한 안전망(스크롤러에서 `overflow-x:hidden`으로 계산됨).
   변경은 전부 `ChatArea.tsx` 내부에 머묾(App.tsx 무변경, 범위 유지).

**검증 수치(합격):** 375px — `document.scrollWidth===clientWidth===375`,
스크롤러 `scrollWidth===clientWidth===369`, 모든 행 `right≤357` (뷰포트 내). 1280px 회귀 —
`scrollerSW===clientWidth===1274`, 버블 `max-w-3xl(768px)` 이내(실측 653px). `tsc`·`vite build` exit 0.
