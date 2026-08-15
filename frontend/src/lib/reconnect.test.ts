import { describe, it, expect } from 'vitest';
import { reconnectDelayMs, reconnectExhausted, RECONNECT_MAX_MS, RECONNECT_MAX_ATTEMPTS } from './reconnect';

describe('reconnectDelayMs', () => {
  it('첫 시도는 기본 지연의 절반에서 시작한다', () => {
    expect(reconnectDelayMs(0, () => 0)).toBe(500);
    expect(reconnectDelayMs(0, () => 1)).toBe(1000);
  });

  it('시도마다 상한이 2배로 늘어난다', () => {
    expect(reconnectDelayMs(1, () => 1)).toBe(2000);
    expect(reconnectDelayMs(2, () => 1)).toBe(4000);
    expect(reconnectDelayMs(3, () => 1)).toBe(8000);
  });

  it('지터가 최대여도 상한을 넘지 않는다', () => {
    expect(reconnectDelayMs(5, () => 1)).toBe(RECONNECT_MAX_MS);
    expect(reconnectDelayMs(20, () => 1)).toBe(RECONNECT_MAX_MS);
  });

  it('지터가 최소여도 상한의 절반 아래로 내려가지 않는다', () => {
    expect(reconnectDelayMs(20, () => 0)).toBe(RECONNECT_MAX_MS / 2);
  });

  it('음수 시도는 0으로 취급한다', () => {
    expect(reconnectDelayMs(-3, () => 1)).toBe(1000);
  });
});

describe('reconnectExhausted', () => {
  it('상한 미만이면 계속 시도한다', () => {
    expect(reconnectExhausted(RECONNECT_MAX_ATTEMPTS - 1)).toBe(false);
  });

  it('상한에 도달하면 중단한다', () => {
    expect(reconnectExhausted(RECONNECT_MAX_ATTEMPTS)).toBe(true);
  });
});
