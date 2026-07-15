# ---- 빌드 스테이지: JDK 21 + Gradle 래퍼로 실행 가능한 boot jar 생성 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# 래퍼/빌드스크립트 먼저 복사해 의존성 레이어 캐시 활용
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true
# 소스 복사 후 boot jar 빌드(테스트는 로컬/CI에서 수행 — 배포 빌드는 패키징만)
COPY . .
RUN chmod +x gradlew && ./gradlew clean bootJar --no-daemon -x test

# ---- 런타임 스테이지: JRE만 담은 가벼운 이미지 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# Railway가 ${PORT}를 주입하면 application.yml의 server.port가 받는다(기본 8081).
EXPOSE 8081
# 힙 상한을 컨테이너 한도의 75%로 고정 — 나머지 25%는 Metaspace·스레드·direct buffer용.
# 없으면 non-heap 증가로 RSS가 Railway 메모리 한도를 넘겨 커널 OOM-killer가 조용히 kill(SIGKILL).
# ExitOnOutOfMemoryError: 진짜 힙 부족 시 조용한 kill 대신 즉시 종료·로깅으로 진단 가능하게.
ENTRYPOINT ["sh", "-c", "java -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -jar app.jar"]
