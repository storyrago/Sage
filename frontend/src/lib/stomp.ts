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
  onDisconnect: () => void;
  onError: () => void;
}

export class SpringStompClient {
  private socket: WebSocket | null = null;
  private connected = false;
  private subscriptionId = 0;
  private currentSubscription?: string;
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
  }

  subscribe(chatroomId: string) {
    if (!this.connected) return;
    if (this.currentSubscription) {
      this.write('UNSUBSCRIBE', { id: this.currentSubscription });
    }

    this.currentSubscription = `sub-${++this.subscriptionId}`;
    this.write('SUBSCRIBE', {
      id: this.currentSubscription,
      destination: `/sub/chatrooms/${chatroomId}`,
      ack: 'auto',
    });
  }

  send(chatroomId: string, content: string) {
    if (!this.connected) return false;
    this.write('SEND', {
      destination: `/pub/chatrooms/${chatroomId}/messages`,
      'content-type': 'application/json',
    }, JSON.stringify({ content }));
    return true;
  }

  private handleRawMessage(raw: string) {
    parseFrames(raw).forEach((frame) => {
      if (frame.command === 'CONNECTED') {
        this.connected = true;
        this.options.onConnect();
        return;
      }

      if (frame.command === 'MESSAGE' && frame.body) {
        this.options.onMessage(JSON.parse(frame.body) as BackendMessage);
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
