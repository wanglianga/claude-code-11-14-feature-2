# 构建阶段：使用 Maven + JDK 25 编译打包
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# 运行阶段：仅 JRE
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/flight-regularity-service.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
