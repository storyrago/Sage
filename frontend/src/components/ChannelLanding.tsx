import { useEffect, useState, type MouseEvent as ReactMouseEvent } from 'react';
import { Plus, Hash, Code, Music, Shuffle, Gamepad2, MessageCircle, Bell, X, LogOut, ArrowRight } from 'lucide-react';
import { Channel } from '../types';

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

interface Props {
  channels: Channel[];
  onSelectChannel: (id: string) => void;
  onCreateChannel: (name: string) => Promise<void>;
  onLogout: () => void;
}

export default function ChannelLanding({ channels, onSelectChannel, onCreateChannel, onLogout }: Props) {
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const [focusedId, setFocusedId] = useState<string | null>(null);
  const [tilt, setTilt] = useState({ rx: 0, ry: 0 });

  const focused = channels.find((c) => c.id === focusedId) || null;

  // ESC로 확대 닫기
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setFocusedId(null); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const submit = async () => {
    const n = name.trim();
    if (!n || busy) return;
    setBusy(true);
    try { await onCreateChannel(n); setName(''); setCreating(false); }
    finally { setBusy(false); }
  };

  // 확대된 우표를 커서 위치에 따라 3D 기울임
  const onTiltMove = (e: ReactMouseEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    const px = (e.clientX - r.left) / r.width - 0.5;   // -0.5 ~ 0.5
    const py = (e.clientY - r.top) / r.height - 0.5;
    setTilt({ rx: -py * 16, ry: px * 16 });
  };

  const openFocus = (id: string) => { setTilt({ rx: 0, ry: 0 }); setFocusedId(id); };

  return (
    <div className="relative h-full w-full overflow-auto" style={{ background: '#141917' }}>
      {/* 상단 바 */}
      <div className="sticky top-0 z-10 flex items-center justify-between px-6 h-14" style={{ background: '#141917' }}>
        <span className="text-[16px] font-bold text-[#e6ece8]">Sage</span>
        <div className="flex items-center gap-2">
          <button onClick={() => setCreating(true)} className="inline-flex items-center gap-1.5 bg-accent text-accent-fg rounded-lg px-3.5 py-2 text-[13px] font-semibold hover:bg-accent-hover transition-colors cursor-pointer">
            <Plus className="w-4 h-4" /> 채널 만들기
          </button>
          <button onClick={onLogout} title="로그아웃" aria-label="로그아웃" className="w-9 h-9 rounded-lg border border-[#2d362f] text-[#9aa8a0] hover:text-[#e6ece8] hover:border-[#4a5a50] transition-colors cursor-pointer flex items-center justify-center">
            <LogOut className="w-4 h-4" />
          </button>
        </div>
      </div>

      {channels.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-4 pt-40 text-center">
          <div className="text-[15px] text-[#9aa8a0]">아직 채널이 없어요.</div>
          <button onClick={() => setCreating(true)} className="inline-flex items-center gap-1.5 bg-accent text-accent-fg rounded-lg px-4 py-2.5 text-[14px] font-bold cursor-pointer"><Plus className="w-4 h-4" /> 첫 채널 만들기</button>
        </div>
      ) : (
        <div className="relative" style={{ height: 560 }}>
          {channels.map((ch, i) => {
            const p = POS[i % POS.length];
            const dim = (hoveredId !== null && hoveredId !== ch.id) || (focusedId !== null && focusedId !== ch.id);
            return (
              <div
                key={ch.id}
                className="stamp-in absolute w-[132px] h-[176px] hover:z-20"
                style={{
                  left: p.left,
                  top: p.top,
                  animationDelay: `${i * 0.06}s`,
                  filter: dim ? 'blur(3px)' : 'none',
                  opacity: dim ? 0.5 : 1,
                  transition: 'filter .25s ease, opacity .25s ease',
                }}
                onMouseEnter={() => setHoveredId(ch.id)}
                onMouseLeave={() => setHoveredId(null)}
              >
                <button
                  onClick={() => openFocus(ch.id)}
                  className="w-full h-full hover:!rotate-0 hover:scale-105 cursor-pointer"
                  style={{
                    transform: `rotate(${p.rot}deg)`,
                    transition: 'transform .22s ease',
                    filter: 'drop-shadow(0 8px 14px rgba(0,0,0,0.5))',
                  }}
                >
                  <StampFace ch={ch} />
                </button>
              </div>
            );
          })}
        </div>
      )}

      {/* 확대 오버레이 — 커서 기울임 + 입장 */}
      {focused && (
        <div
          className="fixed inset-0 z-40 flex flex-col items-center justify-center gap-6"
          style={{ background: 'rgba(10,13,11,0.72)', backdropFilter: 'blur(6px)', WebkitBackdropFilter: 'blur(6px)' }}
          onClick={() => setFocusedId(null)}
        >
          <div className="stamp-focus-in" style={{ perspective: '1100px' }} onClick={(e) => e.stopPropagation()}>
            <div
              className="w-[300px] h-[400px]"
              style={{
                transform: `rotateX(${tilt.rx}deg) rotateY(${tilt.ry}deg)`,
                transition: 'transform .12s ease-out',
                filter: 'drop-shadow(0 30px 55px rgba(0,0,0,0.6))',
              }}
              onMouseMove={onTiltMove}
              onMouseLeave={() => setTilt({ rx: 0, ry: 0 })}
            >
              <StampFace ch={focused} big />
            </div>
          </div>

          <button
            onClick={(e) => { e.stopPropagation(); onSelectChannel(focused.id); }}
            className="inline-flex items-center gap-2 bg-accent text-accent-fg rounded-xl px-6 py-3 text-[15px] font-bold hover:bg-accent-hover transition-colors cursor-pointer shadow-xl"
          >
            입장하기 <ArrowRight className="w-4 h-4" />
          </button>
          <div className="text-[12px] text-[#8a978d] select-none">바깥을 클릭하거나 ESC로 닫기</div>
        </div>
      )}

      {/* 채널 만들기 다이얼로그 */}
      {creating && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-6" role="dialog" aria-modal="true">
          <div className="absolute inset-0 bg-black/55" onClick={() => setCreating(false)} />
          <div className="relative w-full max-w-[360px] bg-surface border border-border rounded-3xl p-5">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-[15px] font-bold text-text">새 채널</h2>
              <button onClick={() => setCreating(false)} aria-label="닫기" className="text-muted hover:text-text cursor-pointer"><X className="w-4 h-4" /></button>
            </div>
            <input autoFocus value={name} maxLength={30} onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && submit()} placeholder="채널 이름" className="w-full bg-surface-2 border border-border rounded-[10px] px-3 py-2.5 text-[14px] text-text outline-none focus:border-accent" />
            <button onClick={submit} disabled={busy} className="mt-3 w-full rounded-xl py-2.5 text-[14px] font-bold bg-accent text-accent-fg hover:bg-accent-hover cursor-pointer disabled:opacity-60">{busy ? '만드는 중…' : '만들기'}</button>
          </div>
        </div>
      )}
    </div>
  );
}
