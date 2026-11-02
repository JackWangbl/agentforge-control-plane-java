# 参与贡献

问题和 Pull Request 用中文或英文都可以。

## 本地跑起来

空库试用见 [docs/getting-started.md](docs/getting-started.md)。默认 profile 不会重建核心表。`SPRING_PROFILES_ACTIVE=demo` 只指向空的 MySQL。

不要提交 `.env`、数据库密码、模型密钥。密钥写在控制台「模型配置」里。

## 改代码

- 新行为和边界放在测试里。`mvn test` 应能通过。
- 启动补表放在 `SchemaMigrator`，并且先判断表或列是否已存在。已有行不要被覆盖。
- 默认的 `ddl-auto` 保持 `none`。自动建表只留在 `demo` profile。

## 提交说明

写清改了哪块、用户能看到什么变化。一种改动一个提交，方便回看。
