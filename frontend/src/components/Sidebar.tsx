import { useState, FormEvent } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Channel, Presence, User } from '../types';
import {
  Hash, Plus, LogOut, Users, MessageSquareCode,
  Sparkles, Settings2, X, Sun, Moon
} from 'lucide-react';

interface SidebarProps {
  channels: Channel[];
  selectedChannelId: string;
  onSelectChannel: (channelId: string) => void;
  onCreateChannel: (id: string, name: string, description: string) => void;
  presences: Presence[];
  currentUser: User;
  onLogout: () => void;
  onEditProfile: () => void;
  theme: 'light' | 'dark';
  onToggleTheme: () => void;
}

export default function Sidebar({
  channels,
  selectedChannelId,
  onSelectChannel,
  onCreateChannel,
  presences,
  currentUser,
  onLogout,
  onEditProfile,
  theme,
  onToggleTheme
}: SidebarProps) {
  const [isCreatingChannel, setIsCreatingChannel] = useState(false);
  const [newChanName, setNewChanName] = useState('');
  const [newChanDesc, setNewChanDesc] = useState('');
  const [createError, setCreateError] = useState('');

  // Active users in CURRENT channel vs active users overall
  const currentChannelUsers = presences.filter(
    p => p.channelId === selectedChannelId && p.lastSeen > 0
  );

  const handleCreateChanSubmit = (e: FormEvent) => {
    e.preventDefault();
    const cleanName = newChanName.trim();
    if (!cleanName) {
      setCreateError('채널 이름을 입력해 주세요.');
      return;
    }

    const chanId = cleanName.toLowerCase()
      .trim()
      .replace(/[^a-z0-9\s-]/g, '') // slugify roughly
      .replace(/\s+/g, '-');

    if (!chanId) {
      setCreateError('유효한 채널 ID를 만들 수 없습니다. 영문이나 숫자를 포함해 주세요.');
      return;
    }

    if (channels.some(c => c.id === chanId)) {
      setCreateError('이미 존재하는 채널 이름 또는 ID 형태입니다.');
      return;
    }

    onCreateChannel(chanId, cleanName, newChanDesc.trim() || `${cleanName} 대화방`);
    setNewChanName('');
    setNewChanDesc('');
    setCreateError('');
    setIsCreatingChannel(false);
  };

  return (
    <div className="w-80 h-full bg-surface border-r border-border flex flex-col relative font-sans">

      {/* Brand Top Header */}
      <div className="p-4 border-b border-border flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-xl bg-accent flex items-center justify-center text-accent-fg shadow-md">
            <MessageSquareCode className="w-4 h-4" />
          </div>
          <div>
            <h1 className="text-md font-bold text-text flex items-center gap-1.5 leading-none">
              Real-Time Chat
              <span className="flex h-2 w-2 relative">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-online opacity-75"></span>
                <span className="relative inline-flex rounded-full h-2 w-2 bg-online"></span>
              </span>
            </h1>
            <p className="text-[10px] text-muted font-medium tracking-wide mt-1">
              CONNECTED • {presences.filter(p => p.lastSeen > 0).length}명 접속 중
            </p>
          </div>
        </div>
      </div>

      {/* Main lists */}
      <div className="flex-1 overflow-y-auto p-3 space-y-6">

        {/* CHANNELS HEADER */}
        <div>
          <div className="flex items-center justify-between text-muted px-2 py-1 mb-1.5">
            <span className="text-xs font-bold uppercase tracking-wider">채널 목록</span>
            <button
              onClick={() => setIsCreatingChannel(true)}
              className="p-1 text-muted hover:text-accent-text hover:bg-surface-2 rounded-lg transition-all cursor-pointer"
              title="새 채널 만들기"
              id="sidebar-create-chan-btn"
            >
              <Plus className="w-4 h-4" />
            </button>
          </div>

          {/* CHANNEL LIST */}
          <div className="space-y-0.5">
            {channels.map((chan) => {
              const isActive = chan.id === selectedChannelId;
              const typingCount = presences.filter(p => p.channelId === chan.id && p.isTyping).length;

              return (
                <button
                  key={chan.id}
                  onClick={() => onSelectChannel(chan.id)}
                  className={`w-full text-left px-3 py-1.5 rounded-xl flex items-center justify-between group cursor-pointer transition-all ${
                    isActive
                      ? 'bg-accent-subtle border border-transparent text-accent-text'
                      : 'border border-transparent text-muted hover:text-text hover:bg-surface-2'
                  }`}
                  id={`channel-tab-${chan.id}`}
                >
                  <div className="flex items-center gap-2 min-w-0">
                    <Hash className={`w-4 h-4 flex-shrink-0 ${isActive ? 'text-accent-text' : 'text-faint group-hover:text-muted'}`} />
                    <span className="text-sm font-semibold truncate leading-none mt-0.5">{chan.name}</span>
                  </div>

                  {typingCount > 0 && (
                    <span className="text-[9px] bg-accent-subtle border border-transparent text-accent-text px-1.5 py-0.5 rounded-md font-bold animate-pulse">
                      입력 중
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        </div>

        {/* ACTIVE USERS SECTION */}
        <div>
          <div className="flex items-center gap-1.5 text-muted px-2 py-1 mb-2">
            <Users className="w-3.5 h-3.5 text-faint" />
            <span className="text-xs font-bold uppercase tracking-wider">
              {channels.find(c => c.id === selectedChannelId)?.name || '현재 채널'} 접속자 ({currentChannelUsers.length})
            </span>
          </div>

          <div className="space-y-1">
            {currentChannelUsers.map((user) => {
              const isSelf = user.userId === currentUser.id;
              return (
                <div
                  key={user.userId}
                  className="px-2.5 py-1.5 rounded-xl flex items-center gap-2.5 text-text hover:bg-surface-2 transition-all text-sm group"
                >
                  {/* Status avatar with indicator overlay */}
                  <div className="relative">
                    <div className={`w-8 h-8 rounded-lg bg-gradient-to-tr ${user.userAvatar} flex items-center justify-center text-xs font-bold`}>
                      {user.userName ? user.userName.charAt(0).toUpperCase() : '?'}
                    </div>
                    <span className="absolute bottom-[-2px] right-[-2px] h-2.5 w-2.5 rounded-full bg-online border-2 border-surface"></span>
                  </div>

                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between">
                      <span className="font-medium truncate block select-none">
                        {user.userName}
                        {isSelf && <span className="text-[10px] text-accent-text ml-1.5 font-bold">나</span>}
                      </span>
                    </div>
                    {user.isTyping ? (
                      <span className="text-[10px] text-accent-text font-medium">메시지 입력 중...</span>
                    ) : (
                      <span className="text-[10px] text-faint font-medium select-none truncate block">온라인</span>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>

      {/* USER SETTINGS AT BOTTOM */}
      <div className="p-3 border-t border-border bg-surface">
        <div className="flex items-center justify-between gap-2.5 px-2 py-1.5 rounded-2xl bg-surface-2 border border-border">
          <div className="flex items-center gap-2 max-w-[65%]">
            <div className={`w-9 h-9 rounded-xl bg-gradient-to-tr ${currentUser.avatar} flex items-center justify-center text-sm font-bold shadow-md`}>
              {currentUser.displayName.charAt(0).toUpperCase()}
            </div>
            <div className="min-w-0">
              <span className="text-xs font-bold text-text block truncate">{currentUser.displayName}</span>
              <span className="text-[10px] text-accent-text font-semibold select-none flex items-center gap-0.5">
                <Sparkles className="w-2.5 h-2.5" /> 프로필 활성
              </span>
            </div>
          </div>

          <div className="flex items-center gap-1">
            <button
              onClick={onToggleTheme}
              className="p-1.5 text-muted hover:text-accent-text hover:bg-surface-2 rounded-lg cursor-pointer transition-all"
              title={theme === 'dark' ? '라이트 모드' : '다크 모드'}
              aria-label={theme === 'dark' ? '라이트 모드로 전환' : '다크 모드로 전환'}
              id="sidebar-theme-toggle-btn"
            >
              {theme === 'dark' ? <Sun className="w-3.5 h-3.5" /> : <Moon className="w-3.5 h-3.5" />}
            </button>
            <button
              onClick={onEditProfile}
              className="p-1.5 text-muted hover:text-accent-text hover:bg-surface-2 rounded-lg cursor-pointer transition-all"
              title="프로필 수정"
              id="sidebar-edit-profile-btn"
            >
              <Settings2 className="w-3.5 h-3.5" />
            </button>
            <button
              onClick={onLogout}
              className="p-1.5 text-muted hover:text-rose-400 hover:bg-rose-500/10 rounded-lg cursor-pointer transition-all border border-transparent hover:border-rose-500/15"
              title="퇴장하기"
              id="sidebar-logout-btn"
            >
              <LogOut className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      </div>

      {/* CREATE CHANNEL OVERLAY DRAWER */}
      <AnimatePresence>
        {isCreatingChannel && (
          <div className="absolute inset-0 bg-bg/85 backdrop-blur-md z-30 flex flex-col justify-end">
            <motion.div
              initial={{ y: 100, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              exit={{ y: 100, opacity: 0 }}
              transition={{ tension: 250, friction: 30 }}
              className="bg-surface border-t border-border rounded-t-3xl p-6 shadow-2xl space-y-4"
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 text-accent-text">
                  <Sparkles className="w-4 h-4" />
                  <span className="text-sm font-bold">새로운 채팅 채널 생성</span>
                </div>
                <button
                  onClick={() => setIsCreatingChannel(false)}
                  className="p-1 text-muted hover:text-text bg-surface-2 rounded-lg cursor-pointer"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>

              <form onSubmit={handleCreateChanSubmit} className="space-y-4">
                <div>
                  <label className="block text-xs font-bold text-muted uppercase mb-1.5">채널 이름</label>
                  <input
                    type="text"
                    required
                    placeholder="예: 자유 수다, 자바스크립트 스터디"
                    value={newChanName}
                    onChange={(e) => {
                      setNewChanName(e.target.value);
                      setCreateError('');
                    }}
                    className="w-full bg-surface-2 border border-border focus:border-accent focus:ring-1 focus:ring-accent/30 text-sm rounded-xl py-2.5 px-3.5 text-text outline-none transition-all"
                  />
                </div>

                <div>
                  <label className="block text-xs font-bold text-muted uppercase mb-1.5">채널 설명</label>
                  <textarea
                    rows={2}
                    placeholder="채널의 목적이나 간단한 규칙을 설명해 주세요."
                    value={newChanDesc}
                    onChange={(e) => setNewChanDesc(e.target.value)}
                    className="w-full bg-surface-2 border border-border focus:border-accent focus:ring-1 focus:ring-accent/30 text-sm rounded-xl py-2.5 px-3.5 text-text outline-none resize-none transition-all"
                  />
                </div>

                {createError && (
                  <p className="text-xs text-rose-400 text-center font-medium animate-shake">
                    {createError}
                  </p>
                )}

                <button
                  type="submit"
                  className="w-full bg-accent hover:bg-accent-hover text-accent-fg font-semibold py-3 rounded-xl transition-all shadow-md text-sm cursor-pointer flex items-center justify-center gap-1.5"
                >
                  <Plus className="w-4 h-4" /> 채널 생성 완료
                </button>
              </form>
            </motion.div>
          </div>
        )}
      </AnimatePresence>
    </div>
  );
}
