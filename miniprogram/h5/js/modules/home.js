(function (app) {
  var timer = 0;

  app.page({
    id: "home",
    path: "/",
    tab: "home",
    title: app.catalog.store.navTitle,
    header: function () {
      var store = app.catalog.store;
      return (
        '<div class="home-header">' +
        '<div class="nav"><div class="nav-title">' + app.esc(store.navTitle) + "</div></div>" +
        '<button type="button" class="search" data-act="search">' +
        '<img src="' + app.esc(app.asset("/assets/icons/search.png")) + '" alt="">' +
        "<span>" + app.esc(store.searchPlaceholder) + "</span></button></div>"
      );
    },
    body: function () {
      var banners = app.catalog.banners.map(function (item, index) {
        return (
          '<img class="banner-img' + (index === 0 ? " on" : "") + '" src="' +
          app.esc(app.asset(item.image)) + '" alt="">'
        );
      }).join("");
      var dots = app.catalog.banners.map(function (item, index) {
        return '<i class="dot' + (index === 0 ? " on" : "") + '"></i>';
      }).join("");
      return (
        '<div class="banner-wrap">' + banners + '<div class="dots">' + dots + "</div></div>" +
        app.grid(app.catalog.products) +
        '<div class="end">已经到底了</div>' +
        '<button type="button" class="fab fab-phone" data-act="phone" aria-label="打电话">' +
        '<img src="' + app.esc(app.asset("/assets/icons/phone.png")) + '" alt=""></button>' +
        '<button type="button" class="fab fab-service" data-act="service" aria-label="客服">' +
        '<img src="' + app.esc(app.asset("/assets/icons/service.png")) + '" alt=""></button>'
      );
    },
    actions: {
      search: function () { app.go("/search"); },
      phone: function () { app.callPhone(); },
      service: function () { app.contactService(); },
      "open-product": function (el) { app.go("/product/" + el.getAttribute("data-id")); }
    },
    mount: function () {
      var shell = document.getElementById("app");
      document.querySelectorAll("#screen .fab").forEach(function (node) {
        shell.appendChild(node);
      });
      var imgs = document.querySelectorAll(".banner-img");
      var dots = document.querySelectorAll(".banner-wrap .dot");
      if (!imgs.length) return;
      var index = 0;
      var wrap = document.querySelector(".banner-wrap");
      function show(next) {
        index = (next + imgs.length) % imgs.length;
        for (var i = 0; i < imgs.length; i += 1) {
          imgs[i].classList.toggle("on", i === index);
          dots[i].classList.toggle("on", i === index);
        }
      }
      timer = setInterval(function () { show(index + 1); }, 4000);
      var startX = 0;
      wrap.addEventListener("touchstart", function (event) {
        startX = event.touches[0].clientX;
      }, { passive: true });
      wrap.addEventListener("touchend", function (event) {
        var delta = event.changedTouches[0].clientX - startX;
        if (Math.abs(delta) < 36) return;
        show(index + (delta < 0 ? 1 : -1));
      });
    },
    destroy: function () {
      clearInterval(timer);
      timer = 0;
      document.querySelectorAll("#app > .fab").forEach(function (node) { node.remove(); });
    }
  });
})(window.KanoH5);
