# 채팅 이미지 업로드 설계 (+ 버튼 · 드래그드롭)

**날짜:** 2026-07-22
**범위:** 채팅에서 이미지를 (1) 입력창 옆 **+ 버튼** 파일선택 (2) 채팅 영역 **드래그&드롭** 으로 올려 전송. 백엔드의 기존 `imageUrl` 필드를 실제 배선해 **URL 텍스트 없이 깔끔한 이미지 메시지**.

## 목표

- + 버튼 클릭 → 파일 선택 → 업로드 → 이미지 메시지 전송.
- 채팅 영역에 이미지 드래그드롭 → 업로드 → 전송. 드래그 중 시각 피드백.
- 이미지 메시지는 **긴 URL 텍스트 없이 썸네일만**(imageUrl 필드 사용).

## 비목표

- 여러 장 동시 업로드(v1은 1장). 이미지 캡션 입력(v1은 이미지 단독). 클라 리사이즈(향후 최적화). 이미지 답장(v1은 독립 전송).

## 현재 상태

- 업로드 API 존재: `uploadImage(token, file)` → `POST /api/images` → URL. (프로필 사진에서 사용, 413은 이미 10MB로 해결)
- 백엔드 `Message.imageUrl`·`MessageRequest.imageUrl`·`MessageResponse.imageUrl` **존재**. STOMP `ChatMessageController`가 `messageRequest.getImageUrl()`을 `create`에 이미 전달(STOMP엔 `@Valid` 없음).
- 프론트: 채팅 이미지는 **텍스트 URL 파싱**(`getEmbeddedImageUrl`)으로만 렌더. `BackendMessage`/`toMessage`/`Message`엔 `imageUrl` 없음. stomp `send`는 `{content, replyToId}`만.

## 핵심 결정

| # | 결정 | 이유 |
|---|------|------|
| 1 | 백엔드 `imageUrl` 필드 배선(텍스트 URL 방식 대체) | URL 텍스트 없는 깔끔한 이미지 메시지. 백엔드 필드 이미 있음. |
| 2 | 이미지 전용 메시지 허용(content 비어도 됨) | 이미지만 보낼 때 캡션 불필요. `content` `@NotBlank` 완화 + "content 또는 imageUrl 필수" 가드. |
| 3 | 업로드는 ChatArea가 담당(token 보유), 전송은 App | 로딩/에러 UX를 ChatArea 로컬로, 전송 권한은 App 일원화. |
| 4 | 기존 텍스트-URL 렌더(`getEmbeddedImageUrl`)는 **유지**(하위호환) | 예전 URL-붙여넣기 메시지도 계속 보임. imageUrl은 추가 경로. |

## 백엔드 (소폭)

### `MessageRequest`
- `content`의 `@NotBlank` **제거**(이미지 전용 허용). `imageUrl`·`replyToId` 그대로.

### `MessageService.create`
- 맨 앞에 가드 추가: `content`와 `imageUrl`이 **둘 다 비어있으면** `CustomException(ErrorCode.EMPTY_MESSAGE)`(신규 ErrorCode) 던짐. (빈 메시지 방지 — 프론트도 막지만 서버 방어)
- 나머지 로직(멤버·방·답장) 그대로.

### `ErrorCode`
- `EMPTY_MESSAGE`(400) 추가.

> STOMP·REST 두 경로 모두 `create`를 거치므로 가드 한 곳이면 충분. REST의 `@Valid`는 `@NotBlank` 제거로 이미지-전용 통과.

## 프론트엔드

### `types.ts`
- `Message`에 `imageUrl?: string` 추가.

### `lib/api.ts`
- `BackendMessage`에 `imageUrl?: string | null` 추가.
- `toMessage`: `imageUrl: message.imageUrl ?? undefined` 매핑.
- `sendMessage(token, chatroomId, content, replyToId?, imageUrl?)` — body에 `imageUrl` 포함.
- `uploadImage` 기존 재사용.

### `lib/stomp.ts`
- `send(chatroomId, content, replyToId?, imageUrl?)` — body `{ content, replyToId, imageUrl }`.

### `App.tsx`
- `handleSendMessage(text, replyToId?, imageUrl?)` — `send`/`sendMessage`에 `imageUrl` 전달.
- (이미지 전송은 `handleSendMessage('', undefined, imageUrl)` 형태로 ChatArea가 호출.)

### `components/ChatArea.tsx`
- **+ 버튼**: `<form>`의 **첫 요소(입력창 왼쪽)**에 + 버튼 + 숨긴 `<input type="file" accept="image/*">`. 클릭 → 파일선택 → `handleUpload(file)`. (전송 버튼은 오른쪽 그대로)
- **드래그드롭**: 메시지 영역(또는 ChatArea 루트)에 `onDragOver`(preventDefault + `dragging` state)·`onDragLeave`·`onDrop`(이미지 파일이면 `handleUpload`). `dragging` 시 오버레이("여기에 이미지를 놓으세요").
- **`handleUpload(file)`**: 이미지 타입 검사 → `setUploading(true)` → `uploadImage(token, file)` → `onSendImage(url)` → finally `setUploading(false)`. 실패 시 에러 표시.
- **로딩 표시**: `uploading` 중 + 버튼/입력영역에 스피너.
- **prop 추가**: `onSendImage: (imageUrl: string) => void` (App이 `handleSendMessage('', undefined, imageUrl)`로 배선).
- **렌더**: 메시지 버블에서 `msg.imageUrl` 있으면 썸네일(기존 `imageUrl`(텍스트파싱) 렌더 블록 재사용/확장). `content`가 비면 텍스트 줄 생략하고 이미지만.
- 이미지 파일만 허용(비이미지 드롭/선택 무시).

## 검증

1. 백엔드 `./gradlew build`(가드 추가 후 기존 테스트 통과 — content 비어도 imageUrl 있으면 OK).
2. **E2E**: `/api/images`에 이미지 업로드 → URL → STOMP로 `{content:'', imageUrl}` 전송 → 방송된 `MessageResponse.imageUrl` 확인. + content·imageUrl 둘 다 비면 거부(EMPTY_MESSAGE).
3. 프론트 `tsc` + `vite build` + (가능하면) 로컬 육안: + 버튼·드래그드롭·이미지 렌더.

## 배포 영향

- **스키마 변경 없음**(imageUrl 컬럼 이미 존재). 배포 안전.

## 향후

- 클라 리사이즈(용량↓), 다중 업로드, 이미지 캡션, 이미지+답장.
