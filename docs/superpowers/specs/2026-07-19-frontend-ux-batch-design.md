# 프론트 UX 배치 설계 (채널 선택 랜딩 + 빠른 수정)

**작성일:** 2026-07-19
**브랜치:** `feat/frontend-ux-batch` → develop PR
**범위:** 프론트엔드 전용 (백엔드 변경 없음, 기존 엔드포인트만 사용)

## Goal

로그인 직후 채팅창이 곧바로 튀어나오는 어색한 흐름을 없애고, **우표 갤러리 형태의 채널 선택 랜딩**을 도입한다. 더불어 그동안 누적된 프론트 UX 버그·미완 기능(로그인 에러 노출, 설정, 상대 프로필, 참가자 목록)을 함께 정리한다.

## 비범위 (다음 버킷)

- **B (모바일 반응형)**: 전 화면 반응형은 별도. 단, 이번에 만드는 랜딩은 좁은 화면에서 **깨지지 않을 정도**(열/우표 수 축소)까지만 대응한다.
- **C (백엔드)**: 답장 기능, 채팅방 이름 한국어(charset), 이모지 반응.
- **D**: OAuth.

## Global Constraints

- **프론트엔드만** 수정. 백엔드 스키마/엔드포인트 변경 없음.
- 유닛 테스트 없음 → 검증은 dev 서버(preview)에서 (1) 무오류 렌더, (2) 각 플로우 수동 확인, (3) `npm --prefix frontend run lint`(tsc) 통과.
- 색은 세이지 시맨틱 토큰 경유(`index.css`). 랜딩은 인트로처럼 **다크 고정**(라이트/다크 무관).
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

## 확정된 설계 결정

1. **홈 복귀 네비게이션**: 채팅 화면의 사이드바 채널 목록은 유지(빠른 전환용). Sage 로고/홈 클릭 시 `selectedChannelId=''`로 만들어 랜딩으로 복귀.
2. **우표 정보량**: 우표에는 **아이콘 + 채널명만**. 참여자 수(N명)는 표시하지 않음(`GET /chatrooms`에 인원 필드 없음). 인원은 채팅 내부 참가자 목록에서 확인.
3. **다크모드 토글**: 설정 모달에서 제거. 사이드바 토글은 유지 → 다크모드 기능 자체는 존치.

---

## Part A′ — 채널 선택 랜딩 (우표 갤러리)

### 신규 컴포넌트: `ChannelLanding.tsx`
- **렌더 조건**: 로그인 상태(`user` 있음) **AND** `selectedChannelId === ''`. (채널 선택 시 채팅 뷰로 교체)
- **레이아웃**:
  - 다크 세이지 보드(`bg-bg` 다크), 상단 바: 좌측 `Sage` 로고, 우측 **"채널 만들기"** 버튼(sage, `+` 아이콘).
  - 본문: 방 목록(`channels`)을 **우표 카드로 흩뿌려 배치**. 위치는 index로부터 결정적으로 계산(살짝씩 다른 회전각). 자동 스크롤 없음 — 정적 배치, 방이 많아 넘치면 보드가 스크롤.
- **우표 카드**:
  - 밝은 종이색 카드 + **톱니(perforation) 가장자리** — CSS mask 기법:
    ```
    -webkit-mask:
      linear-gradient(#000 0 0) 50%/calc(100% - 11px) calc(100% - 11px) no-repeat,
      radial-gradient(circle 5px,#000 96%,#0000) 0 0/12px 12px round;
    -webkit-mask-composite:source-over;  mask-composite:add;
    ```
  - 내용: 아이콘 + 채널명. 아이콘은 채널 id 해시로 결정적 선택(시각적 다양성, 순수 프론트). 일부 카드는 옅은 세이지 틴트로 변주(결정적).
  - **hover**: 회전 0으로 펴지며 `scale(1.1)`, z-index 상승, 커서 pointer.
- **채널 만들기**: 우상단 버튼 → 작은 입력 다이얼로그(채널명) → `POST /api/chatrooms` → 목록 새로고침 → 새 우표 등장.
- **빈 상태**: 방이 하나도 없으면 중앙에 "첫 채널을 만들어보세요" + 만들기 버튼.

### 입장 전환
- 우표 클릭 → `selectedChannelId` 설정(기존 `joinChatRoom` + 메시지 로드 로직 그대로 탐, `App.tsx` 기존 effect 재사용).
- 연출: 클릭한 우표가 중앙으로 **확대**되고 채팅 뷰가 그 안에서 **스르륵(fade/scale-in)**. 기존 `warping` state 패턴을 채널 진입용으로 재사용(전환 중 잠깐 오버레이).

### 데이터/API (기존)
- `GET /api/chatrooms` (목록), `POST /api/chatrooms` (생성), 진입 시 기존 `joinChatRoom`/`getMessages`.

---

## Part A — 빠른 수정

### A1. 로그인 직후 자동 입장 제거
- `App.tsx`의 방 목록 로드부(현재 `setSelectedChannelId((current) => current || mappedRooms[0].id)`)에서 **첫 방 자동선택 제거** → 로그인 후 `selectedChannelId`는 `''` 유지 → 랜딩(A′) 표시.
- 부수효과 정리: 자동선택이 사라지므로 로그인만으로 임의의 방에 join되던 문제 해소.

### A2. 로그인 에러 raw JSON 노출 수정
- 현재 로그인 실패 시 `{"message":"비밀번호가 일치하지 않습니다."}` 원문 노출.
- API 에러 처리에서 응답 본문을 파싱해 **`message` 문자열만** 추출, 실패 시 일반 메시지("로그인에 실패했습니다") 폴백. 로그인 폼(`Welcome.tsx`)은 그 문자열만 렌더.

### A3. 설정 모달 정리 (`SettingsModal.tsx`)
- **다크모드 토글 제거**(설정에서만). 사이드바 테마 토글은 유지.
- **프로필 사진 수정 복구**: 이미지 선택 → `POST /api/images`(multipart) → 받은 URL로 `PATCH /api/members/me/profile-image` → 로컬 `user` 갱신(App `setUser`).

### A4. 상대 프로필 조회
- 메시지의 아바타/이름 클릭 → **프로필 모달**(신규 `ProfileModal.tsx` 또는 기존 모달 재사용).
- `GET /api/members/{id}`로 이름·프로필 이미지(가능 시 가입일) 표시. 읽기 전용.

### A5. 채널 참가자 목록 표시
- 채팅 헤더에 참가자 버튼/패널 → `GET /api/chatrooms/{chatroomId}/members` → 이름·아바타 목록(정적).
- "온라인 여부"는 presence(백엔드) 필요 → 이번 범위 밖. 지금은 참여자 명단만.

---

## 컴포넌트 영향 요약

| 파일 | 변경 |
|---|---|
| `App.tsx` | 자동선택 제거(A1), 랜딩/채팅 분기(A′), 홈복귀, 채널진입 전환 state |
| `ChannelLanding.tsx` (신규) | 우표 갤러리 랜딩 |
| `ProfileModal.tsx` (신규) | 상대 프로필 조회(A4) |
| `Welcome.tsx` | 로그인 에러 메시지 파싱(A2) |
| `SettingsModal.tsx` | 다크모드 제거 + 프로필 사진 수정 복구(A3) |
| `ChatArea.tsx` / `Sidebar.tsx` | 참가자 목록 진입점(A5), 아바타 클릭→프로필(A4), 홈복귀 트리거 |
| `lib/api.ts` | 에러 파싱 헬퍼(A2), 필요 시 members/{id} 래퍼 |

## 검증 (Definition of Done)

- 로그인 → **랜딩(우표 갤러리)** 표시, 자동 입장 없음.
- 우표 클릭 → 전환 연출 후 해당 방 채팅 진입. 로고/홈 → 랜딩 복귀.
- "채널 만들기" → 새 방 생성 후 우표로 나타남.
- 로그인 실패 → 깔끔한 메시지(“비밀번호가 일치하지 않습니다.”)만 노출, JSON 없음.
- 설정: 다크모드 토글 없음, 프로필 사진 변경 동작(업로드→반영).
- 메시지 아바타 클릭 → 상대 프로필 모달.
- 채팅에서 참가자 명단 확인 가능.
- `npm --prefix frontend run lint` 통과, dev 렌더 무오류.
