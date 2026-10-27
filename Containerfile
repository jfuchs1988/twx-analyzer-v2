# twxav2 API runtime image (package built by scripts/build.sh)
FROM docker.io/library/eclipse-temurin:25-jre
WORKDIR /app
COPY LICENSE NOTICE ./
COPY docs/THIRD-PARTY-NOTICES.md ./docs/
COPY target/quarkus-app/lib/ ./lib/
COPY target/quarkus-app/*.jar ./
COPY target/quarkus-app/app/ ./app/
COPY target/quarkus-app/quarkus/ ./quarkus/
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
