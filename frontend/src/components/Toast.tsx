import { useEffect } from 'react';
import { motion, AnimatePresence } from 'motion/react';

interface ToastProps {
  toast: { id: number; text: string } | null;
  onClose: () => void;
}

// 실패를 알리는 단일 알림. 같은 문구가 연달아 발생해도 다시 뜨도록 id로 구분한다.
export default function Toast({ toast, onClose }: ToastProps) {
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(onClose, 4000);
    return () => clearTimeout(timer);
  }, [toast?.id, onClose]);

  return (
    <AnimatePresence>
      {toast && (
        <motion.button
          key={toast.id}
          type="button"
          role="alert"
          onClick={onClose}
          initial={{ y: 20, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          exit={{ y: 20, opacity: 0 }}
          className="fixed bottom-6 left-1/2 -translate-x-1/2 z-[70] max-w-[min(560px,calc(100vw-32px))] rounded-xl bg-rose-600 px-4 py-3 text-left text-[13px] font-semibold text-white shadow-2xl cursor-pointer"
        >
          {toast.text}
        </motion.button>
      )}
    </AnimatePresence>
  );
}
