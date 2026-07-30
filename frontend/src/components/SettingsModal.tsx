import { useState, useEffect, useRef, ChangeEvent } from 'react';
import { X } from 'lucide-react';
import { User } from '../types';
import Avatar from './Avatar';
import { useProfilePhotoDraft } from '../lib/useProfilePhotoDraft';

interface SettingsModalProps {
  open: boolean;
  onClose: () => void;
  currentUser: User;
  token: string;
  onUpdateName: (name: string) => void | Promise<void>;
  onUpdatePhoto: (url: string) => void;
}

export default function SettingsModal({ open, onClose, currentUser, token, onUpdateName, onUpdatePhoto }: SettingsModalProps) {
  const [name, setName] = useState(currentUser.displayName);
  const [saved, setSaved] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const [saving, setSaving] = useState(false);
  const [nameError, setNameError] = useState('');

  const {
    previewUrl,
    error: photoError,
    pick: pickPhoto,
    commit: commitPhoto,
    reset: resetPhoto,
  } = useProfilePhotoDraft(token);

  const handlePhoto = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) pickPhoto(file);
    e.target.value = '';            // 같은 파일을 다시 골라도 onChange가 발생하게 한다
  };

  useEffect(() => {
    if (open) {
      setName(currentUser.displayName);
      setSaved(false);
      setNameError('');
    } else {
      resetPhoto();
    }
  }, [open, currentUser.displayName, resetPhoto]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

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
    setSaving(false);
    setSaved(true);
    setTimeout(() => {
      setSaved(false);
      onClose();
    }, 700);
  };

  const inputCls =
    'w-full bg-surface-2 border border-border rounded-[10px] px-3 py-2.5 text-[13px] text-text outline-none focus:border-accent focus:ring-2 focus:ring-accent/20 transition-all';

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center p-6" role="dialog" aria-modal="true">
      <div className="absolute inset-0 bg-black/55 backdrop-blur-[3px]" onClick={onClose} />
      <div className="relative w-full max-w-[440px] max-h-[calc(100vh-64px)] overflow-y-auto bg-surface border border-border rounded-3xl shadow-2xl">
        <div className="flex items-center justify-between px-5 py-4 border-b border-border">
          <h2 className="text-[17px] font-bold text-text">설정</h2>
          <button onClick={onClose} aria-label="닫기" className="w-8 h-8 rounded-lg border border-border text-muted hover:text-text hover:border-accent transition-all cursor-pointer flex items-center justify-center">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="px-5 py-4 border-b border-border">
          <div className="text-[11px] font-bold tracking-wider uppercase text-muted mb-3">프로필</div>
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
          <div>
            <div className="text-[13px] font-semibold text-text mb-1.5">표시 이름</div>
            <input className={inputCls} value={name} maxLength={20} onChange={(e) => setName(e.target.value)} />
            {nameError && <p className="text-[12px] text-red-300 mt-1.5">{nameError}</p>}
          </div>
        </div>

        <div className="px-5 py-4 flex gap-2.5">
          <button
            onClick={handleSave}
            disabled={saving}
            className="flex-1 rounded-xl py-3 text-[14px] font-bold bg-accent text-accent-fg hover:bg-accent-hover transition-all cursor-pointer disabled:opacity-60 disabled:cursor-default"
          >
            {saving ? '저장 중…' : saved ? '저장됨 ✓' : '저장'}
          </button>
        </div>
      </div>
    </div>
  );
}
