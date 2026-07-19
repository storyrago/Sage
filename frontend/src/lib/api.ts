import { avatarForId } from './avatar';
import { Channel, Message, User } from '../types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

export interface AuthPayload {
  email: string;
  password: string;
}

export interface SignupPayload extends AuthPayload {
  nickname: string;
}

export interface BackendMember {
  id: number;
  email: string;
  nickname: string;
  profileImageUrl?: string | null;
  createdAt?: string;
}

export interface BackendChatRoom {
  id: number;
  name: string;
  createdAt?: string;
}

export interface BackendMessage {
  messageId: number;
  content: string;
  memberId: number;
  nickname: string;
  chatroomId: number;
  createdAt?: string;
}

interface LoginResponse {
  tokenType: string;
  accessToken: string;
}

async function request<T>(path: string, options: RequestInit = {}, token?: string): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set('Content-Type', 'application/json');

  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
  });

  if (!response.ok) {
    const text = await response.text();
    let message = text || `요청 실패: ${response.status}`;
    try {
      const parsed = JSON.parse(text);
      if (parsed && typeof parsed.message === 'string') message = parsed.message;
    } catch {
      /* JSON 아님 — 원문 유지 */
    }
    throw new Error(message);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return response.json() as Promise<T>;
}

export async function signup(payload: SignupPayload) {
  return request<BackendMember>('/api/members', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export async function login(payload: AuthPayload) {
  const response = await request<LoginResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
  return response.accessToken;
}

export async function getMe(token: string) {
  return request<BackendMember>('/api/members/me', {}, token);
}

export async function getChatRooms(token: string) {
  return request<BackendChatRoom[]>('/api/chatrooms', {}, token);
}

export async function createChatRoom(token: string, name: string) {
  return request<BackendChatRoom>('/api/chatrooms', {
    method: 'POST',
    body: JSON.stringify({ name }),
  }, token);
}

export async function joinChatRoom(token: string, chatroomId: string) {
  try {
    await request(`/api/chatrooms/${chatroomId}/members`, { method: 'POST' }, token);
  } catch (error) {
    const message = error instanceof Error ? error.message : '';
    if (!message.includes('ALREADY_JOINED_ROOM') && !message.includes('already') && !message.includes('이미 참여')) {
      throw error;
    }
  }
}

export async function getMessages(token: string, chatroomId: string) {
  return request<BackendMessage[]>(`/api/chatrooms/${chatroomId}/messages`, {}, token);
}

export async function sendMessage(token: string, chatroomId: string, content: string) {
  return request<BackendMessage>(`/api/chatrooms/${chatroomId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  }, token);
}

export async function getMemberById(token: string, id: string) {
  return request<BackendMember>(`/api/members/${id}`, {}, token);
}

export interface BackendChatRoomMember {
  id: number;
  memberId: number;
  chatRoomId: number;
}

export async function getChatRoomMembers(token: string, chatroomId: string) {
  return request<BackendChatRoomMember[]>(`/api/chatrooms/${chatroomId}/members`, {}, token);
}

// DTO엔 이름이 없어 memberId로 각 회원을 조회(N+1). 방이 작아 허용. 향후 백엔드 DTO에 nickname 추가 시 단순화 가능.
export async function getRoomMemberProfiles(token: string, chatroomId: string): Promise<BackendMember[]> {
  const members = await getChatRoomMembers(token, chatroomId);
  return Promise.all(members.map((m) => getMemberById(token, String(m.memberId))));
}

export async function uploadImage(token: string, file: File): Promise<string> {
  const form = new FormData();
  form.append('file', file);
  const res = await fetch(`${API_BASE_URL}/api/images`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` }, // Content-Type은 브라우저가 boundary 포함해 자동 설정
    body: form,
  });
  if (!res.ok) {
    const text = await res.text();
    let message = text || `업로드 실패: ${res.status}`;
    try { const p = JSON.parse(text); if (p?.message) message = p.message; } catch { /* keep */ }
    throw new Error(message);
  }
  const data = (await res.json()) as { url: string };
  return data.url;
}

export async function updateProfileImage(token: string, imageUrl: string) {
  return request<BackendMember>('/api/members/me/profile-image', {
    method: 'PATCH',
    body: JSON.stringify({ imageUrl }),
  }, token);
}

export function toUser(member: BackendMember): User {
  return {
    id: String(member.id),
    email: member.email,
    displayName: member.nickname,
    avatar: avatarForId(member.id),
    photoUrl: member.profileImageUrl ?? undefined,
  };
}

export function toChannel(room: BackendChatRoom): Channel {
  return {
    id: String(room.id),
    name: room.name,
    description: `${room.name} 대화방`,
    createdBy: 'backend',
    createdAt: room.createdAt ? Date.parse(room.createdAt) : Date.now(),
  };
}

export function toMessage(message: BackendMessage): Message {
  return {
    id: String(message.messageId),
    channelId: String(message.chatroomId),
    text: message.content,
    userId: String(message.memberId),
    userName: message.nickname,
    userAvatar: avatarForId(message.memberId),
    createdAt: message.createdAt ? Date.parse(message.createdAt) : Date.now(),
    reactions: {},
  };
}
