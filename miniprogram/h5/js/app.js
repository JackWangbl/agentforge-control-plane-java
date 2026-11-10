(function (app) {
  var screen = document.getElementById("screen");
  var topbar = document.getElementById("topbar");
  var tabbar = document.getElementById("tabbar");
  var footer = document.getElementById("footer");
  var current = null;
  var tabs = [
    { id: "home", href: "/", text: "首页", icon: "/assets/tab/home.png", active: "/assets/tab/home-active.png" },
    { id: "category", href: "/category", text: "分类", icon: "/assets/tab/category.png", active: "/assets/tab/category-active.png" },
    { id: "order", href: "/orders", text: "订单", icon: "/assets/tab/order.png", active: "/assets/tab/order-active.png" },
    { id: "mine", href: "/mine", text: "我的", icon: "/assets/tab/mine.png", active: "/assets/tab/mine-active.png" }
  ];

  function match(pattern, path) {
    var expected = pattern.split("/").filter(Boolean);
    var actual = path.split("/").filter(Boolean);
    if (expected.length !== actual.length) return null;
    var params = {};
    for (var i = 0; i < expected.length; i += 1) {
      if (expected[i].charAt(0) === ":") params[expected[i].slice(1)] = decodeURIComponent(actual[i]);
      else if (expected[i] !== actual[i]) return null;
    }
    return params;
  }

  function parse() {
    var raw = (location.hash || "#/").replace(/^#/, "");
    if (!raw || raw.charAt(0) !== "/") raw = "/" + raw;
    var queryIndex = raw.indexOf("?");
    var path = queryIndex >= 0 ? raw.slice(0, queryIndex) : raw;
    if (path.length > 1 && path.charAt(path.length - 1) === "/") path = path.slice(0, -1);
    return { path: path || "/", query: new URLSearchParams(queryIndex >= 0 ? raw.slice(queryIndex + 1) : "") };
  }

  function find(path) {
    for (var i = 0; i < app.pages.length; i += 1) {
      var params = match(app.pages[i].path, path);
      if (params) return { page: app.pages[i], params: params };
    }
    return null;
  }

  function setHash(path, replace) {
    var next = path.charAt(0) === "#" ? path : "#" + (path.charAt(0) === "/" ? path : "/" + path);
    if (location.hash === next) {
      render();
      return;
    }
    if (replace && history.replaceState) {
      history.replaceState(null, "", next);
      render();
      return;
    }
    location.hash = next;
  }

  function render() {
    if (current && current.page.destroy) current.page.destroy();
    var loc = parse();
    var found = find(loc.path);
    var ctx = {
      params: found ? found.params : {},
      query: loc.query,
      path: loc.path
    };
    var page = found ? found.page : null;
    current = { page: page || {}, ctx: ctx };
    if (!page) {
      topbar.innerHTML = "";
      topbar.hidden = true;
      footer.hidden = true;
      footer.innerHTML = "";
      tabbar.hidden = false;
      screen.innerHTML = '<div class="empty"><b>页面不存在</b></div>';
      paintTabs(null);
      document.title = "手之劤";
      return;
    }
    document.title = page.title || "手之劤";
    var header = page.header ? page.header(ctx) : "";
    topbar.hidden = !header;
    topbar.innerHTML = header || "";
    screen.innerHTML = page.body ? page.body(ctx) : "";
    screen.scrollTop = 0;
    var foot = page.footer ? page.footer(ctx) : "";
    footer.hidden = !foot;
    footer.innerHTML = foot || "";
    var showTabs = !!page.tab && !foot;
    tabbar.hidden = !showTabs;
    paintTabs(page.tab);
    if (page.mount) page.mount(ctx);
  }

  function paintTabs(active) {
    tabbar.innerHTML = tabs.map(function (item) {
      var on = item.id === active;
      return (
        '<button type="button" class="tab' + (on ? " on" : "") + '" data-act="tab" data-href="' + item.href + '">' +
        '<img src="' + app.esc(app.asset(on ? item.active : item.icon)) + '" alt="">' +
        "<span>" + item.text + "</span></button>"
      );
    }).join("");
  }

  function runAction(event) {
    var el = event.target.closest("[data-act]");
    if (!el || !document.getElementById("app").contains(el)) return;
    if (el.getAttribute("data-act") === "tab") {
      app.go(el.getAttribute("data-href"));
      return;
    }
    if (!current || !current.page.actions) return;
    var handler = current.page.actions[el.getAttribute("data-act")];
    if (!handler) return;
    event.preventDefault();
    handler(el, event);
  }

  app.go = function (path) { setHash(path, false); };
  app.replace = function (path) { setHash(path, true); };
  app.rerender = render;

  var shell = document.getElementById("app");
  shell.addEventListener("click", runAction);
  shell.addEventListener("input", function (event) {
    var field = event.target.getAttribute("data-field");
    if (!field || !current || !current.page.fields || !current.page.fields[field]) return;
    current.page.fields[field](event.target.value);
  });
  window.addEventListener("hashchange", render);
  if (!location.hash) location.hash = "#/";
  else render();
})(window.KanoH5);
