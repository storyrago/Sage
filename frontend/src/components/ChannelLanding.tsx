import { useEffect, useRef, useState, type CSSProperties, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent } from 'react';
import { Plus, Hash, Code, Music, Shuffle, Gamepad2, MessageCircle, Bell, X, LogOut, Lock, UserX, Crown } from 'lucide-react';
import { Channel, User } from '../types';
import Avatar from './Avatar';
import { ApiError, BackendBannedMember, getRoomBans, getRoomMemberProfiles, leaveChatRoom, RoomMemberProfile, transferOwnership, unbanMember } from '../lib/api';
import { toUserMessage } from '../lib/errors';
import { hash, unit, boardHeightPx, STAMP_W, STAMP_H, MIN_BOARD_W } from '../lib/stampLayout';
import { parseSavedPositions, resolveStampPositions, stampStorageKey, type SavedPositionMap } from '../lib/stampPlacement';

// 우표를 옮기다 놓친 게 아니라 진짜 드래그했다고 볼 최소 이동 거리(px) — 이 밑이면 클릭(확대)으로 본다.
const DRAG_THRESHOLD = 5;

interface DragState {
  id: string;
  pointerId: number;
  grabDX: number; // 포인터가 우표를 잡은 지점의 우표 내부 오프셋(px)
  grabDY: number;
  startX: number; // 드래그 판정용 시작 좌표(clientX/Y)
  startY: number;
  moved: boolean; // DRAG_THRESHOLD를 넘어 실제 드래그로 확정됐는지
  leftPct: number;
  topPct: number;
}

// localStorage는 회원별로 분리해서 읽는다 — 접근 자체가 막혀 있거나(프라이빗 모드 등)
// 저장값이 깨져 있어도 앱이 죽지 않고 빈 배치(=자동 배치)로 떨어진다.
function readSavedPositions(userId: string): SavedPositionMap {
  try {
    return parseSavedPositions(window.localStorage.getItem(stampStorageKey(userId)));
  } catch {
    return {};
  }
}

const ICONS = [Hash, Code, Music, Shuffle, Gamepad2, MessageCircle, Bell];

// 우표 우상단 마스킹테이프의 각도·길이·오프셋을 채널마다 은근하게 흔든다(unit()으로 축마다 독립적인 값).
// (테스트에서 회귀 검증용으로 직접 import)
export function tapeVars(id: string): CSSProperties {
  const rot = 45 + Math.round((unit(id, 13) * 2 - 1) * 12); // 45deg ±12deg
  const scale = 1 + (unit(id, 29) * 2 - 1) * 0.25; // ±25%
  const offset = Math.round((unit(id, 101) * 2 - 1) * 3); // ±3px

  return {
    '--tape-rot': `${rot}deg`,
    '--tape-w': `${Math.round(34 * scale)}px`,
    '--tape-h': `${Math.round(13 * scale)}px`,
    '--tape-top': `${-5 + offset}px`,
    '--tape-right': `${-5 + offset}px`,
    '--tape-w-md': `${Math.round(46 * scale)}px`,
    '--tape-h-md': `${Math.round(17 * scale)}px`,
    '--tape-top-md': `${-7 + offset}px`,
    '--tape-right-md': `${-7 + offset}px`,
  } as CSSProperties;
}

// 확대 우표 크기 (반응형 — 좁은 폭에서 넘치지 않게)
function calcBig() {
  const w = typeof window !== 'undefined' ? window.innerWidth : 1200;
  const bw = Math.min(300, Math.round(w * 0.84));
  return { w: bw, h: Math.round((bw * 4) / 3) };
}

function fmtDate(ms: number) {
  const d = new Date(ms);
  return `${String(d.getMonth() + 1).padStart(2, '0')}/${d.getFullYear()}`;
}

// 세로 직사각형 우표 얼굴 (갤러리 small / 확대 big 공용)
function StampFace({ ch, big = false }: { ch: Channel; big?: boolean }) {
  const Icon = ICONS[hash(ch.id) % ICONS.length];
  const tint = hash(ch.id) % 2 === 0 ? '#dfe9df' : '#e8ecdc';
  return (
    <div className={`stamp-paper bg-[#fbf9f3] w-full h-full flex flex-col ${big ? 'p-4' : 'p-2.5'}`}>
      <div className={`font-mono text-[#5a6357] uppercase tracking-tight leading-tight ${big ? 'text-[13px]' : 'text-[8px]'}`}>
        {ch.name}
      </div>
      <div
        className={`flex-1 flex items-center justify-center overflow-hidden ${big ? 'my-3 rounded-[3px]' : 'my-1.5 rounded-[2px]'}`}
        style={{
          background: `radial-gradient(115% 115% at 50% 25%, ${tint}, #cbd7c4 68%, #b6c4ac)`,
          boxShadow: 'inset 0 0 0 1px rgba(90,99,87,0.18)',
        }}
      >
        <Icon className={big ? 'w-20 h-20' : 'w-7 h-7'} style={{ color: '#5E9079', opacity: 0.8 }} strokeWidth={1.5} />
      </div>
      <div className={`font-mono text-[#5a6357] self-end leading-none ${big ? 'text-[12px]' : 'text-[7px]'}`}>
        {fmtDate(ch.createdAt)}
      </div>
    </div>
  );
}

// 안읽음 개수를 새기는 소인(消印) — 원형 스탬프 + 킬러바
function Postmark({ count }: { count: number }) {
  const label = count > 99 ? '99+' : String(count);
  const wide = label.length >= 3;
  return (
    <svg
      viewBox="0 0 186 132"
      role="img"
      aria-label={`안 읽음 ${label}개`}
      className="postmark-strike pointer-events-none absolute left-[9px] top-[13px] w-[123px] h-[87px] md:left-[12px] md:top-[18px] md:w-[168px] md:h-[119px]"
      style={{ transform: 'rotate(-13deg)', opacity: 0.92 }}
    >
      <g filter="url(#pm-rough)" fill="none" stroke="#C2402C" strokeLinecap="round">
        <circle cx="56" cy="58" r="45" strokeWidth="3.4" />
        <circle cx="56" cy="58" r="37" strokeWidth="1.2" />
        <g className="hidden md:inline">
          <path d="M95,34 q9,-4 18,0 t18,0 t18,0 t18,0" strokeWidth="3.6" />
          <path d="M100,45 q9,-4 18,0 t18,0 t18,0 t18,0" strokeWidth="3.6" />
          <path d="M100,71 q9,-4 18,0 t18,0 t18,0 t18,0" strokeWidth="3.6" />
          <path d="M95,82 q9,-4 18,0 t18,0 t18,0 t18,0" strokeWidth="3.6" />
        </g>
      </g>
      <g filter="url(#pm-rough)" fill="#C2402C">
        <text
          x="56"
          y={wide ? 71 : 75}
          textAnchor="middle"
          style={{ fontWeight: 800, fontSize: wide ? 33 : 46, letterSpacing: wide ? '-0.03em' : '-0.045em', fontVariantNumeric: 'tabular-nums' }}
        >
          {label}
        </text>
      </g>
    </svg>
  );
}

// 초대 코드 표시 + 복사 — 생성 직후 다이얼로그와 확대된 우표(주인 전용) 양쪽에서 쓴다.
// 담긴 컨테이너의 배경이 서로 달라(테마 대응 다이얼로그 vs 항상 어두운 코르크보드) 박스 색만 호출부에서 받는다.
function InviteCode({ code, boxClassName }: { code: string; boxClassName: string }) {
  const [copied, setCopied] = useState(false);
  useEffect(() => setCopied(false), [code]); // 재발급 등으로 코드가 바뀌면 "복사됨" 표시를 지운다
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
    } catch {
      // 클립보드 접근이 막혀도(권한 거부 등) 코드는 화면에 그대로 있어 수동 선택은 가능하다.
    }
  };
  return (
    <div className="flex items-center gap-2 w-full">
      <code className={`flex-1 min-w-0 border rounded-[10px] px-3 py-2.5 text-[14px] font-mono tracking-wider select-all truncate ${boxClassName}`}>
        {code}
      </code>
      <button onClick={copy} className="btn-label flex-shrink-0 px-3 py-2.5 text-[13px] font-semibold cursor-pointer">
        {copied ? '복사됨' : '복사'}
      </button>
    </div>
  );
}

interface Origin { cx: number; cy: number; scale: number; rot: number; }

interface Props {
  channels: Channel[];
  onSelectChannel: (id: string) => void;
  onCreateChannel: (name: string, isPrivate: boolean) => Promise<Channel>;
  onJoinRoom: (channelId: string, inviteCode: string) => Promise<void>;
  onLogout: () => void;
  unread?: Record<string, number>;
  currentUser: User;
  token: string;
  onOpenSettings: () => void;
  onReissueCode: (channelId: string) => Promise<void>;
  onSetPrivacy: (channelId: string, isPrivate: boolean) => Promise<void>;
  onDeleteRoom: (channelId: string) => Promise<void>;
  onRefreshRooms: () => Promise<void>;
}

export default function ChannelLanding({ channels, onSelectChannel, onCreateChannel, onJoinRoom, onLogout, unread, currentUser, token, onOpenSettings, onReissueCode, onSetPrivacy, onDeleteRoom, onRefreshRooms }: Props) {
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [isPrivate, setIsPrivate] = useState(false);
  const [busy, setBusy] = useState(false);
  const [createError, setCreateError] = useState('');
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  // 생성 직후 모달에서 보여줄 방(초대 코드 포함) — 확대된 우표 쪽은 channels[].inviteCode를 바로 쓴다.
  const [createdRoom, setCreatedRoom] = useState<Channel | null>(null);

  // 확대 상태 (자기 자리 → 중앙 FLIP)
  const [focusedId, setFocusedId] = useState<string | null>(null);
  const [origin, setOrigin] = useState<Origin | null>(null);
  const [open, setOpen] = useState(false);
  const [tilt, setTilt] = useState({ rx: 0, ry: 0 });
  const [big, setBig] = useState(calcBig);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 잠긴 방 초대 코드 입력 (확대된 우표에서만 쓴다)
  const [joinCode, setJoinCode] = useState('');
  const [joinBusy, setJoinBusy] = useState(false);
  const [joinError, setJoinError] = useState('');

  // 방장 액션 (확대된 우표, 주인 전용)
  const [ownerBusy, setOwnerBusy] = useState<'reissue' | 'privacy' | 'transfer' | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  // 차단 목록 (확대된 우표, 주인 전용)
  const [showBans, setShowBans] = useState(false);
  const [bans, setBans] = useState<BackendBannedMember[] | null>(null);
  const [bansError, setBansError] = useState('');
  const [unbanningId, setUnbanningId] = useState<number | null>(null);
  const [unbanError, setUnbanError] = useState('');

  // 방장 넘기기 (확대된 우표, 주인 전용) — 후보 목록 로딩 → 대상 선택 → 확인
  const [showTransfer, setShowTransfer] = useState(false);
  const [transferCandidates, setTransferCandidates] = useState<RoomMemberProfile[] | null>(null);
  const [transferError, setTransferError] = useState('');
  const [transferTargetId, setTransferTargetId] = useState<number | null>(null);

  // 방 나가기 (확대된 우표, 주인이 아니고 참여 중일 때만)
  const [leaveBusy, setLeaveBusy] = useState(false);
  const [confirmLeave, setConfirmLeave] = useState(false);
  const [leaveError, setLeaveError] = useState('');

  // 우표 드래그 배치 (개인용 — localStorage, 회원별 키)
  const boardRef = useRef<HTMLDivElement>(null);
  const [boardW, setBoardW] = useState(MIN_BOARD_W);
  const [savedPositions, setSavedPositions] = useState<SavedPositionMap>(() => readSavedPositions(currentUser.id));
  const [drag, setDrag] = useState<DragState | null>(null);
  const suppressClickRef = useRef(false); // 드래그 직후 이어지는 click을 openFocus로 새지 않게 막는다

  const focused = channels.find((c) => c.id === focusedId) || null;

  useEffect(() => {
    const onResize = () => setBig(calcBig());
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  // 계정이 바뀌면(같은 브라우저에서 다른 회원으로 로그인) 그 회원의 저장 배치를 새로 읽는다.
  useEffect(() => {
    setSavedPositions(readSavedPositions(currentUser.id));
  }, [currentUser.id]);

  // 보드의 실제 렌더링 폭을 재서 드래그 시 경계 가두기에 쓴다 — 보드 폭이 유동이라
  // 창 크기가 바뀌면 다시 잰다.
  useEffect(() => {
    const el = boardRef.current;
    if (!el) return;
    const measure = () => setBoardW(el.clientWidth || MIN_BOARD_W);
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [channels.length]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && focusedId) closeFocus(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusedId]);

  useEffect(() => () => { if (closeTimer.current) clearTimeout(closeTimer.current); }, []);

  // 다른 우표를 열거나 닫을 때 이전 방의 코드·오류·삭제 확인 상태가 남아있지 않게 한다.
  useEffect(() => {
    setJoinCode('');
    setJoinError('');
    setConfirmDelete(false);
    setShowBans(false);
    setBans(null);
    setBansError('');
    setUnbanError('');
    setShowTransfer(false);
    setTransferCandidates(null);
    setTransferError('');
    setTransferTargetId(null);
    setConfirmLeave(false);
    setLeaveError('');
  }, [focusedId]);

  const submitJoinCode = async () => {
    if (!focused || joinBusy) return;
    const code = joinCode.trim();
    if (!code) return;
    setJoinBusy(true);
    setJoinError('');
    try {
      await onJoinRoom(focused.id, code);
      onSelectChannel(focused.id);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'ROOM_BANNED') {
        setJoinError('이 채팅방에 참여할 수 없어요.');
      } else if (err instanceof ApiError && err.code === 'INVALID_INVITE_CODE') {
        setJoinError('초대 코드가 올바르지 않아요.');
      } else {
        setJoinError(toUserMessage(err, '입장하지 못했어요.'));
      }
    } finally {
      setJoinBusy(false);
    }
  };

  const reissueCode = async () => {
    if (!focused || ownerBusy) return;
    setOwnerBusy('reissue');
    try {
      await onReissueCode(focused.id);
    } catch {
      // 실패 토스트는 App이 담당
    } finally {
      setOwnerBusy(null);
    }
  };

  const togglePrivacy = async () => {
    if (!focused || ownerBusy) return;
    setOwnerBusy('privacy');
    try {
      await onSetPrivacy(focused.id, !focused.locked);
    } catch {
      // 실패 토스트는 App이 담당
    } finally {
      setOwnerBusy(null);
    }
  };

  const openBans = async () => {
    if (!focused) return;
    setShowBans(true);
    setBans(null);
    setBansError('');
    setUnbanError('');
    try {
      const list = await getRoomBans(token, focused.id);
      setBans(list);
    } catch (err) {
      setBansError(toUserMessage(err, '차단 목록을 불러오지 못했어요.'));
    }
  };

  const unban = async (memberId: number) => {
    if (!focused || unbanningId !== null) return;
    setUnbanningId(memberId);
    setUnbanError('');
    try {
      await unbanMember(token, focused.id, String(memberId));
      setBans((prev) => (prev ? prev.filter((b) => b.memberId !== memberId) : prev));
    } catch (err) {
      setUnbanError(toUserMessage(err, '차단을 해제하지 못했어요.'));
    } finally {
      setUnbanningId(null);
    }
  };

  const openTransfer = async () => {
    if (!focused) return;
    setShowTransfer(true);
    setTransferCandidates(null);
    setTransferError('');
    setTransferTargetId(null);
    try {
      const profiles = await getRoomMemberProfiles(token, focused.id);
      setTransferCandidates(profiles.filter((m) => String(m.id) !== currentUser.id));
    } catch (err) {
      setTransferError(toUserMessage(err, '참가자 목록을 불러오지 못했어요.'));
    }
  };

  const confirmTransfer = async () => {
    if (!focused || transferTargetId === null || ownerBusy) return;
    setOwnerBusy('transfer');
    setTransferError('');
    try {
      await transferOwnership(token, focused.id, String(transferTargetId));
    } catch (err) {
      if (err instanceof ApiError && err.code === 'NOT_JOINED_ROOM') {
        setTransferError('그 참가자는 이미 방을 나갔어요.');
      } else {
        setTransferError(toUserMessage(err, '방장을 넘기지 못했어요.'));
      }
      setOwnerBusy(null);
      return;
    }
    setShowTransfer(false);
    setTransferTargetId(null);
    try {
      await onRefreshRooms();
    } catch (refreshError) {
      console.error('[Room] 방장 위임 후 목록 갱신 실패(무시하고 계속):', refreshError);
    } finally {
      setOwnerBusy(null);
    }
  };

  const leaveRoom = async () => {
    if (!focused || leaveBusy) return;
    setLeaveBusy(true);
    setLeaveError('');
    try {
      await leaveChatRoom(token, focused.id);
    } catch (err) {
      setLeaveError(toUserMessage(err, '방을 나가지 못했어요.'));
      setLeaveBusy(false);
      return;
    }
    setConfirmLeave(false);
    try {
      await onRefreshRooms();
    } catch (refreshError) {
      console.error('[Room] 방 나가기 후 목록 갱신 실패(무시하고 계속):', refreshError);
    } finally {
      setLeaveBusy(false);
    }
  };

  // 방 자체가 없어지므로 되돌아갈 원래 자리도 없다 — 애니메이션으로 닫지 않고 즉시 오버레이를 정리한다.
  // setFocusedId(null)을 목록 갱신(onDeleteRoom 내부의 refreshRooms)보다 먼저 불러야
  // focusedId만 남고 focused가 null이 되어 배경 딤이 사라지는 상태를 피할 수 있다.
  const deleteRoom = () => {
    if (!focused || ownerBusy) return;
    const id = focused.id;
    setFocusedId(null);
    setOrigin(null);
    onDeleteRoom(id);
  };

  const submit = async () => {
    const n = name.trim();
    if (!n || busy) return;
    setBusy(true);
    setCreateError('');
    try {
      const room = await onCreateChannel(n, isPrivate);
      setName('');
      setIsPrivate(false);
      if (room.locked && room.inviteCode) {
        setCreatedRoom(room); // 코드를 보여주고, 사용자가 직접 닫을 때까지 대기
      } else {
        setCreating(false);
        onSelectChannel(room.id);
      }
    } catch (err) {
      // 다이얼로그를 열어둔 채 입력값을 유지해 그대로 다시 시도할 수 있게 한다.
      setCreateError(toUserMessage(err, '채널을 만들지 못했어요.'));
    } finally {
      setBusy(false);
    }
  };

  const closeCreateDialog = () => {
    setCreating(false);
    setCreateError('');
    setCreatedRoom(null);
  };

  const enterCreatedRoom = () => {
    if (!createdRoom) return;
    onSelectChannel(createdRoom.id);
    closeCreateDialog();
  };

  // 클릭한 우표의 화면 위치를 잡아 중앙으로 날아오게
  const openFocus = (ch: Channel, el: HTMLElement) => {
    if (closeTimer.current) { clearTimeout(closeTimer.current); closeTimer.current = null; }
    const r = el.getBoundingClientRect();
    setOrigin({
      cx: r.left + r.width / 2,
      cy: r.top + r.height / 2,
      scale: r.width / big.w,
      rot: Number(el.dataset.rot || 0),
    });
    setTilt({ rx: 0, ry: 0 });
    setFocusedId(ch.id);
    setOpen(false);
    requestAnimationFrame(() => requestAnimationFrame(() => setOpen(true)));
  };

  const closeFocus = () => {
    setOpen(false);
    setTilt({ rx: 0, ry: 0 });
    closeTimer.current = setTimeout(() => { setFocusedId(null); setOrigin(null); }, 460);
  };

  const onTiltMove = (e: ReactMouseEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    const px = (e.clientX - r.left) / r.width - 0.5;
    const py = (e.clientY - r.top) / r.height - 0.5;
    setTilt({ rx: -py * 16, ry: px * 16 });
  };

  // FLIP transform 계산 (transform-origin: center)
  const flyTransform = () => {
    if (!origin) return '';
    const w = typeof window !== 'undefined' ? window.innerWidth : 1200;
    const h = typeof window !== 'undefined' ? window.innerHeight : 700;
    if (open) {
      const cx = w / 2, cy = h / 2 - 26;
      return `translate(${cx - big.w / 2}px, ${cy - big.h / 2}px) scale(1) rotate(0deg)`;
    }
    return `translate(${origin.cx - big.w / 2}px, ${origin.cy - big.h / 2}px) scale(${origin.scale}) rotate(${origin.rot}deg)`;
  };

  // 우표 드래그: 클릭과 구분하기 위해 DRAG_THRESHOLD를 넘어야 실제 이동으로 확정한다.
  const onStampPointerDown = (e: ReactPointerEvent<HTMLDivElement>, channelId: string) => {
    if (window.innerWidth < MIN_BOARD_W) return; // 모바일(md 미만)은 grid 배치라 드래그 대상 아님
    const board = boardRef.current;
    if (!board) return;
    const boardRect = board.getBoundingClientRect();
    const stampRect = e.currentTarget.getBoundingClientRect();
    e.currentTarget.setPointerCapture(e.pointerId);
    setDrag({
      id: channelId,
      pointerId: e.pointerId,
      grabDX: e.clientX - stampRect.left,
      grabDY: e.clientY - stampRect.top,
      startX: e.clientX,
      startY: e.clientY,
      moved: false,
      leftPct: ((stampRect.left - boardRect.left) / boardRect.width) * 100,
      topPct: ((stampRect.top - boardRect.top) / boardRect.height) * 100,
    });
  };

  const onStampPointerMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    setDrag((prev) => {
      if (!prev || prev.pointerId !== e.pointerId) return prev;
      const dx = e.clientX - prev.startX;
      const dy = e.clientY - prev.startY;
      if (!prev.moved && Math.hypot(dx, dy) < DRAG_THRESHOLD) return prev; // 아직 클릭인지 드래그인지 불명 — 자리 유지
      const board = boardRef.current;
      if (!board) return prev;
      const boardRect = board.getBoundingClientRect();
      // 우표 사각형(132×176) 전체가 보드 안에 들어가도록 가둔다.
      const leftPx = Math.min(Math.max(e.clientX - boardRect.left - prev.grabDX, 0), Math.max(0, boardRect.width - STAMP_W));
      const topPx = Math.min(Math.max(e.clientY - boardRect.top - prev.grabDY, 0), Math.max(0, boardRect.height - STAMP_H));
      return { ...prev, moved: true, leftPct: (leftPx / boardRect.width) * 100, topPct: (topPx / boardRect.height) * 100 };
    });
  };

  const onStampPointerUp = (e: ReactPointerEvent<HTMLDivElement>) => {
    setDrag((prev) => {
      if (!prev || prev.pointerId !== e.pointerId) return null;
      if (prev.moved) {
        suppressClickRef.current = true; // 이 드래그 뒤에 이어질 click은 openFocus로 새지 않게 막는다
        const next: SavedPositionMap = {
          ...savedPositions,
          [prev.id]: { left: `${prev.leftPct.toFixed(2)}%`, top: `${prev.topPct.toFixed(2)}%` },
        };
        setSavedPositions(next);
        try {
          window.localStorage.setItem(stampStorageKey(currentUser.id), JSON.stringify(next));
        } catch {
          // 저장 실패(프라이빗 모드, 용량 초과 등) — 화면엔 이미 반영됐으니 무시
        }
      }
      return null;
    });
  };

  const onStampPointerCancel = () => setDrag(null); // 취소되면 반영하지 않는다

  // 채널 수에 맞춰 겹치지 않는 산포 위치를 결정적으로 계산하고, 사용자가 옮겨놓은
  // 자리(저장값)와 합친다 (데스크톱 절대배치 전용).
  const positions = resolveStampPositions(channels.map((c) => c.id), savedPositions, boardW, boardHeightPx(channels.length));

  // 액션 패널의 고정 top — 방장 액션이 늘어난 만큼, 뷰포트 하단까지 남는 공간을
  // max-height로 넘겨 스크롤되게 한다(낮은 뷰포트에서 화면 밖으로 밀려나지 않도록).
  // 가로모드 휴대폰처럼 뷰포트가 낮으면 이상적인 top 기준으로는 남는 공간이 음수가 될 수 있어,
  // "입장하기" 버튼 하나 + 안내문이 스크롤 없이 보이는 최소 높이(ACTION_PANEL_MIN_H)는 확보되도록
  // top을 끌어올리고 max-height도 그 값 밑으로 내려가지 않게 한다. 세로 화면처럼 충분히 높은
  // 뷰포트에서는 이상적인 top이 그대로 쓰여 기존 배치가 유지된다.
  const ACTION_PANEL_MIN_H = 160;
  const viewportH = typeof window !== 'undefined' ? window.innerHeight : 700;
  const idealPanelTop = viewportH / 2 - 26 + big.h / 2 + 18;
  const panelTop = Math.min(idealPanelTop, Math.max(8, viewportH - ACTION_PANEL_MIN_H - 16));
  const panelMaxHeight = Math.max(ACTION_PANEL_MIN_H, viewportH - panelTop - 16);

  return (
    <div className="relative h-full w-full overflow-auto" style={{ background: '#141917' }}>
      <svg width="0" height="0" className="absolute" aria-hidden="true">
        <defs>
          <filter id="pm-rough" x="-20%" y="-20%" width="140%" height="140%">
            <feTurbulence type="fractalNoise" baseFrequency="0.72" numOctaves="4" seed="9" result="n" />
            <feDisplacementMap in="SourceGraphic" in2="n" scale="1.5" xChannelSelector="R" yChannelSelector="G" />
          </filter>
        </defs>
      </svg>
      {/* 상단 바 */}
      <div className="sticky top-0 z-10 flex items-center justify-between px-6 h-14" style={{ background: '#141917' }}>
        <span className="text-[16px] font-bold text-[#e6ece8]">Sage</span>
        <div className="flex items-center gap-2">
          <button
            onClick={onOpenSettings}
            aria-label={`${currentUser.displayName} · 프로필 설정`}
            className="flex items-center gap-2 min-w-0 rounded-[3px] border border-[#2d362f] pl-1.5 pr-3 py-1.5 text-[13px] font-semibold text-[#e6ece8] hover:border-[#4a5a50] transition-colors cursor-pointer"
          >
            <Avatar
              photoUrl={currentUser.photoUrl}
              gradient={currentUser.avatar}
              name={currentUser.displayName}
              className="w-6 h-6 rounded-md text-[11px]"
            />
            <span className="truncate max-w-[100px]">{currentUser.displayName}</span>
          </button>
          <button onClick={() => setCreating(true)} className="btn-label flex-shrink-0 inline-flex items-center gap-1.5 px-3.5 py-2 text-[13px] font-semibold transition-colors cursor-pointer">
            <Plus className="w-4 h-4" /> 채널 만들기
          </button>
          <button onClick={onLogout} title="로그아웃" aria-label="로그아웃" className="flex-shrink-0 w-9 h-9 rounded-[3px] border border-[#2d362f] text-[#9aa8a0] hover:text-[#e6ece8] hover:border-[#4a5a50] transition-colors cursor-pointer flex items-center justify-center">
            <LogOut className="w-4 h-4" />
          </button>
        </div>
      </div>

      {channels.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-4 pt-40 text-center">
          <div className="text-[15px] text-[#9aa8a0]">아직 채널이 없어요.</div>
          <button onClick={() => setCreating(true)} className="btn-label inline-flex items-center gap-1.5 px-4 py-2.5 text-[14px] font-bold cursor-pointer"><Plus className="w-4 h-4" /> 첫 채널 만들기</button>
        </div>
      ) : (
        <div
          ref={boardRef}
          className="stamp-board grid grid-cols-2 justify-items-center gap-x-3 gap-y-8 px-4 pt-6 pb-12 md:block md:relative md:gap-0 md:p-0"
          style={{ '--board-h': `${boardHeightPx(channels.length)}px` } as CSSProperties}
        >
          {channels.map((ch, i) => {
            const p = positions[i];
            const dim = (hoveredId !== null && hoveredId !== ch.id) || (focusedId !== null && focusedId !== ch.id);
            const hidden = focusedId === ch.id; // 확대 클론이 대신 표시되는 동안 원본 숨김
            const count = unread?.[ch.id] ?? 0;
            const dragging = drag !== null && drag.id === ch.id && drag.moved;
            const left = dragging ? `${drag!.leftPct.toFixed(2)}%` : p.left;
            const top = dragging ? `${drag!.topPct.toFixed(2)}%` : p.top;
            return (
              <div
                key={ch.id}
                data-rot={p.rot}
                className="stamp-in w-[96px] h-[128px] md:absolute md:w-[132px] md:h-[176px] hover:z-20"
                style={{
                  left,
                  top,
                  zIndex: dragging ? 30 : undefined,
                  animationDelay: `${i * 0.06}s`,
                  filter: dim ? 'blur(3px)' : 'none',
                  opacity: hidden ? 0 : (dim ? 0.5 : 1),
                  transition: dragging ? 'none' : 'filter .25s ease, opacity .25s ease',
                }}
                onMouseEnter={() => setHoveredId(ch.id)}
                onMouseLeave={() => setHoveredId(null)}
                onPointerDown={(e) => onStampPointerDown(e, ch.id)}
                onPointerMove={onStampPointerMove}
                onPointerUp={onStampPointerUp}
                onPointerCancel={onStampPointerCancel}
                onClick={(e) => {
                  if (suppressClickRef.current) { suppressClickRef.current = false; return; }
                  openFocus(ch, e.currentTarget);
                }}
              >
                <div
                  className="relative w-full h-full cursor-pointer hover:!rotate-0 hover:scale-105"
                  style={{
                    transform: `rotate(${p.rot}deg)`,
                    transition: 'transform .22s ease',
                    filter: 'drop-shadow(0 8px 14px rgba(0,0,0,0.5))',
                  }}
                >
                  <StampFace ch={ch} />
                  <span className="stamp-tape" aria-hidden="true" style={tapeVars(ch.id)} />
                  {count > 0 && (
                    <span key={count} className="contents">
                      <Postmark count={count} />
                    </span>
                  )}
                  {ch.locked && !ch.joined && (
                    <span
                      aria-label="초대 코드 필요"
                      className="absolute right-1 bottom-1 md:right-1.5 md:bottom-1.5 w-4 h-4 md:w-5 md:h-5 rounded-full bg-[#1c2420]/85 flex items-center justify-center pointer-events-none"
                    >
                      <Lock className="w-2.5 h-2.5 md:w-3 md:h-3 text-[#e6ece8]" strokeWidth={2.5} />
                    </span>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* 확대: 원래 자리 → 중앙 (FLIP), 바깥/ESC로 원래 자리 복귀 */}
      {focused && origin && (
        <>
          <div
            className="fixed inset-0 z-40"
            style={{
              background: 'rgba(10,13,11,0.72)',
              backdropFilter: open ? 'blur(6px)' : 'blur(0px)',
              WebkitBackdropFilter: open ? 'blur(6px)' : 'blur(0px)',
              opacity: open ? 1 : 0,
              transition: 'opacity .38s ease, backdrop-filter .38s ease',
            }}
            onClick={closeFocus}
          />
          <div
            className="fixed z-50"
            style={{
              left: 0, top: 0, width: big.w, height: big.h,
              transformOrigin: 'center',
              transform: flyTransform(),
              transition: 'transform .46s cubic-bezier(0.22, 1, 0.36, 1)',
              pointerEvents: open ? 'auto' : 'none',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div
              className="w-full h-full"
              style={{
                transform: `perspective(1100px) rotateX(${tilt.rx}deg) rotateY(${tilt.ry}deg)`,
                transition: 'transform .12s ease-out',
                filter: 'drop-shadow(0 30px 55px rgba(0,0,0,0.6))',
              }}
              onMouseMove={open ? onTiltMove : undefined}
              onMouseLeave={() => setTilt({ rx: 0, ry: 0 })}
            >
              <StampFace ch={focused} big />
            </div>
          </div>
          <div
            className="fixed z-50 left-1/2 -translate-x-1/2 flex flex-col items-center gap-3 overflow-y-auto"
            style={{
              top: panelTop,
              maxHeight: panelMaxHeight,
              opacity: open ? 1 : 0,
              transition: 'opacity .28s ease .12s',
              pointerEvents: open ? 'auto' : 'none',
            }}
          >
            {focused.locked && !focused.joined ? (
              <div className="flex flex-col items-center gap-2 w-[240px]">
                <div className="flex items-center gap-1.5 text-[12px] text-[#8a978d]">
                  <Lock className="w-3.5 h-3.5" /> 초대 코드가 필요해요
                </div>
                <div className="flex items-center gap-2 w-full">
                  <input
                    autoFocus
                    value={joinCode}
                    onChange={(e) => { setJoinCode(e.target.value); setJoinError(''); }}
                    onKeyDown={(e) => e.key === 'Enter' && submitJoinCode()}
                    placeholder="초대 코드"
                    className="flex-1 min-w-0 bg-[#1b211d] border border-[#2d362f] rounded-[10px] px-3 py-2 text-[13px] text-[#e6ece8] outline-none focus:border-[#4a5a50] placeholder:text-[#5f6b62]"
                  />
                  <button
                    onClick={submitJoinCode}
                    disabled={joinBusy || !joinCode.trim()}
                    className="btn-label flex-shrink-0 px-3 py-2 text-[13px] font-semibold cursor-pointer"
                  >
                    {joinBusy ? '확인 중…' : '입장'}
                  </button>
                </div>
                {joinError && <p className="text-[12px] text-rose-400 self-start">{joinError}</p>}
              </div>
            ) : (
              <>
                {focused.owner && focused.inviteCode && (
                  <div className="flex flex-col items-center gap-2 w-[240px]">
                    <div className="flex items-center gap-1.5 text-[12px] text-[#8a978d]">
                      <Lock className="w-3.5 h-3.5" /> 내 초대 코드
                    </div>
                    <InviteCode code={focused.inviteCode} boxClassName="bg-[#1b211d] border-[#2d362f] text-[#e6ece8]" />
                  </div>
                )}
                <button
                  onClick={() => onSelectChannel(focused.id)}
                  className="btn-stamp transition-colors cursor-pointer"
                >
                  입장하기
                </button>
                {focused.owner && (
                  <div className="flex flex-col items-center gap-2 w-[240px] pt-1">
                    <div className="flex items-center gap-2 w-full">
                      {focused.locked && (
                        <button
                          onClick={reissueCode}
                          disabled={ownerBusy !== null}
                          className="btn-label flex-1 px-3 py-2 text-[12px] font-semibold cursor-pointer disabled:opacity-60 disabled:cursor-default"
                        >
                          {ownerBusy === 'reissue' ? '재발급 중…' : '코드 재발급'}
                        </button>
                      )}
                      <button
                        onClick={togglePrivacy}
                        disabled={ownerBusy !== null}
                        className="btn-label flex-1 px-3 py-2 text-[12px] font-semibold cursor-pointer disabled:opacity-60 disabled:cursor-default"
                      >
                        {ownerBusy === 'privacy' ? '변경 중…' : focused.locked ? '공개로 전환' : '비공개로 전환'}
                      </button>
                    </div>
                    <button
                      onClick={() => (showBans ? setShowBans(false) : openBans())}
                      className="w-full btn-label px-3 py-2 text-[12px] font-semibold cursor-pointer flex items-center justify-center gap-1.5"
                    >
                      <UserX className="w-3.5 h-3.5" /> {showBans ? '차단 목록 닫기' : '차단 목록'}
                    </button>
                    {showBans && (
                      <div className="w-full max-h-40 overflow-y-auto rounded-[10px] border border-[#2d362f] bg-[#1b211d] p-2">
                        {bansError && bans === null ? (
                          <div className="py-2 text-center">
                            <p className="text-[12px] text-rose-400">{bansError}</p>
                            <button
                              onClick={openBans}
                              className="mt-1 text-[12px] font-semibold text-[#e6ece8] hover:underline cursor-pointer"
                            >
                              다시 시도
                            </button>
                          </div>
                        ) : bans === null ? (
                          <div className="py-3 text-center text-[12px] text-[#8a978d] select-none">불러오는 중…</div>
                        ) : bans.length === 0 ? (
                          <div className="py-3 text-center text-[12px] text-[#8a978d] select-none">차단된 사람이 없어요</div>
                        ) : (
                          <>
                            {unbanError && (
                              <p className="pb-1 text-center text-[12px] text-rose-400">{unbanError}</p>
                            )}
                            <ul className="space-y-1">
                              {bans.map((b) => (
                                <li key={b.memberId} className="flex items-center justify-between gap-2 px-1 py-1">
                                  <span className="text-[13px] text-[#e6ece8] truncate">{b.nickname ?? '탈퇴한 회원'}</span>
                                  <button
                                    onClick={() => unban(b.memberId)}
                                    disabled={unbanningId === b.memberId}
                                    className="flex-shrink-0 text-[12px] font-semibold text-[#8a978d] hover:text-[#e6ece8] transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-default"
                                  >
                                    {unbanningId === b.memberId ? '해제 중…' : '차단 해제'}
                                  </button>
                                </li>
                              ))}
                            </ul>
                          </>
                        )}
                      </div>
                    )}
                    <button
                      onClick={() => (showTransfer ? setShowTransfer(false) : openTransfer())}
                      disabled={ownerBusy !== null}
                      className="w-full btn-label px-3 py-2 text-[12px] font-semibold cursor-pointer flex items-center justify-center gap-1.5 disabled:opacity-60 disabled:cursor-default"
                    >
                      <Crown className="w-3.5 h-3.5" /> {showTransfer ? '방장 넘기기 닫기' : '방장 넘기기'}
                    </button>
                    {showTransfer && (
                      <div className="w-full max-h-40 overflow-y-auto rounded-[10px] border border-[#2d362f] bg-[#1b211d] p-2">
                        {transferError && transferCandidates === null ? (
                          <div className="py-2 text-center">
                            <p className="text-[12px] text-rose-400">{transferError}</p>
                            <button
                              onClick={openTransfer}
                              className="mt-1 text-[12px] font-semibold text-[#e6ece8] hover:underline cursor-pointer"
                            >
                              다시 시도
                            </button>
                          </div>
                        ) : transferCandidates === null ? (
                          <div className="py-3 text-center text-[12px] text-[#8a978d] select-none">불러오는 중…</div>
                        ) : transferCandidates.length === 0 ? (
                          <div className="py-3 text-center text-[12px] text-[#8a978d] select-none">넘길 수 있는 참가자가 없어요</div>
                        ) : transferTargetId === null ? (
                          <ul className="space-y-1">
                            {transferCandidates.map((m) => (
                              <li key={m.id}>
                                <button
                                  onClick={() => { setTransferTargetId(m.id); setTransferError(''); }}
                                  className="w-full text-left px-1 py-1 text-[13px] text-[#e6ece8] hover:text-[#8a978d] transition-colors cursor-pointer truncate"
                                >
                                  {m.nickname}
                                </button>
                              </li>
                            ))}
                          </ul>
                        ) : (
                          <div className="flex flex-col items-center gap-2 py-1">
                            <div className="text-[12px] text-red-400 text-center">
                              {transferCandidates.find((m) => m.id === transferTargetId)?.nickname}님에게 방장을 넘길까요?
                              넘기면 내 방장 권한은 즉시 사라지고, 되돌리려면 새 방장이 다시 넘겨줘야 해요.
                            </div>
                            {transferError && <p className="text-[12px] text-rose-400 text-center">{transferError}</p>}
                            <div className="flex gap-2 w-full">
                              <button
                                onClick={() => { setTransferTargetId(null); setTransferError(''); }}
                                disabled={ownerBusy !== null}
                                className="flex-1 rounded-[10px] py-2 text-[12px] font-semibold border border-[#2d362f] text-[#e6ece8] hover:border-[#4a5a50] transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-default"
                              >
                                취소
                              </button>
                              <button
                                onClick={confirmTransfer}
                                disabled={ownerBusy !== null}
                                className="flex-1 rounded-[10px] py-2 text-[12px] font-bold border border-red-500/40 text-red-400 hover:bg-red-500/10 transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-default"
                              >
                                {ownerBusy === 'transfer' ? '넘기는 중…' : '넘기기'}
                              </button>
                            </div>
                          </div>
                        )}
                      </div>
                    )}
                    {!confirmDelete ? (
                      <button
                        onClick={() => setConfirmDelete(true)}
                        disabled={ownerBusy !== null}
                        className="text-[12px] text-[#8a978d] hover:text-red-400 transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-default"
                      >
                        방 삭제
                      </button>
                    ) : (
                      <div className="flex flex-col items-center gap-2 w-full">
                        <div className="text-[12px] text-red-400 text-center">정말 삭제할까요? 되돌릴 수 없어요.</div>
                        <div className="flex gap-2 w-full">
                          <button
                            onClick={() => setConfirmDelete(false)}
                            className="flex-1 rounded-[10px] py-2 text-[12px] font-semibold border border-[#2d362f] text-[#e6ece8] hover:border-[#4a5a50] transition-colors cursor-pointer"
                          >
                            취소
                          </button>
                          <button
                            onClick={deleteRoom}
                            disabled={ownerBusy !== null}
                            className="flex-1 rounded-[10px] py-2 text-[12px] font-bold border border-red-500/40 text-red-400 hover:bg-red-500/10 transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-default"
                          >
                            삭제
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                )}
                {!focused.owner && focused.joined && (
                  <div className="flex flex-col items-center gap-2 w-[240px] pt-1">
                    {!confirmLeave ? (
                      <button
                        onClick={() => setConfirmLeave(true)}
                        disabled={leaveBusy}
                        className="text-[12px] text-[#8a978d] hover:text-red-400 transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-default"
                      >
                        방 나가기
                      </button>
                    ) : (
                      <div className="flex flex-col items-center gap-2 w-full">
                        <div className="text-[12px] text-red-400 text-center">정말 나갈까요? 되돌릴 수 없어요.</div>
                        {leaveError && <p className="text-[12px] text-rose-400 text-center">{leaveError}</p>}
                        <div className="flex gap-2 w-full">
                          <button
                            onClick={() => { setConfirmLeave(false); setLeaveError(''); }}
                            disabled={leaveBusy}
                            className="flex-1 rounded-[10px] py-2 text-[12px] font-semibold border border-[#2d362f] text-[#e6ece8] hover:border-[#4a5a50] transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-default"
                          >
                            취소
                          </button>
                          <button
                            onClick={leaveRoom}
                            disabled={leaveBusy}
                            className="flex-1 rounded-[10px] py-2 text-[12px] font-bold border border-red-500/40 text-red-400 hover:bg-red-500/10 transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-default"
                          >
                            {leaveBusy ? '나가는 중…' : '나가기'}
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </>
            )}
            <div className="text-[12px] text-[#8a978d] select-none">바깥을 클릭하거나 ESC로 닫기</div>
          </div>
        </>
      )}

      {/* 채널 만들기 다이얼로그 */}
      {creating && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center p-6" role="dialog" aria-modal="true">
          <div className="absolute inset-0 bg-black/55" onClick={closeCreateDialog} />
          <div className="relative w-full max-w-[360px] bg-surface border border-border rounded-3xl p-5">
            {createdRoom ? (
              <>
                <div className="flex items-center justify-between mb-3">
                  <h2 className="text-[15px] font-bold text-text">초대 코드</h2>
                  <button onClick={closeCreateDialog} aria-label="닫기" className="text-muted hover:text-text cursor-pointer"><X className="w-4 h-4" /></button>
                </div>
                <p className="text-[13px] text-muted mb-3">이 코드가 있어야 다른 사람이 들어올 수 있어요. 나중에 다시 보려면 이 방을 열어서 확인하세요.</p>
                {createdRoom.inviteCode && (
                  <InviteCode code={createdRoom.inviteCode} boxClassName="bg-surface-2 border-border text-text" />
                )}
                <button onClick={enterCreatedRoom} className="btn-label mt-3 w-full py-2.5 text-[14px] font-bold cursor-pointer">입장하기</button>
              </>
            ) : (
              <>
                <div className="flex items-center justify-between mb-3">
                  <h2 className="text-[15px] font-bold text-text">새 채널</h2>
                  <button onClick={closeCreateDialog} aria-label="닫기" className="text-muted hover:text-text cursor-pointer"><X className="w-4 h-4" /></button>
                </div>
                <input autoFocus value={name} maxLength={30} onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && submit()} placeholder="채널 이름" className="w-full bg-surface-2 border border-border rounded-[10px] px-3 py-2.5 text-[14px] text-text outline-none focus:border-accent" />
                <label className="mt-3 flex items-center gap-2 text-[13px] text-muted cursor-pointer select-none">
                  <input type="checkbox" checked={isPrivate} onChange={(e) => setIsPrivate(e.target.checked)} className="w-4 h-4 accent-current" />
                  비공개 방으로 만들기
                </label>
                {createError && (
                  <p className="mt-2 text-[12px] text-rose-400">{createError}</p>
                )}
                <button onClick={submit} disabled={busy} className="btn-label mt-3 w-full py-2.5 text-[14px] font-bold cursor-pointer">{busy ? '만드는 중…' : '만들기'}</button>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
