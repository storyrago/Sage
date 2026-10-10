import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  toMessage,
  BackendMessage,
  deleteAccount,
  logout,
  exchangeOAuthCode,
  setUnauthorizedHandler,
  getUnreadCounts,
  uploadImage,
  getMessages,
  markRoomRead,
  sendMessage,
  SEND_TIMEOUT_MS,
} from './api';

const base: BackendMessage = {
  messageId: 1,
  content: '안녕',
  memberId: 7,
  nickname: '작성자',
  chatroomId: 3,
  seq: 1,
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

  it('순번과 clientMessageId를 옮긴다', () => {
    const message = toMessage({ ...base, seq: 42, clientMessageId: 'c-1' });

    expect(message.seq).toBe(42);
    expect(message.clientMessageId).toBe('c-1');
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

describe('getUnreadCounts', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setUnauthorizedHandler(null);
  });

  it('replyCount를 그대로 파싱해서 돌려준다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify([{ chatroomId: 3, unreadCount: 5, replyCount: 2, lastReadMessageId: 10 }]),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    const result = await getUnreadCounts('tok-123');

    expect(result[0].replyCount).toBe(2);
  });
});

describe('uploadImage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('purpose가 chat이면 요청 URL에 purpose=chat을 싣는다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ url: 'https://example.com/img.png' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await uploadImage('tok-123', new File(['x'], 'x.png'), 'chat');

    expect(String(fetchMock.mock.calls[0][0])).toContain('purpose=chat');
  });

  it('purpose가 profile이면 요청 URL에 purpose=profile을 싣는다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ url: 'https://example.com/img.png' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await uploadImage('tok-123', new File(['x'], 'x.png'), 'profile');

    expect(String(fetchMock.mock.calls[0][0])).toContain('purpose=profile');
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

describe('getMessages', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const okPage = () =>
    new Response(JSON.stringify({ messages: [], hasMore: false }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });

  it('afterSeq 커서를 쿼리로 보낸다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okPage());
    vi.stubGlobal('fetch', fetchMock);

    await getMessages('tok', '3', { afterSeq: 7 }, 50);

    const url = new URL(String(fetchMock.mock.calls[0][0]), 'http://x');
    expect(url.pathname).toBe('/api/chatrooms/3/messages');
    expect(url.searchParams.get('afterSeq')).toBe('7');
    expect(url.searchParams.get('beforeSeq')).toBeNull();
    expect(url.searchParams.get('limit')).toBe('50');
  });

  it('커서가 없으면 limit만 보낸다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okPage());
    vi.stubGlobal('fetch', fetchMock);

    await getMessages('tok', '3');

    const url = new URL(String(fetchMock.mock.calls[0][0]), 'http://x');
    expect([...url.searchParams.keys()]).toEqual(['limit']);
  });
});

describe('markRoomRead', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('seq가 있으면 본문으로 보낸다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await markRoomRead('tok', '3', 12);

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/api/chatrooms/3/read');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({ seq: 12 });
  });

  it('seq가 없으면 본문 없이 보낸다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await markRoomRead('tok', '3');

    expect(fetchMock.mock.calls[0][1].body).toBeUndefined();
  });
});

describe('sendMessage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('clientMessageId를 본문에 싣는다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ...base, seq: 5, clientMessageId: 'c-1' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await sendMessage('tok', '3', { content: '안녕', replyToId: '9', clientMessageId: 'c-1' });

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/api/chatrooms/3/messages');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({ content: '안녕', replyToId: 9, imageUrl: null, clientMessageId: 'c-1' });
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it('시간 안에 응답이 없으면 AbortError로 끝난다', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    })));

    const sending = sendMessage('tok', '3', { content: 'x', clientMessageId: 'c-2' });
    const assertion = expect(sending).rejects.toMatchObject({ name: 'AbortError' });
    await vi.advanceTimersByTimeAsync(SEND_TIMEOUT_MS);
    await assertion;
  });
});
