(function (app) {
  var keyword = "";

  function resultsHtml() {
    var text = keyword.trim();
    if (!text) {
      var history = app.history();
      if (!history.length) return "";
      return (
        '<div class="history"><div class="history-head"><span>最近搜索</span>' +
        '<button type="button" data-act="clear-history">清空</button></div><div class="words">' +
        history.map(function (word) {
          return '<button type="button" class="word" data-act="history" data-word="' + app.esc(word) + '">' + app.esc(word) + "</button>";
        }).join("") +
        "</div></div>"
      );
    }
    var products = app.catalog.filterProducts({ keyword: text });
    if (!products.length) {
      return '<div class="empty"><b>没有找到相关商品</b><span>试试「扳手」「角磨」或型号 SZR</span></div>';
    }
    return app.grid(products);
  }

  function paintResults() {
    var node = document.getElementById("search-results");
    if (node) node.innerHTML = resultsHtml();
  }

  app.page({
    id: "search",
    path: "/search",
    tab: null,
    title: "搜索",
    header: function () {
      return (
        '<div class="sub-header search-header">' +
        '<button type="button" class="back" data-act="back" aria-label="返回">‹</button>' +
        '<label class="search-field">' +
        '<img src="' + app.esc(app.asset("/assets/icons/search.png")) + '" alt="">' +
        '<input id="search-input" placeholder="' + app.esc(app.catalog.store.searchPlaceholder) + '" enterkeyhint="search">' +
        "</label>" +
        '<button type="button" class="go" data-act="submit-search">搜索</button></div>'
      );
    },
    body: function () {
      return '<div id="search-results">' + resultsHtml() + "</div>";
    },
    actions: {
      back: function () { history.back(); },
      "submit-search": function () {
        app.pushHistory(keyword);
        paintResults();
      },
      "clear-history": function () {
        app.clearHistory();
        paintResults();
      },
      history: function (el) {
        keyword = el.getAttribute("data-word") || "";
        var input = document.getElementById("search-input");
        if (input) input.value = keyword;
        app.pushHistory(keyword);
        paintResults();
      },
      "open-product": function (el) { app.go("/product/" + el.getAttribute("data-id")); }
    },
    fields: {
      "search-input": function (value) {
        keyword = value;
        paintResults();
      }
    },
    mount: function () {
      keyword = "";
      var input = document.getElementById("search-input");
      if (!input) return;
      input.setAttribute("data-field", "search-input");
      input.focus();
      input.addEventListener("keydown", function (event) {
        if (event.key === "Enter") {
          app.pushHistory(keyword);
          paintResults();
        }
      });
    }
  });
})(window.KanoH5);
