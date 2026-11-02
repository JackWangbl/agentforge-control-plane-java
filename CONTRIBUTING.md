# Contributing to AgentForge Control Plane

感谢你愿意参与 AgentForge Control Plane。小修复、文档改进、测试和完整功能都欢迎。

## 开始开发

需要 JDK 21、Maven 3.9 和 MySQL 8。第一次本地运行可以让应用创建空库所需的表：

```bash
cp .env.example .env
mvn spring-boot:run -Dspring-boot.run.profiles=bootstrap
```

`bootstrap` 只用于全新的本地数据库，不要对生产库启用。也可以直接运行 `./scripts/docker-up.sh`；脚本会自动生成本地 Docker secrets。

## 提交修改

1. 先开 Issue 描述问题；很小的修复可以直接提交 PR。
2. 从 `main` 建立简短、单一目的的分支。
3. FastAPI 路由保持简洁，业务逻辑放在 `services`；SQLAlchemy 使用 2.0 风格。Java 代码延续现有分层，Controller 只处理 HTTP 协议，业务逻辑放在对应服务中。
4. 所有公共函数添加类型注解；JavaScript 把用户数据写入 HTML 前必须调用 `escapeHtml`。
5. 不要提交 API Key、密码、数据库凭证、真实对话或客户文档。
6. 修改后运行自测：

```bash
mvn test
```

涉及容器启动方式时，再运行：

```bash
./scripts/docker-up.sh --prepare-only
docker compose config
./scripts/docker-up.sh
curl http://127.0.0.1:8080/api/health
```

PR 请说明用户可见的变化、自测结果和相关 Issue。UI 修改请附截图。

## 适合首次贡献的方向

- 补充不同模型供应商的配置示例
- 改进错误提示和空状态
- 为 RAG、评测、A2A 与恢复流程增加测试
- 完善英文文档和部署文档
- 提交可复现的兼容性问题

