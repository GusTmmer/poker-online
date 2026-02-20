FROM gradle:8.5-jdk21 AS build

WORKDIR /app
COPY . .

RUN gradle :server-gcp:installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /app/server-gcp/build/install/server-gcp ./

EXPOSE 8080

CMD ["./bin/server-gcp"]
