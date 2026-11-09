const catalog = require("../../data/catalog.js");

Page({
  data: {
    product: null,
    qty: 1,
    phoneDisplay: catalog.store.phoneDisplay,
  },

  onLoad(options) {
    const found = catalog.getProduct(options.id);
    const product = found
      ? Object.assign({}, found, {
          specRows: found.specs.map((row) => ({ k: row[0], v: row[1] })),
        })
      : null;
    this.setData({ product: product });
    if (product) wx.setNavigationBarTitle({ title: product.model });
  },

  changeQty(e) {
    const step = Number(e.currentTarget.dataset.step);
    const qty = Math.min(99, Math.max(1, this.data.qty + step));
    this.setData({ qty });
  },

  goHome() {
    wx.switchTab({ url: "/pages/index/index" });
  },

  openService() {
    const store = catalog.store;
    wx.showActionSheet({
      itemList: ["拨打客服 " + store.phoneDisplay, "复制客服微信"],
      success(res) {
        if (res.tapIndex === 0) wx.makePhoneCall({ phoneNumber: store.phone });
        if (res.tapIndex === 1) wx.setClipboardData({ data: store.wechat });
      },
    });
  },

  buy() {
    const product = this.data.product;
    if (!product) return;
    wx.navigateTo({
      url: "/pages/checkout/checkout?id=" + product.id + "&qty=" + this.data.qty,
    });
  },

  onShareAppMessage() {
    const product = this.data.product;
    if (!product) return { title: catalog.store.navTitle, path: "/pages/index/index" };
    return {
      title: product.name,
      path: "/pages/detail/detail?id=" + product.id,
    };
  },
});
