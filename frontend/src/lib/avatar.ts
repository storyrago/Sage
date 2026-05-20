export const AVATAR_GRADIENTS = [
  'from-pink-500 via-red-500 to-yellow-500 text-white',
  'from-blue-600 to-violet-600 text-white',
  'from-emerald-400 to-cyan-500 text-slate-900',
  'from-purple-600 via-pink-600 to-blue-600 text-white',
  'from-orange-400 to-rose-500 text-white',
  'from-indigo-400 to-slate-700 text-white',
];

export function avatarForId(id: string | number) {
  const source = String(id);
  const index = [...source].reduce((sum, char) => sum + char.charCodeAt(0), 0) % AVATAR_GRADIENTS.length;
  return AVATAR_GRADIENTS[index];
}
