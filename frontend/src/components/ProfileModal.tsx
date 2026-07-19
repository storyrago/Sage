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
