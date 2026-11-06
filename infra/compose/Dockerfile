# One image recipe for every service. Build the jars first with ./gradlew bootJar (make up does this).
FROM eclipse-temurin:21-jre
ARG MODULE
ARG JAR
WORKDIR /app
COPY ${MODULE}/build/libs/${JAR} /app/app.jar
USER 1000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
