import { describe, it, expect } from 'vitest';
import { applyUnreadNotice, fromSnapshots, markReadUpTo, toBadges } from './unread';

const state = fromSnapshots([
  { chatroomId: 1, lastMessageSeq: 10, lastReadSeq: 7, replyCount: 1 },
  { chatroomId: 2, lastMessageSeq: 4, lastReadSeq: 4, replyCount: 0 },
]);

describe('fromSnapshots / toBadges', () => {
  it('배지는 최신 순번 − 읽은 순번', () => {
    expect(toBadges(state)).toEqual({ '1': { count: 3, replies: 1 }, '2': { count: 0, replies: 0 } });
  });
});

describe('applyUnreadNotice', () => {
  it('새 순번이면 최신 순번을 올린다', () => {
    const next = applyUnreadNotice(state, '1', 11, false);
    expect(toBadges(next)['1']).toEqual({ count: 4, replies: 1 });
  });

  it('같은 알림을 두 번 받아도 배지가 늘지 않는다', () => {
    const once = applyUnreadNotice(state, '1', 11, true);
    const twice = applyUnreadNotice(once, '1', 11, true);
    expect(twice).toBe(once);
    expect(toBadges(twice)['1']).toEqual({ count: 4, replies: 2 });
  });

  it('순서가 뒤바뀐 옛 알림은 무시한다', () => {
    const next = applyUnreadNotice(applyUnreadNotice(state, '1', 13, false), '1', 12, true);
    expect(toBadges(next)['1']).toEqual({ count: 6, replies: 1 });
  });

  it('처음 보는 방이면 한 건으로 시작한다', () => {
    const next = applyUnreadNotice(state, '9', 5, true);
    expect(toBadges(next)['9']).toEqual({ count: 1, replies: 1 });
  });
});

describe('markReadUpTo', () => {
  it('끝까지 읽으면 배지와 답장 수가 0', () => {
    expect(toBadges(markReadUpTo(state, '1', 10))['1']).toEqual({ count: 0, replies: 0 });
  });

  it('일부만 읽으면 남은 만큼, 답장 수는 유지', () => {
    expect(toBadges(markReadUpTo(state, '1', 8))['1']).toEqual({ count: 2, replies: 1 });
  });

  it('읽은 위치는 뒤로 가지 않는다', () => {
    expect(markReadUpTo(state, '1', 5)).toBe(state);
  });

  it('최신보다 앞선 순번을 읽으면 최신 순번도 함께 올린다', () => {
    const next = markReadUpTo(state, '2', 6);
    expect(next['2']).toEqual({ lastMessageSeq: 6, lastReadSeq: 6, replies: 0 });
  });
});
