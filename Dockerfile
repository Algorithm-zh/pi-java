# syntax=docker/dockerfile:1

# ── 构建阶段：只编译运行所需的模块，跳过测试与检查点 ──────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# 先复制 POM 再拉依赖，源码变更时不会让依赖层失效。
COPY .mvn .mvn
COPY pom.xml .
COPY config config
COPY pi-java-domain/pom.xml pi-java-domain/
COPY pi-java-plugin-api/pom.xml pi-java-plugin-api/
COPY pi-java-application/pom.xml pi-java-application/
COPY pi-java-infrastructure/pom.xml pi-java-infrastructure/
COPY pi-java-trigger/pom.xml pi-java-trigger/
COPY pi-java-app/pom.xml pi-java-app/
COPY examples/pi-plugin-example/pom.xml examples/pi-plugin-example/
COPY examples/pi-plugin-timer/pom.xml examples/pi-plugin-timer/
RUN mvn -B -ntp -Dmaven.test.skip=true -DexcludeGroupIds=com.piagent \
        -pl pi-java-app -am dependency:go-offline

COPY pi-java-domain/src pi-java-domain/src
COPY pi-java-plugin-api/src pi-java-plugin-api/src
COPY pi-java-application/src pi-java-application/src
COPY pi-java-infrastructure/src pi-java-infrastructure/src
COPY pi-java-trigger/src pi-java-trigger/src
COPY pi-java-app/src pi-java-app/src
RUN mvn -B -ntp -Dmaven.test.skip=true -pl pi-java-app -am package \
    && cp pi-java-app/target/pi-java-app-*.jar /build/app.jar

# ── 运行阶段：非 root、只带 JRE、数据与工作区分开 ────────────────────────────
FROM eclipse-temurin:21-jre AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system pi \
    && useradd --system --gid pi --uid 10001 --create-home pi \
    && mkdir -p /app/data /workspace \
    && chown -R pi:pi /app /workspace

COPY --from=build --chown=pi:pi /build/app.jar /app/app.jar

WORKDIR /workspace
USER pi

# 容器内必须监听 0.0.0.0 才能被端口映射访问；宿主机侧仍只映射回环地址。
ENV PI_SERVER_ADDRESS=0.0.0.0 \
    PI_DATA_DIR=/app/data \
    SPRING_PROFILES_ACTIVE=demo

EXPOSE 8080
VOLUME ["/app/data"]

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/api/v1/system/info > /dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
