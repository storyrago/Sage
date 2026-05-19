import { Navigate, Route, Routes } from "react-router-dom";
import ChatRoomPage from "./pages/ChatRoomPage";
import ChatRoomsPage from "./pages/ChatRoomsPage";
import LoginPage from "./pages/LoginPage";
import SignupPage from "./pages/SignupPage";
import { getAccessToken } from "./lib/storage";

function ProtectedRoute({ children }) {
  return getAccessToken() ? children : <Navigate to="/login" replace />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route
        path="/chatrooms"
        element={
          <ProtectedRoute>
            <ChatRoomsPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/chatrooms/:chatroomId"
        element={
          <ProtectedRoute>
            <ChatRoomPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="*"
        element={<Navigate to={getAccessToken() ? "/chatrooms" : "/login"} replace />}
      />
    </Routes>
  );
}
