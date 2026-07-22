export interface User {
  id: string;
  email: string;
  displayName: string;
  avatar: string; // Tailored color index, gradient, or icon abbreviation
  photoUrl?: string;
}

export interface Message {
  id: string;
  channelId: string;
  text: string;
  userId: string;
  userName: string;
  userAvatar: string;
  createdAt: number; // unix epoch ms
  replyToId?: string; // 답장 대상 메시지 ID
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
