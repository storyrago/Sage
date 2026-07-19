# 프론트 UX 배치 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그인 직후 어색한 자동 채팅 진입을 우표 갤러리 채널 선택 랜딩으로 대체하고, 프론트 UX 버그·미완 기능(에러 노출·설정·프로필 사진·상대 프로필·참가자)을 정리한다.

**Architecture:** 프론트엔드(React 19 + Vite + TS + Tailwind v4) 전용. 백엔드/스키마 변경 없이 기존 엔드포인트만 사용. 아바타 렌더링을 "사진 URL이면 `<img>`, 없으면 그라디언트"로 일원화(신규 `Avatar` 컴포넌트)하여 프로필 사진 기능을 실현.

**Tech Stack:** React 19, Vite 6, TypeScript, Tailwind CSS v4, lucide-react.

## Global Constraints

- 프론트엔드만 수정. 백엔드 무변경.
- 유닛 테스트 없음 → 각 태스크는 `npm --prefix frontend run lint`(= `tsc --noEmit`) 통과 + dev preview 육안 확인으로 닫는다.
- 색은 세이지 시맨틱 토큰(`bg-bg`/`bg-surface`/`text-text`/`bg-accent` 등) 경유. 채널 랜딩은 인트로처럼 **다크 고정**.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- 브랜치 `feat/frontend-ux-batch`, 최종 develop 대상 PR.
- 백엔드 계약(확인됨):
  - `GET /api/members/{id}` → `{ id, email, nickname, profileImageUrl, createdAt }`
  - `PATCH /api/members/me/profile-image` 바디 `{ "imageUrl": "<url>" }` → 갱신된 member
  - `POST /api/images` (multipart, 필드 `file`) → `{ "url": "<url>" }`
  - `GET /api/chatrooms/{id}/members` → `[{ id, memberId, chatRoomId }]` (이름 없음 → memberId로 별도 조회)
  - 에러 응답 본문은 `{ "message": "..." }` JSON.

---

### Task 1: API 레이어 — 에러 파싱 + 회원/이미지 엔드포인트

**Files:**
- Modify: `frontend/src/lib/api.ts`

**Interfaces:**
- Produces: `getMemberById(token, id) => Promise<BackendMember>`, `uploadImage(token, File) => Promise<string>`, `updateProfileImage(token, imageUrl) => Promise<BackendMember>`; `BackendMember.profileImageUrl?: string | null`; `toUser` now sets `photoUrl`.

- [ ] **Step 1: `request`의 에러 본문을 JSON 파싱해 message만 던지기 (A2 픽스)**

`api.ts`의 `request` 함수 내 `if (!response.ok)` 블록을 아래로 교체:

```ts
  if (!response.ok) {
    const text = await response.text();
    let message = text || `요청 실패: ${response.status}`;
    try {
      const parsed = JSON.parse(text);
      if (parsed && typeof parsed.message === 'string') message = parsed.message;
    } catch {
      /* JSON 아님 — 원문 유지 */
    }
    throw new Error(message);
  }
```

- [ ] **Step 2: `BackendMember`에 `profileImageUrl` 추가**

```ts
export interface BackendMember {
  id: number;
  email: string;
  nickname: string;
  profileImageUrl?: string | null;
  createdAt?: string;
}
```

- [ ] **Step 3: 회원 단건 조회 + 이미지 업로드 + 프로필 이미지 갱신 함수 추가**

`api.ts` 하단(`sendMessage` 아래)에 추가:

```ts
export async function getMemberById(token: string, id: string) {
  return request<BackendMember>(`/api/members/${id}`, {}, token);
}

export async function uploadImage(token: string, file: File): Promise<string> {
  const form = new FormData();
  form.append('file', file);
  const res = await fetch(`${API_BASE_URL}/api/images`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` }, // Content-Type은 브라우저가 boundary 포함해 자동 설정
    body: form,
  });
  if (!res.ok) {
    const text = await res.text();
    let message = text || `업로드 실패: ${res.status}`;
    try { const p = JSON.parse(text); if (p?.message) message = p.message; } catch { /* keep */ }
    throw new Error(message);
  }
  const data = (await res.json()) as { url: string };
  return data.url;
}

export async function updateProfileImage(token: string, imageUrl: string) {
  return request<BackendMember>('/api/members/me/profile-image', {
    method: 'PATCH',
    body: JSON.stringify({ imageUrl }),
  }, token);
}
```

- [ ] **Step 4: `toUser`에 `photoUrl` 매핑**

> 이 변경은 `User.photoUrl`(Task 2 Step 1)에 의존한다. 아직 없으면 `frontend/src/types.ts`의 `User`에 `photoUrl?: string;`를 **먼저** 추가한 뒤 진행.

```ts
export function toUser(member: BackendMember): User {
  return {
    id: String(member.id),
    email: member.email,
    displayName: member.nickname,
    avatar: avatarForId(member.id),
    photoUrl: member.profileImageUrl ?? undefined,
  };
}
```

- [ ] **Step 5: lint 통과 확인**

Run: `npm --prefix frontend run lint`
Expected: 에러 없음. (`User.photoUrl`은 Task 2에서 타입 추가 — 이 태스크에서 lint가 photoUrl 미정의로 실패하면 Task 2의 types 변경을 먼저 적용)

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/lib/api.ts
git commit -m "feat(api): 에러 message 파싱 + 회원조회·이미지 업로드·프로필이미지 갱신"
```

---

### Task 2: `User` 타입 + 공용 `Avatar` 컴포넌트

**Files:**
- Modify: `frontend/src/types.ts`
- Create: `frontend/src/components/Avatar.tsx`

**Interfaces:**
- Produces: `User.photoUrl?: string`; `<Avatar photoUrl? gradient name className? />`.

- [ ] **Step 1: `User`에 `photoUrl` 추가**

`types.ts`의 `User` 인터페이스:

```ts
export interface User {
  id: string;
  email: string;
  displayName: string;
  avatar: string;      // 그라디언트 클래스(사진 없을 때 fallback)
  photoUrl?: string;   // 프로필 사진 URL(있으면 우선)
}
```

- [ ] **Step 2: `Avatar.tsx` 작성 (URL이면 이미지, 없으면 그라디언트+이니셜)**

```tsx
interface AvatarProps {
  photoUrl?: string;
  gradient: string;               // 예: "from-blue-600 to-violet-600 text-white"
  name: string;
  className?: string;             // 크기/모양, 예: "w-9 h-9 rounded-xl"
}

export default function Avatar({ photoUrl, gradient, name, className = 'w-9 h-9 rounded-lg' }: AvatarProps) {
  if (photoUrl) {
    return <img src={photoUrl} alt={name} className={`${className} object-cover`} />;
  }
  return (
    <div className={`${className} bg-gradient-to-tr ${gradient} flex items-center justify-center font-bold select-none`}>
      {name.slice(0, 1).toUpperCase()}
    </div>
  );
}
```

- [ ] **Step 3: lint 통과 확인**

Run: `npm --prefix frontend run lint`
Expected: 에러 없음.

- [ ] **Step 4: 커밋**

```bash
git add frontend/src/types.ts frontend/src/components/Avatar.tsx
git commit -m "feat(avatar): photoUrl 지원 공용 Avatar 컴포넌트 + User.photoUrl"
```

---

### Task 3: Sidebar·ChatArea 아바타를 `Avatar`로 치환

**Files:**
- Modify: `frontend/src/components/Sidebar.tsx` (현재 아바타 div: 라인 ~167, ~196)
- Modify: `frontend/src/components/ChatArea.tsx` (메시지 아바타 div: 라인 ~194)

**Interfaces:**
- Consumes: `<Avatar>` (Task 2).

- [ ] **Step 1: import 추가 (두 파일 모두)**

각 파일 상단에: `import Avatar from './Avatar';`

- [ ] **Step 2: Sidebar 하단 현재 사용자 아바타 치환 (~196)**

기존 `<div className={\`w-9 h-9 rounded-full bg-gradient-to-tr ${currentUser.avatar} ...\`}> ...이니셜... </div>` 을:

```tsx
<Avatar
  photoUrl={currentUser.photoUrl}
  gradient={currentUser.avatar}
  name={currentUser.displayName}
  className="w-9 h-9 rounded-full text-sm shadow-md flex-shrink-0"
/>
```

- [ ] **Step 3: Sidebar 접속자(presence) 아바타 치환 (~167)**

presence 목록 아바타는 사진 URL이 없으므로 gradient만:

```tsx
<Avatar gradient={user.userAvatar} name={user.userName} className="w-8 h-8 rounded-lg text-xs" />
```

- [ ] **Step 4: ChatArea 메시지 작성자 아바타 치환 (~194)**

메시지엔 photoUrl이 없으므로 gradient 유지:

```tsx
<Avatar gradient={msg.userAvatar} name={msg.userName} className="w-9 h-9 rounded-xl text-xs font-sans self-start shadow-md flex-shrink-0" />
```

- [ ] **Step 5: lint + preview 확인**

Run: `npm --prefix frontend run lint`
Preview: dev 서버에서 사이드바/채팅 아바타가 기존처럼 그라디언트+이니셜로 보이는지(사진 설정 전이라 동일). 콘솔 에러 0.

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/components/Sidebar.tsx frontend/src/components/ChatArea.tsx
git commit -m "refactor(avatar): Sidebar·ChatArea 아바타를 Avatar 컴포넌트로 치환"
```

---

### Task 4: 설정 모달 — 다크모드 제거 + 프로필 사진 수정 (A3)

**Files:**
- Modify: `frontend/src/components/SettingsModal.tsx`
- Modify: `frontend/src/App.tsx` (SettingsModal 사용처)

**Interfaces:**
- Consumes: `uploadImage`, `updateProfileImage` (Task 1), `<Avatar>` (Task 2).
- Produces: SettingsModal props에서 `theme`/`onSelectTheme` 제거, `token: string`·`onUpdatePhoto: (url: string) => void` 추가.

- [ ] **Step 1: props 인터페이스 수정**

```ts
interface SettingsModalProps {
  open: boolean;
  onClose: () => void;
  currentUser: User;
  token: string;
  onUpdateName: (name: string) => void;
  onUpdatePhoto: (url: string) => void;
}
```
그리고 컴포넌트 시그니처에서 `theme, onSelectTheme` 제거, `token, onUpdatePhoto` 추가. `import { X, Sun, Moon }` → `import { X }` (Sun/Moon은 테마 섹션에서만 쓰였음). 상단에 `import Avatar from './Avatar';`, `import { uploadImage, updateProfileImage } from '../lib/api';`, `useRef` 추가.

- [ ] **Step 2: 테마 섹션 삭제**

`{/* 테마 */}` 블록(라벨 "테마" + light/dark 토글, 대략 라인 109–123 `<div className="px-5 py-4 border-b border-border"> ... 다크 ... </div>`)을 통째로 제거.

- [ ] **Step 3: 프로필 사진 업로드 상태/핸들러 추가**

컴포넌트 본문 상단(다른 useState 근처)에:

```tsx
const fileRef = useRef<HTMLInputElement>(null);
const [uploading, setUploading] = useState(false);

const handlePhoto = async (e: React.ChangeEvent<HTMLInputElement>) => {
  const file = e.target.files?.[0];
  if (!file) return;
  setUploading(true);
  try {
    const url = await uploadImage(token, file);
    await updateProfileImage(token, url);
    onUpdatePhoto(url);
  } catch (err) {
    alert(err instanceof Error ? err.message : '사진 변경에 실패했습니다.');
  } finally {
    setUploading(false);
    e.target.value = '';
  }
};
```

- [ ] **Step 4: 프로필 섹션에 사진 행 추가**

"프로필" 섹션(표시 이름 위)에 삽입:

```tsx
<div className="mb-4 flex items-center gap-3">
  <Avatar photoUrl={currentUser.photoUrl} gradient={currentUser.avatar} name={currentUser.displayName} className="w-14 h-14 rounded-2xl text-lg" />
  <div>
    <button
      onClick={() => fileRef.current?.click()}
      disabled={uploading}
      className="rounded-lg border border-border px-3 py-2 text-[13px] font-semibold text-text hover:border-accent transition-all cursor-pointer disabled:opacity-60"
    >
      {uploading ? '업로드 중…' : '사진 변경'}
    </button>
    <input ref={fileRef} type="file" accept="image/*" hidden onChange={handlePhoto} />
  </div>
</div>
```

- [ ] **Step 5: App.tsx 사용처 수정**

App.tsx의 `<SettingsModal ... />`에서 `theme`/`onSelectTheme` 전달 제거, `token={token ?? ''}`와 `onUpdatePhoto`를 추가:

```tsx
onUpdatePhoto={(url) => {
  setUser((prev) => {
    if (!prev) return prev;
    const next = { ...prev, photoUrl: url };
    if (token) persistSession(token, next);
    return next;
  });
}}
```

- [ ] **Step 6: lint + preview 확인**

Run: `npm --prefix frontend run lint`
Preview: 설정 모달에 **테마 섹션 없음**, "사진 변경" 클릭 → 파일 선택 → 업로드 후 미리보기+사이드바 아바타가 사진으로 바뀌는지. (사이드바 테마 토글은 그대로 있어야 함.)

- [ ] **Step 7: 커밋**

```bash
git add frontend/src/components/SettingsModal.tsx frontend/src/App.tsx
git commit -m "feat(settings): 다크모드 섹션 제거 + 프로필 사진 변경 기능"
```

---

### Task 5: 상대 프로필 조회 모달 (A4)

**Files:**
- Create: `frontend/src/components/ProfileModal.tsx`
- Modify: `frontend/src/components/ChatArea.tsx` (아바타/이름 클릭)
- Modify: `frontend/src/App.tsx` (모달 상태·렌더)

**Interfaces:**
- Consumes: `getMemberById` (Task 1), `<Avatar>`.
- Produces: `<ProfileModal open memberId token onClose />`; ChatArea prop `onOpenProfile: (userId: string) => void`.

- [ ] **Step 1: `ProfileModal.tsx` 작성**

```tsx
import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import Avatar from './Avatar';
import { getMemberById, BackendMember } from '../lib/api';
import { avatarForId } from '../lib/avatar';

interface ProfileModalProps {
  open: boolean;
  memberId: string | null;
  token: string;
  onClose: () => void;
}

export default function ProfileModal({ open, memberId, token, onClose }: ProfileModalProps) {
  const [member, setMember] = useState<BackendMember | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open || !memberId) return;
    setMember(null);
    setError('');
    getMemberById(token, memberId)
      .then(setMember)
      .catch((e) => setError(e instanceof Error ? e.message : '조회 실패'));
  }, [open, memberId, token]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-6" role="dialog" aria-modal="true">
      <div className="absolute inset-0 bg-black/55 backdrop-blur-[3px]" onClick={onClose} />
      <div className="relative w-full max-w-[340px] bg-surface border border-border rounded-3xl p-6 text-center">
        <button onClick={onClose} aria-label="닫기" className="absolute top-3 right-3 w-8 h-8 rounded-lg border border-border text-muted hover:text-text transition-all cursor-pointer flex items-center justify-center"><X className="w-4 h-4" /></button>
        {error && <div className="py-8 text-[13px] text-muted">{error}</div>}
        {!error && !member && <div className="py-8 text-[13px] text-muted">불러오는 중…</div>}
        {member && (
          <div className="flex flex-col items-center gap-3 pt-2">
            <Avatar photoUrl={member.profileImageUrl ?? undefined} gradient={avatarForId(member.id)} name={member.nickname} className="w-20 h-20 rounded-3xl text-2xl" />
            <div className="text-[17px] font-bold text-text">{member.nickname}</div>
            <div className="text-[13px] text-muted">{member.email}</div>
            {member.createdAt && <div className="text-[12px] text-faint">가입: {new Date(member.createdAt).toLocaleDateString('ko-KR')}</div>}
          </div>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: ChatArea에서 아바타/이름 클릭 → onOpenProfile**

ChatArea props에 `onOpenProfile: (userId: string) => void` 추가. 메시지의 Avatar(Task 3)와 작성자 이름을 클릭 가능하게 감싸기:

```tsx
<button onClick={() => onOpenProfile(msg.userId)} className="cursor-pointer" aria-label={`${msg.userName} 프로필`}>
  <Avatar gradient={msg.userAvatar} name={msg.userName} className="w-9 h-9 rounded-xl text-xs shadow-md flex-shrink-0" />
</button>
```
(작성자 이름 span에도 동일 onClick 부여 가능.)

- [ ] **Step 3: App.tsx 상태·렌더 배선**

App에 상태 추가: `const [profileMemberId, setProfileMemberId] = useState<string | null>(null);`
`<ChatArea ... onOpenProfile={(id) => setProfileMemberId(id)} />` 전달.
렌더 하단에:
```tsx
<ProfileModal open={profileMemberId !== null} memberId={profileMemberId} token={token ?? ''} onClose={() => setProfileMemberId(null)} />
```
그리고 `import ProfileModal from './components/ProfileModal';`.

- [ ] **Step 4: lint + preview 확인**

Run: `npm --prefix frontend run lint`
Preview: 채팅에서 상대 메시지의 아바타/이름 클릭 → 그 사람 프로필(사진/이름/이메일/가입일) 모달.

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/components/ProfileModal.tsx frontend/src/components/ChatArea.tsx frontend/src/App.tsx
git commit -m "feat(profile): 상대 프로필 조회 모달"
```

---

### Task 6: 채팅방 참가자 목록 (A5)

**Files:**
- Modify: `frontend/src/lib/api.ts` (참가자 조회 + 이름 해석)
- Modify: `frontend/src/components/ChatArea.tsx` (헤더 참가자 패널)

**Interfaces:**
- Produces: `BackendChatRoomMember`, `getChatRoomMembers(token, chatroomId)`, `getRoomMemberProfiles(token, chatroomId) => Promise<BackendMember[]>`.

- [ ] **Step 1: api.ts에 참가자 조회 추가**

```ts
export interface BackendChatRoomMember {
  id: number;
  memberId: number;
  chatRoomId: number;
}

export async function getChatRoomMembers(token: string, chatroomId: string) {
  return request<BackendChatRoomMember[]>(`/api/chatrooms/${chatroomId}/members`, {}, token);
}

// DTO엔 이름이 없어 memberId로 각 회원을 조회(N+1). 방이 작아 허용. 향후 백엔드 DTO에 nickname 추가 시 단순화 가능.
export async function getRoomMemberProfiles(token: string, chatroomId: string): Promise<BackendMember[]> {
  const members = await getChatRoomMembers(token, chatroomId);
  return Promise.all(members.map((m) => getMemberById(token, String(m.memberId))));
}
```

- [ ] **Step 2: ChatArea 헤더에 참가자 버튼 + 패널**

ChatArea props에 `token: string` 추가(App에서 전달). 상태:

```tsx
const [participants, setParticipants] = useState<BackendMember[] | null>(null);
const [showMembers, setShowMembers] = useState(false);

const openMembers = async () => {
  setShowMembers(true);
  setParticipants(null);
  try {
    const list = await getRoomMemberProfiles(token, activeChannel.id); // ChatArea가 받는 현재 방 객체의 id
    setParticipants(list);
  } catch {
    setParticipants([]);
  }
};
```
헤더에 버튼(`lucide-react`의 `Users` 아이콘) → `openMembers()`. 패널(드롭다운/사이드)에서 `participants` 를 `Avatar`(gradient) + `nickname` 목록으로 렌더. 로딩·빈 상태 처리. `import { getRoomMemberProfiles, BackendMember } from '../lib/api';`, `import Avatar from './Avatar';`.

> 참고: 현재 방 id는 ChatArea가 이미 받는 `activeChannel` prop의 `id`(App의 `selectedChannelId`)를 사용. ChatArea가 방 정보를 다른 prop명으로 받으면 그 id로 대체. "온라인 여부"는 presence(백엔드) 필요 → 이번 범위 밖, 명단만.

- [ ] **Step 3: App.tsx에서 ChatArea에 token 전달**

`<ChatArea ... token={token ?? ''} />`

- [ ] **Step 4: lint + preview 확인**

Run: `npm --prefix frontend run lint`
Preview: 채팅 헤더의 참가자 버튼 → 참여자 명단(이름) 표시.

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/lib/api.ts frontend/src/components/ChatArea.tsx frontend/src/App.tsx
git commit -m "feat(members): 채팅방 참가자 명단 조회·표시"
```

---

### Task 7: 로그인 직후 채널 자동 진입 제거 (A1)

**Files:**
- Modify: `frontend/src/App.tsx` (방 목록 로드부, 현재 라인 ~94–96)

- [ ] **Step 1: 첫 방 자동선택 제거**

`refreshRooms` 내부의 아래 블록을 삭제(또는 자동선택 문장만 제거):

```tsx
// 삭제 대상
if (mappedRooms.length > 0) {
  setSelectedChannelId((current) => current || mappedRooms[0].id);
}
```
→ 방 목록만 `setChannels(mappedRooms)` 하고 `selectedChannelId`는 건드리지 않음(빈 문자열 유지).

- [ ] **Step 2: lint + preview 확인**

Run: `npm --prefix frontend run lint`
Preview: 로그인 후 **어떤 방에도 자동 입장되지 않고** 빈 상태(다음 태스크에서 랜딩으로 교체됨)로 남는지.

- [ ] **Step 3: 커밋**

```bash
git add frontend/src/App.tsx
git commit -m "fix(app): 로그인 직후 첫 채널 자동 입장 제거"
```

---

### Task 8: 채널 선택 랜딩 — 우표 갤러리 (A′)

**Files:**
- Modify: `frontend/src/index.css` (우표 톱니 마스크 유틸)
- Create: `frontend/src/components/ChannelLanding.tsx`
- Modify: `frontend/src/App.tsx` (랜딩/채팅 분기, 홈 복귀)
- Modify: `frontend/src/components/Sidebar.tsx` (로고 클릭 → 홈 복귀)

**Interfaces:**
- Consumes: `Channel[]`, `User`.
- Produces: `<ChannelLanding channels currentUser onSelectChannel onCreateChannel />`; Sidebar prop `onGoHome?: () => void`.

- [ ] **Step 1: index.css에 우표 마스크 유틸 추가**

`index.css` 하단에:

```css
.stamp-paper {
  -webkit-mask:
    linear-gradient(#000 0 0) 50%/calc(100% - 11px) calc(100% - 11px) no-repeat,
    radial-gradient(circle 5px, #000 96%, #0000) 0 0/12px 12px round;
  -webkit-mask-composite: source-over;
  mask:
    linear-gradient(#000 0 0) 50%/calc(100% - 11px) calc(100% - 11px) no-repeat,
    radial-gradient(circle 5px, #000 96%, #0000) 0 0/12px 12px round;
  mask-composite: add;
}
```

- [ ] **Step 2: `ChannelLanding.tsx` 작성**

```tsx
import { useState } from 'react';
import { Plus, Hash, Code, Music, Shuffle, Gamepad2, MessageCircle, Bell, X } from 'lucide-react';
import { Channel, User } from '../types';

const ICONS = [Hash, Code, Music, Shuffle, Gamepad2, MessageCircle, Bell];

// id로부터 결정적 값(아이콘/회전/틴트/산포 위치)
function hash(id: string) {
  return [...id].reduce((s, c) => s + c.charCodeAt(0), 0);
}
const POS = [
  { left: '9%', top: '26%', rot: -9 }, { left: '27%', top: '15%', rot: 6 },
  { left: '20%', top: '55%', rot: -3 }, { left: '47%', top: '30%', rot: 10 },
  { left: '43%', top: '61%', rot: -7 }, { left: '68%', top: '19%', rot: 5 },
  { left: '70%', top: '52%', rot: -11 }, { left: '55%', top: '10%', rot: 3 },
];

interface Props {
  channels: Channel[];
  currentUser: User;
  onSelectChannel: (id: string) => void;
  onCreateChannel: (name: string) => Promise<void>;
}

export default function ChannelLanding({ channels, onSelectChannel, onCreateChannel }: Props) {
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    const n = name.trim();
    if (!n || busy) return;
    setBusy(true);
    try { await onCreateChannel(n); setName(''); setCreating(false); }
    finally { setBusy(false); }
  };

  return (
    <div className="relative h-full w-full overflow-auto" style={{ background: '#141917' }}>
      <div className="sticky top-0 z-10 flex items-center justify-between px-6 h-14" style={{ background: '#141917' }}>
        <span className="text-[16px] font-bold text-[#e6ece8]">Sage</span>
        <button onClick={() => setCreating(true)} className="inline-flex items-center gap-1.5 bg-accent text-accent-fg rounded-lg px-3.5 py-2 text-[13px] font-semibold hover:bg-accent-hover transition-colors cursor-pointer">
          <Plus className="w-4 h-4" /> 채널 만들기
        </button>
      </div>

      {channels.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-4 pt-40 text-center">
          <div className="text-[15px] text-[#9aa8a0]">아직 채널이 없어요.</div>
          <button onClick={() => setCreating(true)} className="inline-flex items-center gap-1.5 bg-accent text-accent-fg rounded-lg px-4 py-2.5 text-[14px] font-bold cursor-pointer"><Plus className="w-4 h-4" /> 첫 채널 만들기</button>
        </div>
      ) : (
        <div className="relative" style={{ height: 520 }}>
          {channels.map((ch, i) => {
            const p = POS[i % POS.length];
            const Icon = ICONS[hash(ch.id) % ICONS.length];
            const tint = hash(ch.id) % 3 === 0;
            return (
              <button
                key={ch.id}
                onClick={() => onSelectChannel(ch.id)}
                className="absolute w-[118px] transition-transform duration-200 hover:!rotate-0 hover:scale-110 hover:z-30 cursor-pointer"
                style={{ left: p.left, top: p.top, transform: `rotate(${p.rot}deg)`, filter: 'drop-shadow(0 6px 10px rgba(0,0,0,0.45))' }}
              >
                <div className={`stamp-paper flex flex-col items-center justify-center gap-1.5 min-h-[104px] px-2.5 py-4 ${tint ? 'bg-[#e7efe6]' : 'bg-[#fdfcf8]'}`}>
                  <Icon className="w-6 h-6" style={{ color: 'var(--accent)' }} />
                  <div className="text-[15px] font-semibold text-[#26251f]">{ch.name}</div>
                </div>
              </button>
            );
          })}
        </div>
      )}

      {creating && (
        <div className="fixed inset-0 z-40 flex items-center justify-center p-6" role="dialog" aria-modal="true">
          <div className="absolute inset-0 bg-black/55" onClick={() => setCreating(false)} />
          <div className="relative w-full max-w-[360px] bg-surface border border-border rounded-3xl p-5">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-[15px] font-bold text-text">새 채널</h2>
              <button onClick={() => setCreating(false)} aria-label="닫기" className="text-muted hover:text-text cursor-pointer"><X className="w-4 h-4" /></button>
            </div>
            <input autoFocus value={name} maxLength={30} onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && submit()} placeholder="채널 이름" className="w-full bg-surface-2 border border-border rounded-[10px] px-3 py-2.5 text-[14px] text-text outline-none focus:border-accent" />
            <button onClick={submit} disabled={busy} className="mt-3 w-full rounded-xl py-2.5 text-[14px] font-bold bg-accent text-accent-fg hover:bg-accent-hover cursor-pointer disabled:opacity-60">{busy ? '만드는 중…' : '만들기'}</button>
          </div>
        </div>
      )}
    </div>
  );
}
```

> 참고: 세이지 토큰은 `:root[data-theme]`에만 정의되므로 div에 `data-theme`를 줘도 안 먹는다. 랜딩은 인트로처럼 **다크 고정**이라 보드 배경/로고색을 하드코딩(`#141917`/`#e6ece8`). 우표는 밝은 종이색 고정(`#fdfcf8`/틴트 `#e7efe6`)이라 다크 위에서 도드라짐.

- [ ] **Step 3: App.tsx — 랜딩/채팅 분기**

메인 렌더에서 로그인 상태이고 선택된 채널이 없으면 랜딩을 보여준다. 기존 `Sidebar + ChatArea` 렌더를 조건 분기:

```tsx
// user && token 있는 채팅 화면 영역에서:
{selectedChannelId ? (
  <>
    <Sidebar /* 기존 props */ onGoHome={() => setSelectedChannelId('')} />
    <ChatArea /* 기존 props */ />
  </>
) : (
  <ChannelLanding
    channels={channels}
    currentUser={user}
    onSelectChannel={(id) => setSelectedChannelId(id)}
    onCreateChannel={async (name) => {
      if (!token) return;
      const room = await createChatRoom(token, name);
      await refreshRooms(token);
      setSelectedChannelId(String(room.id)); // 만들자마자 입장
    }}
  />
)}
```
`import ChannelLanding from './components/ChannelLanding';`, `import { createChatRoom } from './lib/api';`(이미 있으면 생략).

- [ ] **Step 4: Sidebar 로고 클릭 → 홈 복귀**

Sidebar props에 `onGoHome?: () => void` 추가. 상단 로고("Sage") 영역을 `onGoHome`이 있으면 버튼으로 감싸 클릭 시 호출:

```tsx
<button onClick={onGoHome} className="cursor-pointer" aria-label="채널 목록으로">…Sage 로고…</button>
```

- [ ] **Step 5: lint + preview 확인**

Run: `npm --prefix frontend run lint`
Preview:
- 로그인 → **우표 랜딩** 표시(다크 보드 + 흩뿌려진 톱니 우표).
- 우표 클릭 → 해당 방 채팅 진입.
- "채널 만들기" → 새 방 생성 후 그 방으로 진입, 홈 복귀 시 새 우표 보임.
- 사이드바 로고 클릭 → 랜딩 복귀. 콘솔 에러 0.

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/index.css frontend/src/components/ChannelLanding.tsx frontend/src/components/Sidebar.tsx frontend/src/App.tsx
git commit -m "feat(landing): 우표 갤러리 채널 선택 랜딩 + 홈 복귀"
```

---

### Task 9: 입장 전환 연출 (A′)

**Files:**
- Modify: `frontend/src/App.tsx` (전환 state)
- Modify: `frontend/src/components/ChannelLanding.tsx` (클릭 우표 확대)
- Modify: `frontend/src/index.css` (전환 키프레임, 기존 `sage-chat-enter` 재사용 가능)

**Interfaces:**
- Consumes: 기존 `warping` state 패턴.

- [ ] **Step 1: 클릭 우표 확대 연출**

`ChannelLanding`에서 클릭 시 즉시 `onSelectChannel` 대신, 클릭된 id를 로컬 state `zoomingId`로 잡아 해당 우표에 `scale`을 크게 주는 클래스를 부여(200–300ms), 그 후 `onSelectChannel(id)` 호출:

```tsx
const [zoomingId, setZoomingId] = useState<string | null>(null);
const enter = (id: string) => {
  setZoomingId(id);
  setTimeout(() => onSelectChannel(id), 260);
};
// 버튼 onClick={() => enter(ch.id)}
// 스타일: style={{ ..., transform: zoomingId === ch.id ? 'scale(2.4)' : `rotate(${p.rot}deg)`, opacity: zoomingId && zoomingId !== ch.id ? 0 : 1, transition: 'transform .26s ease, opacity .26s ease' }}
```

- [ ] **Step 2: 채팅 진입 페이드인**

App에서 채널 선택 시 짧은 진입 연출을 위해 `selectedChannelId` 설정 직후 채팅 영역에 기존 `sage-chat-enter` 클래스(있으면)로 페이드인. 없으면 `index.css`에 추가:

```css
@keyframes sageChatEnter { from { opacity: 0; transform: scale(0.98); } to { opacity: 1; transform: none; } }
.sage-chat-enter { animation: sageChatEnter .28s ease-out; }
```
채팅 컨테이너(Sidebar+ChatArea 감싸는 요소)에 `className="... sage-chat-enter"` 적용.

- [ ] **Step 3: lint + preview 확인**

Run: `npm --prefix frontend run lint`
Preview: 우표 클릭 → 그 우표가 확대되고 다른 우표는 사라지며 → 채팅이 스르륵 등장. 어색한 "툭" 없음.

- [ ] **Step 4: 커밋**

```bash
git add frontend/src/App.tsx frontend/src/components/ChannelLanding.tsx frontend/src/index.css
git commit -m "feat(landing): 우표 확대 → 채팅 페이드인 입장 전환"
```

---

## 최종 검증 (전체)

- [ ] `npm --prefix frontend run lint` 통과.
- [ ] 로그인 → 우표 랜딩(자동입장 없음), 우표 클릭 → 전환 후 채팅.
- [ ] 로그인 실패 → 깔끔한 메시지만("해당 회원을 찾을 수 없습니다." 등), **JSON 없음**.
- [ ] 설정: 테마 섹션 없음, 프로필 사진 변경 동작(업로드→아바타 반영), 사이드바 테마 토글 유지.
- [ ] 메시지 아바타/이름 클릭 → 상대 프로필 모달(사진 포함).
- [ ] 채팅 헤더 → 참가자 명단.
- [ ] "채널 만들기" 동작, 사이드바 로고 → 랜딩 복귀.
- [ ] `feat/frontend-ux-batch` → develop PR 생성.

> **범위 밖(다음 버킷)**: 모바일 반응형(B), 답장·이름 한국어 charset·이모지 반응(C), OAuth(D). 랜딩은 좁은 화면에서 깨지지 않을 정도만 대응(전면 반응형은 B).
