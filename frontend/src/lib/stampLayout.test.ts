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
