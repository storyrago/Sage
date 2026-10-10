import { describe, it, expect } from 'vitest';
import { ApiError } from './api';
import { PendingMessage, addPending, classifySendError, markFailed, markSending, newClientMessageId, removePending } from './pending';

function pendingOf(id: string, roomId = '1'): PendingMessage {
  return { clientMessageId: id, channelId: roomId, text: id, createdAt: 0, status: 'sending', retryable: true };
}

describe('대기 목록 상태 전이', () => {
  it('보낸 순서대로 쌓인다', () => {
    const state = addPending(addPending({}, pendingOf('a')), pendingOf('b'));
    expect(state['1'].map((p) => p.clientMessageId)).toEqual(['a', 'b']);
  });

  it('실패로 바꾸면 사유와 재시도 가능 여부를 남긴다', () => {
    const state = markFailed(addPending({}, pendingOf('a')), '1', 'a', { retryable: false, message: '권한 없음' });
    expect(state['1'][0]).toMatchObject({ status: 'failed', retryable: false, error: '권한 없음' });
  });

  it('다시 보내면 전송 중으로 돌아가고 사유를 지운다', () => {
    const failed = markFailed(addPending({}, pendingOf('a')), '1', 'a', { retryable: true, message: '끊김' });
    const state = markSending(failed, '1', 'a');
    expect(state['1'][0].status).toBe('sending');
    expect(state['1'][0].error).toBeUndefined();
  });

  it('응답과 방송 중 나중에 온 쪽의 제거는 아무것도 바꾸지 않는다', () => {
    const once = removePending(addPending({}, pendingOf('a')), '1', 'a');
    expect(removePending(once, '1', 'a')).toBe(once);
  });

  it('이미 확정돼 사라진 항목을 실패로 바꾸지 않는다', () => {
    const state = removePending(addPending({}, pendingOf('a')), '1', 'a');
    expect(markFailed(state, '1', 'a', { retryable: true, message: 'x' })).toBe(state);
  });

  it('다른 방의 같은 id는 건드리지 않는다', () => {
    const state = addPending(addPending({}, pendingOf('a', '1')), pendingOf('b', '2'));
    expect(removePending(state, '2', 'a')).toBe(state);
  });
});

describe('classifySendError', () => {
  it('5xx·408·429는 다시 보낼 수 있다', () => {
    expect(classifySendError(new ApiError('x', 503)).retryable).toBe(true);
    expect(classifySendError(new ApiError('x', 408)).retryable).toBe(true);
    expect(classifySendError(new ApiError('x', 429)).retryable).toBe(true);
  });

  it('그 밖의 4xx는 다시 보내도 소용없고 서버 문구를 쓴다', () => {
    expect(classifySendError(new ApiError('채널에 참여하지 않았어요.', 403))).toEqual({
      retryable: false,
      message: '채널에 참여하지 않았어요.',
    });
  });

  it('시간 초과와 네트워크 오류는 다시 보낼 수 있다', () => {
    expect(classifySendError(new DOMException('aborted', 'AbortError'))).toEqual({ retryable: true, message: '응답이 없어요.' });
    expect(classifySendError(new TypeError('Failed to fetch'))).toEqual({ retryable: true, message: '네트워크에 연결할 수 없어요.' });
  });
});

describe('newClientMessageId', () => {
  const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

  it('randomUUID가 있으면 그대로 쓴다', () => {
    expect(newClientMessageId({ randomUUID: () => 'from-native', getRandomValues: (a) => a })).toBe('from-native');
  });

  it('randomUUID가 없는 환경(HTTP로 연 다른 기기)에서도 서버가 받는 UUID v4를 만든다', () => {
    const id = newClientMessageId({ getRandomValues: (a) => crypto.getRandomValues(a) });
    expect(id).toMatch(UUID_V4);
  });
});
