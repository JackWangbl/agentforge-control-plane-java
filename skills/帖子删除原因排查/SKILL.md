---
name: 帖子删除原因排查
version: 1.0.0
description: 在没有浏览器的远程服务器上用 HTTP 登录 58 同城并查询帖子删除原因
---

---
name: 帖子删除原因排查
version: 1.0.0
description: 在没有浏览器的远程服务器上用 HTTP 登录 58 同城并查询帖子删除原因
---

---
name: 58-post-delete-reason
description: >-
  在没有浏览器的远程服务器上，用 HTTP 登录 58 同城并查询帖子删除原因。
  Use when the user asks to 查帖子删除原因、58 同城删帖、info.vip.58.com 帖子列表、
  delReason、系统删除，或给出 infoId / searchText 要看帖子为什么被删。
---

# 58 同城帖子删除原因

执行环境是远程服务器，没有 Chrome / Chromium。不要调用 `browser_*`、`opencli`，也不要在服务器上安装浏览器。

用 `sandbox_run_python`（或同一沙箱里的 `curl`）发 HTTP。凭证只从当次用户消息读取。不要把用户名、密码、Cookie 写入 skill、仓库、日志或最终回复。

## 输入

- 用户名、密码。用户没给就先问，不要沿用历史对话里的密码。
- 或者用户直接给已登录的 `Cookie`。有 Cookie 就跳过登录，只做第 3 步。
- 帖子 ID，对应 `searchText`。
- 列表默认参数（用户改了再用用户的）：`id=1008`，`type=1003`，`cateId=0`，`cpId` 空，`orderId=0`，`pageIndex=1`，`pageSize=10`。

列表地址：

```text
https://info.vip.58.com/info/v1/list?id=1008&type=1003&cateId=0&cpId=&orderId=0&pageIndex=1&pageSize=10&searchText={INFO_ID}
```

这个地址的正文是 JSON，不是网页。登录成功后用同一套 Cookie 去 GET 即可。

## 1. 准备会话

整段登录和查询共用一个 Cookie 罐。请求都带：

- `User-Agent: Mozilla/5.0`
- 登录相关请求的 `Referer: https://passport.58.com/login/?path=https%3A%2F%2Fbj.58.com%2F&source=58-default-pc`

先 GET 这个登录页（`Referer: https://bj.58.com/`），让护照站种下 Cookie。不带 Referer 时，后面的 init 会返回 `code: 1026`、`请求非法`。

## 2. 账号密码登录

按这个顺序，全部走 HTTP：

1. `GET https://passport.58.com/58/login/init?source=58-default-pc&path=https%3A%2F%2Fbj.58.com%2F`  
   成功时 `code` 为 0，`data.token` 是下一步要用的 token。
2. `GET https://passport.58.com/rsa`  
   读取 `data.rsaExponent` 和 `data.rsaModulus`。
3. 加密密码。明文是固定前缀加编码后的密码，不是当前时间：

   ```text
   1411093327735 + urlencode(密码)
   ```

   用上面的指数和模数做 RSA PKCS#1 v1.5，输出小写十六进制。沙箱里没有加密库时，在同一次沙箱里 `pip install pycryptodome` 后再加密。pip 失败就停，说明沙箱出不了网，不要改去装浏览器。
4. `POST https://passport.58.com/58/login/pc/dologin`  
   `Content-Type: application/x-www-form-urlencoded`，并加请求头 `xxzlbbid: xxzlbbid123`。表单字段：

   - `username`：用户名
   - `password`：上一步的十六进制密文
   - `token`：init 返回的 token
   - `source`：`58-default-pc`
   - `path`：`https://bj.58.com/` 做一次 URL 编码
   - `isremember`：`false`
   - `autologin`：`false`
   - `isredirect`：`false`
   - `finger2`、`validcode`、`vcodekey`：先空着

`finger2` 是浏览器画布指纹。服务器没有浏览器，留空。若返回验证码、滑块、语音验证或新设备挑战，把 `code` 和 `msg` 原文告诉用户并停止。不要猜验证码，也不要安装 Chromium。

用户可以改在一台有浏览器的电脑登录一次，把请求头里的 `Cookie` 贴过来。拿到 Cookie 后只做第 3 步。

## 3. 查删除原因

用同一 Cookie 罐 GET 列表地址。解析 JSON，不要根据页面编造字段。

`ret` 不是 0、`total` 为 0、或没有 `info.listData[0]` 时，原样说明接口结果，不要补一个删除原因。

| 字段 | 含义 |
|---|---|
| `delReason.isWho` | 删除方，例如「系统删除」 |
| `delReason.reason` | 原因说明；空字符串就是没有文案 |
| `delReason.operation` | 操作记录；空数组就是没有记录 |
| `state` | 帖子状态码，原样输出 |

`reason` 为空时就写空，不要用 `isWho` 或 `state` 编造更具体的原因。

同时带上：`id`、`title`、`location`、`publicDate`、`publicTime`、`url`，以及 `msg`、`total`。

## 输出

```text
帖子 {id}

删除方：{delReason.isWho}
原因说明：{delReason.reason 或「空」}
操作记录：{operation 的原文，空则写「空」}
帖子状态 state：{state}

标题：{title}
地区类目：{location}
发布时间：{publicDate} {publicTime}
链接：{url}

接口：{msg}，共 {total} 条
```
