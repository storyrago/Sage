import { describe, it, expect } from 'vitest';
import {
  parseSavedPositions,
  resolveStampPositions,
  stampOverlapRatio,
  stampStorageKey,
  SEVERE_OVERLAP_RATIO,
} from './stampPlacement';
import { layoutStamps, boardHeightPx, STAMP_W, STAMP_H, MIN_BOARD_W } from './stampLayout';

function ids(n: number) {
  return Array.from({ length: n }, (_, i) => `${i + 1}`);
}

describe('stampStorageKey', () => {
  it('회원 id마다 다른 키를 낸다', () => {
    expect(stampStorageKey('u1')).not.toBe(stampStorageKey('u2'));
    expect(stampStorageKey('u1')).toBe(stampStorageKey('u1'));
  });
});

describe('parseSavedPositions — 깨진 값에도 앱이 죽지 않는다', () => {
  it('null/undefined는 빈 맵', () => {
    expect(parseSavedPositions(null)).toEqual({});
    expect(parseSavedPositions(undefined)).toEqual({});
  });

  it('JSON 파싱 실패는 빈 맵', () => {
    expect(parseSavedPositions('{not valid json')).toEqual({});
  });

  it('최상위가 배열/원시값이면 빈 맵', () => {
    expect(parseSavedPositions('[1,2,3]')).toEqual({});
    expect(parseSavedPositions('"hello"')).toEqual({});
    expect(parseSavedPositions('42')).toEqual({});
  });

  it('항목 형식이 어긋나면 그 항목만 버리고 나머지는 살린다', () => {
    const raw = JSON.stringify({
      '1': { left: '10.00%', top: '20.00%' }, // 정상
      '2': { left: '10.00%' }, // top 없음
      '3': { left: 10, top: 20 }, // 숫자(문자열 아님)
      '4': 'not-an-object',
      '5': { left: '10.00%', top: '20.00%px' }, // % 아님
    });
    expect(parseSavedPositions(raw)).toEqual({
      '1': { left: '10.00%', top: '20.00%' },
    });
  });
});

describe('resolveStampPositions', () => {
  it('저장된 위치가 자동 배치를 덮어쓴다', () => {
    const list = ids(5);
    const boardH = boardHeightPx(list.length);
    const saved = { '2': { left: '10.00%', top: '10.00%' } };
    const result = resolveStampPositions(list, saved, MIN_BOARD_W, boardH);
    expect(result[1].left).toBe('10.00%');
    expect(result[1].top).toBe('10.00%');
  });

  it('저장값에 없는 채널은 자동 배치를 그대로 쓴다', () => {
    const list = ids(5);
    const boardH = boardHeightPx(list.length);
    const auto = layoutStamps(list);
    const result = resolveStampPositions(list, {}, MIN_BOARD_W, boardH);
    expect(result).toEqual(auto);
  });

  it('없는 채널의 저장값이 있어도 결과가 정상이다', () => {
    const list = ids(3);
    const boardH = boardHeightPx(list.length);
    const saved = { 'ghost-room': { left: '50.00%', top: '50.00%' } };
    const result = resolveStampPositions(list, saved, MIN_BOARD_W, boardH);
    expect(result.length).toBe(3);
    for (const p of result) {
      expect(typeof p.left).toBe('string');
      expect(typeof p.top).toBe('string');
    }
  });

  it('보드 밖 좌표가 경계 안으로 가둬진다', () => {
    const list = ids(3);
    const boardH = boardHeightPx(list.length);
    const saved = { '1': { left: '150.00%', top: '-30.00%' } };
    const result = resolveStampPositions(list, saved, MIN_BOARD_W, boardH);
    const leftPx = (parseFloat(result[0].left) / 100) * MIN_BOARD_W;
    const topPx = (parseFloat(result[0].top) / 100) * boardH;
    expect(leftPx).toBeGreaterThanOrEqual(0);
    expect(leftPx + STAMP_W).toBeLessThanOrEqual(MIN_BOARD_W + 0.5);
    expect(topPx).toBeGreaterThanOrEqual(0);
    expect(topPx + STAMP_H).toBeLessThanOrEqual(boardH + 0.5);
  });

  it('채널이 줄어 보드가 짧아지면 저장된 우표도 짧아진 보드 안으로 가둬진다', () => {
    // n=20일 때 유효했던 하단 근처 좌표를 n=2(훨씬 짧은 보드)에 그대로 저장값으로 준다.
    const bigBoardH = boardHeightPx(20);
    const nearBottomTopPct = ((bigBoardH - STAMP_H) / bigBoardH) * 100;
    const smallList = ids(2);
    const smallBoardH = boardHeightPx(smallList.length);
    const saved = { '1': { left: '10.00%', top: `${nearBottomTopPct.toFixed(2)}%` } };
    const result = resolveStampPositions(smallList, saved, MIN_BOARD_W, smallBoardH);
    const topPx = (parseFloat(result[0].top) / 100) * smallBoardH;
    expect(topPx + STAMP_H).toBeLessThanOrEqual(smallBoardH + 0.5);
  });

  it('새 채널이 저장된 우표와 심하게 겹치지 않는다', () => {
    const list = ids(2);
    const boardH = boardHeightPx(list.length);
    const auto = layoutStamps(list);
    // 채널 '1'을 채널 '2'의 자동 배치 자리에 정확히 겹치도록 사용자가 옮겨놓은 상황을 흉내낸다.
    const saved = { '1': { left: auto[1].left, top: auto[1].top } };
    const result = resolveStampPositions(list, saved, MIN_BOARD_W, boardH);
    const ratio = stampOverlapRatio(result[0], result[1], MIN_BOARD_W, boardH);
    expect(ratio).toBeLessThanOrEqual(SEVERE_OVERLAP_RATIO);
  });

  it('같은 입력이면 항상 같은 결과(결정적)', () => {
    const list = ids(9);
    const boardH = boardHeightPx(list.length);
    const saved = { '3': { left: '5.00%', top: '5.00%' } };
    const r1 = resolveStampPositions(list, saved, MIN_BOARD_W, boardH);
    const r2 = resolveStampPositions(list, saved, MIN_BOARD_W, boardH);
    expect(r1).toEqual(r2);
  });
});
