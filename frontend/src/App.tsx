import { useState, useEffect, useRef, useCallback } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Channel, Message, Presence, User } from './types';
import Welcome from './components/Welcome';
import SettingsModal from './components/SettingsModal';
import ProfileModal from './components/ProfileModal';
import ChatArea from './components/ChatArea';
import ChannelLanding from './components/ChannelLanding';
import { WifiOff, RefreshCw } from 'lucide-react';
import {
  createChatRoom,
  getChatRooms,
  getMe,
  getMessages,
  joinChatRoom,
  login,
  sendMessage,
  signup,
  toChannel,
  toMessage,
  toUser,
} from './lib/api';
import { SpringStompClient } from './lib/stomp';
import { useTheme } from './lib/useTheme';

interface StoredSession {
  token: string;
  user: User;
}

const SESSION_KEY = 'chat_auth_session';

export default function App() {
  const [token, setToken] = useState<string | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [channels, setChannels] = useState<Channel[]>([]);
  const [selectedChannelId, setSelectedChannelId] = useState<string>('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [presences, setPresences] = useState<Presence[]>([]);
  const [onlineMemberIds, setOnlineMemberIds] = useState<Set<string>>(new Set());
  const [connected, setConnected] = useState<boolean>(false);
  const [settingsOpen, setSettingsOpen] = useState<boolean>(false);
  const [profileMemberId, setProfileMemberId] = useState<string | null>(null);
  const [warping, setWarping] = useState<boolean>(false);
  const [reconnectCount, setReconnectCount] = useState<number>(0);
  const [loadingMessage, setLoadingMessage] = useState<string>('채팅 정보를 불러오는 중입니다.');

  const stompRef = useRef<SpringStompClient | null>(null);
  const selectedChannelRef = useRef<string>('');
  const typingSentAtRef = useRef<number>(0);
  const typingActiveRef = useRef<boolean>(false);
  const typingExpiryRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  const { theme, toggleTheme } = useTheme();

  useEffect(() => {
    selectedChannelRef.current = selectedChannelId;
  }, [selectedChannelId]);

  const persistSession = useCallback((nextToken: string, nextUser: User) => {
    const session: StoredSession = { token: nextToken, user: nextUser };
    localStorage.setItem(SESSION_KEY, JSON.stringify(session));
    setToken(nextToken);
    setUser(nextUser);
  }, []);

  const clearSession = useCallback(() => {
    localStorage.removeItem(SESSION_KEY);
    stompRef.current?.disconnect();
    stompRef.current = null;
    setToken(null);
    setUser(null);
    setChannels([]);
    setMessages([]);
    setPresences([]);
    setOnlineMemberIds(new Set());
    typingExpiryRef.current.forEach((t) => clearTimeout(t));
    typingExpiryRef.current.clear();
    typingActiveRef.current = false;
    typingSentAtRef.current = 0;
    setSelectedChannelId('');
    setConnected(false);
  }, []);

  useEffect(() => {
    const saved = localStorage.getItem(SESSION_KEY);
    if (!saved) return;

    try {
      const parsed = JSON.parse(saved) as StoredSession;
      if (parsed?.token && parsed?.user) {
        setToken(parsed.token);
        setUser(parsed.user);
      }
    } catch {
      localStorage.removeItem(SESSION_KEY);
    }
  }, []);

  const refreshRooms = useCallback(async (authToken: string) => {
    const rooms = await getChatRooms(authToken);
    const mappedRooms = rooms.map(toChannel);
    setChannels(mappedRooms);
  }, []);

  useEffect(() => {
    if (!token || !user) return;

    let cancelled = false;

    async function bootstrap() {
      try {
        setLoadingMessage('계정과 채팅방 정보를 확인하는 중입니다.');
        const currentMember = await getMe(token);
        if (cancelled) return;

        const currentUser = toUser(currentMember);
        persistSession(token, currentUser);
        await refreshRooms(token);
      } catch (error) {
        console.error('[Auth] Saved token is invalid:', error);
        if (!cancelled) clearSession();
      }
    }

    bootstrap();
    return () => {
      cancelled = true;
    };
  }, [token, user?.id, persistSession, refreshRooms, clearSession]);

  useEffect(() => {
    if (!token || !selectedChannelId) return;

    let cancelled = false;

    async function loadMessages() {
      try {
        setLoadingMessage('메시지를 불러오는 중입니다.');
        await joinChatRoom(token, selectedChannelId);
        const nextMessages = await getMessages(token, selectedChannelId);
        if (!cancelled) {
          setMessages((prev) => {
            const otherRooms = prev.filter((message) => message.channelId !== selectedChannelId);
            return [...otherRooms, ...nextMessages.map(toMessage)];
          });
        }
      } catch (error) {
        console.error('[ChatRoom] Failed to load messages:', error);
      }
    }

    loadMessages();
    stompRef.current?.subscribe(selectedChannelId);

    return () => {
      cancelled = true;
    };
  }, [token, selectedChannelId]);

  useEffect(() => {
    if (!token || !user) return;

    let disposed = false;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;

    const scheduleReconnect = () => {
      if (disposed || reconnectTimer) return;
      setConnected(false);
      setReconnectCount((count) => count + 1);
      reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        connect();
      }, 3000);
    };

    const connect = () => {
      const client = new SpringStompClient({
        token,
        onConnect: () => {
          setConnected(true);
          setReconnectCount(0);
          if (selectedChannelRef.current) {
            client.subscribe(selectedChannelRef.current);
          }
        },
        onMessage: (backendMessage) => {
          const nextMessage = toMessage(backendMessage);
          setMessages((prev) => {
            if (prev.some((message) => message.id === nextMessage.id)) return prev;
            return [...prev, nextMessage];
          });
        },
        onPresence: (roomId, ids) => {
          if (roomId === selectedChannelRef.current) {
            setOnlineMemberIds(new Set(ids));
          }
        },
        onTyping: ({ chatroomId, memberId, nickname, typing }) => {
          setPresences((prev) => {
            const others = prev.filter((p) => p.userId !== memberId);
            return typing
              ? [...others, { userId: memberId, userName: nickname, userAvatar: '', isTyping: true, channelId: chatroomId, lastSeen: Date.now() }]
              : others;
          });
          const timers = typingExpiryRef.current;
          const existing = timers.get(memberId);
          if (existing) clearTimeout(existing);
          if (typing) {
            timers.set(memberId, setTimeout(() => {
              setPresences((prev) => prev.filter((p) => p.userId !== memberId));
              timers.delete(memberId);
            }, 5000));   // 하트비트(3초)보다 길게
          } else {
            timers.delete(memberId);
          }
        },
        onDisconnect: scheduleReconnect,
        onError: scheduleReconnect,
      });

      stompRef.current = client;
      client.connect();
    };

    connect();

    return () => {
      disposed = true;
      if (reconnectTimer) {
        clearTimeout(reconnectTimer);
      }
      stompRef.current?.disconnect();
      stompRef.current = null;
    };
  }, [token, user?.id]);

  useEffect(() => {
    // presences는 이제 "남의 타이핑"만 담는다 (self는 typingUsers 필터에서 제외되므로 불필요).
    // 채널/유저 전환 시 이전 방의 타이핑 표시를 초기화.
    setPresences([]);
  }, [user, selectedChannelId]);

  useEffect(() => {
    if (!selectedChannelId) {
      stompRef.current?.unsubscribeRoom();
      setOnlineMemberIds(new Set());
    }
  }, [selectedChannelId]);

  const handleSendMessage = async (text: string, replyToId?: string) => {
    if (!token || !selectedChannelId) return;

    const sentOverStomp = stompRef.current?.send(selectedChannelId, text, replyToId) ?? false;
    if (!sentOverStomp) {
      const saved = await sendMessage(token, selectedChannelId, text, replyToId);
      const nextMessage = toMessage(saved);
      setMessages((prev) => [...prev, nextMessage]);
    }
  };

  const handleTypeStateChange = (isTyping: boolean) => {
    const client = stompRef.current;
    if (!client || !selectedChannelId) return;

    if (isTyping) {
      const now = Date.now();
      if (now - typingSentAtRef.current >= 3000) {   // 3초 하트비트 스로틀
        client.sendTyping(selectedChannelId, true);
        typingSentAtRef.current = now;
        typingActiveRef.current = true;
      }
    } else if (typingActiveRef.current) {
      client.sendTyping(selectedChannelId, false);
      typingActiveRef.current = false;
      typingSentAtRef.current = 0;
    }
  };

  const handleLogout = () => {
    clearSession();
  };

  const handleSetupComplete = async (
    credentials: { email: string; password: string; nickname?: string },
    mode: 'login' | 'signup',
  ) => {
    if (mode === 'signup') {
      await signup({
        email: credentials.email,
        password: credentials.password,
        nickname: credentials.nickname ?? '',
      });
    }

    const nextToken = await login({
      email: credentials.email,
      password: credentials.password,
    });
    const currentMember = await getMe(nextToken);
    // 로그인 성공 → warp 전환 재생 후 채팅으로 진입
    setWarping(true);
    await new Promise((resolve) => setTimeout(resolve, 1200));
    persistSession(nextToken, toUser(currentMember));
    setWarping(false);
  };

  const activeChannel = channels.find((channel) => channel.id === selectedChannelId) || {
    id: selectedChannelId || 'empty',
    name: selectedChannelId ? '채팅방' : '채팅방 없음',
    description: selectedChannelId ? loadingMessage : '왼쪽에서 채팅방을 만들거나 백엔드에 채팅방을 생성해 주세요.',
    createdBy: 'system',
    createdAt: Date.now(),
  };

  if (!user) {
    return (
      <Welcome
        onComplete={handleSetupComplete}
        initialUser={user}
        warping={warping}
      />
    );
  }

  return (
    <div className="flex h-screen w-screen bg-bg text-text font-sans select-none overflow-hidden relative sage-chat-enter">
      <AnimatePresence>
        {!connected && (
          <motion.div
            initial={{ y: -50 }}
            animate={{ y: 0 }}
            exit={{ y: -50 }}
            className="absolute top-0 inset-x-0 bg-rose-600 border-b border-rose-500 text-white z-50 text-center py-2 px-4 shadow-xl flex items-center justify-center gap-2 text-xs font-bold leading-none"
          >
            <WifiOff className="w-4 h-4 animate-pulse flex-shrink-0" />
            <span>실시간 채팅 연결 대기 중입니다. REST API는 계속 사용할 수 있습니다. ({reconnectCount}회)</span>
            <RefreshCw className="w-3.5 h-3.5 animate-spin ml-2 flex-shrink-0" />
          </motion.div>
        )}
      </AnimatePresence>

      <div className={`flex w-full h-full transition-all duration-350 ${!connected ? 'pt-8' : ''}`}>
        {selectedChannelId ? (
          <div className="flex w-full h-full sage-chat-enter">
            <ChatArea
              channel={activeChannel}
              messages={messages}
              presences={presences}
              currentUser={user}
              token={token ?? ''}
              onSendMessage={handleSendMessage}
              onTypeStateChange={handleTypeStateChange}
              onOpenProfile={(id) => setProfileMemberId(id)}
              onlineMemberIds={onlineMemberIds}
              theme={theme}
              onToggleTheme={toggleTheme}
              onOpenSettings={() => setSettingsOpen(true)}
              onGoHome={() => setSelectedChannelId('')}
            />
          </div>
        ) : (
          <ChannelLanding
            channels={channels}
            onSelectChannel={(id) => setSelectedChannelId(id)}
            onCreateChannel={async (name) => {
              if (!token) return;
              const room = await createChatRoom(token, name);
              await refreshRooms(token);
              setSelectedChannelId(String(room.id));
            }}
            onLogout={handleLogout}
          />
        )}
      </div>

      <SettingsModal
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        currentUser={user}
        token={token ?? ''}
        onUpdateName={(displayName) => setUser((u) => (u ? { ...u, displayName } : u))}
        onUpdatePhoto={(url) => {
          setUser((prev) => {
            if (!prev) return prev;
            const next = { ...prev, photoUrl: url };
            if (token) persistSession(token, next);
            return next;
          });
        }}
      />

      <ProfileModal
        open={profileMemberId !== null}
        memberId={profileMemberId}
        token={token ?? ''}
        onClose={() => setProfileMemberId(null)}
      />
    </div>
  );
}
