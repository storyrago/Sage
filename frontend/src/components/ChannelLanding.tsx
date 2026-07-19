import { useState } from 'react';
import { Plus, Hash, Code, Music, Shuffle, Gamepad2, MessageCircle, Bell, X } from 'lucide-react';
import { Channel, User } from '../types';

const ICONS = [Hash, Code, Music, Shuffle, Gamepad2, MessageCircle, Bell];

// id로부터 결정적 값(아이콘/회전/틴트/산포 위치)
function hash(id: string) {
  return [...id].reduce((s, c) => s + c.charCodeAt(0), 0);
}
const POS = [
  { left: '9%', top: '26%', rot: -9 }, { left: '27%', top: '15%', rot: 6 },
  { left: '20%', top: '55%', rot: -3 }, { left: '47%', top: '30%', rot: 10 },
  { left: '43%', top: '61%', rot: -7 }, { left: '68%', top: '19%', rot: 5 },
  { left: '70%', top: '52%', rot: -11 }, { left: '55%', top: '10%', rot: 3 },
];

interface Props {
  channels: Channel[];
  currentUser: User;
  onSelectChannel: (id: string) => void;
  onCreateChannel: (name: string) => Promise<void>;
}

export default function ChannelLanding({ channels, onSelectChannel, onCreateChannel }: Props) {
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [zoomingId, setZoomingId] = useState<string | null>(null);

  const submit = async () => {
    const n = name.trim();
    if (!n || busy) return;
    setBusy(true);
    try { await onCreateChannel(n); setName(''); setCreating(false); }
    finally { setBusy(false); }
  };

  const enter = (id: string) => {
    setZoomingId(id);
    setTimeout(() => onSelectChannel(id), 260);
  };

  return (
    <div className="relative h-full w-full overflow-auto" style={{ background: '#141917' }}>
      <div className="sticky top-0 z-10 flex items-center justify-between px-6 h-14" style={{ background: '#141917' }}>
        <span className="text-[16px] font-bold text-[#e6ece8]">Sage</span>
        <button onClick={() => setCreating(true)} className="inline-flex items-center gap-1.5 bg-accent text-accent-fg rounded-lg px-3.5 py-2 text-[13px] font-semibold hover:bg-accent-hover transition-colors cursor-pointer">
          <Plus className="w-4 h-4" /> 채널 만들기
        </button>
      </div>

      {channels.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-4 pt-40 text-center">
          <div className="text-[15px] text-[#9aa8a0]">아직 채널이 없어요.</div>
          <button onClick={() => setCreating(true)} className="inline-flex items-center gap-1.5 bg-accent text-accent-fg rounded-lg px-4 py-2.5 text-[14px] font-bold cursor-pointer"><Plus className="w-4 h-4" /> 첫 채널 만들기</button>
        </div>
      ) : (
        <div className="relative" style={{ height: 520 }}>
          {channels.map((ch, i) => {
            const p = POS[i % POS.length];
            const Icon = ICONS[hash(ch.id) % ICONS.length];
            const tint = hash(ch.id) % 3 === 0;
            return (
              <button
                key={ch.id}
                onClick={() => enter(ch.id)}
                className="absolute w-[118px] hover:!rotate-0 hover:scale-110 hover:z-30 cursor-pointer"
                style={{
                  left: p.left,
                  top: p.top,
                  transform: zoomingId === ch.id ? 'scale(2.4)' : `rotate(${p.rot}deg)`,
                  opacity: zoomingId && zoomingId !== ch.id ? 0 : 1,
                  zIndex: zoomingId === ch.id ? 30 : undefined,
                  transition: 'transform .26s ease, opacity .26s ease',
                  filter: 'drop-shadow(0 6px 10px rgba(0,0,0,0.45))',
                }}
              >
                <div className={`stamp-paper flex flex-col items-center justify-center gap-1.5 min-h-[104px] px-2.5 py-4 ${tint ? 'bg-[#e7efe6]' : 'bg-[#fdfcf8]'}`}>
                  <Icon className="w-6 h-6" style={{ color: 'var(--accent)' }} />
                  <div className="text-[15px] font-semibold text-[#26251f]">{ch.name}</div>
                </div>
              </button>
            );
          })}
        </div>
      )}

      {creating && (
        <div className="fixed inset-0 z-40 flex items-center justify-center p-6" role="dialog" aria-modal="true">
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
