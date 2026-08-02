import { describe, it, expect, vi, afterEach } from 'vitest';
import { toMessage, BackendMessage, deleteAccount, logout, exchangeOAuthCode, setUnauthorizedHandler } from './api';

const base: BackendMessage = {
  messageId: 1,
  content: '안녕',
  memberId: 7,
  nickname: '작성자',
  chatroomId: 3,
  createdAt: '2026-08-02T00:00:00.000Z',
};

describe('toMessage', () => {
  it('작성자가 있으면 그대로 변환한다', () => {
    const message = toMessage(base);

    expect(message.userId).toBe('7');
    expect(message.userName).toBe('작성자');
    expect(message.userAvatar).not.toBe('');
  });

  it('memberId가 null이면 삭제된 사용자로 표시하고 아바타는 비어있지 않다', () => {
    const message = toMessage({ ...base, memberId: null, nickname: null });

    expect(message.userName).toBe('삭제된 사용자');
    expect(message.userId).toBe('');
    expect(message.userAvatar).not.toBe('');
  });
});

describe('logout', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setUnauthorizedHandler(null);
    vi.useRealTimers();
  });

  it('토큰을 Authorization 헤더로 보낸다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await logout('tok-123');

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/api/auth/logout');
    expect(init.method).toBe('POST');
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer tok-123');
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it('401이면 이미 무효화된 토큰이므로 성공으로 본다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);

    await expect(logout('tok-123')).resolves.toBeUndefined();
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('서버 오류는 예외를 던지되 전역 401 처리기는 부르지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 500 })));
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);

    await expect(logout('tok-123')).rejects.toThrow();
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('8초 안에 응답이 없으면 요청을 끊고 reject한다', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn((_url: string, init?: RequestInit) => {
      return new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          reject(new DOMException('The operation was aborted.', 'AbortError'));
        });
      });
    });
    vi.stubGlobal('fetch', fetchMock);

    const assertion = expect(logout('tok-123')).rejects.toThrow();
    await vi.advanceTimersByTimeAsync(8000);

    await assertion;
  });
});

describe('deleteAccount', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setUnauthorizedHandler(null);
  });

  it('DELETE 메서드로 /api/members/me를 호출하고 토큰을 Authorization 헤더로 보낸다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await deleteAccount('tok-123');

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/api/members/me');
    expect(init.method).toBe('DELETE');
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer tok-123');
  });
});

describe('exchangeOAuthCode', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setUnauthorizedHandler(null);
  });

  it('코드를 본문으로 보내고 액세스 토큰을 돌려준다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ accessToken: 'tok-abc', tokenType: 'Bearer' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(exchangeOAuthCode('code-123')).resolves.toBe('tok-abc');

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/api/auth/oauth/token');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({ code: 'code-123' });
  });

  it('코드를 URL에 싣지 않는다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ accessToken: 'tok-abc' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await exchangeOAuthCode('code-123');

    expect(String(fetchMock.mock.calls[0][0])).not.toContain('code-123');
  });

  it('실패하면 예외를 던지되 전역 401 처리기는 부르지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);

    await expect(exchangeOAuthCode('code-123')).rejects.toThrow();
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});
