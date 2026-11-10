(function (app) {
  var tabs = [
    { id: "all", name: "全部" },
    { id: "pending_ship", name: "待发货" },
    { id: "pending_receive", name: "待收货" },
    { id: "done", name: "已完成" }
  ];

  function currentFilter(ctx) {
    var filter = ctx.query.get("status") || "all";
    return tabs.some(function (item) { return item.id === filter; }) ? filter : "all";
  }

  app.page({
    id: "orders",
    path: "/orders",
    tab: "order",
    title: "订单",
    header: function (ctx) {
      var filter = currentFilter(ctx);
      return (
        '<div class="order-tabs">' +
        tabs.map(function (item) {
          return (
            '<button type="button" class="' + (item.id === filter ? "on" : "") +
            '" data-act="filter" data-id="' + item.id + '">' + item.name + "</button>"
          );
        }).join("") +
        "</div>"
      );
    },
    body: function (ctx) {
      var filter = currentFilter(ctx);
      var list = app.orders().filter(function (item) {
        return filter === "all" || item.status === filter;
      });
      if (!list.length) {
        return '<div class="empty"><b>还没有订单</b><span>在首页挑选工具后即可下单</span></div>';
      }
      return (
        list.map(function (item) {
          var actions = "";
          if (item.status === "pending_ship") {
            actions =
              '<button type="button" class="ghost" data-act="cancel" data-id="' + item.id + '">取消订单</button>' +
              '<button type="button" class="solid" data-act="ship" data-id="' + item.id + '">模拟发货</button>';
          } else if (item.status === "pending_receive") {
            actions = '<button type="button" class="solid" data-act="receive" data-id="' + item.id + '">确认收货</button>';
          }
          return (
            '<article class="panel order">' +
            '<div class="order-head"><span>' + app.esc(item.id) + '</span><b class="status-' + app.esc(item.status) + '">' +
            app.esc(app.STATUS[item.status] || item.status) + "</b></div>" +
            '<button type="button" class="goods" data-act="open-product" data-id="' + app.esc(item.productId) + '">' +
            '<img src="' + app.esc(app.asset(item.image)) + '" alt="">' +
            '<span><strong>' + app.esc(item.name) + "</strong><em>" + app.esc(item.model) + " × " + app.esc(item.qty) +
            '</em><i class="price">¥' + app.esc(item.amount) + "</i></span></button>" +
            '<div class="order-foot"><span>' + app.esc(app.formatTime(item.createdAt)) + "</span><div>" + actions + "</div></div></article>"
          );
        }).join("") +
        '<p class="local-note">订单保存在这台手机里。接入后台时，只替换 h5/js/store.js。</p>'
      );
    },
    actions: {
      filter: function (el) {
        var id = el.getAttribute("data-id");
        app.replace(id === "all" ? "/orders" : "/orders?status=" + id);
      },
      "open-product": function (el) { app.go("/product/" + el.getAttribute("data-id")); },
      cancel: function (el) {
        var id = el.getAttribute("data-id");
        app.confirm("取消订单", "确定取消这张订单吗？").then(function (ok) {
          if (!ok) return;
          app.updateStatus(id, "cancelled");
          app.rerender();
        });
      },
      ship: function (el) {
        app.updateStatus(el.getAttribute("data-id"), "pending_receive");
        app.toast("已标记发货");
        app.rerender();
      },
      receive: function (el) {
        var id = el.getAttribute("data-id");
        app.confirm("确认收货", "确认已经收到商品吗？").then(function (ok) {
          if (!ok) return;
          app.updateStatus(id, "done");
          app.rerender();
        });
      }
    }
  });
})(window.KanoH5);
