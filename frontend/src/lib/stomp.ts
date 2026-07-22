import { BackendMessage } from './api';

interface StompFrame {
  command: string;
  headers: Record<string, string>;
  body: string;
}

interface StompClientOptions {
  token: string;
  onConnect: () => void;
  onMessage: (message: BackendMessage) => void;
  onPresence?: (roomId: string, onlineMemberIds: string[]) => void;
  onTyping?: (p: { chatroomId: string; memberId: string; nickname: string; typing: boolean }) => void;
  onDisconnect: () => void;
  onError: () => void;
}

export class SpringStompClient {
  private socket: WebSocket | null = null;
  private connected = false;
  private subscriptionId = 0;
  private currentChatSubscription?: string;
  private currentTypingSubscription?: string;
  private currentRoomPresenceSubscription?: string;
  // subscription id -> 종류: 들어온 MESSAGE 프레임을 알맞은 핸들러로 라우팅
  private subscriptionKinds = new Map<string, 'chat' | 'typing' | 'roompresence'>();
  private options: StompClientOptions;

  constructor(options: StompClientOptions) {
    this.options = options;
  }

  connect() {
    const configuredUrl = import.meta.env.VITE_WS_URL as string | undefined;
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = configuredUrl || `${protocol}//${window.location.host}/ws`;

    this.socket = new WebSocket(url);
    this.socket.onopen = () => {
      this.write('CONNECT', {
        'accept-version': '1.2',
        'heart-beat': '10000,10000',
        Authorization: `Bearer ${this.options.token}`,
      });
    };

    this.socket.onmessage = (event) => this.handleRawMessage(String(event.data));
    this.socket.onclose = () => {
      this.connected = false;
      this.options.onDisconnect();
    };
    this.socket.onerror = () => this.options.onError();
  }

  disconnect() {
    if (this.connected) {
      this.write('DISCONNECT', {});
    }
    this.socket?.close();
    this.socket = null;
    this.connected = false;
    this.currentChatSubscription = undefined;
    this.currentTypingSubscription = undefined;
    this.currentRoomPresenceSubscription = undefined;
    this.subscriptionKinds.clear();
  }

  subscribe(chatroomId: string) {
    if (!this.connected) return;
    this.unsubscribeRoom();   // 이전 방 구독 정리

    // 1) 방 presence (내 입장 방송 놓치지 않게 채팅보다 먼저)
    this.currentRoomPresenceSubscription = `sub-${++this.subscriptionId}`;
    this.subscriptionKinds.set(this.currentRoomPresenceSubscription, 'roompresence');
    this.write('SUBSCRIBE', {
      id: this.currentRoomPresenceSubscription,
      destination: `/sub/chatrooms/${chatroomId}/presence`,
      ack: 'auto',
    });

    // 2) 채팅 (참여 신호)
    this.currentChatSubscription = `sub-${++this.subscriptionId}`;
    this.subscriptionKinds.set(this.currentChatSubscription, 'chat');
    this.write('SUBSCRIBE', {
      id: this.currentChatSubscription,
      destination: `/sub/chatrooms/${chatroomId}`,
      ack: 'auto',
    });

    // 3) 타이핑
    this.currentTypingSubscription = `sub-${++this.subscriptionId}`;
    this.subscriptionKinds.set(this.currentTypingSubscription, 'typing');
    this.write('SUBSCRIBE', {
      id: this.currentTypingSubscription,
      destination: `/sub/chatrooms/${chatroomId}/typing`,
      ack: 'auto',
    });
  }

  /** 방 나가기(랜딩) — 방 구독 3개 해제. 백엔드가 채팅 unsubscribe로 방에서 제거. */
  unsubscribeRoom() {
    for (const sub of [this.currentChatSubscription, this.currentTypingSubscription, this.currentRoomPresenceSubscription]) {
      if (sub) {
        this.write('UNSUBSCRIBE', { id: sub });
        this.subscriptionKinds.delete(sub);
      }
    }
    this.currentChatSubscription = undefined;
    this.currentTypingSubscription = undefined;
    this.currentRoomPresenceSubscription = undefined;
  }

  send(chatroomId: string, content: string, replyToId?: string, imageUrl?: string) {
    if (!this.connected) return false;
    this.write('SEND', {
      destination: `/pub/chatrooms/${chatroomId}/messages`,
      'content-type': 'application/json',
    }, JSON.stringify({ content, replyToId: replyToId ? Number(replyToId) : null, imageUrl: imageUrl ?? null }));
    return true;
  }

  sendTyping(chatroomId: string, isTyping: boolean) {
    if (!this.connected) return;
    this.write('SEND', {
      destination: `/pub/chatrooms/${chatroomId}/typing`,
      'content-type': 'application/json',
    }, JSON.stringify({ typing: isTyping }));
  }

  private handleRawMessage(raw: string) {
    parseFrames(raw).forEach((frame) => {
      if (frame.command === 'CONNECTED') {
        this.connected = true;
        this.options.onConnect();
        return;
      }

      if (frame.command === 'MESSAGE' && frame.body) {
        const kind = this.subscriptionKinds.get(frame.headers.subscription);
        if (kind === 'roompresence') {
          const payload = JSON.parse(frame.body) as { roomId: number | string; onlineMemberIds: Array<number | string> };
          this.options.onPresence?.(String(payload.roomId), payload.onlineMemberIds.map(String));
        } else if (kind === 'typing') {
          const p = JSON.parse(frame.body) as { chatroomId: number | string; memberId: number | string; nickname: string; typing: boolean };
          this.options.onTyping?.({
            chatroomId: String(p.chatroomId),
            memberId: String(p.memberId),
            nickname: p.nickname,
            typing: p.typing,
          });
        } else {
          this.options.onMessage(JSON.parse(frame.body) as BackendMessage);
        }
        return;
      }

      if (frame.command === 'ERROR') {
        this.options.onError();
      }
    });
  }

  private write(command: string, headers: Record<string, string>, body = '') {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return;
    const headerLines = Object.entries(headers).map(([key, value]) => `${key}:${value}`);
    this.socket.send(`${command}\n${headerLines.join('\n')}\n\n${body}\0`);
  }
}

function parseFrames(raw: string): StompFrame[] {
  return raw
    .split('\0')
    .map((frame) => frame.trim())
    .filter(Boolean)
    .map((frame) => {
      const [headerBlock, ...bodyParts] = frame.split('\n\n');
      const [command, ...headerLines] = headerBlock.split('\n');
      const headers = Object.fromEntries(
        headerLines
          .map((line) => line.split(':'))
          .filter(([key, value]) => key && value)
          .map(([key, ...value]) => [key, value.join(':')]),
      );

      return {
        command,
        headers,
        body: bodyParts.join('\n\n'),
      };
    });
}
