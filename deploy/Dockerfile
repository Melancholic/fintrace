FROM eclipse-temurin:25-jre

# Built jar is copied in rather than built here: `./gradlew bootJar` first.
# Requires the plain jar to be disabled in build.gradle.kts, or this glob matches two files.
ARG JAR_FILE=fintrace-core/build/libs/*.jar

RUN useradd --system --create-home --shell /usr/sbin/nologin fintrace
USER fintrace
WORKDIR /app

COPY --chown=fintrace:fintrace ${JAR_FILE} app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
