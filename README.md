<div align="center">

# 🍃 Sage

**🔗 라이브 데모 — [sagertc.duckdns.org](https://sagertc.duckdns.org)**

</div>

---

## Table of Contents

- [Demo](#demo)
- [System Architecture](#system-architecture)
- [ERD](#erd)
- [Tech Stack](#tech-stack)
- [API](#api)
- [Getting Started](#getting-started)

---

## Demo

**🔗 라이브 — [sagertc.duckdns.org](https://sagertc.duckdns.org)**

### 로그인 · 온보딩

이메일 / Google / Kakao 로그인과, 소셜 첫 진입에서 닉네임을 정하는 흐름

<!-- 1. 여기에 GIF/이미지를 넣으세요.
     GitHub 웹에서 이 파일을 편집(연필 아이콘) → 파일을 편집창에 끌어다 놓으면
     ![...](https://github.com/user-attachments/assets/...) 가 자동 삽입됩니다.
     이 주석은 지우고 그 줄만 남기면 됩니다. -->

### 실시간 채팅

창 두 개로 주고받는 메시지, 접속자 표시, 입력 중 표시

<!-- 2. 여기에 GIF/이미지를 넣으세요.
     GitHub 웹에서 이 파일을 편집(연필 아이콘) → 파일을 편집창에 끌어다 놓으면
     ![...](https://github.com/user-attachments/assets/...) 가 자동 삽입됩니다.
     이 주석은 지우고 그 줄만 남기면 됩니다. -->

### 안읽음 · 답장

안읽음 배지와 "여기부터 안 읽음" 구분선, 답장과 나에게 온 답장

<!-- 3. 여기에 GIF/이미지를 넣으세요.
     GitHub 웹에서 이 파일을 편집(연필 아이콘) → 파일을 편집창에 끌어다 놓으면
     ![...](https://github.com/user-attachments/assets/...) 가 자동 삽입됩니다.
     이 주석은 지우고 그 줄만 남기면 됩니다. -->

### 비공개방 · 초대 코드

초대 코드로 입장, 공개↔비공개 전환

<!-- 4. 여기에 GIF/이미지를 넣으세요.
     GitHub 웹에서 이 파일을 편집(연필 아이콘) → 파일을 편집창에 끌어다 놓으면
     ![...](https://github.com/user-attachments/assets/...) 가 자동 삽입됩니다.
     이 주석은 지우고 그 줄만 남기면 됩니다. -->

### 방장 운영

강퇴 · 차단 · 방장 위임 · 초대 코드 재발급

<!-- 5. 여기에 GIF/이미지를 넣으세요.
     GitHub 웹에서 이 파일을 편집(연필 아이콘) → 파일을 편집창에 끌어다 놓으면
     ![...](https://github.com/user-attachments/assets/...) 가 자동 삽입됩니다.
     이 주석은 지우고 그 줄만 남기면 됩니다. -->

### 이미지 전송

이미지 업로드와 프리사인드 URL로 열람

<!-- 6. 여기에 GIF/이미지를 넣으세요.
     GitHub 웹에서 이 파일을 편집(연필 아이콘) → 파일을 편집창에 끌어다 놓으면
     ![...](https://github.com/user-attachments/assets/...) 가 자동 삽입됩니다.
     이 주석은 지우고 그 줄만 남기면 됩니다. -->

---

### 기능 요약

| 주요 기능 | 설명 |
|---|---|
| **회원 / 인증** | **JWT 발급** — 이메일 · **Google** · **Kakao** OAuth. REST는 필터로, WebSocket은 **STOMP CONNECT 시** 토큰 검증. 로그아웃·탈퇴 시 **토큰 회수**(무효화 목록) |
| **온보딩** | 소셜 로그인 후 닉네임을 직접 정하는 첫 진입 흐름 |
| **채팅방 — 인가** | **공개 / 비공개(초대 코드)** 2상태 (+ 레거시 데이터에만 남은 **동결**). 목록·입장·메시지 읽기·쓰기가 모두 **참여 여부**로 갈린다 |
| **방장 운영** | 강퇴 · 차단 · 차단 해제 · 초대 코드 재발급 · 공개↔비공개 전환 · 방 삭제 · **방장 위임**. 위임·나가기·강퇴는 방 행을 **비관적 잠금**으로 잡아 경합을 막는다 |
| **실시간 채팅** | **WebSocket(STOMP)** 송수신. **Redis Pub/Sub** 경유로 **모든 서버의 구독자**에게 브로드캐스트 |
| **실시간 부가** | 방별 **접속자(presence)** · **입력 중** 표시 · **답장** · **안읽음 카운트**와 "여기부터 안 읽음" 구분선 · **나에게 온 답장** 구분 |
| **이미지** | **S3** 업로드. 채팅 이미지는 **프리사인드 URL(1시간)** 로만 열람, 프로필 사진만 공개. 업로드는 실제 디코딩까지 검증 |
| **프로필** | 프로필 이미지·닉네임 변경 (본인만), 내 정보 조회 |

---

## System Architecture

<img width="741" height="493" alt="image" src="https://github.com/user-attachments/assets/f5cc56f2-f2db-4328-bc64-c915292ce310" />


---

## ERD

<img width="2190" height="922" alt="realtimechatERD (1)" src="https://github.com/user-attachments/assets/4aa86e84-d8e9-4eea-b09b-926192eea817" />

---

## Tech Stack

| Category | Technology |
|---|---|
| **Language / Build** | ![Java](https://img.shields.io/badge/Java%2017-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white) ![Gradle](https://img.shields.io/badge/Gradle%209.4-02303A?style=for-the-badge&logo=gradle&logoColor=white) |
| **Backend** | ![Spring Boot](https://img.shields.io/badge/Spring%20Boot%204.0.5-6DB33F?style=for-the-badge&logo=springboot&logoColor=white) ![Spring Security](https://img.shields.io/badge/Spring%20Security-6DB33F?style=for-the-badge&logo=springsecurity&logoColor=white) ![Hibernate](https://img.shields.io/badge/JPA%20%2F%20Hibernate-59666C?style=for-the-badge&logo=hibernate&logoColor=white) |
| **Realtime / Auth** | ![STOMP](https://img.shields.io/badge/WebSocket%20STOMP-010101?style=for-the-badge&logo=socketdotio&logoColor=white) ![JWT](https://img.shields.io/badge/JWT-000000?style=for-the-badge&logo=jsonwebtokens&logoColor=white) |
| **Frontend** | ![React](https://img.shields.io/badge/React%2019-61DAFB?style=for-the-badge&logo=react&logoColor=black) ![Vite](https://img.shields.io/badge/Vite-646CFF?style=for-the-badge&logo=vite&logoColor=white) ![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white) ![Tailwind](https://img.shields.io/badge/Tailwind%20CSS-06B6D4?style=for-the-badge&logo=tailwindcss&logoColor=white) |
| **Migration** | ![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=for-the-badge&logo=flyway&logoColor=white) |
| **Observability** | ![Actuator](https://img.shields.io/badge/Spring%20Actuator-6DB33F?style=for-the-badge&logo=springboot&logoColor=white) ![Micrometer](https://img.shields.io/badge/Micrometer%20OTLP-117DBB?style=for-the-badge&logo=opentelemetry&logoColor=white) ![Grafana](https://img.shields.io/badge/Grafana%20Cloud-F46800?style=for-the-badge&logo=grafana&logoColor=white) ![CloudWatch](https://img.shields.io/badge/CloudWatch%20Logs-FF4F8B?style=for-the-badge&logo=amazoncloudwatch&logoColor=white) |
| **Database** | ![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=for-the-badge&logo=mysql&logoColor=white) ![Redis](https://img.shields.io/badge/Redis%207-DC382D?style=for-the-badge&logo=redis&logoColor=white) ![H2](https://img.shields.io/badge/H2%20test-004488?style=for-the-badge&logo=databricks&logoColor=white) |
| **Infrastructure** | ![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white) ![Nginx](https://img.shields.io/badge/Nginx-009639?style=for-the-badge&logo=nginx&logoColor=white) ![EC2](https://img.shields.io/badge/AWS%20EC2-FF9900?style=for-the-badge&logo=amazonec2&logoColor=white) ![RDS](https://img.shields.io/badge/AWS%20RDS-527FFF?style=for-the-badge&logo=amazonrds&logoColor=white) ![S3](https://img.shields.io/badge/AWS%20S3-569A31?style=for-the-badge&logo=amazons3&logoColor=white) ![Let's Encrypt](https://img.shields.io/badge/Let's%20Encrypt-003A70?style=for-the-badge&logo=letsencrypt&logoColor=white) |
| **CI / CD** | ![GitHub Actions](https://img.shields.io/badge/GitHub%20Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white) ![GHCR](https://img.shields.io/badge/GHCR-222222?style=for-the-badge&logo=github&logoColor=white) |
| **Docs** | ![Swagger](https://img.shields.io/badge/Swagger-85EA2D?style=for-the-badge&logo=swagger&logoColor=black) |

---

## API

<div align="center">
     <img width="428" height="844" alt="image" src="https://github.com/user-attachments/assets/86cf951c-2509-45a3-9dfb-eb37b2ac29af" />
</div>

---

## Getting Started

### 폴더 구조

```
.
├── backend/    Spring Boot — Gradle 래퍼·Dockerfile 포함
├── frontend/   React + Vite — nginx 설정·Dockerfile 포함
├── docs/       설계 문서·운영 런북
└── docker-compose.yml   두 서비스를 함께 띄운다 (루트에 둔다)
```


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

GOOGLE_CLIENT_ID={Google OAuth 클라이언트 ID}
GOOGLE_CLIENT_SECRET={Google OAuth 시크릿}
KAKAO_CLIENT_ID={Kakao REST API 키}
KAKAO_CLIENT_SECRET={Kakao 시크릿}
FRONTEND_URL=https://sagertc.duckdns.org   # OAuth 성공 후 JWT를 넘겨줄 프론트 주소
```

> ⚠️ **새 환경변수는 `.env`와 `docker-compose.yml`의 `environment` 양쪽에 넣어야 합니다.**
> compose의 `environment`는 명시 목록이라, `.env`에만 두면 컨테이너로 전달되지 않습니다.

> ⚠️ `JWT_SECRET`은 **기본값이 없습니다.** 설정하지 않으면 앱이 기동되지 않습니다 (취약한 상태로 조용히 뜨는 것을 막기 위한 의도).
> 생성: `openssl rand -base64 32`

### 3. 로컬 실행 (Docker 없이)

MySQL(`localhost:3306`, DB `spring_realtimechat_service`) · Redis(`localhost:6379`) 필요

```bash
export JWT_SECRET=$(openssl rand -base64 32)
cd backend && ./gradlew bootRun
```

### 5. 테스트

```bash
cd backend && ./gradlew test
```
**H2 인메모리 DB**와 더미 설정을 사용합니다.

---
