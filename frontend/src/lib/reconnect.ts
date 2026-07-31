// 재연결 간격은 지수적으로 늘리고 상한을 둔다.
// 지터는 서버가 되살아날 때 모든 클라이언트가 같은 시점에 몰려 다시 과부하를 주는 것을 막는다.
export const RECONNECT_BASE_MS = 1000;
export const RECONNECT_MAX_MS = 30000;
export const RECONNECT_MAX_ATTEMPTS = 8;

/** attempt는 0부터. random은 테스트에서 고정하기 위한 주입점이다. */
export function reconnectDelayMs(attempt: number, random: () => number = Math.random): number {
  const steps = Math.max(0, attempt);
  const exponential = Math.min(RECONNECT_BASE_MS * 2 ** steps, RECONNECT_MAX_MS);
  const jitter = 1 + (random() * 0.4 - 0.2);
  return Math.round(exponential * jitter);
}

/** 자동 재시도를 멈추고 사용자에게 알려야 하는 시점 */
export function reconnectExhausted(attempt: number): boolean {
  return attempt >= RECONNECT_MAX_ATTEMPTS;
}
