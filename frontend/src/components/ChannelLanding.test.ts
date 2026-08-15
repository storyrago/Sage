import { describe, it, expect } from 'vitest';
import { tapeVars } from './ChannelLanding';

// stampLayout.ts의 hash/unit 회귀와 같은 이유로 테이프 변주도 격자처럼 안 보이는지 고정한다.
// 실제 채널 id는 DB 숫자 id 문자열("1","2","3"…)이라 그 형태도 함께 검증한다.
describe('tapeVars — 테이프 변주가 채널마다 실제로 다르다', () => {
  const numericIds = Array.from({ length: 12 }, (_, i) => `${i + 1}`);
  const roomIds = Array.from({ length: 12 }, (_, i) => `room-${i + 1}`);

  it.each([
    ['숫자 문자열 id', numericIds],
    ['room- 접두 id', roomIds],
  ])('%s — 각도(--tape-rot)가 최소 6가지 이상 나온다', (_label, ids) => {
    const rots = ids.map((id) => tapeVars(id)['--tape-rot']);
    expect(new Set(rots).size).toBeGreaterThanOrEqual(6);
  });

  it.each([
    ['숫자 문자열 id', numericIds],
    ['room- 접두 id', roomIds],
  ])('%s — 오프셋(--tape-top-md)이 한 값으로 고정되지 않는다', (_label, ids) => {
    // 옛 코드: offset = (h*7) % 7 - 3 은 h값과 무관하게 항상 -3(배수를 자기 자신으로 나눈 나머지는 항상 0)
    const offsets = ids.map((id) => tapeVars(id)['--tape-top-md']);
    expect(new Set(offsets).size).toBeGreaterThanOrEqual(2);
  });
});
