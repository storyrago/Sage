import { describe, it, expect } from 'vitest';
import { layoutStamps, boardHeightPx, STAMP_W, STAMP_H, MIN_BOARD_W, type StampPosition } from './stampLayout';

function ids(n: number) {
  return Array.from({ length: n }, (_, i) => `channel-${i}`);
}

// 컴포넌트는 left/top을 우표의 좌상단 모서리로 그대로 쓴다(translate 보정 없음) —
// 따라서 사각형도 그 기준으로 계산해야 실제 렌더링과 일치한다.
function toRect(pos: StampPosition, boardH: number) {
  const leftPx = (parseFloat(pos.left) / 100) * MIN_BOARD_W;
  const topPx = (parseFloat(pos.top) / 100) * boardH;
  return {
    x1: leftPx,
    x2: leftPx + STAMP_W,
    y1: topPx,
    y2: topPx + STAMP_H,
  };
}

function overlaps(a: ReturnType<typeof toRect>, b: ReturnType<typeof toRect>) {
  return a.x1 < b.x2 && a.x2 > b.x1 && a.y1 < b.y2 && a.y2 > b.y1;
}

describe('layoutStamps', () => {
  it.each([8, 9, 12, 20])('%i개일 때 어떤 두 우표도 겹치지 않는다', (n) => {
    const boardH = boardHeightPx(n);
    const rects = layoutStamps(ids(n)).map((p) => toRect(p, boardH));
    for (let i = 0; i < rects.length; i++) {
      for (let j = i + 1; j < rects.length; j++) {
        expect(overlaps(rects[i], rects[j])).toBe(false);
      }
    }
  });

  it('같은 채널 id 목록이면 항상 같은 좌표를 낸다', () => {
    const list = ids(12);
    expect(layoutStamps(list)).toEqual(layoutStamps(list));
  });

  it('모든 우표가 보드 안에 들어간다', () => {
    for (const n of [8, 9, 12, 20]) {
      const boardH = boardHeightPx(n);
      for (const rect of layoutStamps(ids(n)).map((p) => toRect(p, boardH))) {
        expect(rect.x1).toBeGreaterThanOrEqual(0);
        expect(rect.x2).toBeLessThanOrEqual(MIN_BOARD_W);
        expect(rect.y1).toBeGreaterThanOrEqual(0);
        expect(rect.y2).toBeLessThanOrEqual(boardH);
      }
    }
  });
});

// 겹침/경계/결정성만으로는 "격자처럼 보이는지"를 못 잡는다 — 해시가 약해 좌표가
// 거의 등차수열로 나오는 회귀를 여기서 고정한다. 실제 채널 id는 DB 숫자 id 문자열
// ("1","2","3"…)이라 그 형태도 반드시 함께 검증한다.
describe('layoutStamps — 격자처럼 보이지 않는다(분산 회귀)', () => {
  const numericIds = (n: number) => Array.from({ length: n }, (_, i) => `${i + 1}`);
  const roomIds = (n: number) => Array.from({ length: n }, (_, i) => `room-${i + 1}`);
  const cases: [string, (n: number) => string[]][] = [
    ['숫자 문자열 id', numericIds],
    ['room- 접두 id', roomIds],
  ];

  function rowGroup(positions: StampPosition[], cols: number, row: number) {
    return positions.filter((_, i) => Math.floor(i / cols) === row);
  }
  function colGroup(positions: StampPosition[], cols: number, col: number) {
    return positions.filter((_, i) => i % cols === col);
  }
  function spread(vals: number[]) {
    return Math.max(...vals) - Math.min(...vals);
  }

  it.each(cases)('%s — n=12에서 회전각이 최소 6가지 이상 나온다', (_label, makeIds) => {
    const rots = layoutStamps(makeIds(12)).map((p) => p.rot);
    expect(new Set(rots).size).toBeGreaterThanOrEqual(6);
  });

  it.each(cases)('%s — n=8, 같은 행 안에서 top이 흔들린다', (_label, makeIds) => {
    // n=8 → cols=3, rows=3 (gridDims와 같은 공식: cols=max(3,ceil(sqrt(n))))
    const positions = layoutStamps(makeIds(8));
    for (const row of [0, 1]) { // row 2는 2칸뿐이라 표본이 적어 제외
      const tops = rowGroup(positions, 3, row).map((p) => parseFloat(p.top));
      expect(spread(tops)).toBeGreaterThan(0.8); // 옛 해시: ~0.18%p, 고친 해시: 1.3%p대 이상
    }
  });

  it.each(cases)('%s — n=8, 같은 열 안에서 left도 흔들린다', (_label, makeIds) => {
    const positions = layoutStamps(makeIds(8));
    for (const col of [0, 1]) { // col 2는 2칸뿐이라 표본이 적어 제외
      const lefts = colGroup(positions, 3, col).map((p) => parseFloat(p.left));
      expect(spread(lefts)).toBeGreaterThan(2.6); // 옛 해시: ~2.40%p, 고친 해시: 3.6%p대 이상
    }
  });
});
