(function (app) {
  app.page({
    id: "category",
    path: "/category",
    tab: "category",
    title: "分类",
    header: function () {
      return (
        '<div class="sub-header plain">' +
        '<button type="button" class="search wide" data-act="search">' +
        '<img src="' + app.esc(app.asset("/assets/icons/search.png")) + '" alt="">' +
        "<span>" + app.esc(app.catalog.store.searchPlaceholder) + "</span></button></div>"
      );
    },
    body: function (ctx) {
      var current = ctx.query.get("id") || "all";
      var known = app.catalog.categories.some(function (item) { return item.id === current; });
      if (!known) current = "all";
      var chips = app.catalog.categories.map(function (item) {
        return (
          '<button type="button" class="chip' + (item.id === current ? " on" : "") +
          '" data-act="chip" data-id="' + app.esc(item.id) + '">' + app.esc(item.name) + "</button>"
        );
      }).join("");
      var products = app.catalog.filterProducts({ category: current });
      var list = products.length
        ? app.grid(products)
        : '<div class="empty"><b>这个分类还没有商品</b></div>';
      return '<div class="chips">' + chips + "</div>" + list;
    },
    actions: {
      search: function () { app.go("/search"); },
      chip: function (el) { app.replace("/category?id=" + el.getAttribute("data-id")); },
      "open-product": function (el) { app.go("/product/" + el.getAttribute("data-id")); }
    }
  });
})(window.KanoH5);
