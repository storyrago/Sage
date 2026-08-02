// 우표 콜라주 배치: 채널 수에 따라 겹치지 않는 좌표를 결정적으로 계산한다.
// 좌표는 데스크톱 절대배치(md:absolute, 132×176 우표)에서만 쓰인다. 모바일은 grid 흐름 배치라 무관.

export interface StampPosition {
  left: string; // 보드 폭 대비 %
  top: string; // 보드 높이 대비 %
  rot: number; // 회전각(도)
}

export const STAMP_W = 132; // md:w-[132px]
export const STAMP_H = 176; // md:h-[176px]
// 이 절대배치가 살아있는 가장 좁은 뷰포트(md 브레이크포인트). % 좌표의 겹침 안전선은
// 이 폭을 기준으로 계산한다 — 여기서 안 겹치면 더 넓은 보드에서는 셀이 더 커져 항상 안전하다.
export const MIN_BOARD_W = 768;
const ROW_H = 220; // 셀 높이 기준(우표 176 + 여유)
const MIN_BOARD_H = 560; // 기존 최소 보드 높이 유지
const JITTER_SAFETY = 0.8; // 셀 경계에 딱 붙지 않도록 흔들림 폭을 여유 있게 줄인다

// id로부터 결정적 값(아이콘/틴트/산포 위치/테이프 변주) — 컴포넌트와 공유
export function hash(id: string): number {
  return [...id].reduce((s, c) => s + c.charCodeAt(0), 0);
}

function gridDims(n: number): { cols: number; rows: number } {
  const cols = Math.max(3, Math.ceil(Math.sqrt(n)));
  const rows = Math.ceil(n / cols);
  return { cols, rows };
}

// 보드 높이(px) — 행이 늘면 커지고, 적을 땐 기존 최소값을 유지한다.
export function boardHeightPx(n: number): number {
  if (n <= 0) return MIN_BOARD_H;
  const { rows } = gridDims(n);
  return Math.max(MIN_BOARD_H, rows * ROW_H);
}

// hash(id)를 서로 다른 배수로 투영해 0~1 사이 결정적 값을 뽑는다(좌표축마다 다른 값이 나오게).
function unit(id: string, salt: number): number {
  return ((hash(id) * salt) % 1000) / 1000;
}

export function layoutStamps(ids: string[]): StampPosition[] {
  const n = ids.length;
  if (n === 0) return [];
  const { cols, rows } = gridDims(n);
  const boardH = boardHeightPx(n);
  const cellWpx = MIN_BOARD_W / cols;
  const cellHpx = boardH / rows;
  const jitterXpx = Math.max(0, ((cellWpx - STAMP_W) / 2) * JITTER_SAFETY);
  const jitterYpx = Math.max(0, ((cellHpx - STAMP_H) / 2) * JITTER_SAFETY);

  return ids.map((id, i) => {
    const col = i % cols;
    const row = Math.floor(i / cols);
    const centerXpx = (col + 0.5) * cellWpx;
    const centerYpx = (row + 0.5) * cellHpx;
    const jx = (unit(id, 31) * 2 - 1) * jitterXpx;
    const jy = (unit(id, 17) * 2 - 1) * jitterYpx;
    const rot = Math.round((unit(id, 7) * 2 - 1) * 10); // -10~10도

    const leftPct = ((centerXpx + jx) / MIN_BOARD_W) * 100;
    const topPct = ((centerYpx + jy) / boardH) * 100;

    return { left: `${leftPct.toFixed(2)}%`, top: `${topPct.toFixed(2)}%`, rot };
  });
}
