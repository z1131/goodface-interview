# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Context

This is the **goodface-interview** service, the AI-powered interview session component of the GoodFace microservices platform. For overall architecture and multi-service guidance, see `/Users/zhangzihao/claude_gf/CLAUDE.md`.

## Technology Stack

- **Language**: Java 11
- **Framework**: Spring Boot 2.7.18
- **RPC**: Apache Dubbo 3.2.11 with Nacos service discovery
- **Database**: MySQL 8.0+ with MyBatis 2.3.1 (NOT JPA)
- **Cache**: Redis (Lettuce client with connection pooling)
- **AI Integration**: Alibaba DashScope SDK 2.21.11 (STT + LLM)
- **Real-time**: WebSocket (custom binary/text protocol)
- **Build**: Maven 3.9.6
- **Container**: Docker multi-stage builds

## Multi-Module Architecture

The service uses a 5-module DDD-inspired structure:

```
goodface-interview/
├── interview-api/        # Dubbo service interfaces + DTOs (published to GitHub Packages)
├── interview-domain/     # Domain models and business logic
├── interview-infra/      # Infrastructure: MyBatis repos, DashScope clients, WebSocket handlers
├── interview-service/    # Dubbo RPC service implementations
└── interview-app/        # Spring Boot application entry point
```

**Dependency Flow**: `app` → `service` → `domain` → `api`
                    `app` → `infra` → `domain`

## Build Commands

### Local Development

```bash
# Build all modules (skip tests for speed)
mvn clean install -DskipTests

# Run locally (requires MySQL, Redis, Nacos running)
cd interview-app
mvn spring-boot:run

# Service starts on http://localhost:8003
```

### Testing

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=ClassName

# Run specific test method
mvn test -Dtest=ClassName#methodName
```

### Docker Build

```bash
# Build using multi-stage Dockerfile
docker build -f interview-app/Dockerfile -t goodface-interview:latest .

# Run container
docker run -p 8003:8003 -p 20882:20882 \
  -e SPRING_DATASOURCE_URL=... \
  -e DASHSCOPE_API_KEY=... \
  goodface-interview:latest
```

### Publishing API Module

When modifying `interview-api`, publish to GitHub Packages for portal service:

```bash
# Increment version in pom.xml
# Build and deploy
mvn -B -s .mvn/settings.xml deploy

# Update portal service pom.xml to use new version
```

## Service Ports

- **8003**: HTTP/REST API + WebSocket endpoint
- **20882**: Dubbo RPC protocol port
- **22222**: Dubbo QOS (Quality of Service) internal command port

## Configuration

### Environment Variables

All sensitive configs use env var substitution with defaults:

```yaml
${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/goodface}
${SPRING_REDIS_HOST:localhost}
${DUBBO_REGISTRY_ADDRESS:nacos://localhost:8848}
${DASHSCOPE_API_KEY:sk-xxxxx}
```

See `interview-app/src/main/resources/application.yml` (110 lines) for full config.

### Local Development Prerequisites

1. **MySQL 8.0+** with database `goodface` (schema auto-created)
2. **Redis** on default port 6379
3. **Nacos** registry on port 8848
4. **GitHub Packages credentials** in `~/.m2/settings.xml`:
   ```xml
   <server>
     <id>github-z1131-goodface-interview</id>
     <username>YOUR_GITHUB_USERNAME</username>
     <password>YOUR_GITHUB_TOKEN</password>
   </server>
   ```

## Key Components

### Dubbo RPC Services

**SessionCreateService** (`interview-service/`):
- `createSession()`: Initialize new interview session
- `endSession()`: Terminate session and persist results

**ModelCatalogService** (`interview-service/`):
- `listModels()`: Return available AI models (qwen-turbo, qwen-plus)

Configuration:
```yaml
dubbo:
  protocol:
    port: 20882
  registry:
    address: nacos://...
  scan:
    base-packages: com.deepknow.goodface.interview.service
```

### WebSocket Protocol

**Endpoint**: `/audio/stream?sessionId={uuid}`
**Handler**: `AudioStreamWebSocketHandler` (`interview-infra/`)

**Message Types**:

*Incoming (Client → Server):*
- **Binary**: PCM audio chunks (16kHz, mono)
- **Text JSON**: `{"type":"ping"}` (heartbeat) or `{"type":"control","action":"end"}`

*Outgoing (Server → Client):*
- `{"type":"pong","timestamp":1234567890}` - heartbeat response
- `{"type":"stt_ready"}` - speech-to-text initialized
- `{"type":"question","content":"..."}` - recognized question
- `{"type":"answer","content":"token"}` - streaming LLM response
- `{"type":"answer","content":"[END]"}` - response complete
- `{"type":"error","code":"...","message":"..."}` - error notification

**Heartbeat**: Client must send ping periodically to prevent idle timeout. Server responds with pong containing timestamp.

### AI Integration

**Speech-to-Text**:
- Model: `gummy-realtime-v1` (Alibaba DashScope)
- Audio format: 16kHz PCM, mono
- Configuration: `stt.provider=aliyun`, `stt.model=gummy-realtime-v1`

**Large Language Model**:
- Default: `qwen-turbo` (basic tier)
- Premium: `qwen-plus` (requires auth)
- Configuration: `llm.provider=aliyun`, `llm.model=qwen-turbo`

**API Key**: Single `DASHSCOPE_API_KEY` used for both STT and LLM

### Database (MyBatis)

**ORM**: MyBatis (NOT JPA/Hibernate)
**Mapper Location**: `interview-infra/src/main/resources/mappers/**/*.xml`
**Scanner**: `@MapperScan("com.deepknow.goodface.interview.repo.mapper")` in Main.java

**Core Entity**: `InterviewSession`
- `id`: String (UUID)
- `userId`: String
- `status`: String (ACTIVE, ENDED)
- `startTime`, `endTime`: LocalDateTime
- `configJson`: String (serialized session config)

## Deployment

### CI/CD Pipeline (`.github/workflows/ci-cd-ha.yml`)

**Trigger**: Push/PR to `main` branch

**Stages**:
1. **CI**: Maven build (`mvn -B -s .mvn/settings.xml package`)
2. **Docker Build & Push**: Build image, tag with commit SHA, push to Alibaba Cloud ACR
3. **Multi-ECS HA Deployment**: Parallel deployment to 2 ECS instances (47.99.32.144, 47.114.90.156)
4. **Health Check**: Verify `/actuator/health` on both instances

**Deployment Directory**: `/opt/goodface-interview/` on each ECS

### Docker Compose (ECS Deployment)

```bash
# On ECS, CI/CD executes:
docker compose --env-file .env pull
docker compose --env-file .env up -d --remove-orphans
```

`.env` file auto-generated from GitHub Secrets containing:
- `IMAGE_TAG`: Git commit SHA
- `SPRING_DATASOURCE_*`: MySQL credentials
- `SPRING_REDIS_*`: Redis connection
- `DUBBO_REGISTRY_*`: Nacos connection + namespace
- `DASHSCOPE_API_KEY`: AI service key

### Kubernetes/Helm

```bash
# Deploy to ACK (Alibaba Cloud Kubernetes)
helm upgrade --install goodface-interview deploy/helm/goodface-interview \
  -n goodface --create-namespace \
  --set image.repository=registry.cn-hangzhou.aliyuncs.com/goodface/goodface-interview \
  --set image.tag=sha-abc1234
```

**Resource Limits**:
- Requests: 100m CPU, 256Mi RAM
- Limits: 500m CPU, 512Mi RAM

## Important Patterns

### Dependency Injection in WebSocket

WebSocket endpoints are not standard Spring beans. Use custom injector pattern:

```java
// In AudioStreamWebSocketHandler
@Autowired
private AudioStreamServiceInjector serviceInjector;

// Injector bridges Spring context to WebSocket instance
```

### Model Catalog Configuration

Available AI models are defined in `application.yml`:

```yaml
models:
  available:
    - id: qwen-turbo
      name: "通义千问-Turbo"
      tier: basic
      provider: aliyun
      llmModel: qwen-turbo
      requiresAuth: false
    - id: qwen-plus
      name: "通义千问-Plus"
      tier: premium
      provider: aliyun
      llmModel: qwen-plus
      requiresAuth: true
```

Consumed by `ModelCatalogServiceImpl` via `@ConfigurationProperties`.

### Health & Monitoring

**Endpoints** (enabled via Spring Boot Actuator):
- `/actuator/health` - service health status
- `/actuator/info` - build/version info
- `/actuator/prometheus` - metrics for Prometheus scraping
- `/actuator/metrics` - detailed metrics

**QOS Console** (internal debugging):
```bash
# Inside container
telnet localhost 22222
```

## Common Workflows

### Adding a New Dubbo Service

1. Define interface in `interview-api/src/main/java/.../api/`:
   ```java
   public interface MyService {
       ResponseDTO myMethod(RequestDTO req);
   }
   ```

2. Implement in `interview-service/src/main/java/.../service/`:
   ```java
   @DubboService(version = "1.0.0", group = "default")
   public class MyServiceImpl implements MyService {
       @Override
       public ResponseDTO myMethod(RequestDTO req) { ... }
   }
   ```

3. Increment `interview-api` version in `pom.xml`, publish to GitHub Packages

4. Update portal service `pom.xml` to consume new API version

5. Portal injects via: `@DubboReference private MyService myService;`

### Modifying WebSocket Protocol

1. Update message schemas in `AudioStreamWebSocketHandler`
2. Modify `onBinaryMessage()` for binary audio processing
3. Modify `onTextMessage()` for control messages
4. Update frontend client to match new protocol
5. Test heartbeat flow: client sends `{"type":"ping"}`, server responds `{"type":"pong","timestamp":...}`

### Changing AI Models

1. Update `application.yml` under `models.available`
2. For new providers, add dependency in `interview-infra/pom.xml`
3. Implement client in `interview-infra/src/main/java/.../client/`
4. Update `ModelCatalogServiceImpl` to expose new models

### Debugging Dubbo Connectivity

```bash
# Check service registration in Nacos
curl "http://nacos-host:8848/nacos/v1/ns/service/list?serviceName=goodface-interview"

# Check Dubbo QOS (inside container)
docker exec goodface-interview telnet localhost 22222
> ls  # list services
> ps  # show service metadata
```

### Rollback Deployment

```bash
# On ECS
ssh root@47.99.32.144
cd /opt/goodface-interview

# Edit .env to use previous IMAGE_TAG
vim .env  # Change IMAGE_TAG=sha-old123

# Restart with old image
docker compose --env-file .env up -d
```

## Security Notes

- **Never commit credentials**: DB passwords, API keys, Redis passwords
- **GitHub Secrets**: All sensitive values stored in repo Settings → Secrets
- **Maven Settings**: `.mvn/settings.xml` generated in CI (not tracked in git)
- **Environment Overrides**: All configs support `${VAR:default}` format

## Module Dependency Rules

- `interview-api` has ZERO dependencies (pure interfaces/DTOs)
- `interview-domain` depends ONLY on `interview-api`
- `interview-infra` depends on `interview-domain` (can add Spring, MyBatis, external SDKs)
- `interview-service` depends on `interview-api`, `interview-domain`, `interview-infra`
- `interview-app` is the assembly module (depends on all others)

**Rule**: Domain logic must NOT depend on infrastructure. Infra implements domain interfaces.
