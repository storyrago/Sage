import { describe, it, expect } from 'vitest';
import { toMessage, BackendMessage } from './api';

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
