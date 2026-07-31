import { describe, it, expect } from 'vitest';
import { reconnectDelayMs, reconnectExhausted, RECONNECT_MAX_MS, RECONNECT_MAX_ATTEMPTS } from './reconnect';

describe('reconnectDelayMs', () => {
  it('첫 시도는 기본 지연에 지터만 적용한다', () => {
    expect(reconnectDelayMs(0, () => 0.5)).toBe(1000);
  });

  it('시도마다 2배로 늘어난다', () => {
    const noJitter = () => 0.5;
    expect(reconnectDelayMs(1, noJitter)).toBe(2000);
    expect(reconnectDelayMs(2, noJitter)).toBe(4000);
    expect(reconnectDelayMs(3, noJitter)).toBe(8000);
  });

  it('상한을 넘지 않는다', () => {
    expect(reconnectDelayMs(20, () => 0.5)).toBe(RECONNECT_MAX_MS);
  });

  it('지터는 ±20% 안에 있다', () => {
    expect(reconnectDelayMs(1, () => 0)).toBe(1600);
    expect(reconnectDelayMs(1, () => 1)).toBe(2400);
  });

  it('음수 시도는 0으로 취급한다', () => {
    expect(reconnectDelayMs(-3, () => 0.5)).toBe(1000);
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
