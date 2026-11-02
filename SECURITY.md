# Security Policy

## Reporting a vulnerability

请不要用公开 Issue 报告可被利用的安全问题，也不要附带真实密钥、数据库备份或客户数据。

请通过仓库的 **Security → Report a vulnerability** 私下提交报告：

https://github.com/JackWangbl/agentforge-control-plane-java/security/advisories/new

报告中请包含受影响版本、复现条件、潜在影响和建议修复方式。维护者确认问题后会协调修复与披露时间。

## Deployment notes

- 示例账号和密码只用于本机试用，公开部署前必须替换或禁用。
- `.env`、模型密钥和 `SECRET_KEY` 不应进入 Git。
- `AUTH_DEV_USER`、`ALLOW_UNSAFE_LOCAL_SANDBOX` 与内网浏览能力不应在公网环境启用。
- 默认配置只监听 `127.0.0.1`；如需反向代理，请同时配置 TLS、访问控制和可信代理范围。

