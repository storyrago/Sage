import { useState, useRef, useEffect, FormEvent, ChangeEvent } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Channel, Message, Presence, User } from '../types';
import Avatar from './Avatar';
import { getRoomMemberProfiles, BackendMember, uploadImage } from '../lib/api';
import { avatarForId } from '../lib/avatar';
import {
  Send, CornerUpLeft, ArrowDown,
  MessageCircle, Hash, Info, Users, X,
  ArrowLeft, Sun, Moon, Settings2, Plus, Loader2
} from 'lucide-react';

interface ChatAreaProps {
  channel: Channel;
  messages: Message[];
  presences: Presence[];
  currentUser: User;
  token: string;
  onSendMessage: (text: string, replyToId?: string) => void;
  onSendImage: (imageUrl: string) => void;
  onTypeStateChange: (isTyping: boolean) => void;
  onOpenProfile: (userId: string) => void;
  onlineMemberIds: Set<string>;
  theme: 'light' | 'dark';
  onToggleTheme: () => void;
  onOpenSettings: () => void;
  onGoHome: () => void;
}

export default function ChatArea({
  channel,
  messages,
  presences,
  currentUser,
  token,
  onSendMessage,
  onSendImage,
  onTypeStateChange,
  onOpenProfile,
  onlineMemberIds,
  theme,
  onToggleTheme,
  onOpenSettings,
  onGoHome
}: ChatAreaProps) {
  const [inputText, setInputText] = useState('');
  const [showScrollBottomBtn, setShowScrollBottomBtn] = useState(false);
  const [participants, setParticipants] = useState<BackendMember[] | null>(null);
  const [showMembers, setShowMembers] = useState(false);
  const [replyMessage, setReplyMessage] = useState<Message | null>(null);
  const [uploading, setUploading] = useState(false);
  const [dragging, setDragging] = useState(false);
  const imageInputRef = useRef<HTMLInputElement>(null);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const typingTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const scrolledChannelRef = useRef<string>('');   // 이 채널에 초기 스크롤(맨아래) 했는지

  // Filter messages for current channel only
  const channelMessages = messages.filter(m => m.channelId === channel.id);

  // Filter typing presence (excluding ourselves)
  const typingUsers = presences.filter(
    p => p.channelId === channel.id && p.isTyping && p.userId !== currentUser.id && p.lastSeen > 0
  );

  // Scroll to bottom
  const scrollToBottom = (behavior: ScrollBehavior = 'smooth') => {
    messagesEndRef.current?.scrollIntoView({ behavior });
  };

  // 이미지가 로드되며 높이가 커지면, 맨 아래 근처였을 때 다시 맨 아래로 (이미지 전송/입장 시)
  const handleImageLoad = () => {
    const el = scrollContainerRef.current;
    if (el && el.scrollHeight - el.scrollTop - el.clientHeight < 400) scrollToBottom('auto');
  };

  // Keep track of scroll position
  const handleScroll = () => {
    if (!scrollContainerRef.current) return;
    const { scrollTop, scrollHeight, clientHeight } = scrollContainerRef.current;

    // Show scroll of bottom button if user has scrolled up significantly
    const isAtBottom = scrollHeight - scrollTop - clientHeight < 150;
    setShowScrollBottomBtn(!isAtBottom);
  };

  // 채널 전환 시 입력/모달 상태 초기화 (스크롤은 아래 통합 이펙트가 처리)
  useEffect(() => {
    setInputText('');
    setShowMembers(false);
    setParticipants(null);
    setReplyMessage(null);
  }, [channel.id]);

  // 스크롤: 방 입장/전환 시 무조건 맨 아래로, 같은 방 새 메시지는 근처에 있을 때만 따라감
  useEffect(() => {
    const el = scrollContainerRef.current;
    if (!el) return;
    if (scrolledChannelRef.current !== channel.id) {
      scrollToBottom('auto');                                      // 입장/전환 → 맨 아래
      if (channelMessages.length > 0) scrolledChannelRef.current = channel.id;  // 메시지 로드 완료 표시
    } else {
      const { scrollTop, scrollHeight, clientHeight } = el;
      if (scrollHeight - scrollTop - clientHeight < 250) {
        setTimeout(() => scrollToBottom('smooth'), 50);            // 새 메시지, 근처면 따라감
      }
    }
  }, [channel.id, channelMessages.length]);

  const openMembers = async () => {
    setShowMembers(true);
    setParticipants(null);
    try {
      const list = await getRoomMemberProfiles(token, channel.id);
      setParticipants(list);
    } catch {
      setParticipants([]);
    }
  };

  const handleSend = (e: FormEvent) => {
    e.preventDefault();
    const cleanText = inputText.trim();
    if (!cleanText) return;

    onSendMessage(cleanText, replyMessage?.id);
    setInputText('');
    setReplyMessage(null);

    // Stop typing state instantly on submit
    if (typingTimeoutRef.current) {
      clearTimeout(typingTimeoutRef.current);
    }
    onTypeStateChange(false);
  };

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

  const handleInputChange = (e: ChangeEvent<HTMLInputElement>) => {
    setInputText(e.target.value);

    // Typing behavior updates
    onTypeStateChange(true);

    if (typingTimeoutRef.current) {
      clearTimeout(typingTimeoutRef.current);
    }

    typingTimeoutRef.current = setTimeout(() => {
      onTypeStateChange(false);
    }, 2000);
  };

  // Simple image file reference detector inside message string
  const getEmbeddedImageUrl = (text: string): string | null => {
    const regex = /(https?:\/\/[^\s]+?\.(?:png|jpe?g|gif|webp))/gi;
    const match = regex.exec(text);
    return match ? match[1] : null;
  };

  // Format historical timestamp nicely
  const formatTime = (epochMs: number): string => {
    const date = new Date(epochMs);
    let hours = date.getHours();
    const minutes = date.getMinutes().toString().padStart(2, '0');
    const ampm = hours >= 12 ? '오후' : '오전';
    hours = hours % 12;
    hours = hours ? hours : 12; // 0 should be 12
    return `${ampm} ${hours}:${minutes}`;
  };

  return (
    <div
      className="flex-1 min-w-0 h-full flex flex-col bg-bg font-sans relative"
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

      {/* CHANNEL CHAT HEADER */}
      <div className="h-16 border-b border-border bg-surface backdrop-blur-md px-3 md:px-6 flex items-center justify-between z-10 relative">
        <div className="flex items-center gap-2 md:gap-3 min-w-0">
          <button
            onClick={onGoHome}
            className="flex items-center gap-1 h-8 px-2 sm:px-2.5 rounded-lg border border-border text-muted hover:text-accent-text hover:border-accent transition-all cursor-pointer text-xs font-semibold flex-shrink-0"
            title="채널 목록으로 나가기"
          >
            <ArrowLeft className="w-4 h-4" /> <span className="hidden sm:inline">나가기</span>
          </button>
          <div className="min-w-0">
            <div className="flex items-center gap-1.5">
              <Hash className="w-4 h-4 text-accent-text mt-0.5" />
              <h2 className="text-md font-bold text-text select-none truncate">
                {channel.name}
              </h2>
            </div>
            <p className="text-[11px] text-muted mt-0.5 truncate select-none leading-none">
              {channel.description}
            </p>
          </div>
        </div>

        <div className="text-right text-xs text-muted font-medium select-none flex items-center gap-2">
          <div className="hidden sm:flex items-center gap-2 mr-1">
            <Info className="w-3.5 h-3.5 text-muted" />
            <span>메시지 {channelMessages.length}개</span>
          </div>

          <button
            onClick={() => (showMembers ? setShowMembers(false) : openMembers())}
            className="w-8 h-8 rounded-lg border border-border text-muted hover:text-accent-text hover:border-accent transition-all cursor-pointer flex items-center justify-center"
            title="참가자 목록"
            aria-label="참가자 목록"
          >
            <Users className="w-4 h-4" />
          </button>

          <button
            onClick={onToggleTheme}
            className="w-8 h-8 rounded-lg border border-border text-muted hover:text-accent-text hover:border-accent transition-all cursor-pointer flex items-center justify-center"
            title={theme === 'dark' ? '라이트 모드' : '다크 모드'}
            aria-label="테마 전환"
          >
            {theme === 'dark' ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
          </button>

          <button
            onClick={onOpenSettings}
            className="w-8 h-8 rounded-lg border border-border text-muted hover:text-accent-text hover:border-accent transition-all cursor-pointer flex items-center justify-center"
            title="설정"
            aria-label="설정"
          >
            <Settings2 className="w-4 h-4" />
          </button>

          <Avatar
            photoUrl={currentUser.photoUrl}
            gradient={currentUser.avatar}
            name={currentUser.displayName}
            className="w-8 h-8 rounded-lg text-xs ml-0.5 flex-shrink-0"
          />
        </div>

        {/* PARTICIPANTS PANEL */}
        <AnimatePresence>
          {showMembers && (
            <motion.div
              initial={{ opacity: 0, y: -8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.15 }}
              className="absolute top-[calc(100%+8px)] right-6 w-72 max-h-96 overflow-y-auto bg-surface border border-border rounded-2xl shadow-xl z-30 p-2"
            >
              <div className="flex items-center justify-between px-2 py-1.5 mb-1">
                <span className="text-xs font-bold text-text select-none">
                  참가자{participants ? ` ${participants.length}명` : ''}
                  {participants
                    ? ` · 온라인 ${participants.filter((m) => onlineMemberIds.has(String(m.id))).length}명`
                    : ''}
                </span>
                <button
                  onClick={() => setShowMembers(false)}
                  className="w-6 h-6 rounded-lg text-muted hover:text-text transition-all cursor-pointer flex items-center justify-center"
                  aria-label="닫기"
                >
                  <X className="w-3.5 h-3.5" />
                </button>
              </div>

              {participants === null && (
                <div className="py-6 text-center text-xs text-muted select-none">불러오는 중…</div>
              )}

              {participants !== null && participants.length === 0 && (
                <div className="py-6 text-center text-xs text-muted select-none">참가자가 없습니다.</div>
              )}

              {participants !== null && participants.length > 0 && (
                <ul className="space-y-1">
                  {participants.map((member) => (
                    <li key={member.id}>
                      <button
                        onClick={() => {
                          onOpenProfile(String(member.id));
                          setShowMembers(false);
                        }}
                        className="w-full flex items-center gap-2.5 px-2 py-1.5 rounded-xl hover:bg-surface-2 transition-colors cursor-pointer text-left"
                      >
                        <div className="relative flex-shrink-0">
                          <Avatar
                            photoUrl={member.profileImageUrl ?? undefined}
                            gradient={avatarForId(member.id)}
                            name={member.nickname}
                            className="w-8 h-8 rounded-lg text-xs"
                          />
                          {onlineMemberIds.has(String(member.id)) && (
                            <span
                              className="absolute -bottom-0.5 -right-0.5 w-2.5 h-2.5 rounded-full bg-emerald-500 border-2 border-surface"
                              title="온라인"
                            />
                          )}
                        </div>
                        <span className="text-sm font-medium text-text truncate">{member.nickname}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {/* MESSAGES VIEWFEED SCROLL AREA */}
      <div
        ref={scrollContainerRef}
        onScroll={handleScroll}
        className="flex-1 overflow-y-auto overflow-x-clip px-3 md:px-6 py-4 space-y-4 relative"
      >

        {/* Topic Welcome Banner */}
        <div className="py-6 border-b border-border mb-4 flex flex-col items-center text-center">
          <div className="w-12 h-12 rounded-2xl bg-accent-subtle border border-transparent text-accent-text flex items-center justify-center mb-3">
            <MessageCircle className="w-6 h-6" />
          </div>
          <h3 className="text-md font-bold text-text">#{channel.name} 채널의 비행이 시작되었습니다!</h3>
          <p className="text-xs text-muted mt-1 max-w-sm">
            {channel.description} 이공간에 참여자들과 유쾌한 대화를 시작해 보세요.
          </p>
        </div>

        {channelMessages.map((msg) => {
          const isSelf = msg.userId === currentUser.id;
          const imageUrl = getEmbeddedImageUrl(msg.text);
          const parentMsg = msg.replyToId ? messages.find(m => m.id === msg.replyToId) : null;

          return (
            <motion.div
              key={msg.id}
              initial={{ opacity: 0, y: 14 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.3, ease: [0.22, 1, 0.36, 1] }}
              className={`flex items-start gap-3 group relative max-w-3xl min-w-0 ${isSelf ? 'ml-auto flex-row-reverse' : 'mr-auto'}`}
              id={`message-bubble-${msg.id}`}
            >

              {/* User Avatar Badge */}
              <button onClick={() => onOpenProfile(msg.userId)} className="cursor-pointer self-start flex-shrink-0" aria-label={`${msg.userName} 프로필`}>
                <Avatar gradient={msg.userAvatar} name={msg.userName} className="w-9 h-9 rounded-xl text-xs font-sans shadow-md" />
              </button>

              {/* Chat Bubble Body Container */}
              <div className="space-y-1 max-w-[85%] min-w-0">

                {/* Header Profile Title */}
                <div className={`flex items-center gap-2 text-[11px] ${isSelf ? 'justify-end' : 'justify-start'}`}>
                  <button onClick={() => onOpenProfile(msg.userId)} className="font-bold text-text cursor-pointer hover:text-accent-text transition-colors" aria-label={`${msg.userName} 프로필`}>
                    {msg.userName}
                  </button>
                  <span className="text-faint font-medium select-none">{formatTime(msg.createdAt)}</span>
                </div>

                {/* Reply display box inside bubble if replies to parent */}
                {parentMsg && (
                  <div className="text-xs px-3 py-1.5 rounded-lg text-muted bg-surface-2 border border-border mb-1 flex items-center gap-1.5">
                    <CornerUpLeft className="w-3 h-3 text-faint flex-shrink-0" />
                    <span className="font-bold text-text flex-shrink-0">{parentMsg.userName}:</span>
                    <span className="truncate min-w-0">{parentMsg.text}</span>
                  </div>
                )}

                {/* Actual Message Text Block */}
                <div className={`px-4 py-2.5 rounded-2xl text-sm leading-relaxed [overflow-wrap:anywhere] whitespace-pre-wrap ${
                  isSelf
                    ? 'bg-accent border border-accent text-accent-fg rounded-tr-none'
                    : 'bg-bubble-other border border-border text-text rounded-tl-none'
                }`}>
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
                        onLoad={handleImageLoad}
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
                        onLoad={handleImageLoad}
                      />
                    </div>
                  )}
                </div>
              </div>

              {/* 답장 버튼 — 말풍선 바로 옆 (hover 시 표시) */}
              <button
                onClick={() => setReplyMessage(msg)}
                className={`self-end flex-shrink-0 opacity-0 group-hover:opacity-100 transition-opacity p-1.5 text-muted hover:text-accent-text rounded-lg cursor-pointer hover:bg-surface-2 ${isSelf ? '-mr-2' : '-ml-2'}`}
                title="답장 달기"
              >
                <CornerUpLeft className="w-3.5 h-3.5" />
              </button>
            </motion.div>
          );
        })}

        <div ref={messagesEndRef} />
      </div>

      {/* FLOAT SCROLL TO BOTTOM BUBBLE BUTTON */}
      <AnimatePresence>
        {showScrollBottomBtn && (
          <motion.button
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 10 }}
            onClick={() => scrollToBottom('smooth')}
            className="absolute bottom-24 right-6 bg-accent hover:bg-accent-hover hover:scale-105 active:scale-95 text-accent-fg py-2 px-3.5 rounded-full shadow-xl border border-accent flex items-center gap-1.5 text-xs font-bold cursor-pointer transition-all z-20"
          >
            <ArrowDown className="w-4 h-4" /> 아래로 이동
          </motion.button>
        )}
      </AnimatePresence>

      {/* LIVE CHAT MESSAGES TYPING STATUS BAR */}
      <div className="h-5 px-4 md:px-6 pb-2 text-xs font-medium text-accent-text flex items-center gap-1.5 select-none font-sans">
        <AnimatePresence>
          {typingUsers.length > 0 && (
            <motion.div
              initial={{ opacity: 0, x: -5 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -5 }}
              className="flex items-center gap-1"
            >
              <div className="flex space-x-0.5 items-center mr-1">
                <span className="w-1.5 h-1.5 bg-accent rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                <span className="w-1.5 h-1.5 bg-accent rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                <span className="w-1.5 h-1.5 bg-accent rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
              </div>
              <span>
                {typingUsers.map(u => u.userName).join(', ')}님이 메시지를 입력 중입니다... ✍️
              </span>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {/* CHAT INPUT AREA PANEL */}
      <div className="border-t border-border p-4 bg-surface relative z-10">

        {/* Active Reply Banner */}
        <AnimatePresence>
          {replyMessage && (
            <motion.div
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 38, opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              className="bg-accent-subtle border border-transparent rounded-xl px-3 flex items-center justify-between text-xs text-accent-text font-bold mb-3 overflow-hidden select-none"
            >
              <span className="truncate flex items-center gap-1.5">
                <CornerUpLeft className="w-3.5 h-3.5" />
                {replyMessage.userName}의 메시지: &quot;{replyMessage.text}&quot; 에 답장하는 중
              </span>
              <button
                onClick={() => setReplyMessage(null)}
                className="text-[10px] uppercase text-accent-text hover:text-text bg-surface-2 border border-border rounded px-1.5 py-0.5 cursor-pointer font-bold"
              >
                취소
              </button>
            </motion.div>
          )}
        </AnimatePresence>

        <form onSubmit={handleSend} className="flex gap-2 items-stretch">

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

          {/* Main Input Text Control */}
          <div className="flex-1 bg-surface-2 border border-border focus-within:border-accent rounded-2xl flex items-center px-4 py-3 transition-colors">
            <input
              type="text"
              placeholder="메시지를 입력하세요..."
              value={inputText}
              onChange={handleInputChange}
              className="bg-transparent flex-1 text-text text-sm outline-none placeholder-text-faint w-full"
            />

            {/* Direct counter indicating limit details */}
            <span className="text-[10px] text-faint font-mono select-none pl-2 border-l border-border ml-2">
              {inputText.length}자
            </span>
          </div>

          <button
            type="submit"
            disabled={!inputText.trim()}
            className="bg-accent hover:bg-accent-hover disabled:opacity-40 text-accent-fg rounded-2xl px-5 flex items-center justify-center cursor-pointer transition-all shadow-md active:scale-95 text-sm"
            id="chat-send-msg-btn"
          >
            <Send className="w-4 h-4" />
          </button>
        </form>
      </div>
    </div>
  );
}
