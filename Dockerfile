FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x ./gradlew && ./gradlew clean installDist -x test --stacktrace

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/install/securechat-server /app
ENV PORT=8080
EXPOSE 8080
CMD ["/app/bin/securechat-server"]
