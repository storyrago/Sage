<div align="center">

# 💬 Realtime Chat

**🔗 라이브 데모 — [sagertc.duckdns.org](https://sagertc.duckdns.org)**

</div>

---

## Table of Contents

- [서비스 소개](#-서비스-소개)
- [시스템 아키텍처](#️-시스템-아키텍처)
- [ERD](#️-erd)
- [기술 스택](#️-기술-스택)
- [API 문서](#-api-문서)
- [실행 방법](#-실행-방법)
- [트러블슈팅](#-트러블슈팅)

---

## 💬 서비스 소개

실시간으로 대화하고, 이미지를 주고받는 채팅 서비스입니다.
단순히 "채팅이 되는 것"에 그치지 않고, **서버가 여러 대여도 메시지가 전달되는 구조**와 **자동으로 빌드·테스트·배포되는 파이프라인**까지 직접 구축했습니다.

| 주요 기능 | 설명 |
|---|---|
| **회원 / 인증** | 로그인 시 **JWT 발급** (이메일 · Google OAuth). REST는 필터로, WebSocket은 **STOMP CONNECT 시** 토큰 검증 |
| **채팅방** | 생성 · 조회 · 입장 · 나가기 · 참여자 목록. 미참여자의 메시지 전송 차단 |
| **실시간 채팅** | **WebSocket(STOMP)** 송수신. **Redis Pub/Sub** 경유로 **모든 서버의 구독자**에게 브로드캐스트 |
| **이미지** | **S3** 업로드 후 공개 URL 발급 → **프로필 이미지 / 채팅 이미지**로 첨부 |
| **프로필** | 프로필 이미지 변경 (본인만), 내 정보 조회 |

---

## 🏗️ 시스템 아키텍처

추가예정

---

## 🗂️ ERD

<img width="1430" height="392" alt="realtimechatERD" src="https://github.com/user-attachments/assets/25cf8a59-a502-4af0-99a1-8006b25b66ed" />

- `chatroom_members`는 **Member ↔ ChatRoom의 M:N을 풀어낸 조인 테이블**이며, `UNIQUE(member_id, chatroom_id)`로 **중복 참여를 방지**합니다.
- 연관관계 주인은 FK를 가진 `Message` / `ChatRoomMember` 쪽이고, 모든 `@ManyToOne`은 `LAZY`입니다.

---

## 🛠️ 기술 스택

| Category | Technology |
|---|---|
| **Language / Build** | ![Java](https://img.shields.io/badge/Java%2017-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white) ![Gradle](https://img.shields.io/badge/Gradle%209.4-02303A?style=for-the-badge&logo=gradle&logoColor=white) |
| **Backend** | ![Spring Boot](https://img.shields.io/badge/Spring%20Boot%204.0.5-6DB33F?style=for-the-badge&logo=springboot&logoColor=white) ![Spring Security](https://img.shields.io/badge/Spring%20Security-6DB33F?style=for-the-badge&logo=springsecurity&logoColor=white) ![Hibernate](https://img.shields.io/badge/JPA%20%2F%20Hibernate-59666C?style=for-the-badge&logo=hibernate&logoColor=white) |
| **Realtime / Auth** | ![STOMP](https://img.shields.io/badge/WebSocket%20STOMP-010101?style=for-the-badge&logo=socketdotio&logoColor=white) ![JWT](https://img.shields.io/badge/JWT-000000?style=for-the-badge&logo=jsonwebtokens&logoColor=white) |
| **Frontend** | ![React](https://img.shields.io/badge/React%2019-61DAFB?style=for-the-badge&logo=react&logoColor=black) ![Vite](https://img.shields.io/badge/Vite-646CFF?style=for-the-badge&logo=vite&logoColor=white) ![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white) ![Tailwind](https://img.shields.io/badge/Tailwind%20CSS-06B6D4?style=for-the-badge&logo=tailwindcss&logoColor=white) |
| **Database** | ![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=for-the-badge&logo=mysql&logoColor=white) ![Redis](https://img.shields.io/badge/Redis%207-DC382D?style=for-the-badge&logo=redis&logoColor=white) ![H2](https://img.shields.io/badge/H2%20test-004488?style=for-the-badge&logo=databricks&logoColor=white) |
| **Infrastructure** | ![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white) ![Nginx](https://img.shields.io/badge/Nginx-009639?style=for-the-badge&logo=nginx&logoColor=white) ![EC2](https://img.shields.io/badge/AWS%20EC2-FF9900?style=for-the-badge&logo=amazonec2&logoColor=white) ![RDS](https://img.shields.io/badge/AWS%20RDS-527FFF?style=for-the-badge&logo=amazonrds&logoColor=white) ![S3](https://img.shields.io/badge/AWS%20S3-569A31?style=for-the-badge&logo=amazons3&logoColor=white) ![Let's Encrypt](https://img.shields.io/badge/Let's%20Encrypt-003A70?style=for-the-badge&logo=letsencrypt&logoColor=white) |
| **CI / CD** | ![GitHub Actions](https://img.shields.io/badge/GitHub%20Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white) ![GHCR](https://img.shields.io/badge/GHCR-222222?style=for-the-badge&logo=github&logoColor=white) |
| **Docs** | ![Swagger](https://img.shields.io/badge/Swagger-85EA2D?style=for-the-badge&logo=swagger&logoColor=black) |

---

## 📡 API 문서

> Swagger UI (로컬/개발): `http://localhost:8080/swagger-ui/index.html`
> — 운영에선 app(8080)이 **nginx 뒤 내부 전용**이라 외부로 노출하지 않습니다.
> 인증이 필요한 API는 헤더에 **`Authorization: Bearer {accessToken}`**

### 인증 / 회원

| Method | Path | 인증 | 설명 |
|:---:|---|:---:|---|
| `POST` | `/api/auth/login` | — | 로그인 → `{ tokenType, accessToken }` |
| `GET` | `/api/members` | ✅ | 회원 목록 |
| `GET` | `/api/members/me` | ✅ | 내 정보 |
| `GET` | `/api/members/{id}` | ✅ | 회원 단건 조회 |
| `PATCH` | `/api/members/me/profile-image` | ✅ | 프로필 이미지 변경 |
| `DELETE` | `/api/members/me` | ✅ | 회원 탈퇴 (**본인만**) |

### 채팅방

| Method | Path | 인증 | 설명 |
|:---:|---|:---:|---|
| `POST` | `/api/chatrooms` | ✅ | 채팅방 생성 |
| `GET` | `/api/chatrooms` | ✅ | 채팅방 목록 |
| `GET` | `/api/chatrooms/{id}` | ✅ | 채팅방 조회 |
| `POST` | `/api/chatrooms/{chatroomId}/members` | ✅ | 채팅방 입장 |
| `GET` | `/api/chatrooms/{chatroomId}/members` | ✅ | 참여자 목록 |
| `DELETE` | `/api/chatrooms/{chatroomId}/members` | ✅ | 채팅방 나가기 |

### 메시지 / 이미지

| Method | Path | 인증 | 설명 |
|:---:|---|:---:|---|
| `POST` | `/api/chatrooms/{chatroomId}/messages` | ✅ | 메시지 저장 (REST, 브로드캐스트 없음) |
| `GET` | `/api/chatrooms/{chatroomId}/messages` | ✅ | 메시지 목록 |
| `POST` | `/api/images` | ✅ | 이미지 업로드 (`multipart/form-data`, 최대 10MB) → `{ "url": "..." }` |

### WebSocket (STOMP)

| 항목 | 값 |
|---|---|
| **핸드셰이크** | 운영 `wss://sagertc.duckdns.org/ws` (nginx 프록시) · 로컬 `ws://localhost:8080/ws` |
| **인증** | `CONNECT` 프레임 헤더 `Authorization: Bearer {token}` |
| **전송(SEND)** | `/pub/chatrooms/{chatroomId}/messages` → `{ "content": "...", "imageUrl": "..." }` |
| **구독(SUBSCRIBE)** | `/sub/chatrooms/{chatroomId}` |
| **수신 페이로드** | `{ messageId, content, imageUrl, memberId, nickname, chatroomId, createdAt }` |

> 💡 **실시간 전송은 STOMP 경로만** 브로드캐스트됩니다 (REST 메시지 API는 저장 전용).
> 💡 **이미지는 2단계** — WebSocket으로 파일을 보낼 수 없어, `POST /api/images`로 먼저 올려 URL을 받고 그 URL만 메시지에 실어 보냅니다.

---

## 🚀 실행 방법

### 1. 클론

```bash
git clone https://github.com/storyrago/realtimechat-backend.git
cd realtimechat-backend
```

### 2. 환경변수 (`.env`)

루트에 `.env` 생성 — **비밀값은 커밋하지 않습니다.**

```bash
SPRING_DATASOURCE_URL=jdbc:mysql://{DB호스트}:3306/spring_realtimechat_service
SPRING_DATASOURCE_USERNAME={DB유저}
SPRING_DATASOURCE_PASSWORD={DB비밀번호}

AWS_ACCESS_KEY_ID={S3 업로드용 IAM 키}
AWS_SECRET_ACCESS_KEY={S3 업로드용 IAM 시크릿}

JWT_SECRET={32바이트 이상 랜덤 문자열}
```

> ⚠️ `JWT_SECRET`은 **기본값이 없습니다.** 설정하지 않으면 앱이 기동되지 않습니다 (취약한 상태로 조용히 뜨는 것을 막기 위한 의도).
> 생성: `openssl rand -base64 32`

### 3. Docker Compose (운영 토폴로지)

운영은 **GitHub Actions가 이미지를 GHCR에 빌드·push → EC2가 pull**합니다. compose는 **app · redis · web(nginx)** 을 띄우고, nginx가 `80/443`에서 정적 프론트 서빙 + `/api`·`/ws` 프록시(TLS 종단), app은 내부 전용(`expose 8080`)입니다.

```bash
docker compose up -d        # GHCR 이미지 pull → 기동
```
- `image:`의 `IMAGE_TAG`는 필수 값이라 미설정 시 즉시 실패합니다. 배포 스크립트가 매 배포마다 `.env`에 `IMAGE_TAG=<배포된 SHA>`를 기록해 두므로, EC2에서는 위 명령이 그대로 동작합니다.
- 특정 버전으로 띄우려면 태그를 직접 지정합니다: `IMAGE_TAG=<sha> docker compose up -d`
- 외부 의존: **MySQL(RDS)**, **S3**
- 접속: **https://sagertc.duckdns.org**
- ⚠️ `web`(nginx)은 TLS 인증서(`/etc/letsencrypt`)가 필요합니다. 인증서 없는 **로컬에선 아래 4번(`bootRun`) + 프론트 `npm --prefix frontend run dev`** 로 개발하세요.

### 4. 로컬 실행 (Docker 없이)

MySQL(`localhost:3306`, DB `spring_realtimechat_service`) · Redis(`localhost:6379`) 필요

```bash
export JWT_SECRET=$(openssl rand -base64 32)
./gradlew bootRun
```

### 5. 테스트

```bash
./gradlew test
```
**H2 인메모리 DB**와 더미 설정을 사용합니다.

---

## 🧩 트러블슈팅

프로젝트를 진행하며 실제로 겪고 해결한 문제들입니다.

| 문제 | 원인 | 해결 |
|---|---|---|
| **서버가 여러 대면 메시지가 안 감** | `convertAndSend`는 **자기 서버 세션에만** 전달 | **Redis Pub/Sub** 경유 브로드캐스트로 전환 |
| **컨테이너에서 DB 접속 실패** | 컨테이너 안 `localhost`는 **자기 자신** | Compose **서비스 이름**으로 접속, 설정은 env로 주입 |
| **앱이 DB보다 먼저 떠서 죽음** | `depends_on`은 **기동**만 보장, 준비 완료는 아님 | MySQL **healthcheck** + `condition: service_healthy` |
| **배포 후 앱이 계속 죽음 (OOM)** | 1GB 서버에서 MySQL까지 함께 구동 | **DB를 RDS로 분리** (+ swap, `.dockerignore`, `--no-daemon`) |
| **CI에서 테스트 전부 실패** | 러너엔 Redis/MySQL이 없음 | **service containers**로 의존 서비스 기동 |
| **JWT 시크릿이 레포에 노출** | 설정 파일에 하드코딩 | **`${JWT_SECRET}` 외부화** + 시크릿 로테이션 |
| **배포가 20분 만에 타임아웃** | 1GB EC2에서 gradle+vite 빌드가 메모리 부족 (swap으로도 느림) | **빌드를 GitHub Actions로 이전** → GHCR 이미지 push → EC2는 `pull`만 (서버 빌드 제거, 20분→30초) |
| **프론트 배포 시 CORS·mixed-content 우려** | 프론트/백 분리 배포면 origin 상이 + HTTPS↔HTTP 혼용 | **nginx 리버스 프록시로 same-origin 통합** + Let's Encrypt로 HTTPS/WSS |

---

<div align="center">

**개인 학습용 프로젝트** — 새로운 기술을 늘리기보다, **하나의 서비스를 끝까지 완성**하는 것을 목표로 했습니다.

</div>
