# 프로필 사진 확정 방식 통일 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 온보딩 화면에서도 프로필 사진을 바꿀 수 있게 하고, 두 화면 모두 "확정 버튼을 눌러야 반영된다"로 통일한다.

**Architecture:** 파일 선택·검증·미리보기·확정을 공유 훅 `useProfilePhotoDraft`에 모은다. 온보딩(`시작하기`)과 설정(`저장`)은 이 훅의 `commit()`을 자기 저장 순서의 첫 단계로 호출한다. 업로드에 성공한 URL은 훅이 캐시해 부분 실패 후 재시도에서 S3에 다시 올리지 않는다.

**Tech Stack:** React + TypeScript (Vite), 기존 `lib/api.ts`의 `uploadImage`·`updateProfileImage`

설계 문서: `docs/superpowers/specs/2026-07-30-onboarding-photo-design.md`

## Global Constraints

- 브랜치는 `feat/onboarding-photo`(이미 존재, `origin/develop`에서 분기). PR 대상은 **develop**.
- **백엔드는 변경하지 않는다.** `src/` 아래 파일을 건드리지 않는다. 스키마 변경도 없다.
- 프론트에 유닛 테스트 러너가 없다. 검증 명령은 `cd frontend && npm run lint && npm run build` 두 개뿐이다.
- 파일 크기 상한은 **10MB**. 백엔드 `spring.servlet.multipart.max-file-size: 10MB`, nginx `client_max_body_size 10m`와 같은 기준이다.
- 닉네임 최대 길이는 **20자**(기존 동작 유지).
- 디자인 토큰: 카드 `#1C241F` / `1px solid #2D362F` / radius 22, 본문 `#E6ECE8`, 보조 `#9AA8A0`, 흐린 `#6B7972`, 오류 텍스트는 온보딩 `#f0a5a5` / 설정 `text-red-300`(각 화면의 기존 방식 유지).
- 오류는 **항목별 인라인**으로 표시한다. 사진 오류는 아바타 아래, 닉네임 오류는 입력 아래. `alert()`을 쓰지 않는다.
- 커밋 메시지·코드 주석은 변경의 목적만 쓴다. "누락/핫픽스/깨져 있었다" 같은 배경 서사 금지.
- **훅이 반환하는 객체를 통째로 `useEffect` 의존성에 넣지 않는다.** 매 렌더마다 새 객체라 효과가 무한히 재실행된다. 필요한 함수만 구조분해해서 쓴다(각 함수는 `useCallback`으로 안정적이다).

## File Structure

| 파일 | 책임 | 작업 |
|---|---|---|
| `frontend/src/lib/useProfilePhotoDraft.ts` | 파일 선택·검증·미리보기·업로드 캐시·확정 | 생성 |
| `frontend/src/components/Onboarding.tsx` | 온보딩 사진 UI, 제출 순서에 사진 추가, 건너뛰기 안내 | 수정 |
| `frontend/src/components/SettingsModal.tsx` | 즉시 반영 → 확정 방식, 사진 오류 인라인화 | 수정 |

---

## Task 1: 공유 훅 `useProfilePhotoDraft`

**Files:**
- Create: `frontend/src/lib/useProfilePhotoDraft.ts`

**Interfaces:**
- Consumes: `uploadImage(token: string, file: File): Promise<string>`, `updateProfileImage(token: string, imageUrl: string): Promise<BackendMember>` (둘 다 `frontend/src/lib/api.ts`에 이미 있다)
- Produces:
  ```ts
  useProfilePhotoDraft(token: string): {
    previewUrl: string;
    hasDraft: boolean;
    error: string;
    pick(file: File): void;
    commit(): Promise<string | null>;
    reset(): void;
  }
  ```
  Task 2·3이 이 훅을 쓴다.

- [ ] **Step 1: 훅 파일을 만든다**

`frontend/src/lib/useProfilePhotoDraft.ts`를 아래 내용으로 생성한다:

```ts
import { useCallback, useEffect, useRef, useState } from 'react';
import { uploadImage, updateProfileImage } from './api';

// 백엔드 spring.servlet.multipart.max-file-size(10MB)·nginx client_max_body_size(10m)와 같은 기준.
// 서버까지 갔다가 실패하면 큰 파일을 올리는 시간을 버린다.
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;

// 프로필 사진을 "고른 상태"로 들고 있다가 확정 시점에 업로드·저장한다.
// 온보딩 화면과 설정 모달이 같은 동작을 공유한다.
export function useProfilePhotoDraft(token: string) {
  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState('');
  const [error, setError] = useState('');
  const [photoSaved, setPhotoSaved] = useState(false);
  const uploadedUrlRef = useRef('');   // 업로드 성공 URL 캐시. 재시도 시 다시 올리지 않는다

  // 미리보기 URL은 교체될 때와 화면을 떠날 때 해제한다
  useEffect(() => {
    if (!previewUrl) return;
    return () => URL.revokeObjectURL(previewUrl);
  }, [previewUrl]);

  const pick = useCallback((f: File) => {
    if (!f.type.startsWith('image/')) {
      setError('이미지 파일만 올릴 수 있어요.');
      return;
    }
    if (f.size > MAX_IMAGE_BYTES) {
      setError('사진은 10MB까지 올릴 수 있어요.');
      return;
    }
    setError('');
    uploadedUrlRef.current = '';
    setPhotoSaved(false);
    setFile(f);
    setPreviewUrl(URL.createObjectURL(f));
  }, []);

  // 고른 파일이 없으면 요청을 보내지 않고 null을 반환한다.
  // 실패하면 error를 채운 뒤 예외를 다시 던져 호출한 화면이 저장 순서를 멈출 수 있게 한다.
  const commit = useCallback(async (): Promise<string | null> => {
    if (!file) return null;
    try {
      if (!uploadedUrlRef.current) {
        uploadedUrlRef.current = await uploadImage(token, file);
      }
      await updateProfileImage(token, uploadedUrlRef.current);
      setPhotoSaved(true);
      setError('');
      return uploadedUrlRef.current;
    } catch (e) {
      setError(e instanceof Error ? e.message : '사진 저장에 실패했어요. 다시 시도해 주세요.');
      throw e;
    }
  }, [file, token]);

  const reset = useCallback(() => {
    setFile(null);
    setPreviewUrl('');
    setError('');
    setPhotoSaved(false);
    uploadedUrlRef.current = '';
  }, []);

  return {
    previewUrl,
    hasDraft: file !== null && !photoSaved,   // 고른 사진이 있고 아직 서버에 반영되지 않음
    error,
    pick,
    commit,
    reset,
  };
}
```

- [ ] **Step 2: 타입 검사와 빌드를 확인한다**

Run: `cd frontend && npm run lint`
Expected: 에러 없음. (이 시점에는 훅을 쓰는 곳이 없다. 미사용 export는 `tsc --noEmit`에서 에러가 아니다.)

Run: `cd frontend && npm run build`
Expected: 성공.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/lib/useProfilePhotoDraft.ts
git commit -m "feat(profile): 사진 초안 상태를 다루는 공유 훅 추가"
```

---

## Task 2: 온보딩 화면에서 사진 변경

**Files:**
- Modify: `frontend/src/components/Onboarding.tsx`

**Interfaces:**
- Consumes: Task 1의 `useProfilePhotoDraft(token)`
- Produces: 없음 (화면 종단)

- [ ] **Step 1: import와 훅 사용을 추가한다**

`Onboarding.tsx`의 맨 위 import 블록을 아래로 교체한다:

```tsx
import { useState, useRef, CSSProperties } from 'react';
import { User } from '../types';
import { MessagesSquare } from 'lucide-react';
import { updateNickname, completeOnboarding, toUser } from '../lib/api';
import { useProfilePhotoDraft } from '../lib/useProfilePhotoDraft';
```

컴포넌트 본문 첫 부분의 상태 선언(`const [nickname, ...]`부터 `const [busy, ...]`까지) 바로 아래에 추가한다. 훅이 반환한 객체를 통째로 쓰지 말고 구조분해한다:

```tsx
  const {
    previewUrl,
    hasDraft,
    error: photoError,
    pick: pickPhoto,
    commit: commitPhoto,
  } = useProfilePhotoDraft(token);
  const fileRef = useRef<HTMLInputElement>(null);
```

- [ ] **Step 2: `handleStart`를 사진 확정이 앞에 오도록 바꾼다**

기존 `handleStart` 전체를 아래로 교체한다:

```tsx
  const handleStart = async () => {
    const trimmed = nickname.trim();
    if (!trimmed) {
      setError('닉네임을 입력해 주세요.');
      return;
    }
    setError('');
    setBusy(true);
    try {
      await commitPhoto();          // 고른 사진이 없으면 요청을 보내지 않는다
    } catch {
      setBusy(false);               // 사진 오류는 훅의 error가 아바타 아래에 표시한다
      return;
    }
    try {
      await updateNickname(token, trimmed);
      const member = await completeOnboarding(token);
      onDone(toUser(member));
    } catch (e) {
      setError(e instanceof Error ? e.message : '저장에 실패했어요. 다시 시도해 주세요.');
    } finally {
      setBusy(false);
    }
  };
```

온보딩 기록(`completeOnboarding`)이 마지막이다. 중간에 실패하면 온보딩이 완료로 남지 않아 사용자가 다시 시도할 수 있다.

`handleSkip`은 바꾸지 않는다. 고른 사진은 확정되지 않았으므로 저장되지 않는다.

- [ ] **Step 3: 아바타를 사진 선택 진입점으로 바꾼다**

기존 아바타 블록(`<div className="flex items-center justify-center"` 로 시작해 `user.photoUrl` 삼항 연산자를 담고 있는 `div`) 전체를 아래로 교체한다:

```tsx
          <button
            type="button"
            onClick={() => fileRef.current?.click()}
            disabled={busy}
            aria-label="프로필 사진 변경"
            className="flex items-center justify-center"
            style={{
              width: 64, height: 64, borderRadius: '50%', background: '#29392F', color: '#9CCBB2',
              margin: '0 auto 8px', overflow: 'hidden', border: 'none', padding: 0,
              cursor: busy ? 'default' : 'pointer',
            }}
          >
            {previewUrl || user.photoUrl
              ? <img src={previewUrl || user.photoUrl} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
              : <MessagesSquare className="w-6 h-6" />}
          </button>

          <input
            ref={fileRef}
            type="file"
            accept="image/*"
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) pickPhoto(f);
              e.target.value = '';        // 같은 파일을 다시 골라도 onChange가 발생하게 한다
            }}
          />

          <button
            type="button"
            onClick={() => fileRef.current?.click()}
            disabled={busy}
            style={{
              display: 'block', margin: '0 auto 6px', background: 'none', border: 'none',
              color: '#9AA8A0', fontSize: 12, cursor: busy ? 'default' : 'pointer',
            }}
          >
            사진 변경
          </button>

          {photoError && (
            <p style={{ fontSize: 12, color: '#f0a5a5', textAlign: 'center', margin: '0 0 10px' }}>{photoError}</p>
          )}
```

미리보기 우선순위는 고른 파일 → provider 사진 → 기본 아이콘이다.

- [ ] **Step 4: 건너뛰기 안내 문구를 추가한다**

`나중에 하기` 버튼 **바로 아래**에 추가한다:

```tsx
          {hasDraft && (
            <p style={{ fontSize: 11, color: '#6B7972', textAlign: 'center', margin: '8px 0 0' }}>
              고른 사진은 저장되지 않아요
            </p>
          )}
```

`hasDraft`는 "고른 사진이 있고 아직 서버에 반영되지 않음"이다. 사진 저장까지 성공한 뒤 닉네임 저장이 실패한 상태에서는 문구가 뜨지 않는다 — 그 사진은 이미 반영되어 있기 때문이다.

- [ ] **Step 5: 검증**

Run: `cd frontend && npm run lint`
Expected: 에러 없음.

Run: `cd frontend && npm run build`
Expected: 성공.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/Onboarding.tsx
git commit -m "feat(onboarding): 온보딩 화면에서 프로필 사진 변경"
```

---

## Task 3: 설정 모달을 확정 방식으로 전환

**Files:**
- Modify: `frontend/src/components/SettingsModal.tsx`

**Interfaces:**
- Consumes: Task 1의 `useProfilePhotoDraft(token)`
- Produces: 없음 (화면 종단)

- [ ] **Step 1: import와 훅 사용을 추가하고 `uploading` 상태를 제거한다**

`SettingsModal.tsx` 상단 import에 추가한다:

```tsx
import { useProfilePhotoDraft } from '../lib/useProfilePhotoDraft';
```

`uploadImage, updateProfileImage`를 더 이상 이 파일에서 직접 쓰지 않으므로 `lib/api` import 목록에서 뺀다. 그 두 개만 import하고 있었다면 import 문 전체를 지운다.

상태 선언부에서 `const [uploading, setUploading] = useState(false);` 줄을 지우고, 그 자리에 훅을 구조분해해 넣는다:

```tsx
  const {
    previewUrl,
    error: photoError,
    pick: pickPhoto,
    commit: commitPhoto,
    reset: resetPhoto,
  } = useProfilePhotoDraft(token);
```

- [ ] **Step 2: `handlePhoto`를 파일 선택만 하도록 바꾼다**

기존 `handlePhoto` 전체(즉시 업로드·저장하고 실패 시 `alert`을 띄우던 함수)를 아래로 교체한다:

```tsx
  const handlePhoto = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) pickPhoto(file);
    e.target.value = '';            // 같은 파일을 다시 골라도 onChange가 발생하게 한다
  };
```

- [ ] **Step 3: 모달이 닫힐 때 고른 사진을 버린다**

`open`이 바뀔 때 입력을 되돌리는 기존 `useEffect`를 아래로 교체한다:

```tsx
  useEffect(() => {
    if (open) {
      setName(currentUser.displayName);
      setSaved(false);
      setNameError('');
    } else {
      resetPhoto();
    }
  }, [open, currentUser.displayName, resetPhoto]);
```

의존성에 훅 객체 전체가 아니라 `resetPhoto`만 넣는다. `resetPhoto`는 `useCallback(..., [])`이라 안정적이다.

- [ ] **Step 4: `handleSave`가 사진을 먼저 확정하게 한다**

기존 `handleSave` 전체를 아래로 교체한다:

```tsx
  const handleSave = async () => {
    setNameError('');
    setSaving(true);
    try {
      const url = await commitPhoto();     // 고른 사진이 없으면 요청을 보내지 않는다
      if (url) onUpdatePhoto(url);
    } catch {
      setSaving(false);                    // 사진 오류는 훅의 error가 아바타 옆에 표시한다
      return;
    }
    try {
      await onUpdateName(name.trim() || currentUser.displayName);
    } catch (err) {
      setNameError(err instanceof Error ? err.message : '닉네임 저장에 실패했어요. 다시 시도해 주세요.');
      setSaving(false);
      return;
    }
    try {
      localStorage.setItem('sage-status', status);
      localStorage.setItem('sage-notif', JSON.stringify(notif));
    } catch {
      /* ignore */
    }
    setSaving(false);
    setSaved(true);
    setTimeout(() => {
      setSaved(false);
      onClose();
    }, 700);
  };
```

- [ ] **Step 5: 아바타에 미리보기를 반영하고 사진 오류를 인라인으로 표시한다**

프로필 영역의 아바타·사진 변경 버튼 블록(`<div className="mb-4 flex items-center gap-3">` 로 시작하는 블록) 전체를 아래로 교체한다:

```tsx
          <div className="mb-4 flex items-center gap-3">
            <Avatar
              photoUrl={previewUrl || currentUser.photoUrl}
              gradient={currentUser.avatar}
              name={currentUser.displayName}
              className="w-14 h-14 rounded-2xl text-lg"
            />
            <div>
              <button
                onClick={() => fileRef.current?.click()}
                disabled={saving}
                className="rounded-lg border border-border px-3 py-2 text-[13px] font-semibold text-text hover:border-accent transition-all cursor-pointer disabled:opacity-60"
              >
                사진 변경
              </button>
              <input ref={fileRef} type="file" accept="image/*" hidden onChange={handlePhoto} />
              {photoError && <p className="text-[12px] text-red-300 mt-1.5">{photoError}</p>}
            </div>
          </div>
```

버튼 라벨의 `업로드 중…` 분기는 사라진다. 업로드가 이제 `저장`을 누른 뒤에 일어나므로, 진행 표시는 저장 버튼이 맡는다.

- [ ] **Step 6: 검증**

Run: `cd frontend && npm run lint`
Expected: 에러 없음. `uploading` 관련 잔여 참조가 있으면 여기서 잡힌다 — 남아 있으면 지운다.

Run: `cd frontend && npm run build`
Expected: 성공.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/SettingsModal.tsx
git commit -m "feat(profile): 설정 화면 사진을 저장 시점에 반영"
```

---

## Task 4: 통합 검증 + PR

**Files:** 없음 (검증·문서 단계)

- [ ] **Step 1: 전체 검증**

Run: `cd frontend && npm run lint && npm run build`
Expected: tsc 에러 0, 빌드 성공.

- [ ] **Step 2: 백엔드가 그대로인지 확인한다**

Run: `git diff origin/develop --stat -- src/`
Expected: 출력 없음. 이 작업은 프론트만 바꾼다.

- [ ] **Step 3: 브랜치를 푸시한다**

```bash
git push -u origin feat/onboarding-photo
```

- [ ] **Step 4: PR을 만든다**

`.github/pull_request_template.md`의 5개 섹션(개요 / 변경 내용 / 검증 / 배포 영향 / 구현 노트·알려진 한계)을 그대로 채운다.

`## 검증`에는 실제로 실행한 것만 쓴다 — lint·build. 실제 업로드는 S3 자격증명이 필요해 로컬에서 재현하지 않았다는 사실을 명시한다.

`## 구현 노트 / 알려진 한계`에 반드시 담을 것:

- **설정 화면의 동작이 바뀐다.** 사진을 고르는 즉시 반영되던 것이, 이제 `저장`을 눌러야 반영된다. 사진 한 장만 바꾸려는 사용자도 `저장`을 눌러야 한다.
- 사진 확정이 실패하면 닉네임 저장으로 넘어가지 않는다. 반대로 사진은 반영됐는데 닉네임 저장이 실패하면 그 상태로 화면에 머물고, 다시 누르면 S3에 재업로드하지 않고 이어간다.
- **S3 정리 정책은 이번 범위 밖이다.** 프로필 사진을 바꿀 때마다 옛 객체가 버킷에 그대로 남는다. 설계 문서 §7에 별도 작업으로 적어뒀다.

```bash
gh pr create --base develop --head feat/onboarding-photo --title "feat(profile): 온보딩 사진 변경과 확정 방식 통일" --body-file <작성한 본문 파일>
```

- [ ] **Step 5: 머지는 사용자가 한다 — 체크포인트**

여기서 멈춘다. 머지 후 배포본에서 확인할 것:

- 온보딩에서 사진을 고르고 `시작하기` → 채팅 진입 후 사이드바·프로필에 새 사진, 새로고침해도 유지
- 온보딩에서 사진만 고르고 `나중에 하기` → 사진이 저장되지 않고 provider 사진 유지, 안내 문구 노출
- 설정에서 사진을 고르고 **저장하지 않고 닫기** → 반영되지 않음
- 설정에서 사진과 닉네임을 함께 바꾸고 `저장` → 둘 다 반영, 새로고침해도 유지
- 10MB 초과 파일 → 업로드 전에 오류 표시
