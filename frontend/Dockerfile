# 1단계: node로 dist 빌드 (빌드 전용, 최종 이미지엔 안 남음)
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# 2단계: nginx로 서빙 (실제 배포 이미지 — 가벼움)
FROM nginx:1.27-alpine
# 컨테이너 헬스체크(compose healthcheck)가 쓸 curl
RUN apk add --no-cache curl
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80 443
