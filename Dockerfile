# Только API. Интерфейс — отдельный проект (kazbeef-frontend) со своим образом:
# его nginx раздаёт статику и проксирует /api и /admin сюда.

# --- сборка ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

# --- запуск ---
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 kazbeef && mkdir -p /data/kb && chown kazbeef /data/kb
COPY --from=build /src/target/kazbeef.jar app.jar
USER kazbeef
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
