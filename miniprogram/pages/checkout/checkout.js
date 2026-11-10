const catalog = require("../../data/catalog.js");
const store = require("../../utils/store.js");

function validPhone(phone) {
  return /^1\d{10}$/.test(phone);
}

Page({
  data: {
    product: null,
    qty: 1,
    amount: 0,
    contact: "",
    phone: "",
    address: "",
    remark: "",
  },

  onLoad(options) {
    const product = catalog.getProduct(options.id);
    const qty = Math.min(99, Math.max(1, Number(options.qty) || 1));
    const saved = store.address();
    this.setData({
      product,
      qty,
      amount: product ? product.price * qty : 0,
      contact: saved.contact,
      phone: saved.phone,
      address: saved.address,
    });
  },

  changeQty(e) {
    if (!this.data.product) return;
    const step = Number(e.currentTarget.dataset.step);
    const qty = Math.min(99, Math.max(1, this.data.qty + step));
    this.setData({
      qty,
      amount: this.data.product.price * qty,
    });
  },

  onContact(e) {
    this.setData({ contact: e.detail.value });
  },
  onPhone(e) {
    this.setData({ phone: e.detail.value });
  },
  onAddress(e) {
    this.setData({ address: e.detail.value });
  },
  onRemark(e) {
    this.setData({ remark: e.detail.value });
  },

  submit() {
    const data = this.data;
    if (!data.product) return;
    const contact = (data.contact || "").trim();
    const phone = (data.phone || "").trim();
    const address = (data.address || "").trim();
    if (!contact) {
      wx.showToast({ title: "请填写收货人", icon: "none" });
      return;
    }
    if (!validPhone(phone)) {
      wx.showToast({ title: "请填写11位手机号", icon: "none" });
      return;
    }
    if (address.length < 5) {
      wx.showToast({ title: "请填写详细地址", icon: "none" });
      return;
    }
    store.saveAddress({ contact, phone, address });
    store.createOrder({
      productId: data.product.id,
      name: data.product.name,
      image: data.product.image,
      model: data.product.model,
      price: data.product.price,
      qty: data.qty,
      contact,
      phone,
      address,
      remark: (data.remark || "").trim(),
    });
    wx.showToast({ title: "下单成功", icon: "success" });
    setTimeout(() => {
      wx.switchTab({ url: "/pages/order/order" });
    }, 500);
  },
});
