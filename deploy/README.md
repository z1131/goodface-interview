# CI/CD 与部署指引（阿里云 ECS -> ACK/K8s）

本项目已集成完整的 CI/CD：
- 使用 GitHub Actions 构建 Maven（Java 11），制作 Docker 镜像并推送到阿里云 ACR。
- 通过 SSH 登录 ECS 主机，拉取最新镜像并用 Docker Compose 方式启动服务。
- 提供 Helm Chart，支持后续迁移到阿里云 ACK/K8s。

## 目录结构
- `interview-app/Dockerfile`：应用容器镜像构建文件。
- `deploy/docker-compose.yml`：ECS 上的 Compose 编排文件。
- `.github/workflows/ci-cd.yml`：CI/CD 工作流配置。
- `deploy/helm/goodface-interview/`：Helm Chart 模板。

## GitHub Secrets（必须配置）
为仓库添加以下 Secrets（Settings -> Secrets and variables -> Actions）：

- `ACR_REGISTRY`：你的 ACR 注册域名，例如 `registry.cn-hangzhou.aliyuncs.com`
- `ACR_NAMESPACE`：你的 ACR 命名空间，例如 `my-namespace`
- `ACR_USERNAME`：登录 ACR 的用户名（可用 `aliyun` 账号的 ACR 登录凭据）
- `ACR_PASSWORD`：登录 ACR 的密码/Token

- `ECS_HOST`：ECS 公网 IP 或域名
- `ECS_PORT`：SSH 端口，默认 `22`
- `ECS_USER`：SSH 用户名（如 `root` 或你的普通用户）
- `ECS_SSH_KEY`：SSH 私钥（PEM 格式，复制到 Secret 内容即可）

- `SPRING_DATASOURCE_URL`：数据库 JDBC URL，例如 `jdbc:mysql://your-mysql:3306/goodface?...`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `SPRING_REDIS_HOST`
- `SPRING_REDIS_PORT`
- `DUBBO_REGISTRY_ADDRESS`：如 `nacos://nacos.your-domain:8848`
- `DASHSCOPE_API_KEY`：阿里云 DashScope 的 API Key

> 注意：不要在仓库中保留默认的敏感值。运行时会用 Secrets 覆盖 `application.yml` 中的默认配置。

## ECS 端准备
在 ECS 上安装 Docker 与 Docker Compose（推荐 Docker Compose v2）：

```bash
# 以 root 为例
curl -fsSL https://get.docker.com | bash
systemctl enable docker && systemctl start docker

# Docker Compose v2 (如果未安装)
DOCKER_COMPOSE_VERSION=$(docker compose version || true)
echo "Compose version: $DOCKER_COMPOSE_VERSION" # 确认可用

# 部署目录
sudo mkdir -p /opt/goodface-interview && cd /opt/goodface-interview
```

工作流会将 `deploy/docker-compose.yml` 拷贝到该目录，并执行：
- 登录 ACR
- 导出必要环境变量（Secrets 注入）
- `docker compose pull && docker compose up -d --remove-orphans`

成功后，服务将以容器运行：
- HTTP：`8003`（对外暴露）
- Dubbo：`20882`（仅容器内部网络可访问，不映射到宿主机）
- QOS：`22222`（仅容器内部网络可访问，不映射到宿主机）

## 运行时配置：由 Secrets 自动生成 .env（可手工覆盖）
工作流会基于 GitHub Secrets 自动生成 `/opt/goodface-interview/.env`；如不使用 Secrets 或需临时调整，可在 ECS 上手工编辑该文件：

1) `.env` 示例（Compose 会自动识别）：

```
# 镜像信息（由工作流导出或手动指定）
ACR_REGISTRY=registry.cn-hangzhou.aliyuncs.com
ACR_NAMESPACE=goodface
IMAGE_TAG=<由工作流传入或你手动指定>

# 应用运行配置
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:mysql://rm-xxx.mysql.rds.aliyuncs.com:3306/goodface?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
SPRING_DATASOURCE_USERNAME=goodface
SPRING_DATASOURCE_PASSWORD=strong-password
SPRING_REDIS_HOST=r-xxx.redis.rds.aliyuncs.com
SPRING_REDIS_PORT=6379
DUBBO_REGISTRY_ADDRESS=nacos://nacos.internal:8848
DASHSCOPE_API_KEY=sk-xxxx
```

2) 工作流以 `--env-file .env` 启动 Compose：

```
docker compose --env-file .env pull
docker compose --env-file .env up -d --remove-orphans
```

3) 注意：工作流会写入 `.env` 并以 `--env-file .env` 启动，`.env` 的值优先生效；如需覆盖，请先编辑该文件再执行部署。

## 触发与回滚
- 推送到 `main` 分支会触发 CI/CD，镜像 tag 使用 `github.sha`。
- 回滚：在 ECS 上指定旧的 `IMAGE_TAG` 后重新执行 `docker compose up -d`。

## Helm（ACK/K8s）部署
1. 将镜像地址写到 `deploy/helm/goodface-interview/values.yaml`：
   ```yaml
   image:
     repository: registry.cn-hangzhou.aliyuncs.com/my-namespace/goodface-interview
     tag: <你的tag>
   ```

2. 根据环境调整 `env` 字段中的配置（数据库、Redis、Nacos、DashScope 等）。

3. 安装到集群：
   ```bash
   helm upgrade --install goodface-interview deploy/helm/goodface-interview \
     -n your-namespace --create-namespace
   ```

4. 暴露服务：当前 Service 为 `ClusterIP`，如需对外访问请结合 Ingress 或改为 `NodePort/LoadBalancer`。

## 常见问题
- Maven 构建卡顿：工作流已开启依赖缓存与 `dependency:go-offline`，如首次较慢属正常。
- 镜像拉取失败：检查 `ACR_*` Secrets 是否正确，以及 ECS 能访问 ACR 注册域名。
- 端口冲突：确认 ECS 上没有其他进程占用 `8003`。Dubbo/QoS 端口为容器内部端口，无需在宿主机开放。
- DashScope 报错：确保未使用仓库中的默认 API Key，使用 Secrets 注入的真实 Key。

## 后续计划（可选）
- 将数据库与注册中心迁移到云托管（如 RDS、ACK 内部 Nacos）。
- 在 CI 上开启测试阶段（去掉 `-DskipTests`），并加入静态检查。
- 引入蓝绿/灰度发布与健康检查，以降低发布风险。