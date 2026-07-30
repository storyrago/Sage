import { useState, useRef, CSSProperties } from 'react';
import { User } from '../types';
import { MessagesSquare } from 'lucide-react';
import { updateNickname, completeOnboarding, toUser } from '../lib/api';
import { useProfilePhotoDraft } from '../lib/useProfilePhotoDraft';

interface OnboardingProps {
  user: User;
  token: string;
  onDone: (updated: User) => void;
}

const MAX_NICKNAME = 20;

export default function Onboarding({ user, token, onDone }: OnboardingProps) {
  const [nickname, setNickname] = useState(user.displayName ?? '');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const {
    previewUrl,
    hasDraft,
    error: photoError,
    pick: pickPhoto,
    commit: commitPhoto,
  } = useProfilePhotoDraft(token);
  const fileRef = useRef<HTMLInputElement>(null);

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

  const handleSkip = async () => {
    setError('');
    setBusy(true);
    try {
      const member = await completeOnboarding(token);
      onDone(toUser(member));
    } catch (e) {
      setError(e instanceof Error ? e.message : '처리에 실패했어요. 다시 시도해 주세요.');
    } finally {
      setBusy(false);
    }
  };

  const inputStyle: CSSProperties = {
    background: '#252E28', border: '1px solid #2D362F', borderRadius: 12,
    padding: '12px 14px', fontSize: 13, color: '#E6ECE8', outline: 'none', width: '100%',
  };

  return (
    <div className="min-h-screen flex items-center justify-center px-6 font-sans"
      style={{ background: '#141917', color: '#E6ECE8' }}>
      <div style={{ width: '100%', maxWidth: 400 }}>

        <div className="text-center" style={{ marginBottom: 16 }}>
          <div style={{ fontSize: 11, letterSpacing: '.16em', color: '#6B7972', fontWeight: 600 }}>ALMOST THERE</div>
          <div style={{ fontSize: 14, color: '#9AA8A0', marginTop: 4 }}>프로필 설정</div>
        </div>

        <div style={{ background: '#1C241F', border: '1px solid #2D362F', borderRadius: 22, padding: '30px 28px' }}>

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

          <h2 className="text-center" style={{ fontWeight: 700, fontSize: 21, color: '#E6ECE8', margin: '0 0 6px' }}>
            어떤 이름으로 부를까요?
          </h2>
          <p className="text-center" style={{ fontSize: 13, color: '#9AA8A0', lineHeight: 1.6, margin: '0 0 24px' }}>
            채팅에서 이 이름으로 표시돼요.<br />나중에 설정에서 바꿀 수 있어요.
          </p>

          <div className="flex items-baseline justify-between" style={{ marginBottom: 5 }}>
            <label style={{ fontSize: 12, fontWeight: 600, color: '#C7D2CB' }}>닉네임</label>
            <span style={{ fontSize: 11, color: '#6B7972' }}>{nickname.trim().length} / {MAX_NICKNAME}</span>
          </div>
          <input
            style={inputStyle}
            value={nickname}
            maxLength={MAX_NICKNAME}
            onChange={(e) => setNickname(e.target.value)}
            id="onboarding-nickname"
          />

          {error && (
            <p style={{ fontSize: 12, color: '#f0a5a5', textAlign: 'center', margin: '12px 0 0' }}>{error}</p>
          )}

          <button type="button" onClick={handleStart} disabled={busy} id="onboarding-start"
            style={{ width: '100%', marginTop: 22, background: '#7AAE92', color: '#12241B', borderRadius: 13, padding: 14, fontWeight: 600, fontSize: 14, border: '1px solid transparent', cursor: 'pointer', opacity: busy ? 0.6 : 1 }}>
            {busy ? '처리 중...' : '시작하기'}
          </button>

          <button type="button" onClick={handleSkip} disabled={busy} id="onboarding-skip"
            style={{ width: '100%', marginTop: 14, background: 'transparent', color: '#6B7972', border: 'none', fontSize: 13, cursor: 'pointer' }}>
            나중에 하기
          </button>

          {hasDraft && (
            <p style={{ fontSize: 11, color: '#6B7972', textAlign: 'center', margin: '8px 0 0' }}>
              고른 사진은 저장되지 않아요
            </p>
          )}

        </div>
      </div>
    </div>
  );
}
