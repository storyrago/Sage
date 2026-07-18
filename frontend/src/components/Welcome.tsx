import { useState, useRef, useEffect, FormEvent, CSSProperties } from 'react';
import { User } from '../types';
import { MessagesSquare } from 'lucide-react';

interface Credentials {
  email: string;
  password: string;
  nickname?: string;
}

interface WelcomeProps {
  onComplete: (credentials: Credentials, mode: 'login' | 'signup') => Promise<void>;
  initialUser?: User | null;
}

interface Particle {
  x: number; y: number; vx: number; vy: number; r: number; c: string; z: number;
}

const PARTICLE_COLORS = ['#5E9079', '#7AAE92', '#9CCBB2'];
const LINK = 140;

export default function Welcome({ onComplete, initialUser }: WelcomeProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const spotRef = useRef<HTMLDivElement>(null);
  const heroRef = useRef<HTMLDivElement>(null);
  const cueRef = useRef<HTMLDivElement>(null);
  const loginRef = useRef<HTMLDivElement>(null);
  const loginWrapRef = useRef<HTMLDivElement>(null);

  const [mode, setMode] = useState<'login' | 'signup'>('login');
  const [email, setEmail] = useState(initialUser?.email || '');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState(initialUser?.displayName || '');
  const [errorCode, setErrorCode] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Canvas particle network + spotlight + scroll parallax
  useEffect(() => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;

    // Always begin at the intro (avoid the browser restoring a scrolled position)
    if ('scrollRestoration' in history) history.scrollRestoration = 'manual';
    window.scrollTo(0, 0);

    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    let W = 0, H = 0, DPR = 1, raf = 0, ticking = false, scrollY = 0;
    let parts: Particle[] = [];
    const mouse = { x: -9999, y: -9999 };

    const init = () => {
      const n = Math.max(38, Math.min(110, Math.round((W * H) / 15000)));
      parts = [];
      for (let i = 0; i < n; i++) {
        parts.push({
          x: Math.random() * W,
          y: Math.random() * H,
          vx: (Math.random() - 0.5) * (reduce ? 0.08 : 0.28),
          vy: (Math.random() - 0.5) * (reduce ? 0.08 : 0.28),
          r: Math.random() * 1.7 + 0.9,
          c: PARTICLE_COLORS[(Math.random() * PARTICLE_COLORS.length) | 0],
          z: Math.random() * 0.7 + 0.3,
        });
      }
    };

    const resize = () => {
      DPR = Math.min(window.devicePixelRatio || 1, 2);
      W = window.innerWidth;
      H = window.innerHeight;
      canvas.width = W * DPR;
      canvas.height = H * DPR;
      ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
      init();
    };

    const frame = () => {
      ctx.clearRect(0, 0, W, H);
      for (let i = 0; i < parts.length; i++) {
        const p = parts[i];
        p.x += p.vx;
        p.y += p.vy;
        if (p.x < -20) p.x = W + 20;
        if (p.x > W + 20) p.x = -20;
        if (p.y < -20) p.y = H + 20;
        if (p.y > H + 20) p.y = -20;
        const dx = p.x - mouse.x, dy = p.y - mouse.y, d2 = dx * dx + dy * dy;
        if (d2 < 15000) {
          const d = Math.sqrt(d2) || 1, f = (1 - d / 122) * 0.9;
          p.x += (dx / d) * f;
          p.y += (dy / d) * f;
        }
      }
      for (let i = 0; i < parts.length; i++) {
        const a = parts[i];
        for (let j = i + 1; j < parts.length; j++) {
          const b = parts[j], ex = a.x - b.x, ey = a.y - b.y, dd2 = ex * ex + ey * ey;
          if (dd2 < LINK * LINK) {
            const dd = Math.sqrt(dd2);
            const o = (1 - dd / LINK) * 0.24;
            ctx.strokeStyle = `rgba(122,174,146,${o.toFixed(3)})`;
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.moveTo(a.x, a.y - scrollY * a.z * 0.12);
            ctx.lineTo(b.x, b.y - scrollY * b.z * 0.12);
            ctx.stroke();
          }
        }
      }
      for (let i = 0; i < parts.length; i++) {
        const p = parts[i];
        ctx.globalAlpha = 0.45 + p.z * 0.55;
        ctx.fillStyle = p.c;
        ctx.beginPath();
        ctx.arc(p.x, p.y - scrollY * p.z * 0.12, p.r, 0, 6.2832);
        ctx.fill();
      }
      ctx.globalAlpha = 1;
      raf = requestAnimationFrame(frame);
    };

    const onMouseMove = (e: MouseEvent) => {
      mouse.x = e.clientX;
      mouse.y = e.clientY;
      if (spotRef.current) spotRef.current.style.transform = `translate(${e.clientX}px, ${e.clientY}px)`;
    };
    const onMouseLeave = () => { mouse.x = -9999; mouse.y = -9999; };
    const onScroll = () => {
      scrollY = window.pageYOffset || document.documentElement.scrollTop;
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(() => {
        const vh = window.innerHeight;
        const hp = Math.min(scrollY / vh, 1);
        if (heroRef.current) {
          heroRef.current.style.opacity = (1 - hp * 1.15).toFixed(3);
          heroRef.current.style.transform = `translateY(${(-hp * 70).toFixed(1)}px)`;
        }
        if (cueRef.current) cueRef.current.style.opacity = (1 - Math.min(scrollY / 220, 1)).toFixed(3);
        if (loginWrapRef.current && scrollY > vh * 0.5) loginWrapRef.current.classList.add('sage-reveal');
        ticking = false;
      });
    };

    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseleave', onMouseLeave);
    window.addEventListener('resize', resize, { passive: true });
    window.addEventListener('scroll', onScroll, { passive: true });
    resize();
    onScroll();
    frame();

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseleave', onMouseLeave);
      window.removeEventListener('resize', resize);
      window.removeEventListener('scroll', onScroll);
    };
  }, []);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setErrorCode('');
    if (!email.trim() || !password.trim()) {
      setErrorCode('이메일과 비밀번호를 입력해 주세요.');
      return;
    }
    if (mode === 'signup' && !nickname.trim()) {
      setErrorCode('회원가입에는 닉네임이 필요합니다.');
      return;
    }
    if (mode === 'signup' && nickname.trim().length > 10) {
      setErrorCode('백엔드 정책상 닉네임은 최대 10자까지 가능합니다.');
      return;
    }
    try {
      setIsSubmitting(true);
      await onComplete({ email: email.trim(), password, nickname: nickname.trim() }, mode);
    } catch (error) {
      setErrorCode(error instanceof Error ? error.message : '인증 요청에 실패했습니다.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const scrollToLogin = () => loginRef.current?.scrollIntoView({ behavior: 'smooth' });
  const inputStyle: CSSProperties = {
    background: '#252E28', border: '1px solid #2D362F', borderRadius: 12,
    padding: '12px 14px', fontSize: 13, color: '#E6ECE8', outline: 'none', width: '100%',
  };
  const labelStyle: CSSProperties = { display: 'block', fontSize: 12, fontWeight: 600, color: '#C7D2CB', marginBottom: 5 };
  const tab = (active: boolean): CSSProperties => ({
    padding: '8px', borderRadius: 10, fontSize: 13, fontWeight: 700, border: 'none', cursor: 'pointer',
    background: active ? '#7AAE92' : 'transparent', color: active ? '#12241B' : '#9AA8A0',
  });

  return (
    <div className="relative font-sans" style={{ background: '#141917', color: '#E6ECE8' }}>
      <canvas ref={canvasRef} className="fixed inset-0 w-full h-full" style={{ zIndex: 0 }} />
      <div
        className="fixed inset-0 pointer-events-none"
        style={{ zIndex: 1, background: 'radial-gradient(1200px 500px at 50% 0%, rgba(122,174,146,.10), transparent 60%), linear-gradient(180deg, transparent 40%, rgba(10,14,12,.6))' }}
      />
      <div
        ref={spotRef}
        className="fixed top-0 left-0 pointer-events-none"
        style={{ zIndex: 1, width: 560, height: 560, marginLeft: -280, marginTop: -280, borderRadius: '50%', background: 'radial-gradient(circle, rgba(122,174,146,.13), transparent 60%)', filter: 'blur(24px)' }}
      />

      <nav className="fixed top-0 left-0 flex items-center gap-2.5 sage-anim" style={{ zIndex: 20, padding: '22px 40px', animationDelay: '0.05s' }}>
        <span className="flex items-center justify-center" style={{ width: 34, height: 34, borderRadius: 11, background: '#29392F', color: '#9CCBB2' }}>
          <MessagesSquare className="w-4 h-4" />
        </span>
        <span style={{ fontWeight: 700, fontSize: 15, color: '#E6ECE8' }}>Sage</span>
      </nav>

      {/* Intro / Hero */}
      <section className="min-h-screen flex flex-col items-center justify-center px-6 text-center relative" style={{ zIndex: 2 }}>
        <div ref={heroRef} style={{ maxWidth: 820 }}>
          <h1 className="sage-anim" style={{ fontWeight: 700, fontSize: 'clamp(26px, 4vw, 50px)', letterSpacing: '-.02em', lineHeight: 1.15, color: '#E6ECE8', margin: 0, animationDelay: '0.28s' }}>
            메시지가 도착하는<br />
            <span style={{ color: '#9CCBB2', fontWeight: 800 }}>그 순간</span>을 함께
          </h1>
        </div>
        <button ref={cueRef} onClick={scrollToLogin} className="absolute flex flex-col items-center gap-2 cursor-pointer" style={{ bottom: 40, background: 'transparent', border: 0, color: '#6B7972' }} aria-label="로그인으로 스크롤">
          <span className="flex justify-center" style={{ width: 22, height: 34, border: '1.5px solid #3d4a41', borderRadius: 12, paddingTop: 6 }}>
            <span className="sage-wheel" style={{ width: 3, height: 7, borderRadius: 2, background: '#7AAE92' }} />
          </span>
          <span style={{ fontSize: 12 }}>아래로 스크롤</span>
        </button>
      </section>

      {/* Login */}
      <section id="login" ref={loginRef} className="min-h-screen flex flex-col items-center justify-center px-6" style={{ zIndex: 2, position: 'relative' }}>
        <div ref={loginWrapRef} className="w-full" style={{ maxWidth: 400 }}>
          <div className="text-center mb-4 sage-stg" style={{ transitionDelay: '0.02s' }}>
            <div style={{ fontSize: 11, letterSpacing: '.16em', color: '#6B7972', fontWeight: 600 }}>WELCOME BACK</div>
            <div style={{ fontSize: 14, color: '#9AA8A0', marginTop: 4 }}>채팅방으로 입장</div>
          </div>

          <div className="sage-card-reveal" style={{ background: '#1C241F', border: '1px solid #2D362F', borderRadius: 22, padding: '30px 28px', boxShadow: '0 40px 80px -40px rgba(0,0,0,.7)' }}>
            <div className="flex items-center justify-center sage-stg" style={{ width: 52, height: 52, borderRadius: 16, background: '#29392F', color: '#9CCBB2', margin: '0 auto 12px', transitionDelay: '0.14s' }}>
              <MessagesSquare className="w-6 h-6" />
            </div>
            <h2 className="text-center sage-stg" style={{ fontWeight: 800, fontSize: 23, color: '#E6ECE8', margin: '0 0 18px', transitionDelay: '0.19s' }}>Sage</h2>

            <div className="grid grid-cols-2 gap-1.5 mb-4 sage-stg" style={{ background: '#252E28', border: '1px solid #2D362F', borderRadius: 13, padding: 4, transitionDelay: '0.3s' }}>
              <button type="button" onClick={() => { setMode('login'); setErrorCode(''); }} style={tab(mode === 'login')}>로그인</button>
              <button type="button" onClick={() => { setMode('signup'); setErrorCode(''); }} style={tab(mode === 'signup')}>회원가입</button>
            </div>

            <form onSubmit={handleSubmit} className="space-y-3">
              <div className="sage-stg" style={{ transitionDelay: '0.36s' }}>
                <label htmlFor="w-email" style={labelStyle}>이메일</label>
                <input id="w-email" className="sage-input" type="email" autoFocus placeholder="you@example.com" value={email} onChange={(e) => setEmail(e.target.value)} style={inputStyle} />
              </div>
              <div className="sage-stg" style={{ transitionDelay: '0.42s' }}>
                <label htmlFor="w-password" style={labelStyle}>비밀번호</label>
                <input id="w-password" className="sage-input" type="password" value={password} onChange={(e) => setPassword(e.target.value)} style={inputStyle} />
              </div>
              {mode === 'signup' && (
                <div className="sage-stg" style={{ transitionDelay: '0.44s' }}>
                  <label htmlFor="w-nickname" style={labelStyle}>닉네임 (최대 10자)</label>
                  <input id="w-nickname" className="sage-input" type="text" placeholder="예: 민준" value={nickname} onChange={(e) => setNickname(e.target.value)} style={inputStyle} />
                </div>
              )}
              {errorCode && <p style={{ fontSize: 12, color: '#f0a5a5', textAlign: 'center', fontWeight: 500, margin: 0 }}>{errorCode}</p>}
              <div className="sage-stg" style={{ transitionDelay: '0.48s' }}>
                <button type="submit" disabled={isSubmitting} className="sage-cta" style={{ width: '100%', background: '#7AAE92', color: '#12241B', borderRadius: 13, padding: 14, fontWeight: 600, fontSize: 14, border: 'none', cursor: 'pointer', opacity: isSubmitting ? 0.6 : 1 }} id="join-chat-btn">
                  {isSubmitting ? '처리 중...' : mode === 'login' ? '로그인' : '회원가입 후 입장'}
                </button>
              </div>
            </form>

            <div className="text-center sage-stg" style={{ fontSize: 12, color: '#6B7972', marginTop: 14, transitionDelay: '0.54s' }}>
              {mode === 'login' ? (
                <>계정이 없으신가요?{' '}
                  <button type="button" onClick={() => { setMode('signup'); setErrorCode(''); }} style={{ color: '#9CCBB2', background: 'none', border: 'none', cursor: 'pointer', padding: 0, font: 'inherit' }}>회원가입</button>
                </>
              ) : (
                <>이미 계정이 있으신가요?{' '}
                  <button type="button" onClick={() => { setMode('login'); setErrorCode(''); }} style={{ color: '#9CCBB2', background: 'none', border: 'none', cursor: 'pointer', padding: 0, font: 'inherit' }}>로그인</button>
                </>
              )}
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
