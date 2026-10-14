FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/target/ev-chargesync-1.0.0.jar /app/app.jar
ENV PORT=10000
EXPOSE 10000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
