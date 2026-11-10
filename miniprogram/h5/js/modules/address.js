(function (app) {
  var form = app.address();

  app.page({
    id: "address",
    path: "/address",
    tab: null,
    title: "收货地址",
    header: function () {
      return (
        '<div class="sub-header"><button type="button" class="back" data-act="back" aria-label="返回">‹</button>' +
        '<div class="sub-title">收货地址</div></div>'
      );
    },
    body: function () {
      form = app.address();
      return (
        '<section class="panel form">' +
        '<label><span>收货人</span><input data-field="contact" placeholder="姓名" value="' + app.esc(form.contact) + '"></label>' +
        '<label><span>手机号</span><input data-field="phone" type="tel" maxlength="11" placeholder="11位手机号" value="' + app.esc(form.phone) + '"></label>' +
        '<label class="area"><span>地址</span><textarea data-field="address" placeholder="省市区和详细地址">' + app.esc(form.address) + "</textarea></label>" +
        "</section>" +
        '<button type="button" class="save" data-act="save">保存</button>'
      );
    },
    fields: {
      contact: function (value) { form.contact = value; },
      phone: function (value) { form.phone = value; },
      address: function (value) { form.address = value; }
    },
    actions: {
      back: function () { history.back(); },
      save: function () {
        var contact = form.contact.trim();
        var phone = form.phone.trim();
        var address = form.address.trim();
        if (!contact || !app.validPhone(phone) || address.length < 5) {
          app.toast("请完整填写收货信息");
          return;
        }
        app.saveAddress({ contact: contact, phone: phone, address: address });
        app.toast("已保存");
        setTimeout(function () { history.back(); }, 400);
      }
    }
  });
})(window.KanoH5);
