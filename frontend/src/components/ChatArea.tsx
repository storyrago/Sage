import { useState, useRef, useEffect, FormEvent, ChangeEvent } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Channel, Message, Presence, User } from '../types';
import EmojiPicker, { POPULAR_EMOJIS } from './EmojiPicker';
import { 
  Send, Smile, CornerUpLeft, Trash2, ArrowDown, Sparkles, 
  MessageCircle, Image, Hash, AlertCircle, Info, Paperclip
} from 'lucide-react';

interface ChatAreaProps {
  channel: Channel;
  messages: Message[];
  presences: Presence[];
  currentUser: User;
  onSendMessage: (text: string, replyToId?: string) => void;
  onSendReaction: (messageId: string, emoji: string) => void;
  onDeleteMessage: (messageId: string) => void;
  onTypeStateChange: (isTyping: boolean) => void;
}

export default function ChatArea({
  channel,
  messages,
  presences,
  currentUser,
  onSendMessage,
  onSendReaction,
  onDeleteMessage,
  onTypeStateChange
}: ChatAreaProps) {
  const [inputText, setInputText] = useState('');
  const [replyMessage, setReplyMessage] = useState<Message | null>(null);
  const [hoveredMessageId, setHoveredMessageId] = useState<string | null>(null);
  const [activeReactionPickerId, setActiveReactionPickerId] = useState<string | null>(null);
  const [showScrollBottomBtn, setShowScrollBottomBtn] = useState(false);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const typingTimeoutRef = useRef<NodeJS.Timeout | null>(null);

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

  // Keep track of scroll position
  const handleScroll = () => {
    if (!scrollContainerRef.current) return;
    const { scrollTop, scrollHeight, clientHeight } = scrollContainerRef.current;
    
    // Show scroll of bottom button if user has scrolled up significantly
    const isAtBottom = scrollHeight - scrollTop - clientHeight < 150;
    setShowScrollBottomBtn(!isAtBottom);
  };

  // Scroll to bottom on first load or when channel changes
  useEffect(() => {
    scrollToBottom('auto');
    setInputText('');
    setReplyMessage(null);
  }, [channel.id]);

  // Scroll on new messages if close to bottom
  useEffect(() => {
    if (!scrollContainerRef.current) return;
    const { scrollTop, scrollHeight, clientHeight } = scrollContainerRef.current;
    const isCloseToBottom = scrollHeight - scrollTop - clientHeight < 250;
    
    if (isCloseToBottom) {
      setTimeout(() => scrollToBottom('smooth'), 50);
    }
  }, [channelMessages.length]);

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
    <div className="flex-1 h-full flex flex-col bg-slate-950 font-sans relative">
      
      {/* CHANNEL CHAT HEADER */}
      <div className="h-16 border-b border-slate-800 bg-slate-900/60 backdrop-blur-md px-6 flex items-center justify-between z-10">
        <div className="min-w-0">
          <div className="flex items-center gap-1.5">
            <Hash className="w-4 h-4 text-indigo-400 mt-0.5" />
            <h2 className="text-md font-bold text-slate-100 select-none truncate">
              {channel.name}
            </h2>
          </div>
          <p className="text-[11px] text-slate-400 mt-0.5 truncate select-none leading-none">
            {channel.description}
          </p>
        </div>

        <div className="text-right text-xs text-slate-500 font-medium select-none flex items-center gap-2">
          <Info className="w-3.5 h-3.5 text-slate-500" />
          <span>메시지 {channelMessages.length}개 누적</span>
        </div>
      </div>

      {/* MESSAGES VIEWFEED SCROLL AREA */}
      <div
        ref={scrollContainerRef}
        onScroll={handleScroll}
        className="flex-1 overflow-y-auto px-6 py-4 space-y-4 relative"
      >
        
        {/* Topic Welcome Banner */}
        <div className="py-6 border-b border-slate-800/50 mb-4 flex flex-col items-center text-center">
          <div className="w-12 h-12 rounded-2xl bg-indigo-600/10 border border-indigo-500/20 text-indigo-400 flex items-center justify-center mb-3">
            <MessageCircle className="w-6 h-6" />
          </div>
          <h3 className="text-md font-bold text-slate-200">#{channel.name} 채널의 비행이 시작되었습니다!</h3>
          <p className="text-xs text-slate-400 mt-1 max-w-sm">
            {channel.description} 이공간에 참여자들과 유쾌한 대화를 시작해 보세요.
          </p>
        </div>

        {channelMessages.map((msg) => {
          const isSelf = msg.userId === currentUser.id;
          const imageUrl = getEmbeddedImageUrl(msg.text);
          const isMsgHovered = hoveredMessageId === msg.id;
          const isPickerOpen = activeReactionPickerId === msg.id;

          // Find replicated parent text if reply exists
          const parentMsg = msg.replyToId ? messages.find(m => m.id === msg.replyToId) : null;

          return (
            <div
              key={msg.id}
              onMouseEnter={() => setHoveredMessageId(msg.id)}
              onMouseLeave={() => {
                setHoveredMessageId(null);
                setActiveReactionPickerId(null);
              }}
              className={`flex items-start gap-3 group relative max-w-3xl ${isSelf ? 'ml-auto flex-row-reverse' : 'mr-auto'}`}
              id={`message-bubble-${msg.id}`}
            >
              
              {/* User Avatar Badge */}
              <div className={`w-9 h-9 rounded-xl flex-shrink-0 bg-gradient-to-tr ${msg.userAvatar} shadow-md flex items-center justify-center text-xs font-bold font-sans self-start select-none`}>
                {msg.userName ? msg.userName.charAt(0).toUpperCase() : '?'}
              </div>

              {/* Chat Bubble Body Container */}
              <div className="space-y-1 max-w-[85%]">
                
                {/* Header Profile Title */}
                <div className={`flex items-center gap-2 text-[11px] ${isSelf ? 'justify-end' : 'justify-start'}`}>
                  <span className="font-bold text-slate-300">{msg.userName}</span>
                  <span className="text-slate-500 font-medium select-none">{formatTime(msg.createdAt)}</span>
                </div>

                {/* Reply display box inside bubble if replies to parent */}
                {parentMsg && (
                  <div className={`text-xs px-3 py-1.5 rounded-lg text-slate-400 bg-slate-900 border border-slate-800/80 mb-1 flex items-center gap-1.5 ${isSelf ? 'border-r-2 border-r-indigo-500' : 'border-l-2 border-l-slate-600'}`}>
                    <CornerUpLeft className="w-3 h-3 text-slate-500" />
                    <span className="font-bold text-slate-300 truncate max-w-[80px]">{parentMsg.userName}:</span>
                    <span className="truncate">{parentMsg.text}</span>
                  </div>
                )}

                {/* Actual Message Text Block */}
                <div className={`px-4 py-2.5 rounded-2xl text-sm leading-relaxed break-words whitespace-pre-wrap ${
                  isSelf
                    ? 'bg-indigo-600 border border-indigo-500 text-white rounded-tr-none'
                    : 'bg-slate-900 border border-slate-800 text-slate-200 rounded-tl-none'
                }`}>
                  {msg.text}

                  {/* Render Embedded Image Link thumbnail if exists */}
                  {imageUrl && (
                    <div className="mt-2.5 rounded-xl overflow-hidden border border-slate-800 bg-slate-950/40">
                      <img
                        src={imageUrl}
                        alt="Shared interactive link"
                        referrerPolicy="no-referrer"
                        className="max-h-60 w-full object-cover cursor-pointer hover:opacity-95 transition-opacity"
                        onClick={() => window.open(imageUrl, '_blank')}
                      />
                    </div>
                  )}
                </div>

                {/* RENDER REACTIONS BADGES */}
                {msg.reactions && Object.keys(msg.reactions).length > 0 && (
                  <div className={`flex flex-wrap items-center gap-1.5 mt-1.5 ${isSelf ? 'justify-end' : 'justify-start'}`}>
                    {Object.entries(msg.reactions).map(([emoji, rUsers]) => {
                      if (!rUsers || rUsers.length === 0) return null;
                      const hasUserReacted = rUsers.includes(currentUser.id);
                      return (
                        <button
                          key={emoji}
                          onClick={() => onSendReaction(msg.id, emoji)}
                          className={`inline-flex items-center gap-1 py-1 px-2 rounded-lg text-xs font-bold border cursor-pointer select-none transition-all active:scale-90 ${
                            hasUserReacted
                              ? 'bg-indigo-500/10 border-indigo-500 text-indigo-400'
                              : 'bg-slate-900 border-slate-800 text-slate-400 hover:text-slate-200 hover:border-slate-700'
                          }`}
                          title={`${rUsers.length}명이 공감했습니다`}
                        >
                          <span>{emoji}</span>
                          <span className="text-[10px] font-medium">{rUsers.length}</span>
                        </button>
                      );
                    })}
                  </div>
                )}
              </div>

              {/* ACTION BUTTON RAILS ON HOVER */}
              <AnimatePresence>
                {(isMsgHovered || isPickerOpen) && (
                  <motion.div
                    initial={{ opacity: 0, scale: 0.95 }}
                    animate={{ opacity: 1, scale: 1 }}
                    exit={{ opacity: 0, scale: 0.95 }}
                    transition={{ duration: 0.15 }}
                    className={`absolute bottom-[-14px] z-20 flex items-center gap-1 bg-slate-900 border border-slate-800 rounded-xl p-0.5 shadow-lg ${
                      isSelf ? 'left-[-40px]' : 'right-[-40px]'
                    }`}
                  >
                    {/* Emoji Reaction Action */}
                    <div className="relative">
                      <button
                        onClick={() => setActiveReactionPickerId(isPickerOpen ? null : msg.id)}
                        className="p-1.5 text-slate-400 hover:text-yellow-400 rounded-lg cursor-pointer hover:bg-slate-800"
                        title="반응 보내기"
                      >
                        <Smile className="w-3.5 h-3.5" />
                      </button>

                      {isPickerOpen && (
                        <div className="absolute top-[28px] left-[-32px] z-50">
                          <EmojiPicker
                            onSelectEmoji={(emoji) => {
                              onSendReaction(msg.id, emoji);
                              setActiveReactionPickerId(null);
                            }}
                            onClose={() => setActiveReactionPickerId(null)}
                          />
                        </div>
                      )}
                    </div>

                    {/* Reply Action */}
                    <button
                      onClick={() => setReplyMessage(msg)}
                      className="p-1.5 text-slate-400 hover:text-indigo-400 rounded-lg cursor-pointer hover:bg-slate-800"
                      title="답장 단기"
                    >
                      <CornerUpLeft className="w-3.5 h-3.5" />
                    </button>

                    {/* Delete Action (Self Only) */}
                    {isSelf && (
                      <button
                        onClick={() => onDeleteMessage(msg.id)}
                        className="p-1.5 text-slate-400 hover:text-rose-500 rounded-lg cursor-pointer hover:bg-slate-800"
                        title="메시지 삭제"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    )}
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
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
            className="absolute bottom-24 right-6 bg-indigo-600 hover:bg-indigo-500 hover:scale-105 active:scale-95 text-white py-2 px-3.5 rounded-full shadow-xl hover:shadow-indigo-500/10 border border-indigo-500 flex items-center gap-1.5 text-xs font-bold cursor-pointer transition-all z-20"
          >
            <ArrowDown className="w-4 h-4" /> 아래로 이동
          </motion.button>
        )}
      </AnimatePresence>

      {/* LIVE CHAT MESSAGES TYPING STATUS BAR */}
      <div className="h-5 px-6 pb-2 text-xs font-medium text-emerald-400 flex items-center gap-1.5 select-none font-sans">
        <AnimatePresence>
          {typingUsers.length > 0 && (
            <motion.div
              initial={{ opacity: 0, x: -5 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -5 }}
              className="flex items-center gap-1"
            >
              <div className="flex space-x-0.5 items-center mr-1">
                <span className="w-1.5 h-1.5 bg-emerald-400 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                <span className="w-1.5 h-1.5 bg-emerald-400 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                <span className="w-1.5 h-1.5 bg-emerald-400 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
              </div>
              <span>
                {typingUsers.map(u => u.userName).join(', ')}님이 메시지를 입력 중입니다... ✍️
              </span>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {/* CHAT INPUT AREA PANEL */}
      <div className="border-t border-slate-800 p-4 bg-slate-900/40 relative z-10">
        
        {/* Active Reply Banner panel inside panel */}
        <AnimatePresence>
          {replyMessage && (
            <motion.div
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 38, opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              className="bg-indigo-500/10 border border-indigo-500/20 rounded-xl px-3 flex items-center justify-between text-xs text-indigo-300 font-bold mb-3 overflow-hidden select-none"
            >
              <span className="truncate flex items-center gap-1.5">
                <CornerUpLeft className="w-3.5 h-3.5" />
                {replyMessage.userName}의 메시지: &quot;{replyMessage.text}&quot; 에 답장하는 중
              </span>
              <button
                onClick={() => setReplyMessage(null)}
                className="text-[10px] uppercase text-indigo-400 hover:text-slate-100 bg-slate-900 border border-slate-800 rounded px-1.5 py-0.5 cursor-pointer font-bold"
              >
                취소
              </button>
            </motion.div>
          )}
        </AnimatePresence>

        <form onSubmit={handleSend} className="flex gap-2">
          
          {/* Main Input Text Control */}
          <div className="flex-1 bg-slate-950/85 border border-slate-800 focus-within:border-indigo-500/50 rounded-2xl flex items-center px-4 py-3 transition-colors">
            <input
              type="text"
              placeholder="메시지를 입력하세요... (이미지 공유를 원하시면 URL 웹 경로를 입력해 주세요 🖼️)"
              value={inputText}
              onChange={handleInputChange}
              className="bg-transparent flex-1 text-slate-100 text-sm outline-none placeholder-slate-500 w-full"
            />
            
            {/* Direct counter indicating limit details */}
            <span className="text-[10px] text-slate-600 font-mono select-none pl-2 border-l border-slate-800 ml-2">
              {inputText.length}자
            </span>
          </div>

          <button
            type="submit"
            disabled={!inputText.trim()}
            className="bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 text-white rounded-2xl px-5 flex items-center justify-center cursor-pointer transition-all shadow-md active:scale-95 text-sm"
            id="chat-send-msg-btn"
          >
            <Send className="w-4 h-4" />
          </button>
        </form>
      </div>
    </div>
  );
}
