# 变更说明

## v0.1.0

第一次对外发布。这是一个给 AgentScope Java 用的自托管控制台：在一个 Spring Boot 进程里管理 Agent、模型、知识库、MCP、评测和会话轨迹。

许可证是 Apache-2.0。

### 试用

需要 JDK 21、Maven 3.9 和 Docker。下面的命令会在空的 MySQL 里建表，不要指到已经有数据的库。

```bash
docker compose up -d
cp .env.example .env
SPRING_PROFILES_ACTIVE=demo mvn spring-boot:run
```

看到 `Started AgentForgeApplication` 后打开 http://127.0.0.1:8080 。

<img width="960" alt="AgentForge 控制台" src="https://github.com/user-attachments/assets/b1fb8215-08fe-4552-bef0-e7b28f2685cc" />

知识库检索还要另起 Milvus。对话要先在「模型配置」里填一个 OpenAI 兼容模型。接到已有库的步骤见 [docs/getting-started.md](docs/getting-started.md)。
