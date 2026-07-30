export interface User {
  id: string;
  email: string | null;   // 소셜 제공자가 이메일을 주지 않을 수 있다
  displayName: string;
  avatar: string; // Tailored color index, gradient, or icon abbreviation
  photoUrl?: string;
  onboarded: boolean;
}

export interface Message {
  id: string;
  channelId: string;
  text: string;
  userId: string;
  userName: string;
  userAvatar: string;
  userPhotoUrl?: string;
  createdAt: number; // unix epoch ms
  replyToId?: string; // 답장 대상 메시지 ID
  imageUrl?: string; // 업로드된 이미지 URL
  edited?: boolean; // 수정됨 표시
  deleted?: boolean; // 소프트 삭제
}

export interface Channel {
  id: string;
  name: string;
  description?: string;
  createdBy: string;
  createdAt: number; // unix epoch ms
}

export interface Presence {
  userId: string;
  userName: string;
  userAvatar: string;
  isTyping: boolean;
  channelId: string;
  lastSeen: number; // unix epoch ms
}

export type WSMessageType =
  | 'init'
  | 'channel:create'
  | 'message:new'
  | 'presence:update'
  | 'user:joined';

export interface WSMessage {
  type: WSMessageType;
  payload: any;
}
