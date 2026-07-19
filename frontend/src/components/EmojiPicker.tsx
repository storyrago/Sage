import { useState } from 'react';

interface EmojiPickerProps {
  onSelectEmoji: (emoji: string) => void;
  onClose?: () => void;
}

export const POPULAR_EMOJIS = ['❤️', '👍', '🔥', '😂', '😮', '😢'];

export default function EmojiPicker({ onSelectEmoji, onClose }: EmojiPickerProps) {
  return (
    <div className="flex items-center gap-1 bg-surface border border-border rounded-2xl p-1 shadow-xl backdrop-blur-md">
      {POPULAR_EMOJIS.map((emoji) => (
        <button
          key={emoji}
          type="button"
          onClick={() => {
            onSelectEmoji(emoji);
            if (onClose) onClose();
          }}
          className="w-8 h-8 flex items-center justify-center text-md hover:bg-surface-2 rounded-xl transition-all cursor-pointer transform hover:scale-110 active:scale-95 duration-100"
          title={`${emoji} 반응하기`}
        >
          {emoji}
        </button>
      ))}
    </div>
  );
}
