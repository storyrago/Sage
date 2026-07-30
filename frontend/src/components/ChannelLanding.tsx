import { useEffect, useRef, useState, type MouseEvent as ReactMouseEvent } from 'react';
import { Plus, Hash, Code, Music, Shuffle, Gamepad2, MessageCircle, Bell, X, LogOut } from 'lucide-react';
import { Channel, User } from '../types';
import Avatar from './Avatar';

const ICONS = [Hash, Code, Music, Shuffle, Gamepad2, MessageCircle, Bell];

// id로부터 결정적 값(아이콘/틴트/산포 위치)
function hash(id: string) {
  return [...id].reduce((s, c) => s + c.charCodeAt(0), 0);
}
const POS = [
  { left: '8%', top: '18%', rot: -8 }, { left: '27%', top: '10%', rot: 6 },
  { left: '20%', top: '52%', rot: -4 }, { left: '46%', top: '24%', rot: 9 },
  { left: '42%', top: '58%', rot: -7 }, { left: '66%', top: '14%', rot: 5 },
  { left: '70%', top: '50%', rot: -10 }, { left: '55%', top: '6%', rot: 3 },
];

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

interface Origin { cx: number; cy: number; scale: number; rot: number; }

interface Props {
  channels: Channel[];
  onSelectChannel: (id: string) => void;
  onCreateChannel: (name: string) => Promise<void>;
  onLogout: () => void;
  unread?: Record<string, number>;
  currentUser: User;
  onOpenSettings: () => void;
}

export default function ChannelLanding({ channels, onSelectChannel, onCreateChannel, onLogout, unread, currentUser, onOpenSettings }: Props) {
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [createError, setCreateError] = useState('');
  const [hoveredId, setHoveredId] = useState<string | null>(null);

  // 확대 상태 (자기 자리 → 중앙 FLIP)
  const [focusedId, setFocusedId] = useState<string | null>(null);
  const [origin, setOrigin] = useState<Origin | null>(null);
  const [open, setOpen] = useState(false);
  const [tilt, setTilt] = useState({ rx: 0, ry: 0 });
  const [big, setBig] = useState(calcBig);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const focused = channels.find((c) => c.id === focusedId) || null;

  useEffect(() => {
    const onResize = () => setBig(calcBig());
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && focusedId) closeFocus(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusedId]);

  useEffect(() => () => { if (closeTimer.current) clearTimeout(closeTimer.current); }, []);

  const submit = async () => {
    const n = name.trim();
    if (!n || busy) return;
    setBusy(true);
    setCreateError('');
    try {
      await onCreateChannel(n);
      setName('');
      setCreating(false);
    } catch (err) {
      // 다이얼로그를 열어둔 채 입력값을 유지해 그대로 다시 시도할 수 있게 한다.
      setCreateError(err instanceof Error ? err.message : '채널을 만들지 못했어요.');
    } finally {
      setBusy(false);
    }
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
        <div className="grid grid-cols-2 justify-items-center gap-x-3 gap-y-8 px-4 pt-6 pb-12 md:block md:relative md:h-[560px] md:gap-0 md:p-0">
          {channels.map((ch, i) => {
            const p = POS[i % POS.length];
            const dim = (hoveredId !== null && hoveredId !== ch.id) || (focusedId !== null && focusedId !== ch.id);
            const hidden = focusedId === ch.id; // 확대 클론이 대신 표시되는 동안 원본 숨김
            const count = unread?.[ch.id] ?? 0;
            return (
              <div
                key={ch.id}
                data-rot={p.rot}
                className="stamp-in w-[96px] h-[128px] md:absolute md:w-[132px] md:h-[176px] hover:z-20"
                style={{
                  left: p.left,
                  top: p.top,
                  animationDelay: `${i * 0.06}s`,
                  filter: dim ? 'blur(3px)' : 'none',
                  opacity: hidden ? 0 : (dim ? 0.5 : 1),
                  transition: 'filter .25s ease, opacity .25s ease',
                }}
                onMouseEnter={() => setHoveredId(ch.id)}
                onMouseLeave={() => setHoveredId(null)}
                onClick={(e) => openFocus(ch, e.currentTarget)}
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
                  <span className="stamp-tape" aria-hidden="true" />
                  {count > 0 && (
                    <span key={count} className="contents">
                      <Postmark count={count} />
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
            className="fixed z-50 left-1/2 -translate-x-1/2 flex flex-col items-center gap-3"
            style={{
              top: (typeof window !== 'undefined' ? window.innerHeight : 700) / 2 - 26 + big.h / 2 + 18,
              opacity: open ? 1 : 0,
              transition: 'opacity .28s ease .12s',
              pointerEvents: open ? 'auto' : 'none',
            }}
          >
            <button
              onClick={() => onSelectChannel(focused.id)}
              className="btn-stamp transition-colors cursor-pointer"
            >
              입장하기
            </button>
            <div className="text-[12px] text-[#8a978d] select-none">바깥을 클릭하거나 ESC로 닫기</div>
          </div>
        </>
      )}

      {/* 채널 만들기 다이얼로그 */}
      {creating && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center p-6" role="dialog" aria-modal="true">
          <div className="absolute inset-0 bg-black/55" onClick={() => { setCreating(false); setCreateError(''); }} />
          <div className="relative w-full max-w-[360px] bg-surface border border-border rounded-3xl p-5">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-[15px] font-bold text-text">새 채널</h2>
              <button onClick={() => { setCreating(false); setCreateError(''); }} aria-label="닫기" className="text-muted hover:text-text cursor-pointer"><X className="w-4 h-4" /></button>
            </div>
            <input autoFocus value={name} maxLength={30} onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && submit()} placeholder="채널 이름" className="w-full bg-surface-2 border border-border rounded-[10px] px-3 py-2.5 text-[14px] text-text outline-none focus:border-accent" />
            {createError && (
              <p className="mt-2 text-[12px] text-rose-400">{createError}</p>
            )}
            <button onClick={submit} disabled={busy} className="btn-label mt-3 w-full py-2.5 text-[14px] font-bold cursor-pointer">{busy ? '만드는 중…' : '만들기'}</button>
          </div>
        </div>
      )}
    </div>
  );
}
