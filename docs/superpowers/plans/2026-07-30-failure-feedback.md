# 실패 알림 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 감사에서 드러난 실패 무음 6곳을 사용자에게 보이게 하고, 메시지 전송 실패 시 입력 내용이 유실되지 않게 한다.

**Architecture:** 공용 토스트를 하나 만들되(App이 상태 보유), 폼·패널을 보고 있는 실패는 그 자리에 인라인으로 표시한다. 메시지 전송은 `onSendMessage`가 `Promise`를 반환하게 바꿔 `ChatArea`가 실패를 받아 입력 내용을 복원한다.

**Tech Stack:** React + TypeScript (Vite), `motion/react`(이미 사용 중)

설계 문서: `docs/superpowers/specs/2026-07-30-failure-feedback-design.md`

## Global Constraints

- 브랜치는 `feat/failure-feedback`(이미 존재, `origin/develop`에서 분기). PR 대상은 **develop**.
- **백엔드(`src/`)를 변경하지 않는다. 스키마 변경도 없다.**
- 프론트에 유닛 테스트 러너가 없다. 검증 명령은 `cd frontend && npm run lint && npm run build` 두 개뿐이다.
- **실패를 다른 정상 상태로 위장하지 않는다.** 빈 목록·빈 화면으로 대체하는 처리를 새로 만들지 않는다.
- 토스트는 **동시에 하나만** 띄운다. 큐를 만들지 않는다.
- 커밋 메시지·코드 주석은 변경의 목적만 쓴다. "누락/핫픽스/깨져 있었다" 같은 배경 서사 금지.
- 오류 문구는 사용자 말로 쓴다. 예외 메시지를 그대로 노출하되, 없으면 정해진 한국어 문구로 대체한다.

## File Structure

| 파일 | 책임 | 작업 |
|---|---|---|
| `frontend/src/components/Toast.tsx` | 하단 중앙 알림 하나. 자동 소멸·클릭 닫기 | 생성 |
| `frontend/src/App.tsx` | 토스트 상태·렌더, 방 입장/메시지 로드 분기, 이전 메시지 오류, `alert()` 제거 | 수정 |
| `frontend/src/components/ChatArea.tsx` | 전송 실패 시 입력 복원, 참가자 목록 오류, 업로드 실패 알림 | 수정 |
| `frontend/src/components/ChannelLanding.tsx` | 채널 생성 실패 인라인 오류 | 수정 |

---

## Task 1: 토스트 기반과 기존 `alert()` 교체

**Files:**
- Create: `frontend/src/components/Toast.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Produces:
  - `Toast({ toast, onClose }: { toast: { id: number; text: string } | null; onClose: () => void })`
  - App 내부의 `notify(text: string): void` — 이후 Task들이 이 함수를 쓴다

- [ ] **Step 1: 토스트 컴포넌트를 만든다**

`frontend/src/components/Toast.tsx`:

```tsx
import { useEffect } from 'react';
import { motion, AnimatePresence } from 'motion/react';

interface ToastProps {
  toast: { id: number; text: string } | null;
  onClose: () => void;
}

// 실패를 알리는 단일 알림. 같은 문구가 연달아 발생해도 다시 뜨도록 id로 구분한다.
export default function Toast({ toast, onClose }: ToastProps) {
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(onClose, 4000);
    return () => clearTimeout(timer);
  }, [toast?.id, onClose]);

  return (
    <AnimatePresence>
      {toast && (
        <motion.button
          key={toast.id}
          type="button"
          role="alert"
          onClick={onClose}
          initial={{ y: 20, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          exit={{ y: 20, opacity: 0 }}
          className="fixed bottom-6 left-1/2 -translate-x-1/2 z-[70] max-w-[min(560px,calc(100vw-32px))] rounded-xl bg-rose-600 px-4 py-3 text-left text-[13px] font-semibold text-white shadow-2xl cursor-pointer"
        >
          {toast.text}
        </motion.button>
      )}
    </AnimatePresence>
  );
}
```

`bottom-6`은 채팅 입력창과 겹치지 않도록 화면 하단에서 띄운 값이다. 실패 색은 기존 재연결 배너(`bg-rose-600`)와 같은 팔레트를 쓴다.

- [ ] **Step 2: App에 토스트 상태와 `notify`를 넣는다**

`App.tsx` 상단 import에 추가한다:

```tsx
import Toast from './components/Toast';
```

상태 선언부(`const [oauthError, ...]` 아래)에 추가한다:

```tsx
  const [toast, setToast] = useState<{ id: number; text: string } | null>(null);
```

`stompRef` 등 ref 선언부에 추가한다:

```tsx
  const toastIdRef = useRef(0);
```

`persistSession` 정의 위에 추가한다:

```tsx
  // 실패를 사용자에게 알린다. 같은 문구가 연달아 나도 다시 보이도록 id를 증가시킨다.
  const notify = useCallback((text: string) => {
    toastIdRef.current += 1;
    setToast({ id: toastIdRef.current, text });
  }, []);
```

- [ ] **Step 3: 토스트를 렌더한다**

`App.tsx`의 반환 JSX에서 `<ProfileModal ... />` **바로 아래**(같은 부모 안, 닫는 `</div>` 앞)에 추가한다:

```tsx
      <Toast toast={toast} onClose={() => setToast(null)} />
```

- [ ] **Step 4: 기존 `alert()` 두 곳을 토스트로 바꾼다**

`handleEditMessage`의 catch를 아래로 교체한다:

```tsx
    } catch (error) {
      notify(error instanceof Error ? error.message : '메시지 수정에 실패했어요.');
    }
```

`handleDeleteMessage`의 catch를 아래로 교체한다:

```tsx
    } catch (error) {
      notify(error instanceof Error ? error.message : '메시지 삭제에 실패했어요.');
    }
```

`console.error`와 `alert` 호출을 모두 지운다. 실패가 화면에 보이므로 콘솔 로그는 중복이다.

- [ ] **Step 5: 검증**

Run: `cd frontend && npm run lint`
Expected: 에러 없음.

Run: `cd frontend && npm run build`
Expected: 성공.

Run: `grep -rn "alert(" frontend/src`
Expected: 출력 없음.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/Toast.tsx frontend/src/App.tsx
git commit -m "feat(ui): 실패 알림 토스트 추가"
```

---

## Task 2: 메시지 전송 실패 시 입력 복원

**Files:**
- Modify: `frontend/src/App.tsx` (`handleSendMessage`, `ChatArea` 사용부)
- Modify: `frontend/src/components/ChatArea.tsx` (props, `handleSend`, `handleUpload`)

**Interfaces:**
- Consumes: Task 1의 `notify`
- Produces: `ChatArea`의 prop 타입 변경
  - `onSendMessage: (text: string, replyToId?: string) => Promise<void>`
  - `onSendImage: (imageUrl: string) => Promise<void>`
  - `onNotify: (text: string) => void` (신규)

- [ ] **Step 1: `ChatArea` prop 타입을 바꾼다**

`ChatArea.tsx`의 props 인터페이스에서 두 줄을 교체하고 한 줄을 추가한다:

```tsx
  onSendMessage: (text: string, replyToId?: string) => Promise<void>;
  onSendImage: (imageUrl: string) => Promise<void>;
  onNotify: (text: string) => void;
```

컴포넌트 시그니처의 구조분해에도 `onNotify`를 추가한다.

- [ ] **Step 2: 전송 실패 시 입력을 복원한다**

`ChatArea.tsx`의 상태 선언부에 추가한다(`inputText` 근처):

```tsx
  const [sendError, setSendError] = useState('');
```

`handleSend`를 아래로 교체한다:

```tsx
  const handleSend = async (e: FormEvent) => {
    e.preventDefault();
    const cleanText = inputText.trim();
    if (!cleanText) return;

    setSendError('');
    setInputText('');

    if (typingTimeoutRef.current) {
      clearTimeout(typingTimeoutRef.current);
    }
    onTypeStateChange(false);

    if (editingMessage) {
      onEditMessage?.(editingMessage.id, cleanText);
      setEditingMessage(null);
      return;
    }

    const replyToId = replyMessage?.id;
    setReplyMessage(null);
    try {
      await onSendMessage(cleanText, replyToId);
    } catch (err) {
      // 전송은 낙관적 렌더가 아니라 실패하면 화면에 아무것도 남지 않는다.
      // 입력 내용을 되돌려 사용자가 그대로 다시 보낼 수 있게 한다.
      setInputText(cleanText);
      setSendError(err instanceof Error ? err.message : '메시지를 보내지 못했어요. 다시 시도해 주세요.');
    }
  };
```

수정(`editingMessage`) 경로는 `onEditMessage`가 자체적으로 토스트를 띄우므로 여기서 다루지 않는다.

- [ ] **Step 3: 입력창 위에 오류를 표시한다**

입력 폼(`<form onSubmit={handleSend} ...>`)의 **바로 안쪽 첫 줄**에 추가한다:

```tsx
          {sendError && (
            <p className="mb-2 text-[12px] text-rose-400">{sendError}</p>
          )}
```

- [ ] **Step 4: 업로드 실패를 알린다**

`handleUpload`의 catch를 아래로 교체한다:

```tsx
    } catch (err) {
      onNotify(err instanceof Error ? err.message : '이미지를 보내지 못했어요.');
    } finally {
```

`console.error` 호출은 지운다. `onSendImage`가 이제 `Promise`를 반환하므로 `await onSendImage(url);`로 바꿔 전송 실패까지 같은 catch가 잡게 한다.

- [ ] **Step 5: App에서 새 prop을 넘긴다**

`App.tsx`의 `<ChatArea ... />` 사용부에 추가한다:

```tsx
              onNotify={notify}
```

`onSendImage`는 이미 `(url) => handleSendMessage('', undefined, url)`로 `Promise`를 반환하므로 그대로 둔다. `handleSendMessage`도 이미 실패 시 예외를 그대로 전파하므로 바꾸지 않는다.

- [ ] **Step 6: 검증**

Run: `cd frontend && npm run lint`
Expected: 에러 없음. prop 타입이 바뀌었으므로 넘기는 쪽과 받는 쪽이 어긋나면 여기서 잡힌다.

Run: `cd frontend && npm run build`
Expected: 성공.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/App.tsx frontend/src/components/ChatArea.tsx
git commit -m "feat(chat): 전송 실패 시 입력 내용 복원과 오류 표시"
```

---

## Task 3: 방 입장·메시지 로드 실패 처리

**Files:**
- Modify: `frontend/src/App.tsx` (`loadMessages` effect, `loadOlderMessages`)

**Interfaces:**
- Consumes: Task 1의 `notify`

- [ ] **Step 1: 입장 실패와 조회 실패를 나눈다**

`App.tsx`의 `loadMessages` 함수에서, `try` 블록 맨 앞의 `await joinChatRoom(...)` 한 줄을 밖으로 빼내 별도 `try/catch`로 감싼다. 함수 앞부분을 아래로 교체한다:

```tsx
    async function loadMessages() {
      setLoadingMessage('메시지를 불러오는 중입니다.');

      try {
        await joinChatRoom(token, selectedChannelId);
      } catch (error) {
        // 방에 못 들어갔으므로 채팅 화면에 남을 이유가 없다. 랜딩으로 되돌린다.
        if (!cancelled) {
          notify(error instanceof Error ? error.message : '채널에 입장하지 못했어요.');
          setSelectedChannelId('');
        }
        return;
      }

      try {
        const page = await getMessages(token, selectedChannelId);
```

그리고 기존 `catch` 블록을 아래로 교체한다(입장은 이미 성공했으므로 화면을 유지한다):

```tsx
      } catch (error) {
        // 입장은 성공했으므로 채팅 화면에 남는다.
        if (!cancelled) {
          notify(error instanceof Error ? error.message : '메시지를 불러오지 못했어요.');
        }
      }
```

`try` 블록 안에 남는 것은 `getMessages` 이후의 기존 처리 전부다. `setLoadingMessage` 호출은 두 `try` 앞으로 옮겼으므로 안에서 중복 호출하지 않는다.

- [ ] **Step 2: effect 의존성에 `notify`를 추가한다**

이 effect의 의존성 배열에 `notify`를 넣는다. `notify`는 `useCallback(..., [])`이라 안정적이므로 재실행을 유발하지 않는다.

- [ ] **Step 3: 이전 메시지 로드 실패를 알린다**

`loadOlderMessages`의 catch를 아래로 교체한다:

```tsx
    } catch (error) {
      notify(error instanceof Error ? error.message : '이전 메시지를 불러오지 못했어요.');
      setPageState((prev) => ({ ...prev, [roomId]: { ...prev[roomId], loading: false } }));
    }
```

`console.error`는 지운다. `loadOlderMessages`가 `useCallback([token])`이므로 의존성 배열에 `notify`를 추가한다.

- [ ] **Step 4: 검증**

Run: `cd frontend && npm run lint`
Expected: 에러 없음.

Run: `cd frontend && npm run build`
Expected: 성공.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "feat(chat): 입장 실패는 랜딩 복귀, 조회 실패는 알림으로 구분"
```

---

## Task 4: 참가자 목록과 채널 생성의 인라인 오류

**Files:**
- Modify: `frontend/src/components/ChatArea.tsx` (`openMembers`, 참가자 패널)
- Modify: `frontend/src/components/ChannelLanding.tsx` (`submit`, 다이얼로그)

**Interfaces:**
- Consumes: 없음 (각 컴포넌트 내부 상태)

- [ ] **Step 1: 참가자 목록 실패를 위장하지 않는다**

`ChatArea.tsx`의 상태 선언부에 추가한다:

```tsx
  const [membersError, setMembersError] = useState('');
```

`openMembers`를 아래로 교체한다:

```tsx
  const openMembers = async () => {
    setShowMembers(true);
    setParticipants(null);
    setMembersError('');
    try {
      const list = await getRoomMemberProfiles(token, channel.id);
      setParticipants(list);
    } catch (err) {
      // 빈 배열로 두면 "참가자가 없습니다"로 위장된다.
      setMembersError(err instanceof Error ? err.message : '참가자를 불러오지 못했어요.');
    }
  };
```

참가자 패널에서 `참가자가 없습니다`를 렌더하는 지점을 찾아, 그 **위에** 오류 분기를 넣는다:

```tsx
              {membersError ? (
                <div className="px-3 py-4 text-center">
                  <p className="text-[12px] text-rose-400">{membersError}</p>
                  <button
                    onClick={openMembers}
                    className="mt-2 text-[12px] font-semibold text-accent-text hover:underline cursor-pointer"
                  >
                    다시 시도
                  </button>
                </div>
              ) : (
                /* 기존 목록·빈 상태 렌더를 그대로 이 자리에 둔다 */
              )}
```

기존 렌더를 지우지 말고 `else` 자리로 옮긴다. `참가자가 없습니다`는 **실제로 참가자가 0명일 때만** 나와야 한다.

- [ ] **Step 2: 채널 생성 실패를 다이얼로그에 표시한다**

`ChannelLanding.tsx`의 상태 선언부에 추가한다:

```tsx
  const [createError, setCreateError] = useState('');
```

`submit`을 아래로 교체한다:

```tsx
  const submit = async () => {
    const n = name.trim();
    if (!n || busy) return;
    setBusy(true);
    setCreateError('');
    try {
      await onCreateChannel(n);
      setName('');
      setCreating(false);
    } catch (err) {
      // 다이얼로그를 열어둔 채 입력값을 유지해 그대로 다시 시도할 수 있게 한다.
      setCreateError(err instanceof Error ? err.message : '채널을 만들지 못했어요.');
    } finally {
      setBusy(false);
    }
  };
```

다이얼로그의 `만들기` 버튼 **바로 위**에 오류를 표시한다:

```tsx
            {createError && (
              <p className="mt-2 text-[12px] text-rose-400">{createError}</p>
            )}
```

다이얼로그를 닫을 때 오류를 지운다 — `setCreating(false)`를 호출하는 지점(닫기 버튼, 배경 클릭) 모두에서 `setCreateError('')`를 함께 호출한다.

- [ ] **Step 3: App의 `onCreateChannel`이 예외를 전파하는지 확인한다**

`App.tsx`의 `onCreateChannel` 구현에 `try/catch`가 없어야 한다(있으면 `ChannelLanding`이 실패를 받지 못한다). 현재 코드에 없으므로 **바꾸지 말고 확인만 한다.** 확인한 사실을 보고서에 적어라.

- [ ] **Step 4: 검증**

Run: `cd frontend && npm run lint`
Expected: 에러 없음.

Run: `cd frontend && npm run build`
Expected: 성공.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ChatArea.tsx frontend/src/components/ChannelLanding.tsx
git commit -m "feat(ui): 참가자 목록·채널 생성 실패를 그 자리에 표시"
```

---

## Task 5: 검증 + PR

**Files:** 없음 (검증·문서 단계)

- [ ] **Step 1: 전체 검증**

Run: `cd frontend && npm run lint && npm run build`
Expected: tsc 에러 0, 빌드 성공.

- [ ] **Step 2: 무음 실패가 남지 않았는지 확인한다**

Run: `grep -rn "console.error" frontend/src`
Expected: 남아 있다면 각각이 **사용자에게 보이는 처리와 함께 있는지** 확인한다. 다음 두 곳은 의도적으로 남긴다 — 사용자가 조치할 수 없는 부수 실패다:
- 안읽음 개수 조회 실패(배지만 안 뜬다)
- 읽음 처리 실패(다음 입장 때 회복된다)

이 둘 외에 사용자 조작의 결과인데 콘솔에만 남는 것이 있으면 보고하라.

Run: `grep -rn "alert(" frontend/src`
Expected: 출력 없음.

- [ ] **Step 3: 범위를 확인한다**

Run: `git diff origin/develop --stat -- src/`
Expected: 출력 없음. 백엔드 무변경.

- [ ] **Step 4: 브랜치를 푸시한다**

```bash
git push -u origin feat/failure-feedback
```

- [ ] **Step 5: PR을 만든다**

`.github/pull_request_template.md`의 5개 섹션을 그대로 채운다.

`## 검증`에는 실제로 실행한 것만 쓴다. **브라우저에서 실패 경로를 재현했는지 여부를 명시한다** — 백엔드를 끄고 전송을 시도하는 식의 확인을 했다면 적고, 안 했으면 안 했다고 쓴다.

`## 구현 노트 / 알려진 한계`에 반드시 담을 것:

- **STOMP가 연결된 상태에서 서버가 거절하면 여전히 조용하다.** `stomp.send()`는 ack를 기다리지 않는다. 이번 범위가 잡는 것은 STOMP 미연결 → REST 폴백 실패 경로다.
- 여러 메시지를 연속으로 보내다 중간 것이 실패하면, 복원된 글이 입력창으로 돌아오므로 나중에 보낸 것보다 뒤에 전송된다.
- 토스트에 큐가 없어 짧은 시간에 두 개가 발생하면 앞의 것을 못 볼 수 있다.
- 안읽음 조회·읽음 처리 실패는 의도적으로 조용히 둔다(사용자가 조치할 수 없고 다음 입장에서 회복된다).

- [ ] **Step 6: 머지는 사용자가 한다 — 체크포인트**

머지 후 배포본에서 확인할 것:
- 네트워크를 끊고 메시지를 보내면 **입력 내용이 되돌아오고** 오류가 보인다
- 참가자 패널이 실패하면 `참가자가 없습니다`가 아니라 오류와 `다시 시도`가 보인다
- 메시지 수정·삭제 실패가 `alert` 창이 아니라 하단 토스트로 뜬다
