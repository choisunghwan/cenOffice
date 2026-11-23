# Render 배포용 이미지: 화면(React)과 백엔드(Spring Boot)를 하나로 묶어 주소 하나로 제공한다.
# 빌드 위치(context)는 저장소 최상위다 (render.yaml 의 dockerContext: .)

# 1단계: 화면 빌드. VITE_API_URL 을 주지 않으므로 화면은 자신과 같은 주소의 백엔드를 부른다.
FROM node:22-alpine AS web
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2단계: 백엔드 jar 빌드. 화면 파일을 static 으로 넣어 Spring Boot 가 함께 내보낸다.
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=web /src/dist ./src/main/resources/static
RUN mvn -q -B -DskipTests package && cp target/*.jar /src/app.jar

# 3단계: 실행
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 cenhub
WORKDIR /app
COPY --from=api /src/app.jar /app/app.jar
USER cenhub
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75" \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_FORWARD_HEADERS_STRATEGY=framework \
    APP_ENV=PROD
# Render 가 주는 값으로 DB 주소와 접속 주소를 채운다:
#  - DB_HOST/DB_PORT/DB_NAME → DB_URL (jdbc 형식)
#  - RENDER_EXTERNAL_URL(https://xxx.onrender.com) → CORS_ORIGINS, APP_BASE_URL (직접 지정했으면 그 값을 쓴다)
ENTRYPOINT ["sh", "-c", "\
  if [ -z \"$DB_URL\" ] && [ -n \"$DB_HOST\" ]; then export DB_URL=\"jdbc:postgresql://$DB_HOST:${DB_PORT:-5432}/$DB_NAME\"; fi; \
  if [ -z \"$CORS_ORIGINS\" ] && [ -n \"$RENDER_EXTERNAL_URL\" ]; then export CORS_ORIGINS=\"$RENDER_EXTERNAL_URL\"; fi; \
  if [ -z \"$APP_BASE_URL\" ] && [ -n \"$RENDER_EXTERNAL_URL\" ]; then export APP_BASE_URL=\"$RENDER_EXTERNAL_URL\"; fi; \
  exec java $JAVA_OPTS -jar /app/app.jar"]
