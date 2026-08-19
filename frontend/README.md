# Sage 프론트엔드

Vite + React + TypeScript로 만든 Sage 실시간 채팅 웹 클라이언트다.
백엔드(Spring Boot)는 저장소 루트에 있고, 개발 서버는 `/api`·`/ws`를 백엔드로 프록시한다.

## 로컬 실행

```bash
npm install
npm run dev
```

백엔드 주소가 `http://localhost:8080`이 아니면 `.env.local`에 `VITE_BACKEND_URL`을 지정한다.

## 검증

```bash
npm test        # vitest
npm run lint    # tsc --noEmit
npm run build   # vite build
```

배포는 `Dockerfile`이 `dist`를 빌드해 nginx 이미지로 서빙한다.
