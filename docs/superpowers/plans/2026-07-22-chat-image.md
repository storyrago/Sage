# 채팅 이미지 업로드 Implementation Plan

> **실행:** 계획/리뷰=Opus, 구현=Sonnet. 참고 코드는 "이 방향". 체크박스로 추적.

**Goal:** + 버튼·드래그드롭으로 이미지를 올려 `imageUrl` 필드로 전송(깔끔한 이미지 메시지).

**Architecture:** 백엔드 `imageUrl` 필드 배선(이미 존재) + content 비어도 imageUrl 있으면 허용. 프론트: ChatArea가 업로드(`uploadImage`) → App이 `imageUrl`로 전송(STOMP/REST). 렌더는 `msg.imageUrl` 썸네일.

**Tech Stack:** Spring Boot, React+TS, 기존 `/api/images` 업로드.

## Global Constraints

- **스키마 변경 없음**(imageUrl 컬럼 이미 존재). 배포 안전.
- 이미지 메시지 = `content:""` + `imageUrl`. content·imageUrl 둘 다 비면 거부.
- v1: 1장, 이미지 전용(캡션·다중·리사이즈·이미지답장 제외).
- 기존 텍스트-URL 렌더(`getEmbeddedImageUrl`)는 유지(하위호환).

---

### Task 1: 백엔드 — 이미지 전용 메시지 허용

**Files:**
- Modify: `dto/MessageRequest.java`
- Modify: `global/exception/ErrorCode.java`
- Modify: `service/MessageService.java`

- [ ] **Step 1: MessageRequest content @NotBlank 제거**
`dto/MessageRequest.java` 교체(이미지 전용 허용, 미사용 import 정리):
```java
package com.example.springboot_realtimechat.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MessageRequest {
    String content;
    String imageUrl;
    Long replyToId;
}
```

- [ ] **Step 2: ErrorCode에 EMPTY_MESSAGE 추가**
`global/exception/ErrorCode.java`의 `MESSAGE_NOT_FOUND`/`NOT_MESSAGE_OWNER` 근처에 추가:
```java
    EMPTY_MESSAGE(400, "내용 또는 이미지가 필요합니다."),
```

- [ ] **Step 3: MessageService.create에 빈 메시지 가드**
`create` 본문 맨 앞(멤버 조회 전)에 추가:
```java
        if ((content == null || content.isBlank()) && (imageUrl == null || imageUrl.isBlank())) {
            throw new CustomException(ErrorCode.EMPTY_MESSAGE);
        }
```
(이후 기존 로직 그대로. `CustomException`·`ErrorCode`는 이미 import돼 있음.)

- [ ] **Step 4: 빌드**
Run: `./gradlew build` → BUILD SUCCESSFUL. (기존 테스트: content 있는 메시지라 통과.)

- [ ] **Step 5: 커밋**
```bash
git add src/main/java/com/example/springboot_realtimechat/dto/MessageRequest.java \
        src/main/java/com/example/springboot_realtimechat/global/exception/ErrorCode.java \
        src/main/java/com/example/springboot_realtimechat/service/MessageService.java
git commit -m "feat(chat-image): 이미지 전용 메시지 허용(content|imageUrl 가드)"
```

---

### Task 2: 프론트 플러밍 — imageUrl 배선(types/api/stomp)

**Files:**
- Modify: `frontend/src/types.ts`
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/lib/stomp.ts`

- [ ] **Step 1: types.ts — Message.imageUrl**
`Message` 인터페이스에 추가(`replyToId` 아래):
```ts
  imageUrl?: string; // 업로드된 이미지 URL
```

- [ ] **Step 2: api.ts — BackendMessage.imageUrl + toMessage + sendMessage**
`BackendMessage`에 추가:
```ts
  imageUrl?: string | null;
```
`toMessage` 반환에 추가:
```ts
    replyToId: message.replyToId != null ? String(message.replyToId) : undefined,
    imageUrl: message.imageUrl ?? undefined,
  };
```
`sendMessage` 교체(imageUrl 인자·body):
```ts
export async function sendMessage(token: string, chatroomId: string, content: string, replyToId?: string, imageUrl?: string) {
  return request<BackendMessage>(`/api/chatrooms/${chatroomId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ content, replyToId: replyToId ? Number(replyToId) : null, imageUrl: imageUrl ?? null }),
  }, token);
}
```

- [ ] **Step 3: stomp.ts — send에 imageUrl**
`send` 교체:
```ts
  send(chatroomId: string, content: string, replyToId?: string, imageUrl?: string) {
    if (!this.connected) return false;
    this.write('SEND', {
      destination: `/pub/chatrooms/${chatroomId}/messages`,
      'content-type': 'application/json',
    }, JSON.stringify({ content, replyToId: replyToId ? Number(replyToId) : null, imageUrl: imageUrl ?? null }));
    return true;
  }
```

- [ ] **Step 4: tsc + build**
Run: `npm --prefix frontend run lint && npm --prefix frontend run build` → 통과.

- [ ] **Step 5: 커밋**
```bash
git add frontend/src/types.ts frontend/src/lib/api.ts frontend/src/lib/stomp.ts
git commit -m "feat(chat-image): 프론트 imageUrl 배선(types/api/stomp)"
```

---

### Task 3: 프론트 UI — ChatArea +버튼·드래그드롭·렌더 + App 배선

**Files:**
- Modify: `frontend/src/components/ChatArea.tsx`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: ChatArea import — 아이콘 + uploadImage**
lucide import에 `Plus, Loader2` 추가. 상단 import에 `uploadImage` 추가:
```ts
import { getRoomMemberProfiles, BackendMember, uploadImage } from '../lib/api';
```

- [ ] **Step 2: props + state + 업로드 핸들러**
`ChatAreaProps`에 추가:
```ts
  onSendImage: (imageUrl: string) => void;
```
구조분해에 `onSendImage` 추가. state·ref 추가(`inputText` 근처):
```ts
  const [uploading, setUploading] = useState(false);
  const [dragging, setDragging] = useState(false);
  const imageInputRef = useRef<HTMLInputElement>(null);
```
핸들러 추가(`handleSend` 근처):
```ts
  const handleUpload = async (file: File) => {
    if (!file.type.startsWith('image/')) return;   // 이미지만
    setUploading(true);
    try {
      const url = await uploadImage(token, file);
      onSendImage(url);
    } catch (err) {
      console.error('이미지 업로드 실패', err);
    } finally {
      setUploading(false);
    }
  };
```

- [ ] **Step 3: 드래그드롭 — 루트에 핸들러 + 오버레이**
ChatArea 루트 `<div className="flex-1 h-full flex flex-col bg-bg font-sans relative">`에 드래그 핸들러 추가:
```tsx
    <div
      className="flex-1 h-full flex flex-col bg-bg font-sans relative"
      onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
      onDragLeave={(e) => { if (e.currentTarget === e.target) setDragging(false); }}
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        const file = e.dataTransfer.files?.[0];
        if (file) handleUpload(file);
      }}
    >
      {dragging && (
        <div className="absolute inset-0 z-40 bg-accent-subtle/90 border-2 border-dashed border-accent flex items-center justify-center pointer-events-none">
          <div className="text-accent-text font-bold text-lg select-none">여기에 이미지를 놓으세요 🖼️</div>
        </div>
      )}
      {/* ...기존 내용... */}
```
(닫는 태그는 그대로. 오버레이는 `pointer-events-none`이라 드롭 이벤트가 루트로 감.)

- [ ] **Step 4: + 버튼 — 폼 첫 요소 + 숨긴 file input**
`<form onSubmit={handleSend} className="flex gap-2">` → `className="flex gap-2 items-stretch"`로 두고, `<form>` 바로 안 맨 앞에 삽입:
```tsx
          <input
            ref={imageInputRef}
            type="file"
            accept="image/*"
            hidden
            onChange={(e) => { const f = e.target.files?.[0]; if (f) handleUpload(f); e.target.value = ''; }}
          />
          <button
            type="button"
            onClick={() => imageInputRef.current?.click()}
            disabled={uploading}
            title="이미지 첨부"
            className="w-11 flex-shrink-0 rounded-2xl border border-border bg-surface-2 text-muted hover:text-accent-text hover:border-accent flex items-center justify-center cursor-pointer transition-all disabled:opacity-40"
          >
            {uploading ? <Loader2 className="w-5 h-5 animate-spin" /> : <Plus className="w-5 h-5" />}
          </button>
```

- [ ] **Step 5: 렌더 — msg.imageUrl 썸네일 + 빈 텍스트 처리**
버블 본문(`{msg.text}` + 기존 imageUrl 블록)을 교체:
```tsx
                  {msg.text && <span>{msg.text}</span>}

                  {/* 업로드된 이미지 (imageUrl 필드) */}
                  {msg.imageUrl && (
                    <div className={`${msg.text ? 'mt-2.5' : ''} rounded-xl overflow-hidden border border-border bg-bg`}>
                      <img
                        src={msg.imageUrl}
                        alt="첨부 이미지"
                        referrerPolicy="no-referrer"
                        className="max-h-60 w-full object-cover cursor-pointer hover:opacity-95 transition-opacity"
                        onClick={() => window.open(msg.imageUrl, '_blank')}
                      />
                    </div>
                  )}

                  {/* 텍스트에 박힌 URL 이미지 (하위호환) */}
                  {imageUrl && (
                    <div className="mt-2.5 rounded-xl overflow-hidden border border-border bg-bg">
                      <img
                        src={imageUrl}
                        alt="Shared interactive link"
                        referrerPolicy="no-referrer"
                        className="max-h-60 w-full object-cover cursor-pointer hover:opacity-95 transition-opacity"
                        onClick={() => window.open(imageUrl, '_blank')}
                      />
                    </div>
                  )}
```

- [ ] **Step 6: App.tsx — handleSendMessage imageUrl + onSendImage 전달**
`handleSendMessage` 교체:
```ts
  const handleSendMessage = async (text: string, replyToId?: string, imageUrl?: string) => {
    if (!token || !selectedChannelId) return;

    const sentOverStomp = stompRef.current?.send(selectedChannelId, text, replyToId, imageUrl) ?? false;
    if (!sentOverStomp) {
      const saved = await sendMessage(token, selectedChannelId, text, replyToId, imageUrl);
      const nextMessage = toMessage(saved);
      setMessages((prev) => [...prev, nextMessage]);
    }
  };
```
`<ChatArea ... />`에 prop 추가:
```tsx
              onSendImage={(url) => handleSendMessage('', undefined, url)}
```

- [ ] **Step 7: tsc + build**
Run: `npm --prefix frontend run lint && npm --prefix frontend run build` → 통과.

- [ ] **Step 8: 커밋**
```bash
git add frontend/src/components/ChatArea.tsx frontend/src/App.tsx
git commit -m "feat(chat-image): ChatArea +버튼·드래그드롭·이미지렌더 + App 배선"
```

---

### Task 4: E2E + 육안

- [ ] **Step 1: 백엔드 기동**(8080, JWT_SECRET, ddl-auto=update).

- [ ] **Step 2: 이미지 업로드+전송 E2E** `scratchpad/image_e2e.mjs`:
  - 로그인 → 방 생성·입장.
  - `POST /api/images`에 작은 이미지(멀티파트) 업로드 → URL.
  - REST `POST /api/chatrooms/{id}/messages` `{content:'', imageUrl:url}` → 응답 `.imageUrl == url` 확인.
  - `{content:'', imageUrl:''}`(둘 다 빈) → 400 EMPTY_MESSAGE 확인.
  - (멀티파트 업로드는 Node `FormData`+`Blob` 사용.)
  Run: `node scratchpad/image_e2e.mjs` → 통과.

- [ ] **Step 3: (가능하면) 로컬 육안** — +버튼 파일선택·드래그드롭·이미지 렌더.

- [ ] **Step 4: 정리** — 백엔드 종료.

---

## Self-Review 결과

- **스펙 커버리지:** content 완화+가드(Task1) / imageUrl types·api·stomp(Task2) / +버튼·드래그드롭·업로드·렌더·App(Task3) / E2E(Task4) — 전 항목 매핑. 이미지전용·하위호환·1장 반영.
- **Placeholder:** 없음. Task4 스크립트는 개요+검증항목(멀티파트 업로드).
- **타입 일관성:** `imageUrl`(백엔드 String, 프론트 string|undefined, 와이어 `imageUrl ?? null`) · `send`/`sendMessage`/`handleSendMessage`(…, imageUrl?) · `onSendImage(url)` · `msg.imageUrl` 렌더 · `EMPTY_MESSAGE` — 일치.
