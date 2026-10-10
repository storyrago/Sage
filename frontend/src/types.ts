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
  seq: number; // 방 안 순번(1부터 빈틈없이 증가). 화면 순서와 빈 순번 판정의 기준
  clientMessageId?: string; // 보낸 클라이언트가 만든 UUID(재전송 식별)
  replyToId?: string; // 답장 대상 메시지 ID
  imageUrl?: string; // 업로드된 이미지 URL
  edited?: boolean; // 수정됨 표시
  deleted?: boolean; // 소프트 삭제
}

export interface Channel {
  id: string;
  name: string;
  description?: string;
  createdAt: number; // unix epoch ms
  locked: boolean;
  joined: boolean;
  owner: boolean;
  inviteCode?: string; // 방 주인일 때만 내려온다
}

export interface Presence {
  userId: string;
  userName: string;
  isTyping: boolean;
  channelId: string;
  lastSeen: number; // unix epoch ms
}
