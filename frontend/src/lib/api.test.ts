import { describe, it, expect, vi, afterEach } from 'vitest';
import { toMessage, BackendMessage, logout, setUnauthorizedHandler } from './api';

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
});
