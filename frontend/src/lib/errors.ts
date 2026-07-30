// 서버·네트워크 원문이 그대로 보이지 않게 거른다.
// fetch 실패는 TypeError로 오고, 502 같은 응답은 HTML 원문이 message에 담긴다.
export function toUserMessage(err: unknown, fallback: string): string {
  if (err instanceof TypeError) return '네트워크에 연결할 수 없어요. 연결을 확인해 주세요.';
  const raw = err instanceof Error ? err.message.trim() : '';
  if (!raw) return fallback;
  if (raw.length > 80 || /[<{]/.test(raw)) return fallback;   // HTML·JSON 원문
  return raw;
}
