# NewsPal backend image (SPEC §9.4). Built by Render from this folder.
#   docker build -t newspal-backend backend

# --- build ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Dependencies first, so code-only changes reuse this layer.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -q -B -DskipTests package && cp target/newspal-*.jar /app.jar

# --- run ---
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 newspal
COPY --from=build /app.jar app.jar
USER newspal

# Render's free instance has 512 MB. UTC avoids Postgres rejecting legacy
# zone ids (see pom.xml).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Duser.timezone=UTC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
