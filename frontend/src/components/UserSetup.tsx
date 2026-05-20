import { useState, FormEvent } from 'react';
import { motion } from 'motion/react';
import { User } from '../types';
import { MessagesSquare, LogIn, UserPlus } from 'lucide-react';
import { AVATAR_GRADIENTS } from '../lib/avatar';

interface Credentials {
  email: string;
  password: string;
  nickname?: string;
}

interface UserSetupProps {
  onComplete: (credentials: Credentials, mode: 'login' | 'signup') => Promise<void>;
  initialUser?: User | null;
}

export default function UserSetup({ onComplete, initialUser }: UserSetupProps) {
  const [mode, setMode] = useState<'login' | 'signup'>('login');
  const [email, setEmail] = useState(initialUser?.email || '');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState(initialUser?.displayName || '');
  const [errorCode, setErrorCode] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [selectedGradient] = useState(
    initialUser?.avatar || AVATAR_GRADIENTS[Math.floor(Math.random() * AVATAR_GRADIENTS.length)]
  );

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setErrorCode('');

    if (!email.trim() || !password.trim()) {
      setErrorCode('이메일과 비밀번호를 입력해 주세요.');
      return;
    }

    if (mode === 'signup' && !nickname.trim()) {
      setErrorCode('회원가입에는 닉네임이 필요합니다.');
      return;
    }

    if (mode === 'signup' && nickname.trim().length > 10) {
      setErrorCode('백엔드 정책상 닉네임은 최대 10자까지 가능합니다.');
      return;
    }

    try {
      setIsSubmitting(true);
      await onComplete({
        email: email.trim(),
        password,
        nickname: nickname.trim(),
      }, mode);
    } catch (error) {
      setErrorCode(error instanceof Error ? error.message : '인증 요청에 실패했습니다.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-950 flex flex-col justify-center items-center p-4 relative overflow-hidden font-sans">
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5, ease: 'easeOut' }}
        className="w-full max-w-md bg-slate-900/80 backdrop-blur-xl border border-slate-800 rounded-2xl p-8 shadow-2xl relative z-10"
      >
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center p-3 bg-indigo-500/10 border border-indigo-500/20 text-indigo-400 rounded-2xl mb-4">
            <MessagesSquare className="w-8 h-8" />
          </div>
          <h1 className="text-3xl font-extrabold text-slate-100 tracking-tight leading-none mb-2">
            Real-Time Chat
          </h1>
          <p className="text-sm text-slate-400">
            Spring Boot 계정으로 로그인해 채팅방에 입장하세요.
          </p>
        </div>

        <div className="grid grid-cols-2 gap-2 bg-slate-950 border border-slate-800 rounded-xl p-1 mb-6">
          <button
            type="button"
            onClick={() => {
              setMode('login');
              setErrorCode('');
            }}
            className={`py-2 rounded-lg text-sm font-bold transition-colors ${mode === 'login' ? 'bg-indigo-600 text-white' : 'text-slate-400 hover:text-slate-100'}`}
          >
            로그인
          </button>
          <button
            type="button"
            onClick={() => {
              setMode('signup');
              setErrorCode('');
            }}
            className={`py-2 rounded-lg text-sm font-bold transition-colors ${mode === 'signup' ? 'bg-indigo-600 text-white' : 'text-slate-400 hover:text-slate-100'}`}
          >
            회원가입
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-5">
          {mode === 'signup' && (
            <div className="flex flex-col items-center gap-2">
              <span className="text-xs font-semibold text-slate-400 tracking-wider uppercase">아바타 미리보기</span>
              <div className={`w-16 h-16 rounded-2xl bg-gradient-to-tr ${selectedGradient} flex items-center justify-center text-2xl font-bold shadow-lg shadow-indigo-500/10`}>
                {nickname ? nickname.charAt(0).toUpperCase() : '?'}
              </div>
            </div>
          )}

          <div>
            <label htmlFor="email" className="block text-sm font-medium text-slate-300 mb-2">
              이메일
            </label>
            <input
              id="email"
              type="email"
              autoFocus
              placeholder="you@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full bg-slate-800/50 border border-slate-700 focus:border-indigo-500/50 focus:ring-2 focus:ring-indigo-500/10 outline-none text-slate-100 rounded-xl py-3 px-4 text-sm transition-all"
            />
          </div>

          <div>
            <label htmlFor="password" className="block text-sm font-medium text-slate-300 mb-2">
              비밀번호
            </label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full bg-slate-800/50 border border-slate-700 focus:border-indigo-500/50 focus:ring-2 focus:ring-indigo-500/10 outline-none text-slate-100 rounded-xl py-3 px-4 text-sm transition-all"
            />
          </div>

          {mode === 'signup' && (
            <div>
              <label htmlFor="nickname" className="block text-sm font-medium text-slate-300 mb-2">
                닉네임 (최대 10자)
              </label>
              <input
                id="nickname"
                type="text"
                placeholder="예: 민준"
                value={nickname}
                onChange={(e) => setNickname(e.target.value)}
                className="w-full bg-slate-800/50 border border-slate-700 focus:border-indigo-500/50 focus:ring-2 focus:ring-indigo-500/10 outline-none text-slate-100 rounded-xl py-3 px-4 text-sm transition-all"
              />
            </div>
          )}

          {errorCode && (
            <p className="text-xs text-rose-400 text-center font-medium">
              {errorCode}
            </p>
          )}

          <button
            type="submit"
            disabled={isSubmitting}
            className="w-full bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white font-semibold py-4 rounded-xl transition-all shadow-lg hover:shadow-indigo-600/30 flex items-center justify-center gap-2 cursor-pointer group"
            id="join-chat-btn"
          >
            {mode === 'login' ? <LogIn className="w-5 h-5" /> : <UserPlus className="w-5 h-5" />}
            <span>{isSubmitting ? '처리 중...' : mode === 'login' ? '로그인' : '회원가입 후 입장'}</span>
          </button>
        </form>
      </motion.div>
    </div>
  );
}
