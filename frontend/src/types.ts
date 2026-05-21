export interface User {
  id: string;
  email: string;
  displayName: string;
  avatar: string; // Tailored color index, gradient, or icon abbreviation
}

export interface ReactionMap {
  [emoji: string]: string[]; // emoji -> list of userIds who reacted
}

export interface Message {
  id: string;
  channelId: string;
  text: string;
  userId: string;
  userName: string;
  userAvatar: string;
  createdAt: number; // unix epoch ms
  replyToId?: string; // Optional message ID being replied to
  reactions: ReactionMap;
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
  | 'message:react'
  | 'message:delete'
  | 'presence:update'
  | 'user:joined';

export interface WSMessage {
  type: WSMessageType;
  payload: any;
}
