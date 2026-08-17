# ===== 构建阶段 =====
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q
COPY src src
RUN ./mvnw clean package -DskipTests -q

# ===== 运行阶段 =====
FROM eclipse-temurin:21-jre
WORKDIR /app

# 默认数据目录（Render Disk 挂载后会覆盖 /data）
RUN mkdir -p /data/uploads

COPY --from=builder /app/target/chat-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]