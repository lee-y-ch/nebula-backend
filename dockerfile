# syntax=docker/dockerfile:1.6

############################
# 1) Build stage
############################
FROM gradle:8.10-jdk17 AS build
WORKDIR /app

# 빌드 캐시 최적화를 위해 의존성 먼저 복사
COPY gradle/ gradle/
COPY gradlew settings.gradle* build.gradle* ./
RUN chmod +x gradlew || true

# 의존성만 먼저 받기 (없어도 무시)
RUN ./gradlew dependencies -x test --no-daemon || true

# 나머지 소스 복사
COPY . .

# Spring Boot 실행 JAR만 생성 (plain.jar 제외)
RUN if [ -f ./gradlew ]; then \
      ./gradlew clean bootJar -x test --no-daemon; \
    else \
      gradle clean bootJar -x test --no-daemon; \
    fi

# 멀티모듈/단일모듈 모두 대응: 실행 가능한 bootJar 하나를 app.jar로 고정
RUN BOOT_JAR=$(find . -type f -path "*/build/libs/*.jar" ! -name "*-plain.jar" | head -n 1) \
    && echo ">> Using boot jar: $BOOT_JAR" \
    && cp "$BOOT_JAR" /app/app.jar \
    && ls -l /app/app.jar

############################
# 2) Runtime stage
############################
FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app

# (선택) 기본 로캘/타임존 등이 필요하면 여기서 설정
# RUN apk add --no-cache tzdata && ln -snf /usr/share/zoneinfo/Asia/Seoul /etc/localtime && echo Asia/Seoul > /etc/timezone

COPY --from=build /app/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
