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
  deleteChatRoom,
  deleteMessage,
  exchangeOAuthCode,
  getChatRooms,
  getMe,
  getMessages,
  getUnreadCounts,
  joinChatRoom,
  logout,
  markRoomRead,
  reissueInviteCode,
  sendMessage,
  setRoomPrivacy,
  setUnauthorizedHandler,
  toChannel,
  toMessage,
  toUser,
  updateMessage,
  updateNickname,
} from './lib/api';
import { SpringStompClient } from './lib/stomp';
import { reconnectDelayMs, reconnectExhausted } from './lib/reconnect';
import { createReadMarker } from './lib/readMarker';
import { gapAfterSeq, insertLive, mergeMessages, replaceWithPage } from './lib/timeline';
import { UnreadState, applyUnreadNotice, fromSnapshots, markReadUpTo, toBadges } from './lib/unread';
import { PendingMessage, PendingState, addPending, classifySendError, markFailed, markSending, newClientMessageId, removePending } from './lib/pending';
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
  // 방별 메시지 목록. 각 목록은 seq 오름차순이며 조작은 lib/timeline.ts로만 한다.
  const [messagesByRoom, setMessagesByRoom] = useState<Record<string, Message[]>>({});
  const messagesByRoomRef = useRef(messagesByRoom);
  useEffect(() => { messagesByRoomRef.current = messagesByRoom; }, [messagesByRoom]);
  const [pageState, setPageState] = useState<Record<string, { hasMore: boolean; loading: boolean }>>({});
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
  const [unread, setUnread] = useState<UnreadState>({});
  // "여기부터 안 읽음" 구분선의 경계(읽은 순번). 배지 상태와 달리 보는 동안 화면에 고정해 두는 값이다.
  const [roomLastRead, setRoomLastRead] = useState<Record<string, number | null>>({});
  // 방별 확정 전 내 메시지. 확정은 POST 응답과 같은 clientMessageId의 방송 중 먼저 온 쪽이 한다.
  const [pending, setPending] = useState<PendingState>({});
  const [notice, setNotice] = useState<string | null>(null);
  const [toast, setToast] = useState<{ id: number; text: string } | null>(null);

  const stompRef = useRef<SpringStompClient | null>(null);
  const selectedChannelRef = useRef<string>('');
  // 재연결 시 구독을 허용할 방 집합 — join API 커밋이 확정된 방만 담는다.
  const joinedRoomsRef = useRef<Set<string>>(new Set());
  const typingSentAtRef = useRef<number>(0);
  const typingActiveRef = useRef<boolean>(false);
  const typingExpiryRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());
  const toastIdRef = useRef(0);
  const readMarkerRef = useRef<ReturnType<typeof createReadMarker> | null>(null);
  // 빈 순번을 채우는 중인 방. 같은 방의 조회가 겹치지 않게 한다.
  const fetchingAfterRef = useRef<Set<string>>(new Set());
  // 조회 중에 들어온 요청. 끝난 뒤 한 번 더 확인한다.
  const refetchRequestedRef = useRef<Set<string>>(new Set());
  // 방마다 마지막으로 채우려 한 빈틈(afterSeq:목록 끝 seq). 같은 빈틈은 목록이 늘기 전까지 다시 조회하지 않는다.
  const lastGapAttemptRef = useRef<Map<string, string>>(new Map());

  const { theme, toggleTheme } = useTheme();

  useEffect(() => {
    selectedChannelRef.current = selectedChannelId;
  }, [selectedChannelId]);

  // 방을 떠날 때, 창 안에서 억제돼 예약만 돼 있던 읽음 처리를 그 자리에서 보낸다.
  // 예약된 보충은 방을 떠나면 취소되므로(떠난 뒤 도착한 메시지까지 읽음 처리되는 것을 막기 위해),
  // 떠나는 시점에 보내지 않으면 마지막으로 본 메시지가 안읽음으로 남는다.
  useEffect(() => {
    if (!selectedChannelId) return;
    return () => {
      readMarkerRef.current?.flush();
    };
  }, [selectedChannelId]);

  // 실패를 사용자에게 알린다. 같은 문구가 연달아 나도 다시 보이도록 id를 증가시킨다.
  const notify = useCallback((text: string) => {
    toastIdRef.current += 1;
    setToast({ id: toastIdRef.current, text });
  }, []);

  // Toast에 안정적인 참조로 넘긴다. 매 렌더 새 함수를 넘기면 Toast의 자동 닫힘 타이머가
  // 매번 리셋되어, 3초마다 리렌더되는 재연결 중에는 타이머가 만료될 틈이 없어진다.
  const closeToast = useCallback(() => setToast(null), []);

  // 한 방의 목록만 바꾼다. 바뀐 것이 없으면 이전 상태를 그대로 돌려 리렌더를 막는다.
  const updateRoom = useCallback((roomId: string, change: (list: Message[]) => Message[]) => {
    setMessagesByRoom((prev) => {
      const current = prev[roomId] ?? [];
      const next = change(current);
      return next === current ? prev : { ...prev, [roomId]: next };
    });
  }, []);

  // afterSeq 다음부터 서버의 최신까지 받아 합친다. 빈 순번 채우기와 재접속 따라잡기에 쓴다.
  // 방마다 한 번에 하나만 돈다. 도는 동안 들어온 요청은 기억해 두었다가 끝난 뒤 빈틈을 다시 확인한다.
  const fetchAfter = useCallback(async (roomId: string, afterSeq: number): Promise<void> => {
    if (!token) return;
    if (fetchingAfterRef.current.has(roomId)) {
      refetchRequestedRef.current.add(roomId);
      return;
    }
    fetchingAfterRef.current.add(roomId);
    try {
      let cursor = afterSeq;
      for (;;) {
        const page = await getMessages(token, roomId, { afterSeq: cursor }, 50);
        updateRoom(roomId, (list) => mergeMessages(list, page.messages.map(toMessage)));
        if (!page.hasMore || page.messages.length === 0) break;
        cursor = page.messages[page.messages.length - 1].seq;
      }
    } catch (error) {
      console.error('[Message] 빠진 메시지 조회 실패:', error);
    } finally {
      fetchingAfterRef.current.delete(roomId);
    }
    if (refetchRequestedRef.current.delete(roomId)) {
      const gap = gapAfterSeq(messagesByRoomRef.current[roomId] ?? []);
      if (gap != null) await fetchAfter(roomId, gap);
    }
  }, [token, updateRoom]);

  // 목록에 빈 순번이 보이면 그 자리부터 다시 받는다(실시간 프레임 유실, 서버 간 전달 지연 등).
  // 서버 순번은 빈틈이 없으므로 한 번 받으면 채워진다. 그래도 같은 빈틈이 남으면(서버 이상) 목록이
  // 늘기 전까지 다시 조회하지 않는다 — 조회 결과를 합칠 때마다 이 effect가 다시 돌아 무한 반복되는 것을 막는다.
  useEffect(() => {
    for (const [roomId, list] of Object.entries(messagesByRoom)) {
      const afterSeq = gapAfterSeq(list);
      if (afterSeq == null) continue;
      const attempt = `${afterSeq}:${list[list.length - 1].seq}`;
      if (lastGapAttemptRef.current.get(roomId) === attempt) continue;
      lastGapAttemptRef.current.set(roomId, attempt);
      fetchAfter(roomId, afterSeq);
    }
  }, [messagesByRoom, fetchAfter]);

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
    setMessagesByRoom({});
    setPending({});
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
          setUnread(fromSnapshots(counts));
          setRoomLastRead(Object.fromEntries(counts.map((c) => [String(c.chatroomId), c.lastReadSeq])));
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
          updateRoom(selectedChannelId, (list) => replaceWithPage(list, mapped));
          setPageState((prev) => ({
            ...prev,
            [selectedChannelId]: { hasMore: page.hasMore, loading: false },
          }));
          const newestSeq = mapped.length ? mapped[mapped.length - 1].seq : null;
          // 입장 시 읽음 처리 → 배지 0. 본 페이지의 마지막 순번까지만 읽는다(그 뒤 도착분은 아래 실시간 처리가 맡는다).
          // (구분선용 lastRead 스냅샷은 ChatArea가 입장 시점 값으로 고정)
          // 읽음 처리 실패는 사용자가 조치할 수 없고 다음 입장에서 회복되므로 알리지 않는다.
          // 여기서 새어나가면 "메시지를 불러오지 못했어요"로 잘못 표시된다.
          try {
            await markRoomRead(token, selectedChannelId, newestSeq ?? undefined);
            if (newestSeq != null) {
              setUnread((prev) => markReadUpTo(prev, selectedChannelId, newestSeq));
            }
          } catch (readError) {
            console.error('[Unread] 입장 시 읽음 처리 실패(무시하고 계속):', readError);
          }
          // 다음 입장 때 낡은 구분선이 뜨지 않도록 경계를 전진 (현재 화면은 ChatArea가 입장 시점 값으로 고정)
          if (newestSeq != null) {
            setRoomLastRead((prev) => ({ ...prev, [selectedChannelId]: newestSeq }));
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
  }, [token, selectedChannelId, notify, updateRoom]);

  useEffect(() => {
    if (!token || !user) return;

    let disposed = false;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let attempt = 0;
    setReconnectGaveUp(false);   // 로컬 시도 횟수와 배너 상태가 어긋나지 않게 함께 초기화한다

    const readMarker = createReadMarker(
      (roomId, seq) => markRoomRead(token, roomId, seq).catch((e) => console.error('[Unread] 읽음 처리 실패:', e)),
      (roomId) => selectedChannelRef.current === roomId,
    );
    readMarkerRef.current = readMarker;

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
            // 끊긴 동안 놓친 메시지를 마지막으로 받은 순번 다음부터 따라잡는다.
            // (아직 불러오지 않은 방이면 입장 처리가 최신 페이지를 받는다)
            const list = messagesByRoomRef.current[room] ?? [];
            if (list.length > 0) fetchAfter(room, list[list.length - 1].seq);
          }
          // 재연결 중 놓쳤을 수 있는 안읽음 이벤트를 보정 (경계는 건드리지 않음)
          getUnreadCounts(token)
            .then((counts) => {
              setUnread(fromSnapshots(counts));
            })
            .catch((e) => console.error('[Unread] 재연결 후 안읽음 재조회 실패:', e));
          // 끊긴 동안 강퇴·삭제당했을 수 있다. 그 통지는 재연결 시 구독 거부로만 드러나므로
          // 목록을 다시 불러와야 반영된다.
          refreshRooms(token).catch((e) => console.error('[Room] 재연결 후 목록 갱신 실패:', e));
        },
        onMessage: (backendMessage) => {
          const nextMessage = toMessage(backendMessage);
          const roomId = nextMessage.channelId;
          // 순번 자리에 넣는다. 같은 순번이면 수정·삭제로 덮어쓰고, 불러온 범위보다 오래된 순번은 버린다.
          // 빈 순번이 생기면 위의 effect가 서버에서 채운다.
          updateRoom(roomId, (list) => insertLive(list, nextMessage));
          // 내가 보낸 메시지의 방송이 POST 응답보다 먼저 오면 여기서 확정한다(나중에 온 응답은 같은 seq 덮어쓰기).
          const confirmedId = nextMessage.clientMessageId;
          if (confirmedId) {
            setPending((prev) => removePending(prev, roomId, confirmedId));
          }

          // 보는 중 도착한 메시지도 읽음 처리(스펙). 1초 스로틀 — 메시지마다 쓰기 금지.
          if (roomId === selectedChannelRef.current && token) {
            // 보는 중 도착 = 읽은 것. 다음 입장 때 낡은 구분선이 뜨지 않도록 로컬 경계도 전진시킨다.
            // (현재 열려 있는 화면은 ChatArea가 입장 시점 스냅샷을 ref로 고정해두므로 영향 없음)
            setRoomLastRead((prev) => ({ ...prev, [roomId]: Math.max(prev[roomId] ?? 0, nextMessage.seq) }));
            setUnread((prev) => markReadUpTo(prev, roomId, nextMessage.seq));
            readMarker.mark(roomId, nextMessage.seq);
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
              ? [...others, { userId: memberId, userName: nickname, isTyping: true, channelId: chatroomId, lastSeen: Date.now() }]
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
        onUnread: ({ chatroomId, seq, replyToMe }) => {
          const roomId = String(chatroomId);
          if (roomId === selectedChannelRef.current) return; // 지금 보는 방은 무시
          setUnread((prev) => applyUnreadNotice(prev, roomId, seq, replyToMe));
        },
        onAuthzError: ({ code, message, destination }) => {
          // 입력 거부는 방 접근 문제가 아니므로 구독·선택 상태를 건드리지 않는다.
          if (code === 'INVALID_INPUT_VALUE') {
            notify(message || '메시지를 보내지 못했어요.');
            return;
          }
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
          // 방 자체가 없어진 경우(삭제·강퇴·탈퇴 회수)만 목록에서도 지운다.
          // NOT_JOINED_ROOM 같은 다른 코드는 방이 여전히 존재하므로 여기서 건드리지 않는다.
          if (deniedRoom && (code === 'ROOM_MEMBERSHIP_REVOKED' || code === 'ROOM_DELETED' || code === 'ROOM_KICKED')) {
            setUnread(({ [deniedRoom]: _removed, ...rest }) => rest);
            setRoomLastRead(({ [deniedRoom]: _removed, ...rest }) => rest);
            refreshRooms(token).catch((refreshError) => {
              console.error('[Room] 통지 후 목록 갱신 실패(무시하고 계속):', refreshError);
            });
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
      readMarker.cancel();
      readMarkerRef.current = null;
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

  // 대기 메시지를 서버에 보낸다. 응답(또는 같은 clientMessageId의 실시간 방송) 중 먼저 온 쪽이 확정한다.
  // 실패하면 대기 말풍선을 실패로 바꾼다. 다시 보내도 같은 clientMessageId라 서버가 중복 저장하지 않는다.
  const deliver = useCallback(async (message: PendingMessage) => {
    if (!token) return;
    try {
      const saved = await sendMessage(token, message.channelId, {
        content: message.text,
        replyToId: message.replyToId,
        imageUrl: message.imageUrl,
        clientMessageId: message.clientMessageId,
      });
      updateRoom(message.channelId, (list) => insertLive(list, toMessage(saved)));
      setPending((prev) => removePending(prev, message.channelId, message.clientMessageId));
    } catch (error) {
      setPending((prev) => markFailed(prev, message.channelId, message.clientMessageId, classifySendError(error)));
    }
  }, [token, updateRoom]);

  const handleSendMessage = async (text: string, replyToId?: string, imageUrl?: string) => {
    if (!token || !selectedChannelId) return;
    const message: PendingMessage = {
      clientMessageId: newClientMessageId(),
      channelId: selectedChannelId,
      text,
      replyToId,
      imageUrl,
      createdAt: Date.now(),
      status: 'sending',
      retryable: true,
    };
    setPending((prev) => addPending(prev, message));
    await deliver(message);
  };

  const retryPending = (clientMessageId: string) => {
    const message = (pending[selectedChannelId] ?? []).find((p) => p.clientMessageId === clientMessageId);
    if (!message) return;
    setPending((prev) => markSending(prev, message.channelId, clientMessageId));
    void deliver(message);
  };

  const discardPending = (clientMessageId: string) => {
    setPending((prev) => removePending(prev, selectedChannelId, clientMessageId));
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
    const oldestSeq = messagesByRoomRef.current[roomId]?.[0]?.seq;
    if (!token || !st || !st.hasMore || st.loading || oldestSeq == null) return;
    setPageState((prev) => ({ ...prev, [roomId]: { ...prev[roomId], loading: true } }));
    try {
      const page = await getMessages(token, roomId, { beforeSeq: oldestSeq });
      updateRoom(roomId, (list) => mergeMessages(list, page.messages.map(toMessage)));
      setPageState((prev) => ({ ...prev, [roomId]: { hasMore: page.hasMore, loading: false } }));
    } catch (error) {
      notify(toUserMessage(error, '이전 메시지를 불러오지 못했어요.'));
      setPageState((prev) => ({ ...prev, [roomId]: { ...prev[roomId], loading: false } }));
    }
  }, [token, notify, updateRoom]);

  // 실패를 ChatArea로 전파한다(전송과 동일한 방식) — 실패 시 입력·수정 상태 복원은
  // ChatArea가 담당하므로 여기서 삼키면 안 된다.
  const handleEditMessage = async (messageId: string, content: string) => {
    if (!token || !selectedChannelId) return;
    const updated = await updateMessage(token, selectedChannelId, messageId, content);
    const mapped = toMessage(updated);
    updateRoom(selectedChannelId, (list) => mergeMessages(list, [mapped]));
  };

  const handleDeleteMessage = async (messageId: string) => {
    if (!token || !selectedChannelId) return;
    try {
      const deleted = await deleteMessage(token, selectedChannelId, messageId);
      const mapped = toMessage(deleted);
      updateRoom(selectedChannelId, (list) => mergeMessages(list, [mapped]));
    } catch (error) {
      notify(toUserMessage(error, '메시지 삭제에 실패했어요.'));
    }
  };

  const activeChannel = channels.find((channel) => channel.id === selectedChannelId) || {
    id: selectedChannelId,
    name: '채팅방',
    description: loadingMessage,
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
              messages={messagesByRoom[selectedChannelId] ?? []}
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
              onGoHome={() => {
                setSelectedChannelId('');
                // 랜딩에 머무는 동안엔 방 구독이 전부 회수돼 강퇴·삭제 통지를 받지 못한다.
                // 복귀 시 목록을 다시 불러오는 것이 유일한 회복 경로다.
                if (token) {
                  refreshRooms(token).catch((e) => console.error('[Room] 랜딩 복귀 후 목록 갱신 실패:', e));
                }
              }}
              onImageExpired={() => {
                if (!token || !selectedChannelId) return;
                // 최신 페이지만 다시 받아 서명을 갱신한다. 다른 방 메시지와
                // 이미 더 불러온 과거 메시지는 그대로 두고 최신 구간만 덮어쓴다.
                getMessages(token, selectedChannelId)
                  .then((page) => {
                    updateRoom(selectedChannelId, (list) => mergeMessages(list, page.messages.map(toMessage)));
                  })
                  .catch((e) => console.error('[Image] 만료된 이미지 갱신 실패:', e));
              }}
              onLoadOlder={() => loadOlderMessages(selectedChannelId)}
              hasMoreOlder={pageState[selectedChannelId]?.hasMore ?? false}
              loadingOlder={pageState[selectedChannelId]?.loading ?? false}
              onEditMessage={handleEditMessage}
              onDeleteMessage={handleDeleteMessage}
              unreadFromSeq={roomLastRead[selectedChannelId] ?? null}
              pending={pending[selectedChannelId] ?? []}
              onRetryPending={retryPending}
              onDiscardPending={discardPending}
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
            unread={toBadges(unread)}
            currentUser={user}
            token={token ?? ''}
            onOpenSettings={() => setSettingsOpen(true)}
            onReissueCode={async (id) => {
              if (!token) return;
              try {
                await reissueInviteCode(token, id);
              } catch (error) {
                notify(toUserMessage(error, '초대 코드를 다시 만들지 못했어요.'));
                return;
              }
              try {
                await refreshRooms(token);
              } catch (refreshError) {
                console.error('[Room] 코드 재발급 후 목록 갱신 실패(무시하고 계속):', refreshError);
              }
            }}
            onSetPrivacy={async (id, isPrivate) => {
              if (!token) return;
              try {
                await setRoomPrivacy(token, id, isPrivate);
              } catch (error) {
                notify(toUserMessage(error, '공개 범위를 바꾸지 못했어요.'));
                return;
              }
              try {
                await refreshRooms(token);
              } catch (refreshError) {
                console.error('[Room] 공개 범위 변경 후 목록 갱신 실패(무시하고 계속):', refreshError);
              }
            }}
            onDeleteRoom={async (id) => {
              if (!token) return;
              try {
                await deleteChatRoom(token, id);
              } catch (error) {
                notify(toUserMessage(error, '채팅방을 삭제하지 못했어요.'));
                return;
              }
              // 지금 보고 있던 방이 삭제된 경우를 대비한 정리 — onAuthzError의 회수 처리와 같은 로직이다.
              if (joinedRoomsRef.current.has(id)) {
                joinedRoomsRef.current.delete(id);
                setUnread(({ [id]: _removed, ...rest }) => rest);
                setRoomLastRead(({ [id]: _removed, ...rest }) => rest);
                setSelectedChannelId('');
              }
              try {
                await refreshRooms(token);
              } catch (refreshError) {
                console.error('[Room] 삭제 후 목록 갱신 실패(무시하고 계속):', refreshError);
              }
            }}
            onRefreshRooms={async () => {
              if (!token) return;
              await refreshRooms(token);
            }}
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
