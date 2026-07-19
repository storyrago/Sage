import { useEffect, useState } from 'react';

interface AvatarProps {
  photoUrl?: string;
  gradient: string;               // 예: "from-blue-600 to-violet-600 text-white"
  name: string;
  className?: string;             // 크기/모양, 예: "w-9 h-9 rounded-xl"
}

export default function Avatar({ photoUrl, gradient, name, className = 'w-9 h-9 rounded-lg' }: AvatarProps) {
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    setFailed(false);
  }, [photoUrl]);

  if (photoUrl && !failed) {
    return (
      <img
        src={photoUrl}
        alt={name}
        className={`${className} object-cover`}
        onError={() => setFailed(true)}
      />
    );
  }
  return (
    <div className={`${className} bg-gradient-to-tr ${gradient} flex items-center justify-center font-bold select-none`}>
      {(name.slice(0, 1) || '?').toUpperCase()}
    </div>
  );
}
