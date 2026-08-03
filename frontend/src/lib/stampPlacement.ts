// 사용자가 드래그로 옮긴 우표 위치(localStorage)를 자동 배치와 합치는 순수 로직.
// DOM에 의존하지 않아 컴포넌트 밖에서 단위 테스트한다. 저장/로드 자체(localStorage IO)는
// 컴포넌트가 맡고, 여기는 계산만 한다.

import { layoutStamps, boardHeightPx, STAMP_W, STAMP_H, MIN_BOARD_W, type StampPosition } from './stampLayout';

export interface SavedPosition {
  left: string; // '12.34%'
  top: string;
}

export type SavedPositionMap = Record<string, SavedPosition>;

// 회원별로 분리 — 같은 브라우저에 다른 계정이 로그인해도 서로의 배치를 물려받지 않는다.
export function stampStorageKey(userId: string): string {
  return `stampPositions:${userId}`;
}

const PERCENT_RE = /^-?\d+(?:\.\d+)?%$/;

function isValidPercent(v: unknown): v is string {
  return typeof v === 'string' && PERCENT_RE.test(v);
}

// localStorage 원본을 안전하게 읽는다. 통째로 깨졌으면(JSON 파싱 실패, 배열/원시값 등)
// 빈 맵을 낸다 — 즉 전부 자동 배치로 떨어진다. 항목 단위로도 형식이 안 맞으면
// (left/top이 없거나 '%'가 아니면) 그 항목만 버리고 나머지는 살린다.
export function parseSavedPositions(raw: string | null | undefined): SavedPositionMap {
  if (!raw) return {};
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return {};
  }
  if (!data || typeof data !== 'object' || Array.isArray(data)) return {};

  const out: SavedPositionMap = {};
  for (const [id, pos] of Object.entries(data as Record<string, unknown>)) {
    if (!pos || typeof pos !== 'object') continue;
    const { left, top } = pos as Record<string, unknown>;
    if (!isValidPercent(left) || !isValidPercent(top)) continue;
    out[id] = { left, top };
  }
  return out;
}

// 겹친 넓이가 우표 면적의 이 비율을 넘으면 "심하게 겹친다"로 본다 — 새 채널이 저장된
// 우표 밑에 대부분 가려져 사용자가 새로 생긴 걸 눈치채지 못하는 상황을 막는 기준.
// 절반 넘게 가려지면 아이콘/이름을 거의 알아볼 수 없다고 보고 이 값을 골랐다.
export const SEVERE_OVERLAP_RATIO = 0.5;

interface Rect { x1: number; x2: number; y1: number; y2: number; }

function clamp(v: number, min: number, max: number): number {
  return Math.min(Math.max(v, min), max);
}

function pctToPx(pos: { left: string; top: string }, boardW: number, boardH: number) {
  return {
    left: (parseFloat(pos.left) / 100) * boardW,
    top: (parseFloat(pos.top) / 100) * boardH,
  };
}

function pxToPct(left: number, top: number, boardW: number, boardH: number): { left: string; top: string } {
  return {
    left: `${((left / boardW) * 100).toFixed(2)}%`,
    top: `${((top / boardH) * 100).toFixed(2)}%`,
  };
}

// 우표 사각형(132×176) 전체가 보드 안에 들어가도록 좌상단 모서리를 가둔다.
function clampCorner(left: number, top: number, boardW: number, boardH: number) {
  return {
    left: clamp(left, 0, Math.max(0, boardW - STAMP_W)),
    top: clamp(top, 0, Math.max(0, boardH - STAMP_H)),
  };
}

function rectFromCorner(left: number, top: number): Rect {
  return { x1: left, x2: left + STAMP_W, y1: top, y2: top + STAMP_H };
}

function overlapRatio(a: Rect, b: Rect): number {
  const ix = Math.max(0, Math.min(a.x2, b.x2) - Math.max(a.x1, b.x1));
  const iy = Math.max(0, Math.min(a.y2, b.y2) - Math.max(a.y1, b.y1));
  return (ix * iy) / (STAMP_W * STAMP_H);
}

function severelyOverlapsAny(rect: Rect, existing: Rect[]): boolean {
  return existing.some((r) => overlapRatio(rect, r) > SEVERE_OVERLAP_RATIO);
}

// 저장된 우표와 심하게 겹치면 8방향 나선으로 후보를 옮겨가며 덜 겹치는 자리를 찾는다.
// 다 실패하면(보드가 빽빽하게 찼을 때) 원래 자동 배치 자리를 그대로 쓴다 — 최선 시도.
function resolveOverlap(left: number, top: number, boardW: number, boardH: number, existing: Rect[]) {
  if (!severelyOverlapsAny(rectFromCorner(left, top), existing)) return { left, top };

  // 정확히 절반(50%)만 옮기면 %로 반올림해 저장할 때 부동소수 오차로 기준선(0.5)을
  // 살짝 넘나들 수 있다 — 60%로 옮겨 여유를 둔다.
  const stepX = STAMP_W * 0.6;
  const stepY = STAMP_H * 0.6;
  const directions = [
    [1, 0], [-1, 0], [0, 1], [0, -1],
    [1, 1], [-1, 1], [1, -1], [-1, -1],
  ];
  for (let radius = 1; radius <= 6; radius++) {
    for (const [dx, dy] of directions) {
      const cand = clampCorner(left + dx * radius * stepX, top + dy * radius * stepY, boardW, boardH);
      if (!severelyOverlapsAny(rectFromCorner(cand.left, cand.top), existing)) return cand;
    }
  }
  return { left, top };
}

// 저장된 배치와 자동 배치(layoutStamps)를 합친다.
// - 저장값이 있는 채널은 그 자리를 쓰되 보드 경계 안으로 가둔다(자유 배치 허용 — 저장된
//   우표끼리 겹치는 건 그대로 둔다).
// - 없는 채널은 자동 배치 자리를 쓰되, 저장된 우표와 심하게 겹치면 다른 자리를 찾는다.
// boardW/boardH는 실제 렌더링 시점의 보드 크기를 받는다(폭은 DOM 측정값, 높이는
// boardHeightPx로 정확히 계산 가능해 항상 그 값을 쓴다).
export function resolveStampPositions(
  ids: string[],
  saved: SavedPositionMap,
  boardW: number = MIN_BOARD_W,
  boardH: number = boardHeightPx(ids.length),
): StampPosition[] {
  const auto = layoutStamps(ids);
  const placed: Rect[] = [];
  const result: StampPosition[] = new Array(ids.length);

  ids.forEach((id, i) => {
    const s = saved[id];
    if (!s) return;
    const px = pctToPx(s, boardW, boardH);
    const corner = clampCorner(px.left, px.top, boardW, boardH);
    result[i] = { ...pxToPct(corner.left, corner.top, boardW, boardH), rot: auto[i].rot };
    placed.push(rectFromCorner(corner.left, corner.top));
  });

  ids.forEach((id, i) => {
    if (saved[id]) return;
    const a = auto[i];
    const px = pctToPx(a, boardW, boardH);
    const corner = resolveOverlap(px.left, px.top, boardW, boardH, placed);
    result[i] = { ...pxToPct(corner.left, corner.top, boardW, boardH), rot: a.rot };
    placed.push(rectFromCorner(corner.left, corner.top));
  });

  return result;
}

// 두 배치 결과가 보드 안에서 얼마나 겹치는지(0~1) — 테스트 검증용.
export function stampOverlapRatio(a: StampPosition, b: StampPosition, boardW: number, boardH: number): number {
  const pa = pctToPx(a, boardW, boardH);
  const pb = pctToPx(b, boardW, boardH);
  return overlapRatio(rectFromCorner(pa.left, pa.top), rectFromCorner(pb.left, pb.top));
}
