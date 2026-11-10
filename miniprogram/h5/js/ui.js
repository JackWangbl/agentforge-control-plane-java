(function (app) {
  var toastTimer = 0;

  function mask() {
    return document.getElementById("mask");
  }

  function closeMask() {
    var node = mask();
    node.hidden = true;
    node.innerHTML = "";
  }

  app.toast = function (text) {
    var node = document.getElementById("toast");
    node.textContent = text;
    node.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      node.hidden = true;
    }, 1600);
  };

  app.alert = function (title, content) {
    return new Promise(function (resolve) {
      var node = mask();
      node.hidden = false;
      node.innerHTML =
        '<div class="dialog" role="dialog">' +
        '<h3>' + app.esc(title) + "</h3>" +
        '<p>' + app.esc(content).replace(/\n/g, "<br>") + "</p>" +
        '<button type="button" class="dialog-ok" data-ui="ok">知道了</button></div>';
      node.querySelector("[data-ui=ok]").onclick = function () {
        closeMask();
        resolve();
      };
    });
  };

  app.confirm = function (title, content) {
    return new Promise(function (resolve) {
      var node = mask();
      node.hidden = false;
      node.innerHTML =
        '<div class="dialog" role="dialog">' +
        '<h3>' + app.esc(title) + "</h3>" +
        '<p>' + app.esc(content) + "</p>" +
        '<div class="dialog-actions">' +
        '<button type="button" data-ui="no">取消</button>' +
        '<button type="button" class="dialog-ok" data-ui="yes">确定</button></div></div>';
      node.querySelector("[data-ui=no]").onclick = function () {
        closeMask();
        resolve(false);
      };
      node.querySelector("[data-ui=yes]").onclick = function () {
        closeMask();
        resolve(true);
      };
    });
  };

  app.sheet = function (items) {
    return new Promise(function (resolve) {
      var node = mask();
      node.hidden = false;
      node.innerHTML =
        '<button type="button" class="sheet-bg" data-ui="close" aria-label="关闭"></button>' +
        '<div class="sheet">' +
        items.map(function (item, index) {
          return '<button type="button" data-ui="item" data-index="' + index + '">' + app.esc(item) + "</button>";
        }).join("") +
        '<button type="button" class="sheet-cancel" data-ui="close">取消</button></div>';
      node.onclick = function (event) {
        var target = event.target.closest("[data-ui]");
        if (!target) return;
        if (target.getAttribute("data-ui") === "close") {
          closeMask();
          node.onclick = null;
          resolve(-1);
          return;
        }
        if (target.getAttribute("data-ui") === "item") {
          var index = Number(target.getAttribute("data-index"));
          closeMask();
          node.onclick = null;
          resolve(index);
        }
      };
    });
  };

  app.copy = function (text) {
    function fallback() {
      var input = document.createElement("textarea");
      input.value = text;
      document.body.appendChild(input);
      input.select();
      try {
        document.execCommand("copy");
        app.toast("已复制");
      } catch (err) {
        app.toast(text);
      }
      input.remove();
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () {
        app.toast("已复制");
      }, fallback);
      return;
    }
    fallback();
  };

  app.callPhone = function () {
    location.href = "tel:" + app.catalog.store.phone;
  };

  app.contactService = function () {
    var store = app.catalog.store;
    app.sheet(["拨打客服 " + store.phoneDisplay, "复制客服微信"]).then(function (index) {
      if (index === 0) app.callPhone();
      if (index === 1) app.copy(store.wechat);
    });
  };
})(window.KanoH5);
