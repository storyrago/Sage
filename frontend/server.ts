import express from 'express';
import { createServer } from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import path from 'path';

// Define shapes in-server to keep standalone compilation bulletproof
interface Channel {
  id: string;
  name: string;
  description: string;
  createdBy: string;
  createdAt: number;
}

interface ReactionMap {
  [emoji: string]: string[];
}

interface Message {
  id: string;
  channelId: string;
  text: string;
  userId: string;
  userName: string;
  userAvatar: string;
  createdAt: number;
  replyToId?: string;
  reactions: ReactionMap;
}

interface Presence {
  userId: string;
  userName: string;
  userAvatar: string;
  isTyping: boolean;
  channelId: string;
  lastSeen: number;
}

interface ClientConnection {
  ws: WebSocket;
  userId?: string;
  currentChannelId?: string;
}

const app = express();
const server = createServer(app);
const wss = new WebSocketServer({ noServer: true });

const PORT = 3000;

// In-memory DB
const channels: Channel[] = [
  {
    id: 'general',
    name: '일반 채팅',
    description: '모두가 함께 자유롭게 이야기하는 로비입니다 🎉',
    createdBy: 'system',
    createdAt: Date.now() - 3600000 * 24, // 1 day ago
  },
  {
    id: 'tech',
    name: '테크 & 개발',
    description: '최신 인공지능, 개발 정보 및 기술적인 수다방입니다 💻',
    createdBy: 'system',
    createdAt: Date.now() - 3600000 * 12, // 12 hours ago
  },
  {
    id: 'gaming',
    name: '게임 소통',
    description: '게임 추천 및 함께 플레이할 유저를 찾는 채널입니다 🎮',
    createdBy: 'system',
    createdAt: Date.now() - 3600000 * 6, // 6 hours ago
  }
];

const messages: Message[] = [
  {
    id: 'welcome-1',
    channelId: 'general',
    text: '실시간 채팅 웹 애플리케이션에 오신 것을 환영합니다! 원하는 닉네임과 스킨색상을 설정하여 다른 탭의 유저들과 즉각 대화해 보세요 ⚡',
    userId: 'system',
    userName: '🤖 안내 비서',
    userAvatar: '🎨',
    createdAt: Date.now() - 10 * 60 * 1000,
    reactions: {}
  },
  {
    id: 'welcome-2',
    channelId: 'tech',
    text: '기술 관련 소식이나 코드 관련 가벼운 질문도 환영합니다! 💡',
    userId: 'system',
    userName: '🤖 테크봇',
    userAvatar: '💻',
    createdAt: Date.now() - 9 * 60 * 1000,
    reactions: {}
  }
];

// Active presences map: userId -> Presence
const activePresences: Map<string, Presence> = new Map();

// Track overall client sockets
const clients: Set<ClientConnection> = new Set();

// Helper to broadcast to specific clients or all
function broadcast(type: string, payload: any) {
  const data = JSON.stringify({ type, payload });
  clients.forEach(client => {
    if (client.ws.readyState === WebSocket.OPEN) {
      client.ws.send(data);
    }
  });
}

// Socket Connection handling
wss.on('connection', (ws: WebSocket) => {
  const clientConn: ClientConnection = { ws };
  clients.add(clientConn);

  // Send initial data to the newly connected client
  ws.send(JSON.stringify({
    type: 'init',
    payload: {
      channels,
      messages,
      presences: Array.from(activePresences.values()),
    }
  }));

  ws.on('message', (rawData: string) => {
    try {
      const parsed = JSON.parse(rawData);
      const { type, payload } = parsed;

      switch (type) {
        case 'user:joined': {
          const { userId, displayName, avatar, channelId } = payload;
          clientConn.userId = userId;
          clientConn.currentChannelId = channelId || 'general';

          // Update in-memory user presence
          activePresences.set(userId, {
            userId,
            userName: displayName,
            userAvatar: avatar,
            isTyping: false,
            channelId: clientConn.currentChannelId,
            lastSeen: Date.now()
          });

          broadcast('presence:update', {
            userId,
            userName: displayName,
            userAvatar: avatar,
            isTyping: false,
            channelId: clientConn.currentChannelId,
            lastSeen: Date.now()
          });
          break;
        }

        case 'channel:create': {
          const { id, name, description, createdBy } = payload;
          // Duplicate check
          if (channels.some(c => c.id === id || c.name.toLowerCase() === name.toLowerCase())) {
            break;
          }
          const newChannel: Channel = {
            id,
            name,
            description,
            createdBy,
            createdAt: Date.now()
          };
          channels.push(newChannel);
          broadcast('channel:create', newChannel);
          break;
        }

        case 'message:new': {
          const { id, channelId, text, userId, userName, userAvatar, replyToId } = payload;
          const newMessage: Message = {
            id,
            channelId,
            text,
            userId,
            userName,
            userAvatar,
            createdAt: Date.now(),
            replyToId,
            reactions: {}
          };
          messages.push(newMessage);
          broadcast('message:new', newMessage);
          break;
        }

        case 'message:react': {
          const { messageId, emoji, userId } = payload;
          const msg = messages.find(m => m.id === messageId);
          if (msg) {
            if (!msg.reactions) {
              msg.reactions = {};
            }
            if (!msg.reactions[emoji]) {
              msg.reactions[emoji] = [];
            }

            const index = msg.reactions[emoji].indexOf(userId);
            if (index > -1) {
              // Toggle: remove reaction
              msg.reactions[emoji].splice(index, 1);
              if (msg.reactions[emoji].length === 0) {
                delete msg.reactions[emoji];
              }
            } else {
              // Add reaction
              msg.reactions[emoji].push(userId);
            }

            broadcast('message:react', {
              messageId,
              reactions: msg.reactions
            });
          }
          break;
        }

        case 'message:delete': {
          const { messageId } = payload;
          const index = messages.findIndex(m => m.id === messageId);
          if (index > -1) {
            messages.splice(index, 1);
            // Also delete replies if needed, but simple deletion of main is ok
            broadcast('message:delete', { messageId });
          }
          break;
        }

        case 'presence:update': {
          const { userId, isTyping, channelId } = payload;
          const current = activePresences.get(userId);
          if (current) {
            current.isTyping = isTyping;
            if (channelId) {
              current.channelId = channelId;
              clientConn.currentChannelId = channelId;
            }
            current.lastSeen = Date.now();
            broadcast('presence:update', current);
          }
          break;
        }

        default:
          break;
      }
    } catch (err) {
      console.error('Error handling websocket message:', err);
    }
  });

  ws.on('close', () => {
    if (clientConn.userId) {
      const uId = clientConn.userId;
      activePresences.delete(uId);

      // Tell other clients that user disconnected
      broadcast('presence:update', {
        userId: uId,
        userName: '',
        userAvatar: '',
        isTyping: false,
        channelId: '',
        lastSeen: 0 // Indicates offline
      });
    }
    clients.delete(clientConn);
  });
});

// Upgrade handling for HTTP to WS
server.on('upgrade', (request, socket, head) => {
  wss.handleUpgrade(request, socket, head, (ws) => {
    wss.emit('connection', ws, request);
  });
});

// Healthy Check endpoint
app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', activeUsers: activePresences.size });
});

// Start server containing Vite dev middleware or Prod build asset server
async function start() {
  if (process.env.NODE_ENV !== 'production') {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
    console.log('[Dev Server] Vite middleware integrated.');
  } else {
    const distPath = path.join(process.cwd(), 'dist');
    app.use(express.static(distPath));
    app.get('*', (req, res) => {
      res.sendFile(path.join(distPath, 'index.html'));
    });
    console.log('[Prod Server] Static asset middleware integrated.');
  }

  server.listen(PORT, '0.0.0.0', () => {
    console.log(`[Server] Running on http://localhost:${PORT}`);
  });
}

start();
