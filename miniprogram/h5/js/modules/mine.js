(function (app) {
  app.page({
    id: "mine",
    path: "/mine",
    tab: "mine",
    title: "我的",
    body: function () {
      var counts = app.countByStatus();
      var shortcuts = [
        { id: "pending_ship", name: "待发货" },
        { id: "pending_receive", name: "待收货" },
        { id: "done", name: "已完成" },
        { id: "all", name: "全部订单" }
      ];
      var store = app.catalog.store;
      return (
        '<section class="profile"><div class="avatar">劤</div><div><h1>手之劤用户</h1><p>精工制造 · 品质卓越</p></div></section>' +
        '<section class="panel shortcuts">' +
        shortcuts.map(function (item) {
          return (
            '<button type="button" data-act="orders" data-id="' + item.id + '"><b>' +
            (counts[item.id] || 0) + "</b><span>" + item.name + "</span></button>"
          );
        }).join("") +
        "</section>" +
        '<section class="panel menu">' +
        '<button type="button" data-act="address"><span>收货地址</span><i>></i></button>' +
        '<button type="button" data-act="phone"><span>联系客服</span><em>' + app.esc(store.phoneDisplay) + "</em></button>" +
        '<button type="button" data-act="about"><span>关于手之劤</span><em>' + app.esc(store.hours) + "</em></button>" +
        "</section>"
      );
    },
    actions: {
      orders: function (el) {
        var id = el.getAttribute("data-id");
        app.go(id === "all" ? "/orders" : "/orders?status=" + id);
      },
      address: function () { app.go("/address"); },
      phone: function () { app.callPhone(); },
      about: function () {
        var info = app.catalog.store;
        app.alert(
          "关于手之劤",
          info.name + " KANO\n精工制造，品质卓越。\n108V 无刷锂电工具。\n客服 " + info.phoneDisplay + "\n" + info.hours
        );
      }
    }
  });
})(window.KanoH5);
