import { avatarForId } from './avatar';
import { Channel, Message, User } from '../types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

// 서버가 내려주는 오류 코드를 그대로 들고 다닌다. 문구가 바뀌어도 분기가 깨지지 않게 하려는 것이다.
export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

// 401은 호출 지점이 많아 각자 확인하면 반드시 빠뜨린다. 한 곳에서 처리기를 받아 둔다.
// 등록은 App이 마운트 시 한 번만 한다.
let unauthorizedHandler: (() => void) | null = null;

export function setUnauthorizedHandler(handler: (() => void) | null) {
  unauthorizedHandler = handler;
}

export interface BackendMember {
  id: number;
  email: string | null;
  nickname: string;
  profileImageUrl?: string | null;
  createdAt?: string;
  onboarded: boolean;
}

export type RoomMemberProfile = Omit<BackendMember, 'onboarded'>;

/** 타인 조회 응답. 이메일이 없다. */
export interface BackendPublicMember {
  id: number;
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
  profileImageUrl?: string | null;
  chatroomId: number;
  createdAt?: string;
  replyToId?: number | null;
  imageUrl?: string | null;
  editedAt?: string | null;
  deleted?: boolean;
}

// 응답 본문에서 code·message를 뽑아 ApiError로 던진다. 401이면 등록된 처리기를 먼저 부른다.
// 코드가 UNAUTHORIZED(또는 없음 — 프록시가 준 코드 없는 401)일 때만 처리기를 부른다.
// INVALID_PASSWORD·SOCIAL_LOGIN_ONLY 같은 다른 401 코드는 세션을 지우면 안 된다.
// multipart 업로드는 request()를 쓸 수 없어(Content-Type을 브라우저가 정해야 한다) 이 헬퍼를 공유한다.
async function throwApiError(response: Response): Promise<never> {
  const text = await response.text();
  let message = text || `요청 실패: ${response.status}`;
  let code: string | undefined;
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed.message === 'string') message = parsed.message;
    if (parsed && typeof parsed.code === 'string') code = parsed.code;
  } catch {
    /* JSON 아님 — 원문 유지 */
  }
  if (response.status === 401 && (code === undefined || code === 'UNAUTHORIZED')) {
    unauthorizedHandler?.();
  }
  throw new ApiError(message, response.status, code);
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
    await throwApiError(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return response.json() as Promise<T>;
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
    // 이미 참여 중인 방에 다시 들어가는 것은 실패가 아니다.
    if (error instanceof ApiError && error.code === 'ALREADY_JOINED_ROOM') return;
    throw error;
  }
}

export interface PagedMessages {
  messages: BackendMessage[];
  hasMore: boolean;
}

export async function getMessages(
  token: string,
  chatroomId: string,
  before?: number,
  limit = 30,
): Promise<PagedMessages> {
  const params = new URLSearchParams();
  if (before != null) params.set('before', String(before));
  params.set('limit', String(limit));
  return request<PagedMessages>(`/api/chatrooms/${chatroomId}/messages?${params.toString()}`, {}, token);
}

export interface UnreadCount {
  chatroomId: number;
  unreadCount: number;
  lastReadMessageId: number | null;
}

export async function getUnreadCounts(token: string): Promise<UnreadCount[]> {
  return request<UnreadCount[]>('/api/chatrooms/unread', {}, token);
}

export async function markRoomRead(token: string, chatroomId: string): Promise<void> {
  return request<void>(`/api/chatrooms/${chatroomId}/read`, { method: 'POST' }, token);
}

export async function sendMessage(token: string, chatroomId: string, content: string, replyToId?: string, imageUrl?: string) {
  return request<BackendMessage>(`/api/chatrooms/${chatroomId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ content, replyToId: replyToId ? Number(replyToId) : null, imageUrl: imageUrl ?? null }),
  }, token);
}

export async function updateMessage(token: string, chatroomId: string, messageId: string, content: string) {
  return request<BackendMessage>(`/api/chatrooms/${chatroomId}/messages/${messageId}`, {
    method: 'PATCH',
    body: JSON.stringify({ content }),
  }, token);
}

export async function deleteMessage(token: string, chatroomId: string, messageId: string) {
  return request<BackendMessage>(`/api/chatrooms/${chatroomId}/messages/${messageId}`, {
    method: 'DELETE',
  }, token);
}

export async function getMemberById(token: string, id: string) {
  return request<BackendPublicMember>(`/api/members/${id}`, {}, token);
}

export interface BackendChatRoomMember {
  id: number;
  memberId: number;
  chatRoomId: number;
  nickname: string;
  profileImageUrl?: string | null;
}

export async function getChatRoomMembers(token: string, chatroomId: string) {
  return request<BackendChatRoomMember[]>(`/api/chatrooms/${chatroomId}/members`, {}, token);
}

// 참가자 목록 응답에 nickname·프로필사진이 포함되어 단일 요청으로 매핑(멤버별 조회 N+1 제거).
export async function getRoomMemberProfiles(token: string, chatroomId: string): Promise<RoomMemberProfile[]> {
  const members = await getChatRoomMembers(token, chatroomId);
  return members.map((m) => ({
    id: m.memberId,
    email: '',
    nickname: m.nickname,
    profileImageUrl: m.profileImageUrl,
  }));
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
    await throwApiError(res);
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

export async function updateNickname(token: string, nickname: string) {
  return request<BackendMember>('/api/members/me', {
    method: 'PATCH',
    body: JSON.stringify({ nickname }),
  }, token);
}

export async function completeOnboarding(token: string) {
  return request<BackendMember>('/api/members/me/onboarding', {
    method: 'POST',
  }, token);
}

export function toUser(member: BackendMember): User {
  return {
    id: String(member.id),
    email: member.email,
    displayName: member.nickname,
    avatar: avatarForId(member.id),
    photoUrl: member.profileImageUrl ?? undefined,
    onboarded: member.onboarded,
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
    userPhotoUrl: message.profileImageUrl ?? undefined,
    createdAt: message.createdAt ? Date.parse(message.createdAt) : Date.now(),
    replyToId: message.replyToId != null ? String(message.replyToId) : undefined,
    imageUrl: message.imageUrl ?? undefined,
    edited: message.editedAt != null,
    deleted: message.deleted ?? false,
  };
}
