# 多阶段构建：先用 Maven 打包，再用轻量 JRE 运行

FROM maven:3.9.6-eclipse-temurin-11 AS build
WORKDIR /app

# 可选的 GitHub 认证参数（用于访问 GitHub Packages）
ARG GITHUB_ACTOR
ARG GITHUB_TOKEN

# 如果提供了 GitHub 凭证，生成 settings.xml
RUN if [ -n "$GITHUB_TOKEN" ]; then \
      mkdir -p /root/.m2 && \
      echo '<settings><servers>' > /root/.m2/settings.xml && \
      echo '<server><id>github</id>' >> /root/.m2/settings.xml && \
      echo "<username>${GITHUB_ACTOR}</username>" >> /root/.m2/settings.xml && \
      echo "<password>${GITHUB_TOKEN}</password>" >> /root/.m2/settings.xml && \
      echo '</server></servers></settings>' >> /root/.m2/settings.xml; \
    fi

# 仅复制 POM 以预热依赖缓存（加速 CI 构建）
COPY pom.xml ./
COPY interview-api/pom.xml interview-api/pom.xml
COPY interview-domain/pom.xml interview-domain/pom.xml
COPY interview-infra/pom.xml interview-infra/pom.xml
COPY interview-service/pom.xml interview-service/pom.xml
COPY interview-app/pom.xml interview-app/pom.xml
RUN mvn -B -ntp -DskipTests dependency:go-offline || true

# 复制全部源码并打包，仅构建应用模块（同时会构建其依赖）
COPY . .
RUN mvn -B -ntp -DskipTests -pl interview-app -am package


FROM eclipse-temurin:11-jre AS runtime
WORKDIR /app

# 可按需调整 JVM 资源限制
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# 复制可执行 JAR（Spring Boot 插件产物）
COPY --from=build /app/interview-app/target/*.jar /app/app.jar

# 暴露 HTTP 端口与 Dubbo/Nacos QOS 端口（如需）
EXPOSE 8003 20882 22222

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]