# One image recipe for every service. Build the jars first with ./gradlew bootJar (make up does this).
FROM eclipse-temurin:21-jre
ARG MODULE
ARG JAR
WORKDIR /app
COPY ${MODULE}/build/libs/${JAR} /app/app.jar
# Writable data folder for the vendor simulator's saved state (a named volume in Docker Compose).
RUN mkdir -p /data && chown 1000 /data
USER 1000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
