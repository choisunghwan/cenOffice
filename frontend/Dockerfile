# CEN HUB 웹 서버 이미지: React 화면을 빌드해 Caddy(HTTPS 자동 발급)에 넣는다.
# 1단계: 화면 빌드. 백엔드 주소는 빌드할 때 정해진다(VITE_API_URL).
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_API_URL
ENV VITE_API_URL=$VITE_API_URL
RUN test -n "$VITE_API_URL" || (echo "VITE_API_URL 이 비어 있습니다 (.env 의 APP_DOMAIN 확인)" && exit 1)
RUN npm run build

# 2단계: Caddy 가 화면 파일을 내보내고 /api, /ws, 회의 주소를 뒤쪽 서버로 넘긴다.
FROM caddy:2.8-alpine
COPY --from=build /src/dist /srv
