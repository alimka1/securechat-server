FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle clean installDist -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/install/securechat-server /app
ENV PORT=8080
EXPOSE 8080
CMD ["/app/bin/securechat-server"]
