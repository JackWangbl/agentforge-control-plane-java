# 手之劤产品

商城首页、分类、搜索、商品详情、下单、订单和我的，主功能都在 H5 里。改页面后把网站文件重新上传即可上线，不用每次提交小程序审核。

## 直接打开网页

在 `miniprogram` 目录启动静态服务：

```bash
python3 -m http.server 8765 --bind 127.0.0.1
```

浏览器打开 `http://127.0.0.1:8765/h5/index.html`。

上线时把整个 `miniprogram` 目录放到静态网站（对象存储、Nginx 都可以），入口是 `/h5/index.html`。商品、价格和客服电话改 `data/catalog.js`。订单目前写在访客自己的浏览器里，以后接服务器时只改 `h5/js/store.js`。

新增一个模块：

1. 在 `h5/js/modules/` 新建脚本，调用 `KanoH5.page({ path, title, body, actions })`。
2. 在 `h5/index.html` 里，`app.js` 之前加上这个脚本。
3. 重新上传网站。

客服电话 `400-108-0888` 和微信号 `kano-hand-tools` 是示例，上线前请改成真实联系方式。截图里看不清价格的两件商品暂定为：12寸无刷链条锯 ¥268，1033无刷扳手 ¥198。

## 微信里打开

小程序默认仍是原生页面，用微信开发者工具打开本目录就能预览。`project.config.json` 里的 AppID 是 `touristappid`。

网页部署到 https，并在小程序后台配置业务域名后，把地址填进 `config.js` 的 `h5Url`。四个底部导航会打开对应网页。之后改商城只更新网站，小程序外壳不用再发版。
