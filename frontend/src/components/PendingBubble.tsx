import { PendingMessage } from '../lib/pending';

interface Props {
  pending: PendingMessage;
  onRetry: () => void;
  onDiscard: () => void;
}

// 보냈지만 아직 확정되지 않은 내 메시지. 확정되면 사라지고 같은 내용이 순번 자리에 나타난다.
export default function PendingBubble({ pending, onRetry, onDiscard }: Props) {
  const failed = pending.status === 'failed';
  return (
    <div className="flex items-start gap-3 max-w-3xl min-w-0 ml-auto flex-row-reverse" data-pending-id={pending.clientMessageId}>
      <div className="w-9 h-9 flex-shrink-0" aria-hidden="true" />
      <div className="space-y-1 max-w-[85%] min-w-0 flex flex-col items-end">
        <div className={`px-4 py-2.5 rounded-2xl text-sm leading-relaxed [overflow-wrap:anywhere] whitespace-pre-wrap bg-accent border border-accent text-accent-fg rounded-tr-none ${failed ? 'opacity-50' : 'opacity-70'}`}>
          {pending.text ? <span>{pending.text}</span> : <span className="italic">이미지</span>}
        </div>
        {failed ? (
          <div className="flex items-center gap-2 text-[11px]">
            <span className="text-rose-500 font-medium">{pending.error ?? '보내지 못했어요.'}</span>
            {pending.retryable && (
              <button type="button" onClick={onRetry} className="font-bold text-accent-text hover:underline cursor-pointer">
                다시 보내기
              </button>
            )}
            <button type="button" onClick={onDiscard} className="text-muted hover:text-rose-500 cursor-pointer">
              지우기
            </button>
          </div>
        ) : (
          <span className="text-[11px] text-faint select-none">보내는 중…</span>
        )}
      </div>
    </div>
  );
}
