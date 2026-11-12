// 방별 안읽음 상태. 배지는 "방 최신 순번 − 읽은 순번"이다.
// 알림은 최신 순번을 올리기만 하므로(이미 본 순번이면 무시) 같은 알림이 두 번 와도, 순서가 뒤바뀌어도 숫자가 맞다.

export interface RoomUnread {
  lastMessageSeq: number;
  lastReadSeq: number;
  replies: number; // 읽은 뒤 내 메시지에 달린 남의 답장 수
}

export type UnreadState = Record<string, RoomUnread>;

/** 서버 GET /api/chatrooms/unread 한 항목에서 쓰는 필드. */
export interface UnreadSnapshot {
  chatroomId: number;
  lastMessageSeq: number;
  lastReadSeq: number;
  replyCount: number;
}

export function fromSnapshots(counts: UnreadSnapshot[]): UnreadState {
  return Object.fromEntries(
    counts.map((c) => [
      String(c.chatroomId),
      { lastMessageSeq: c.lastMessageSeq, lastReadSeq: c.lastReadSeq, replies: c.replyCount },
    ]),
  );
}

export function applyUnreadNotice(state: UnreadState, roomId: string, seq: number, replyToMe: boolean): UnreadState {
  const current = state[roomId];
  if (!current) {
    // 목록을 받기 전에 알림이 먼저 온 방. 이 알림 한 건만 안 읽은 것으로 시작하고, 다음 재조회가 바로잡는다.
    return { ...state, [roomId]: { lastMessageSeq: seq, lastReadSeq: seq - 1, replies: replyToMe ? 1 : 0 } };
  }
  if (seq <= current.lastMessageSeq) return state;
  return {
    ...state,
    [roomId]: { ...current, lastMessageSeq: seq, replies: current.replies + (replyToMe ? 1 : 0) },
  };
}

export function markReadUpTo(state: UnreadState, roomId: string, seq: number): UnreadState {
  const current = state[roomId] ?? { lastMessageSeq: 0, lastReadSeq: 0, replies: 0 };
  if (state[roomId] && seq <= current.lastReadSeq) return state;
  const lastReadSeq = Math.max(current.lastReadSeq, seq);
  const lastMessageSeq = Math.max(current.lastMessageSeq, seq);
  // 일부만 읽었으면 남은 답장이 어느 순번인지 모르므로 수를 그대로 둔다. 끝까지 읽으면 0.
  const replies = lastReadSeq >= lastMessageSeq ? 0 : current.replies;
  return { ...state, [roomId]: { lastMessageSeq, lastReadSeq, replies } };
}

export function toBadges(state: UnreadState): Record<string, { count: number; replies: number }> {
  return Object.fromEntries(
    Object.entries(state).map(([roomId, r]) => [
      roomId,
      { count: Math.max(0, r.lastMessageSeq - r.lastReadSeq), replies: r.replies },
    ]),
  );
}
