import { ApiError } from './api';

// 보냈지만 아직 확정되지 않은 메시지. 서버 순번이 없으므로 방의 메시지 목록과 따로 두고,
// POST 응답이나 같은 clientMessageId의 실시간 방송 중 먼저 온 쪽이 이 목록에서 지운다.
// 상태 함수는 입력을 바꾸지 않고, 대상이 없으면 같은 객체를 돌려준다 — 늦게 온 쪽의 처리가 아무것도 바꾸지 않게.

export type PendingStatus = 'sending' | 'failed';

export interface PendingMessage {
  clientMessageId: string;
  channelId: string;
  text: string;
  replyToId?: string;
  imageUrl?: string;
  createdAt: number;
  status: PendingStatus;
  retryable: boolean; // 실패했을 때 다시 보내기를 보여 줄지
  error?: string;
}

export type PendingState = Record<string, PendingMessage[]>;

export interface SendFailure {
  retryable: boolean;
  message: string;
}

export function addPending(state: PendingState, message: PendingMessage): PendingState {
  return { ...state, [message.channelId]: [...(state[message.channelId] ?? []), message] };
}

function updateOne(
  state: PendingState,
  roomId: string,
  clientMessageId: string,
  change: (message: PendingMessage) => PendingMessage,
): PendingState {
  const list = state[roomId];
  const index = list ? list.findIndex((p) => p.clientMessageId === clientMessageId) : -1;
  if (index < 0) return state;
  const next = [...list];
  next[index] = change(list[index]);
  return { ...state, [roomId]: next };
}

export function markFailed(state: PendingState, roomId: string, clientMessageId: string, failure: SendFailure): PendingState {
  return updateOne(state, roomId, clientMessageId, (p) => ({
    ...p,
    status: 'failed',
    retryable: failure.retryable,
    error: failure.message,
  }));
}

export function markSending(state: PendingState, roomId: string, clientMessageId: string): PendingState {
  return updateOne(state, roomId, clientMessageId, (p) => ({ ...p, status: 'sending', error: undefined }));
}

export function removePending(state: PendingState, roomId: string, clientMessageId: string): PendingState {
  const list = state[roomId];
  if (!list || !list.some((p) => p.clientMessageId === clientMessageId)) return state;
  return { ...state, [roomId]: list.filter((p) => p.clientMessageId !== clientMessageId) };
}

/**
 * 다시 보내서 나아질 수 있는 실패인지 가른다. 응답이 없거나(시간 초과·네트워크) 서버가 일시적으로 못 받은 경우만
 * 다시 보내기를 보여 준다. 입력 오류·권한 없음 같은 4xx는 같은 요청을 다시 보내도 결과가 같다.
 */
export function classifySendError(error: unknown): SendFailure {
  if (error instanceof ApiError) {
    if (error.status >= 500 || error.status === 408 || error.status === 429) {
      return { retryable: true, message: '서버가 응답하지 못했어요.' };
    }
    return { retryable: false, message: error.message || '보낼 수 없는 메시지예요.' };
  }
  if (error instanceof DOMException && error.name === 'AbortError') {
    return { retryable: true, message: '응답이 없어요.' };
  }
  return { retryable: true, message: '네트워크에 연결할 수 없어요.' };
}
