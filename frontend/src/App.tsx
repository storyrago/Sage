import { useState, useEffect, useRef, useCallback } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Channel, Message, Presence, User } from './types';
import Welcome from './components/Welcome';
import Onboarding from './components/Onboarding';
import SettingsModal from './components/SettingsModal';
import ProfileModal from './components/ProfileModal';
import ChatArea from './components/ChatArea';
import ChannelLanding from './components/ChannelLanding';
import Toast from './components/Toast';
import { WifiOff, RefreshCw } from 'lucide-react';
import {
  ApiError,
  createChatRoom,
  deleteAccount,
  deleteMessage,
  exchangeOAuthCode,
  getChatRooms,
  getMe,
  getMessages,
  getUnreadCounts,
  joinChatRoom,
  logout,
  markRoomRead,
  sendMessage,
  setUnauthorizedHandler,
  toChannel,
  toMessage,
  toUser,
  updateMessage,
  updateNickname,
} from './lib/api';
import { SpringStompClient } from './lib/stomp';
import { reconnectDelayMs, reconnectExhausted } from './lib/reconnect';
import { useTheme } from './lib/useTheme';
import { toUserMessage, isSessionExpiredError } from './lib/errors';

interface StoredSession {
  token: string;
  user: User;
}

const SESSION_KEY = 'chat_auth_session';

// /sub/chatrooms/{id}, /sub/chatrooms/{id}/typing, /pub/chatrooms/{id}/messages 등에서 방 id를 뽑는다
const ROOM_DESTINATION = /^\/(?:sub|pub)\/chatrooms\/(\d+)(?:\/|$)/;

function roomIdFromDestination(destination?: string): string | null {
  if (!destination) return null;
  const matched = ROOM_DESTINATION.exec(destination);
  return matched ? matched[1] : null;
}

export default function App() {
  const [token, setToken] = useState<string | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [channels, setChannels] = useState<Channel[]>([]);
  const [selectedChannelId, setSelectedChannelId] = useState<string>('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [pageState, setPageState] = useState<Record<string, { oldestId: number | null; hasMore: boolean; loading: boolean }>>({});
  const pageStateRef = useRef(pageState);
  useEffect(() => { pageStateRef.current = pageState; }, [pageState]);
  const [presences, setPresences] = useState<Presence[]>([]);
  const [onlineMemberIds, setOnlineMemberIds] = useState<Set<string>>(new Set());
  const [connected, setConnected] = useState<boolean>(false);
  const [settingsOpen, setSettingsOpen] = useState<boolean>(false);
  const [profileMemberId, setProfileMemberId] = useState<string | null>(null);
  const [warping, setWarping] = useState<boolean>(false);
  const [reconnectCount, setReconnectCount] = useState<number>(0);
  const [reconnectGaveUp, setReconnectGaveUp] = useState(false);
  const [loadingMessage, setLoadingMessage] = useState<string>('채팅 정보를 불러오는 중입니다.');
  const [unread, setUnread] = useState<Record<string, number>>({});
  const [roomLastRead, setRoomLastRead] = useState<Record<string, number | null>>({});
  const [notice, setNotice] = useState<string | null>(null);
  const [toast, setToast] = useState<{ id: number; text: string } | null>(null);

  const stompRef = useRef<SpringStompClient | null>(null);
  const selectedChannelRef = useRef<string>('');
  // 재연결 시 구독을 허용할 방 집합 — join API 커밋이 확정된 방만 담는다.
  const joinedRoomsRef = useRef<Set<string>>(new Set());
  const typingSentAtRef = useRef<number>(0);
  const typingActiveRef = useRef<boolean>(false);
  const typingExpiryRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());
  const lastMarkReadAtRef = useRef<number>(0);
  const toastIdRef = useRef(0);

  const { theme, toggleTheme } = useTheme();

  useEffect(() => {
    selectedChannelRef.current = selectedChannelId;
  }, [selectedChannelId]);

  // 실패를 사용자에게 알린다. 같은 문구가 연달아 나도 다시 보이도록 id를 증가시킨다.
  const notify = useCallback((text: string) => {
    toastIdRef.current += 1;
    setToast({ id: toastIdRef.current, text });
  }, []);

  // Toast에 안정적인 참조로 넘긴다. 매 렌더 새 함수를 넘기면 Toast의 자동 닫힘 타이머가
  // 매번 리셋되어, 3초마다 리렌더되는 재연결 중에는 타이머가 만료될 틈이 없어진다.
  const closeToast = useCallback(() => setToast(null), []);

  const persistSession = useCallback((nextToken: string, nextUser: User) => {
    const session: StoredSession = { token: nextToken, user: nextUser };
    localStorage.setItem(SESSION_KEY, JSON.stringify(session));
    setToken(nextToken);
    setUser(nextUser);
    setNotice(null);
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
    joinedRoomsRef.current.clear();
    setSelectedChannelId('');
    setConnected(false);
    setWarping(false);   // Welcome이 다시 뜰 때 워프가 켜진 채 시작하면 콘텐츠가 숨겨진다
    setNotice(null);
  }, []);

  // 401은 어느 요청에서든 올 수 있다. 한 곳에서 받아 세션을 정리하고 이유를 알린다.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      clearSession();
      setNotice('세션이 만료되었어요. 다시 로그인해 주세요.');
    });
    return () => setUnauthorizedHandler(null);
  }, [clearSession]);

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

  useEffect(() => {
    const hash = window.location.hash;
    if (!hash) return;
    const params = new URLSearchParams(hash.slice(1));
    const oauthCode = params.get('code');
    const errCode = params.get('oauth_error');
    const legacyToken = params.has('token');
    // 해시 즉시 제거(코드가 URL/히스토리에 남지 않게)
    // 반드시 아래 교환의 첫 await보다 앞에 있어야 한다 — 뒤로 옮기면 StrictMode의 이중 실행에서
    // 같은 코드로 교환이 두 번 나가고(코드는 1회용이므로) 두 번째 요청이 401을 받는다.
    if (oauthCode || errCode || legacyToken) {
      history.replaceState(null, '', window.location.pathname + window.location.search);
    }
    if (errCode) {
      setNotice(
        errCode === 'EMAIL_ALREADY_REGISTERED'
          ? '이미 등록된 이메일이에요. 기존에 사용하던 소셜 계정으로 로그인해 주세요.'
          : '소셜 로그인에 실패했어요. 다시 시도해 주세요.',
      );
      return;
    }
    // 배포 전환·롤백 중 옛 백엔드가 이 형식으로 보낼 수 있다. 새 프론트는 code만 읽으므로
    // 안내 없이 두면 사용자는 주소창에 토큰이 남은 채 이유 없이 로그인 화면에 머무른다.
    if (legacyToken) {
      setNotice('소셜 로그인에 실패했어요. 다시 시도해 주세요.');
      return;
    }
    if (!oauthCode) return;
    (async () => {
      // 워프 연출이 눈에 보이도록 최소 노출 시간을 둔다.
      // 요청이 이미 그보다 오래 걸리면 추가로 기다리지 않는다.
      // 모션 최소화를 켠 사용자는 연출을 보지 못하므로 기다리게 하지 않는다
      const WARP_MIN_MS = window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 900;
      const startedAt = Date.now();
      setWarping(true);
      const guard = setTimeout(() => {
        setWarping(false);
        setNotice('로그인 처리가 지연되고 있어요. 다시 시도해 주세요.');
      }, 10000);
      try {
        const accessToken = await exchangeOAuthCode(oauthCode);
        const member = await getMe(accessToken);
        const elapsed = Date.now() - startedAt;
        if (elapsed < WARP_MIN_MS) {
          await new Promise((resolve) => setTimeout(resolve, WARP_MIN_MS - elapsed));
        }
        persistSession(accessToken, toUser(member));
      } catch (e) {
        console.error('[OAuth] 핸드오프 실패:', e);
        setWarping(false);
        // 5xx는 사용자의 재시도로 해결되지 않는다(서버 쪽 장애) — 4xx와 문구를 분리한다.
        setNotice(
          e instanceof ApiError && e.status >= 500
            ? '지금 로그인 서버에 문제가 있어요. 잠시 후 다시 시도해 주세요.'
            : '로그인 처리에 실패했어요. 다시 시도해 주세요.',
        );
      } finally {
        clearTimeout(guard);
      }
    })();
  }, [persistSession]);

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

        try {
          const counts = await getUnreadCounts(token);
          if (cancelled) return;
          setUnread(Object.fromEntries(counts.map((c) => [String(c.chatroomId), c.unreadCount])));
          setRoomLastRead(Object.fromEntries(counts.map((c) => [String(c.chatroomId), c.lastReadMessageId])));
        } catch (unreadError) {
          console.error('[Unread] 안읽음 개수 조회 실패(무시하고 계속):', unreadError);
        }
      } catch (error) {
        console.error('[Auth] 부트스트랩 실패:', error);
        if (cancelled) return;
        // 세션 만료는 api.ts의 401 처리기가 이미 세션 정리와 안내를 마쳤다.
        // 여기서 clearSession()을 다시 부르면 그 안내를 지운다.
        if (!isSessionExpiredError(error)) {
          notify(toUserMessage(error, '계정 정보를 불러오지 못했어요.'));
        }
      }
    }

    bootstrap();
    return () => {
      cancelled = true;
    };
  }, [token, user?.id, persistSession, refreshRooms, clearSession, notify]);

  useEffect(() => {
    if (!token || !selectedChannelId) return;

    let cancelled = false;

    async function enterRoom() {
      setLoadingMessage('메시지를 불러오는 중입니다.');

      try {
        await joinChatRoom(token, selectedChannelId);
      } catch (error) {
        // 방에 못 들어갔으므로 채팅 화면에 남을 이유가 없다. 랜딩으로 되돌린다.
        if (!cancelled) {
          const message = error instanceof ApiError && error.code === 'INVALID_INVITE_CODE'
            ? '초대 코드가 필요한 방이에요.'
            : toUserMessage(error, '채널에 입장하지 못했어요.');
          notify(message);
          setSelectedChannelId('');
        }
        return;
      }

      joinedRoomsRef.current.add(selectedChannelId);

      // 방 전환이 겹치면 이전 방을 구독하지 않는다.
      if (cancelled || selectedChannelRef.current !== selectedChannelId) return;

      // 입장이 확정된 뒤 구독한다. 메시지 로드보다 먼저 해야 그 사이 도착한 메시지를 놓치지 않는다.
      stompRef.current?.subscribe(selectedChannelId);

      try {
        const page = await getMessages(token, selectedChannelId);
        if (!cancelled) {
          const mapped = page.messages.map(toMessage);
          setMessages((prev) => {
            const otherRooms = prev.filter((message) => message.channelId !== selectedChannelId);
            return [...otherRooms, ...mapped];
          });
          setPageState((prev) => ({
            ...prev,
            [selectedChannelId]: {
              oldestId: page.messages.length ? page.messages[0].messageId : null,
              hasMore: page.hasMore,
              loading: false,
            },
          }));
          // 입장 시 읽음 처리 → 배지 0. (구분선용 lastRead 스냅샷은 App의 roomLastRead를 갱신하지 않아 세션 동안 고정)
          // 읽음 처리 실패는 사용자가 조치할 수 없고 다음 입장에서 회복되므로 알리지 않는다.
          // 여기서 새어나가면 "메시지를 불러오지 못했어요"로 잘못 표시된다.
          try {
            await markRoomRead(token, selectedChannelId);
            setUnread((prev) => ({ ...prev, [selectedChannelId]: 0 }));
          } catch (readError) {
            console.error('[Unread] 입장 시 읽음 처리 실패(무시하고 계속):', readError);
          }
          // 다음 입장 때 낡은 구분선이 뜨지 않도록 경계를 전진 (현재 화면은 ChatArea가 입장 시점 값으로 고정)
          const newestId = page.messages.length ? page.messages[page.messages.length - 1].messageId : null;
          if (newestId != null) {
            setRoomLastRead((prev) => ({ ...prev, [selectedChannelId]: newestId }));
          }
        }
      } catch (error) {
        // 입장은 성공했으므로 채팅 화면에 남는다.
        if (!cancelled) {
          notify(toUserMessage(error, '메시지를 불러오지 못했어요.'));
        }
      }
    }

    enterRoom();

    return () => {
      cancelled = true;
    };
  }, [token, selectedChannelId, notify]);

  useEffect(() => {
    if (!token || !user) return;

    let disposed = false;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let attempt = 0;
    setReconnectGaveUp(false);   // 로컬 시도 횟수와 배너 상태가 어긋나지 않게 함께 초기화한다

    const scheduleReconnect = () => {
      if (disposed || reconnectTimer) return;
      setConnected(false);

      if (reconnectExhausted(attempt)) {
        setReconnectGaveUp(true);   // 조용한 무한 재시도 대신 사용자에게 알린다
        return;
      }

      const delay = reconnectDelayMs(attempt);
      attempt += 1;
      setReconnectCount(attempt);
      reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        connect();
      }, delay);
    };

    const connect = () => {
      const client = new SpringStompClient({
        token,
        onConnect: () => {
          setConnected(true);
          attempt = 0;
          setReconnectCount(0);
          setReconnectGaveUp(false);
          const room = selectedChannelRef.current;
          if (room && joinedRoomsRef.current.has(room)) {
            client.subscribe(room);
          }
          // 재연결 중 놓쳤을 수 있는 안읽음 이벤트를 보정 (경계는 건드리지 않음)
          getUnreadCounts(token)
            .then((counts) => {
              setUnread(Object.fromEntries(counts.map((c) => [String(c.chatroomId), c.unreadCount])));
            })
            .catch((e) => console.error('[Unread] 재연결 후 안읽음 재조회 실패:', e));
        },
        onMessage: (backendMessage) => {
          const nextMessage = toMessage(backendMessage);
          setMessages((prev) => {
            const idx = prev.findIndex((message) => message.id === nextMessage.id);
            if (idx >= 0) {
              const copy = [...prev];
              copy[idx] = nextMessage; // 수정/삭제 등 기존 메시지 제자리 갱신
              return copy;
            }
            // 로드 안 된 메시지: 이 방에서 가장 새 id보다 클 때만 새 메시지로 append.
            // 더 오래된 id면 로드되지 않은 옛 메시지의 수정/삭제이므로 무시(페이지네이션으로 최신 상태를 받음).
            const roomMax = prev.reduce(
              (mx, m) => (m.channelId === nextMessage.channelId ? Math.max(mx, Number(m.id)) : mx),
              0,
            );
            if (Number(nextMessage.id) > roomMax) return [...prev, nextMessage];
            return prev;
          });

          // 보는 중 도착한 메시지도 읽음 처리(스펙). 1초 스로틀 — 메시지마다 쓰기 금지.
          const roomId = String(backendMessage.chatroomId);
          if (roomId === selectedChannelRef.current && token) {
            // 보는 중 도착 = 읽은 것. 다음 입장 때 낡은 구분선이 뜨지 않도록 로컬 경계도 전진시킨다.
            // (현재 열려 있는 화면은 ChatArea가 입장 시점 스냅샷을 ref로 고정해두므로 영향 없음)
            setRoomLastRead((prev) => ({ ...prev, [roomId]: Number(backendMessage.messageId) }));

            const now = Date.now();
            if (now - lastMarkReadAtRef.current >= 1000) {
              lastMarkReadAtRef.current = now;
              markRoomRead(token, roomId).catch((e) => console.error('[Unread] 읽음 처리 실패:', e));
            }
          }
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
        onUnread: ({ chatroomId }) => {
          const roomId = String(chatroomId);
          if (roomId === selectedChannelRef.current) return; // 지금 보는 방은 무시
          setUnread((prev) => ({ ...prev, [roomId]: (prev[roomId] ?? 0) + 1 }));
        },
        onAuthzError: ({ code, message, destination }) => {
          // 세션은 살아있고 특정 목적지만 거부된 것이므로 재연결하지 않는다.
          // 회수는 본인이 방을 나간 결과이므로 오류로 알리지 않는다.
          if (code !== 'ROOM_MEMBERSHIP_REVOKED') {
            notify(message || '이 채널에 접근할 수 없어요.');
          }
          const deniedRoom = roomIdFromDestination(destination);
          if (deniedRoom) {
            joinedRoomsRef.current.delete(deniedRoom);   // 재연결 때 다시 구독하지 않는다
            if (deniedRoom === selectedChannelRef.current) {
              setSelectedChannelId('');   // 볼 수 없는 방에 머무르지 않는다
            }
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

  const handleSendMessage = async (text: string, replyToId?: string, imageUrl?: string) => {
    if (!token || !selectedChannelId) return;

    const sentOverStomp = stompRef.current?.send(selectedChannelId, text, replyToId, imageUrl) ?? false;
    if (!sentOverStomp) {
      const saved = await sendMessage(token, selectedChannelId, text, replyToId, imageUrl);
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

  const handleLogout = async () => {
    // 서버 무효화가 실패해도 이 기기의 세션은 정리한다. 남겨두면 사용자가 갇힌다.
    let serverLogoutFailed = false;
    if (token) {
      try {
        await logout(token);
      } catch {
        serverLogoutFailed = true;
      }
    }
    clearSession();
    if (serverLogoutFailed) {
      setNotice('로그아웃 요청이 서버에 닿지 않았어요. 이 기기에서만 로그아웃됩니다.');
    }
  };

  const loadOlderMessages = useCallback(async (roomId: string) => {
    const st = pageStateRef.current[roomId];
    if (!token || !st || !st.hasMore || st.loading || st.oldestId == null) return;
    setPageState((prev) => ({ ...prev, [roomId]: { ...prev[roomId], loading: true } }));
    try {
      const page = await getMessages(token, roomId, st.oldestId);
      const mapped = page.messages.map(toMessage);
      setMessages((prev) => [...mapped, ...prev]); // prepend older (before는 exclusive라 중복 없음)
      setPageState((prev) => ({
        ...prev,
        [roomId]: {
          oldestId: page.messages.length ? page.messages[0].messageId : prev[roomId].oldestId,
          hasMore: page.hasMore,
          loading: false,
        },
      }));
    } catch (error) {
      notify(toUserMessage(error, '이전 메시지를 불러오지 못했어요.'));
      setPageState((prev) => ({ ...prev, [roomId]: { ...prev[roomId], loading: false } }));
    }
  }, [token, notify]);

  // 실패를 ChatArea로 전파한다(전송과 동일한 방식) — 실패 시 입력·수정 상태 복원은
  // ChatArea가 담당하므로 여기서 삼키면 안 된다.
  const handleEditMessage = async (messageId: string, content: string) => {
    if (!token || !selectedChannelId) return;
    const updated = await updateMessage(token, selectedChannelId, messageId, content);
    const mapped = toMessage(updated);
    setMessages((prev) => prev.map((m) => (m.id === mapped.id ? mapped : m)));
  };

  const handleDeleteMessage = async (messageId: string) => {
    if (!token || !selectedChannelId) return;
    try {
      const deleted = await deleteMessage(token, selectedChannelId, messageId);
      const mapped = toMessage(deleted);
      setMessages((prev) => prev.map((m) => (m.id === mapped.id ? mapped : m)));
    } catch (error) {
      notify(toUserMessage(error, '메시지 삭제에 실패했어요.'));
    }
  };

  const activeChannel = channels.find((channel) => channel.id === selectedChannelId) || {
    id: selectedChannelId || 'empty',
    name: selectedChannelId ? '채팅방' : '채팅방 없음',
    description: selectedChannelId ? loadingMessage : '왼쪽에서 채팅방을 만들거나 백엔드에 채팅방을 생성해 주세요.',
    createdBy: 'system',
    createdAt: Date.now(),
    // 찾지 못한 방을 대신하는 값이라 권한은 전부 닫아 둔다.
    locked: false,
    joined: false,
    owner: false,
  };

  if (!user) {
    return (
      <Welcome
        warping={warping}
        notice={notice}
      />
    );
  }

  if (!user.onboarded) {
    return (
      <Onboarding
        user={user}
        token={token ?? ''}
        onDone={(updated) => {
          if (token) persistSession(token, updated);
        }}
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
            <WifiOff className={`w-4 h-4 flex-shrink-0 ${reconnectGaveUp ? '' : 'animate-pulse'}`} />
            <span>
              {reconnectGaveUp
                ? '실시간 채팅에 연결할 수 없습니다. 페이지를 새로고침해 주세요. REST API는 계속 사용할 수 있습니다.'
                : `실시간 채팅 연결 대기 중입니다. REST API는 계속 사용할 수 있습니다. (${reconnectCount}회)`}
            </span>
            {!reconnectGaveUp && <RefreshCw className="w-3.5 h-3.5 animate-spin ml-2 flex-shrink-0" />}
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
              onSendImage={(url) => handleSendMessage('', undefined, url)}
              onNotify={notify}
              onTypeStateChange={handleTypeStateChange}
              onOpenProfile={(id) => setProfileMemberId(id)}
              onlineMemberIds={onlineMemberIds}
              theme={theme}
              onToggleTheme={toggleTheme}
              onOpenSettings={() => setSettingsOpen(true)}
              onGoHome={() => setSelectedChannelId('')}
              onLoadOlder={() => loadOlderMessages(selectedChannelId)}
              hasMoreOlder={pageState[selectedChannelId]?.hasMore ?? false}
              loadingOlder={pageState[selectedChannelId]?.loading ?? false}
              onEditMessage={handleEditMessage}
              onDeleteMessage={handleDeleteMessage}
              unreadFromId={roomLastRead[selectedChannelId] ?? null}
            />
          </div>
        ) : (
          <ChannelLanding
            channels={channels}
            onSelectChannel={(id) => setSelectedChannelId(id)}
            onCreateChannel={async (name, isPrivate) => {
              if (!token) throw new Error('로그인이 필요합니다.');
              const room = await createChatRoom(token, name, isPrivate);
              // 방은 이미 생성됐다. 목록 갱신 실패로 "만들지 못했어요"를 띄우면
              // 사용자가 재시도해 이름이 중복된 방을 하나 더 만들게 된다.
              try {
                await refreshRooms(token);
              } catch (refreshError) {
                console.error('[Channel] 생성 후 목록 갱신 실패(무시하고 계속):', refreshError);
              }
              // 비공개 방은 초대 코드를 보여주고 사용자가 직접 입장할 때까지
              // 랜딩(ChannelLanding)에 머무른다. 서버는 주인에게 방 목록마다 코드를 계속
              // 내려주지만, 지금은 그것을 다시 보여줄 화면이 없어서 생성 직후 이 자리가
              // 코드를 볼 수 있는 유일한 지점이다.
              return toChannel(room);
            }}
            onJoinRoom={async (id, code) => {
              if (!token) throw new Error('로그인이 필요합니다.');
              await joinChatRoom(token, id, code);
              try {
                await refreshRooms(token);
              } catch (refreshError) {
                console.error('[Channel] 입장 후 목록 갱신 실패(무시하고 계속):', refreshError);
              }
            }}
            onLogout={handleLogout}
            unread={unread}
            currentUser={user}
            onOpenSettings={() => setSettingsOpen(true)}
          />
        )}
      </div>

      <SettingsModal
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        currentUser={user}
        token={token ?? ''}
        onUpdateName={async (displayName) => {
          if (!token) return;
          const member = await updateNickname(token, displayName);
          persistSession(token, toUser(member));
        }}
        onUpdatePhoto={(url) => {
          setUser((prev) => {
            if (!prev) return prev;
            const next = { ...prev, photoUrl: url };
            if (token) persistSession(token, next);
            return next;
          });
        }}
        onDeleteAccount={async () => {
          if (!token) return;
          await deleteAccount(token);
          setSettingsOpen(false);
          clearSession();
          setNotice('탈퇴가 완료됐어요. 그동안 이용해 주셔서 감사합니다.');
        }}
      />

      <ProfileModal
        open={profileMemberId !== null}
        memberId={profileMemberId}
        token={token ?? ''}
        onClose={() => setProfileMemberId(null)}
      />

      <Toast toast={toast} onClose={closeToast} />
    </div>
  );
}
