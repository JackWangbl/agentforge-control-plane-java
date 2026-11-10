(function (app) {
  var state = null;
  var submitting = false;

  function load(ctx) {
    var product = app.catalog.getProduct(ctx.query.get("id"));
    var saved = app.address();
    var qty = Math.min(99, Math.max(1, Number(ctx.query.get("qty")) || 1));
    state = {
      product: product,
      qty: qty,
      contact: saved.contact,
      phone: saved.phone,
      address: saved.address,
      remark: ""
    };
    submitting = false;
  }

  app.page({
    id: "checkout",
    path: "/checkout",
    tab: null,
    title: "确认订单",
    header: function () {
      return (
        '<div class="sub-header"><button type="button" class="back" data-act="back" aria-label="返回">‹</button>' +
        '<div class="sub-title">确认订单</div></div>'
      );
    },
    body: function (ctx) {
      load(ctx);
      if (!state.product) return '<div class="empty"><b>商品已失效</b></div>';
      var product = state.product;
      return (
        '<section class="panel goods"><img src="' + app.esc(app.asset(product.image)) + '" alt="">' +
        '<div><h1>' + app.esc(product.name) + "</h1><p>" + app.esc(product.model) +
        '</p><div class="price">¥' + app.esc(product.price) + "</div></div></section>" +
        '<section class="panel qty-row"><span>数量</span><div class="stepper">' +
        '<button type="button" data-act="qty" data-step="-1">－</button><b id="qty">' + state.qty + "</b>" +
        '<button type="button" data-act="qty" data-step="1">＋</button></div></section>' +
        '<section class="panel form">' +
        field("收货人", "contact", "text", "姓名", state.contact) +
        field("手机号", "phone", "tel", "11位手机号", state.phone) +
        '<label class="area"><span>地址</span><textarea data-field="address" placeholder="省市区和详细地址">' +
        app.esc(state.address) + "</textarea></label>" +
        field("备注", "remark", "text", "选填", state.remark) +
        "</section>"
      );
    },
    footer: function () {
      if (!state || !state.product) return "";
      return (
        '<div class="buy-bar checkout-bar"><div class="sum">合计 <b>¥' +
        (state.product.price * state.qty) + '</b></div>' +
        '<button type="button" class="buy" data-act="submit">提交订单</button></div>'
      );
    },
    fields: {
      contact: function (value) { state.contact = value; },
      phone: function (value) { state.phone = value; },
      address: function (value) { state.address = value; },
      remark: function (value) { state.remark = value; }
    },
    actions: {
      back: function () { history.back(); },
      qty: function (el) {
        state.qty = Math.min(99, Math.max(1, state.qty + Number(el.getAttribute("data-step"))));
        document.getElementById("qty").textContent = String(state.qty);
        document.querySelector(".checkout-bar b").textContent = "¥" + state.product.price * state.qty;
      },
      submit: function () {
        if (submitting || !state.product) return;
        var contact = state.contact.trim();
        var phone = state.phone.trim();
        var address = state.address.trim();
        if (!contact) return app.toast("请填写收货人");
        if (!app.validPhone(phone)) return app.toast("请填写11位手机号");
        if (address.length < 5) return app.toast("请填写详细地址");
        submitting = true;
        app.saveAddress({ contact: contact, phone: phone, address: address });
        app.createOrder({
          productId: state.product.id,
          name: state.product.name,
          image: state.product.image,
          model: state.product.model,
          price: state.product.price,
          qty: state.qty,
          contact: contact,
          phone: phone,
          address: address,
          remark: state.remark.trim()
        });
        app.toast("下单成功");
        setTimeout(function () { app.go("/orders"); }, 400);
      }
    }
  });

  function field(label, name, type, placeholder, value) {
    return (
      '<label><span>' + app.esc(label) + '</span><input data-field="' + name + '" type="' + type +
      '" maxlength="' + (name === "phone" ? "11" : "80") + '" placeholder="' + app.esc(placeholder) +
      '" value="' + app.esc(value) + '"></label>'
    );
  }
})(window.KanoH5);
