import { Message } from '../types';

// 한 방의 메시지 목록을 다룬다. 목록은 항상 seq 오름차순이고 같은 seq는 하나뿐이다.
// 서버의 seq는 방 안에서 빈틈없이 증가하므로, 목록에 빈 순번이 보이면 못 받은 메시지가 있다는 뜻이다.
// 모든 함수는 입력을 바꾸지 않고, 바뀐 것이 없으면 같은 배열을 돌려준다(불필요한 리렌더 방지).

/** 서버가 준 메시지를 합친다. 같은 seq는 새 것으로 덮어쓴다 — 실시간 페이로드에 이벤트 종류가 없어 수정·삭제도 이렇게 반영된다. */
export function mergeMessages(list: Message[], incoming: Message[]): Message[] {
  if (incoming.length === 0) return list;
  const bySeq = new Map<number, Message>();
  for (const message of list) bySeq.set(message.seq, message);
  for (const message of incoming) bySeq.set(message.seq, message);
  return [...bySeq.values()].sort((a, b) => a.seq - b.seq);
}

/**
 * 실시간으로 받은 메시지를 넣는다. 불러온 범위보다 오래된 seq(로드하지 않은 옛 메시지의 수정·삭제)는
 * 버린다 — 과거 스크롤로 받을 때 최신 상태가 온다.
 */
export function insertLive(list: Message[], message: Message): Message[] {
  if (list.length > 0 && message.seq < list[0].seq) return list;
  return mergeMessages(list, [message]);
}

/**
 * 방에 들어올 때 최신 페이지로 바꾼다. 구독이 페이지 조회보다 먼저라 그 사이 도착한 메시지가
 * 페이지보다 새로울 수 있으므로 그것만 남긴다.
 */
export function replaceWithPage(list: Message[], page: Message[]): Message[] {
  if (page.length === 0) return list;
  const newest = page[page.length - 1].seq;
  return mergeMessages(page, list.filter((message) => message.seq > newest));
}

/** 빈 순번이 있으면 다시 받기 시작할 순번(afterSeq) — 맨 앞부터 이어진 마지막 seq. 없으면 null. */
export function gapAfterSeq(list: Message[]): number | null {
  if (list.length === 0) return null;
  let contiguous = list[0].seq;
  for (let i = 1; i < list.length; i++) {
    if (list[i].seq !== contiguous + 1) return contiguous;
    contiguous = list[i].seq;
  }
  return null;
}
