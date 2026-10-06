FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/target/datasifter-backend-*.jar /app/app.jar
RUN mkdir -p /opt/datasifter/plugins /var/lib/datasifter
VOLUME ["/var/lib/datasifter", "/opt/datasifter/plugins"]
ENTRYPOINT ["java", "-Dloader.path=/opt/datasifter/plugins", "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]
