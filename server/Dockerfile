# Build context is the repository root (the server needs the shared :core module).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon -PnoApp :server:installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server /app
ENV DB_PATH=/app/data/antcity.db PORT=8080
EXPOSE 8080
CMD ["/app/bin/server"]
