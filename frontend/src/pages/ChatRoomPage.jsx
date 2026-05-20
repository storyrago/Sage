import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api, extractErrorMessage } from "../lib/api";
import { createChatClient } from "../lib/chatClient";
import { clearAccessToken, getAccessToken } from "../lib/storage";

function isAlreadyJoinedError(error) {
  return error.response?.status === 409;
}

function formatMessageTime(createdAt) {
  if (!createdAt) {
    return "";
  }

  const date = new Date(createdAt);

  if (Number.isNaN(date.getTime())) {
    return "";
  }

  return date.toLocaleString("ko-KR", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
}

export default function ChatRoomPage() {
  const navigate = useNavigate();
  const { chatroomId } = useParams();
  const token = getAccessToken();
  const messageEndRef = useRef(null);
  const clientRef = useRef(null);
  const [room, setRoom] = useState(null);
  const [messages, setMessages] = useState([]);
  const [content, setContent] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [connectionStatus, setConnectionStatus] = useState("idle");
  const [me, setMe] = useState(null);

  const statusLabel = useMemo(() => {
    if (connectionStatus === "connected") return "Connected";
    if (connectionStatus === "connecting") return "Connecting";
    if (connectionStatus === "error") return "Error";
    return "Disconnected";
  }, [connectionStatus]);

  useEffect(() => {
    messageEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  useEffect(() => {
    let cancelled = false;

    const initializeRoom = async () => {
      setLoading(true);
      setError("");

      try {
        await api.post(`/chatrooms/${chatroomId}/members`);
      } catch (joinError) {
        if (!isAlreadyJoinedError(joinError)) {
          throw joinError;
        }
      }

      const [roomResponse, messageResponse, meResponse] = await Promise.all([
        api.get(`/chatrooms/${chatroomId}`),
        api.get(`/chatrooms/${chatroomId}/messages`),
        api.get("/members/me")
      ]);

      if (cancelled) {
        return;
      }

      setRoom(roomResponse.data);
      setMessages(messageResponse.data);
      setMe(meResponse.data);

      const chatClient = createChatClient({
        token,
        roomId: chatroomId,
        onStatusChange: setConnectionStatus,
        onError: (message) => setError(message),
        onMessage: (message) => {
          setMessages((current) => {
            const exists = current.some((item) => item.messageId === message.messageId);
            return exists ? current : [...current, message];
          });
        }
      });

      clientRef.current = chatClient;
      chatClient.activate();
      setLoading(false);
    };

    initializeRoom().catch((initializeError) => {
      if (initializeError.response?.status === 401) {
        clearAccessToken();
        navigate("/login", { replace: true });
        return;
      }

      setError(extractErrorMessage(initializeError, "채팅방을 불러오지 못했습니다."));
      setLoading(false);
    });

    return () => {
      cancelled = true;
      clientRef.current?.deactivate();
      clientRef.current = null;
    };
  }, [chatroomId, navigate, token]);

  const handleSendMessage = async (event) => {
    event.preventDefault();

    if (!content.trim() || !clientRef.current) {
      return;
    }

    try {
      setSending(true);
      setError("");
      clientRef.current.publishMessage(content.trim());
      setContent("");
    } catch (sendError) {
      setError(extractErrorMessage(sendError, "메시지 전송에 실패했습니다."));
    } finally {
      setSending(false);
    }
  };

  const handleLeave = async () => {
    try {
      await api.delete(`/chatrooms/${chatroomId}/members`);
    } catch (leaveError) {
      setError(extractErrorMessage(leaveError, "채팅방 퇴장에 실패했습니다."));
      return;
    }

    clientRef.current?.deactivate();
    navigate("/chatrooms");
  };

  if (loading) {
    return (
      <main className="app-page">
        <p className="muted">채팅방을 준비하는 중...</p>
      </main>
    );
  }

  return (
    <main className="chat-page">
      <header className="chat-header">
        <div>
          <Link className="back-link" to="/chatrooms">
            채팅방 목록
          </Link>
          <h1>{room?.name || `Room #${chatroomId}`}</h1>
        </div>

        <div className="header-actions">
          <span className={`status-badge status-${connectionStatus}`}>{statusLabel}</span>
          <button className="secondary-button" type="button" onClick={handleLeave}>
            나가기
          </button>
        </div>
      </header>

      {error ? <p className="error-banner">{error}</p> : null}

      <section className="message-panel">
        <ul className="message-list">
          {messages.map((message) => {
            const isMine = me?.id === message.memberId;

            return (
              <li
                key={message.messageId}
                className={`message-item ${isMine ? "message-item-mine" : ""}`}
              >
                <div className="message-meta">
                  <span>{message.nickname || `User #${message.memberId}`}</span>
                  <time dateTime={message.createdAt || undefined}>
                    {formatMessageTime(message.createdAt)}
                  </time>
                </div>
                <p>{message.content}</p>
              </li>
            );
          })}
          <li ref={messageEndRef} />
        </ul>
      </section>

      <form className="message-form" onSubmit={handleSendMessage}>
        <input
          value={content}
          onChange={(event) => setContent(event.target.value)}
          placeholder="메시지를 입력하세요"
          disabled={connectionStatus !== "connected"}
        />
        <button
          className="primary-button"
          type="submit"
          disabled={sending || connectionStatus !== "connected"}
        >
          전송
        </button>
      </form>
    </main>
  );
}
