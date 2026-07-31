import { ApiError } from './api';

// 서버·네트워크 원문이 그대로 보이지 않게 거른다.
// fetch 실패는 TypeError로 오고, 502 같은 응답은 HTML 원문이 message에 담긴다.
export function toUserMessage(err: unknown, fallback: string): string {
  if (err instanceof TypeError) return '네트워크에 연결할 수 없어요. 연결을 확인해 주세요.';
  const raw = err instanceof Error ? err.message.trim() : '';
  if (!raw) return fallback;
  if (raw.length > 80 || /[<{]/.test(raw)) return fallback;   // HTML·JSON 원문
  return raw;
}

// 세션이 만료된 경우에만 로그인 화면으로 돌린다.
// 기준은 api.ts의 401 처리기와 같다 — 같은 401이라도 INVALID_PASSWORD 같은 코드는 세션을 지우면 안 된다.
export function isSessionExpiredError(err: unknown): boolean {
  if (!(err instanceof ApiError)) return false;
  if (err.status !== 401) return false;
  return err.code === undefined || err.code === 'UNAUTHORIZED';
}
