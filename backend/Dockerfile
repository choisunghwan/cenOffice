# CEN HUB 백엔드(Spring Boot) 운영 이미지
# 1단계: Maven 으로 jar 빌드 (테스트는 배포 전에 별도로 실행)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/*.jar /src/app.jar

# 2단계: 실행용 (JRE 만 포함)
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 cenhub
WORKDIR /app
COPY --from=build /src/app.jar /app/app.jar
USER cenhub
EXPOSE 8080
ENV TZ=Asia/Seoul JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Seoul"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
