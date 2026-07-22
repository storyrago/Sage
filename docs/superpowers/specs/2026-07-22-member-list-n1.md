# 참가자 목록 N+1 제거 — 설계·검증

- 날짜: 2026-07-22
- 브랜치: `feat/member-list-n1` (develop 분기)
- 범위: 채팅 참가자 패널 로딩의 **1+N 네트워크 왕복**을 단일 요청으로.

## 문제

참가자 패널을 열면 프론트 `getRoomMemberProfiles`가:
1. `GET /api/chatrooms/{id}/members` — 명단(`{id, memberId, chatRoomId}`, **이름·사진 없음**) 1회, 그리고
2. 멤버 수만큼 `GET /api/members/{memberId}` — 프로필 개별 조회.

→ N명이면 **1 + N 요청**. 백엔드 DTO에 이름이 없어 생긴 구조적 N+1(네트워크 왕복이라 DB N+1보다 체감 큼).

## 결정

- 명단 응답(`ChatRoomMemberResponse`)에 **`nickname` + `profileImageUrl` 포함** → 프론트는 **단일 요청**으로 매핑.
- 이름을 넣으면 백엔드 `from()`이 LAZY `member`를 건드려 **백엔드 N+1이 새로 생기므로**, 리포지토리 조회에 **`JOIN FETCH cm.member`** 적용(한 쿼리로 멤버까지 로드).
- **이메일은 노출 안 함**(프라이버시). 프론트 매핑에서 `email: ''` 자리만 채움(참가자 패널은 email 미사용).

## 구현 (최소 변경)

- `ChatRoomMemberRepository.findByChatRoom` → `@Query("... JOIN FETCH cm.member ...")` (메서드명 유지 → 서비스 호출 무변경).
- `ChatRoomMemberResponse`에 `nickname`·`profileImageUrl` 필드 + `from()`이 `member.getNickname()/getProfileImageUrl()` 매핑.
- 프론트 `BackendChatRoomMember`에 두 필드 추가, `getRoomMemberProfiles`가 `getMemberById` 반복(Promise.all) 제거하고 명단을 직접 매핑.

## 검증 (실측)

- **백엔드** — 신규 `ChatRoomMemberN1Test`(`@SpringBootTest`/H2): 2명 가입 → `getChatRoomMembersById` → `from()` 매핑 결과에 닉네임·memberId가 정확히 담기는지. `./gradlew test` 전체 통과.
- **프론트** — 격리 하니스에서 `fetch`를 가로채 호출 URL을 기록. 참가자 패널을 열었을 때 **총 요청 1회**(`/chatrooms/1/members`), **멤버별 `/members/{id}` 0회** → `N1_ELIMINATED: true`. 패널에 이름·온라인표시 정상 렌더. `tsc`·`vite build` exit 0.
  - 효과: 20명 방 기준 **21요청 → 1요청**.

## 범위 밖

- 실시간 presence(온라인 표시)는 기존 그대로. 이번 변경은 정적 명단 로딩만.
