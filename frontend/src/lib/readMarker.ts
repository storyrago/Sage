// 읽음 처리는 메시지마다 쓰면 안 되므로 스로틀을 두되, 선행 호출만 보내는 방식은
// 창 안에서 억제된 마지막 호출을 영영 잃는다 — 그 사이 도착한 메시지를 서버가 안읽음으로 되돌린다.
// 그래서 억제된 호출은 버리지 않고 창이 끝나는 시점에 한 번 보충한다(trailing).
export const MARK_READ_THROTTLE_MS = 1000;

export function createReadMarker(
  send: (roomId: string) => void,
  stillViewing: (roomId: string) => boolean,
) {
  let lastSentAt = -Infinity; // 아직 한 번도 보낸 적 없음 — 첫 호출은 항상 창 밖으로 취급
  let pendingTimer: ReturnType<typeof setTimeout> | null = null;
  let pendingRoomId: string | null = null;

  const mark = (roomId: string): void => {
    const now = Date.now();
    if (now - lastSentAt >= MARK_READ_THROTTLE_MS) {
      lastSentAt = now;
      if (pendingTimer) {
        clearTimeout(pendingTimer);
        pendingTimer = null;
        pendingRoomId = null;
      }
      send(roomId);
      return;
    }

    // 창 안의 호출: 방금 억제된 시점 기준이 아니라 창이 실제로 끝나는 시점에 맞춰 예약한다.
    pendingRoomId = roomId;
    if (pendingTimer) return; // 이미 예약돼 있으면 방 id만 최신으로 갱신하고 타이머는 재사용

    const delay = MARK_READ_THROTTLE_MS - (now - lastSentAt);
    pendingTimer = setTimeout(() => {
      pendingTimer = null;
      const targetRoomId = pendingRoomId as string;
      pendingRoomId = null;
      // 창이 끝나는 사이 사용자가 그 방을 떠났다면, 떠난 뒤 도착한 메시지까지 읽음 처리될 수 있어 보내지 않는다.
      if (!stillViewing(targetRoomId)) return;
      lastSentAt = Date.now();
      send(targetRoomId);
    }, delay);
  };

  const cancel = (): void => {
    if (pendingTimer) {
      clearTimeout(pendingTimer);
      pendingTimer = null;
      pendingRoomId = null;
    }
  };

  return { mark, cancel };
}
