# ---- 빌드 스테이지: JDK 21 + Gradle 래퍼로 실행 가능한 boot jar 생성 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# 래퍼/빌드스크립트 먼저 복사해 의존성 레이어 캐시 활용
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
# gradle.properties의 로컬 전용 org.gradle.java.home(Windows 경로)은 컨테이너에서 무효 — 제거
RUN sed -i '/org.gradle.java.home/d' gradle.properties \
 && chmod +x gradlew && ./gradlew dependencies --no-daemon || true
# 소스 복사 후 boot jar 빌드(테스트는 로컬/CI에서 수행 — 배포 빌드는 패키징만)
COPY . .
RUN sed -i '/org.gradle.java.home/d' gradle.properties \
 && chmod +x gradlew && ./gradlew clean bootJar --no-daemon -x test

# ---- 런타임 스테이지: JRE만 담은 가벼운 이미지 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# Railway가 ${PORT}를 주입하면 application.yml의 server.port가 받는다(기본 8081).
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "java -jar app.jar"]
