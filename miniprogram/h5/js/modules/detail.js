(function (app) {
  var qty = 1;
  var productId = "";

  app.page({
    id: "detail",
    path: "/product/:id",
    tab: null,
    title: "商品详情",
    header: function (ctx) {
      var product = app.catalog.getProduct(ctx.params.id);
      return (
        '<div class="sub-header">' +
        '<button type="button" class="back" data-act="back" aria-label="返回">‹</button>' +
        '<div class="sub-title">' + app.esc(product ? product.model : "商品详情") + "</div></div>"
      );
    },
    body: function (ctx) {
      productId = ctx.params.id;
      qty = 1;
      var product = app.catalog.getProduct(productId);
      if (!product) return '<div class="empty"><b>没有找到这件商品</b></div>';
      var tags = product.highlights.map(function (item) {
        return '<span class="tag">' + app.esc(item) + "</span>";
      }).join("");
      var specs = product.specs.map(function (row) {
        return '<div class="spec"><span>' + app.esc(row[0]) + "</span><b>" + app.esc(row[1]) + "</b></div>";
      }).join("");
      return (
        '<img class="hero" src="' + app.esc(app.asset(product.image)) + '" alt="' + app.esc(product.name) + '">' +
        '<section class="panel info"><div class="price-row"><span class="price">¥' + app.esc(product.price) +
        '</span><span class="sold">已售' + app.esc(product.sold) + "</span></div>" +
        '<h1>' + app.esc(product.name) + "</h1><p>" + app.esc(product.subtitle) + "</p>" +
        '<div class="tags">' + tags + "</div></section>" +
        '<section class="panel"><h2>商品参数</h2>' + specs + "</section>" +
        '<section class="panel copy"><h2>商品说明</h2><p>' + app.esc(product.detail) + "</p></section>" +
        '<section class="panel qty-row"><span>数量</span><div class="stepper">' +
        '<button type="button" data-act="qty" data-step="-1">－</button><b id="qty">' + qty + "</b>" +
        '<button type="button" data-act="qty" data-step="1">＋</button></div></section>'
      );
    },
    footer: function (ctx) {
      if (!app.catalog.getProduct(ctx.params.id)) return "";
      return (
        '<div class="buy-bar">' +
        '<button type="button" class="mini" data-act="service"><img src="' +
        app.esc(app.asset("/assets/icons/service-dark.png")) + '" alt=""><span>客服</span></button>' +
        '<button type="button" class="mini" data-act="home"><img src="' +
        app.esc(app.asset("/assets/tab/home.png")) + '" alt=""><span>首页</span></button>' +
        '<button type="button" class="buy" data-act="buy">立即购买</button></div>'
      );
    },
    actions: {
      back: function () { history.back(); },
      service: function () { app.contactService(); },
      home: function () { app.go("/"); },
      qty: function (el) {
        qty = Math.min(99, Math.max(1, qty + Number(el.getAttribute("data-step"))));
        var node = document.getElementById("qty");
        if (node) node.textContent = String(qty);
      },
      buy: function () {
        app.go("/checkout?id=" + encodeURIComponent(productId) + "&qty=" + qty);
      }
    }
  });
})(window.KanoH5);
