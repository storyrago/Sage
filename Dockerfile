FROM eclipse-temurin:17-jre
# 컨테이너 헬스체크(compose healthcheck)가 쓸 curl
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
COPY build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
