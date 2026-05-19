import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api, extractErrorMessage } from "../lib/api";

const initialForm = {
  email: "",
  password: "",
  nickname: ""
};

export default function SignupPage() {
  const navigate = useNavigate();
  const [form, setForm] = useState(initialForm);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);

  const handleChange = (event) => {
    const { name, value } = event.target;
    setForm((current) => ({ ...current, [name]: value }));
  };

  const handleSubmit = async (event) => {
    event.preventDefault();

    setPending(true);
    setError("");

    try {
      await api.post("/members", {
        email: form.email.trim(),
        password: form.password,
        nickname: form.nickname.trim()
      });

      navigate("/login", {
        replace: true,
        state: { message: "회원가입이 완료되었습니다. 로그인해 주세요." }
      });
    } catch (submitError) {
      setError(extractErrorMessage(submitError, "회원가입에 실패했습니다."));
    } finally {
      setPending(false);
    }
  };

  return (
    <main className="auth-page">
      <section className="auth-panel">
        <div className="auth-copy">
          <p className="eyebrow">Create Account</p>
          <h1>새 계정 만들기</h1>
          <p className="muted">
            닉네임을 설정하고 채팅방에 입장해 실시간 대화를 시작할 수 있습니다.
          </p>
        </div>

        <form className="auth-form" onSubmit={handleSubmit}>
          <label className="field">
            <span>이메일</span>
            <input
              name="email"
              type="email"
              placeholder="test@example.com"
              value={form.email}
              onChange={handleChange}
              autoComplete="email"
              required
            />
          </label>

          <label className="field">
            <span>비밀번호</span>
            <input
              name="password"
              type="password"
              placeholder="비밀번호"
              value={form.password}
              onChange={handleChange}
              autoComplete="new-password"
              required
            />
          </label>

          <label className="field">
            <span>닉네임</span>
            <input
              name="nickname"
              type="text"
              placeholder="최대 10자"
              value={form.nickname}
              onChange={handleChange}
              autoComplete="nickname"
              maxLength={10}
              required
            />
          </label>

          {error ? <p className="error-text">{error}</p> : null}

          <button className="primary-button" type="submit" disabled={pending}>
            {pending ? "가입 중..." : "회원가입"}
          </button>

          <div className="auth-switch">
            <span>이미 계정이 있으신가요?</span>
            <Link className="secondary-button auth-link-button" to="/login">
              로그인
            </Link>
          </div>
        </form>
      </section>
    </main>
  );
}
