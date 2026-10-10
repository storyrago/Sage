import { describe, it, expect } from 'vitest';
import { Message } from '../types';
import { gapAfterSeq, insertLive, mergeMessages, replaceWithPage } from './timeline';

function msg(seq: number, text = `m${seq}`): Message {
  return {
    id: String(seq + 100),
    channelId: '1',
    text,
    userId: '7',
    userName: 'u',
    userAvatar: 'a',
    createdAt: 0,
    seq,
  };
}

const seqs = (list: Message[]) => list.map((m) => m.seq);

describe('mergeMessages', () => {
  it('순번 자리에 끼워 넣는다', () => {
    expect(seqs(mergeMessages([msg(1), msg(3)], [msg(2)]))).toEqual([1, 2, 3]);
  });

  it('같은 순번은 새 것으로 덮어쓴다(수정·삭제 반영)', () => {
    const merged = mergeMessages([msg(1), msg(2)], [msg(2, '수정됨')]);
    expect(seqs(merged)).toEqual([1, 2]);
    expect(merged[1].text).toBe('수정됨');
  });

  it('넣을 것이 없으면 같은 배열을 돌려준다', () => {
    const list = [msg(1)];
    expect(mergeMessages(list, [])).toBe(list);
  });
});

describe('insertLive', () => {
  it('늦게 도착한 작은 순번도 버리지 않고 자리에 넣는다', () => {
    expect(seqs(insertLive([msg(5), msg(7)], msg(6)))).toEqual([5, 6, 7]);
  });

  it('불러온 범위보다 오래된 순번은 무시한다', () => {
    const list = [msg(5), msg(6)];
    expect(insertLive(list, msg(2))).toBe(list);
  });

  it('빈 목록에는 그대로 넣는다', () => {
    expect(seqs(insertLive([], msg(9)))).toEqual([9]);
  });
});

describe('replaceWithPage', () => {
  it('최신 페이지로 바꾸고, 페이지보다 새로운 메시지만 남긴다', () => {
    const list = [msg(1), msg(2), msg(12)];
    const page = [msg(9), msg(10), msg(11)];
    expect(seqs(replaceWithPage(list, page))).toEqual([9, 10, 11, 12]);
  });

  it('빈 페이지면 기존 목록을 그대로 둔다', () => {
    const list = [msg(1)];
    expect(replaceWithPage(list, [])).toBe(list);
  });
});

describe('gapAfterSeq', () => {
  it('끝까지 이어져 있으면 null', () => {
    expect(gapAfterSeq([msg(3), msg(4), msg(5)])).toBeNull();
  });

  it('빈 순번이 있으면 이어진 마지막 순번을 돌려준다', () => {
    expect(gapAfterSeq([msg(3), msg(4), msg(7), msg(8)])).toBe(4);
  });

  it('빈 목록이면 null', () => {
    expect(gapAfterSeq([])).toBeNull();
  });
});
