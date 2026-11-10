const catalog = require("../../data/catalog.js");
const store = require("../../utils/store.js");

Page({
  data: {
    counts: { pending_ship: 0, pending_receive: 0, done: 0, all: 0 },
    phoneDisplay: catalog.store.phoneDisplay,
    hours: catalog.store.hours,
    shortcuts: [
      { id: "pending_ship", name: "待发货" },
      { id: "pending_receive", name: "待收货" },
      { id: "done", name: "已完成" },
      { id: "all", name: "全部订单" },
    ],
  },

  onShow() {
    if (require("../../utils/open-h5.js").openH5("#/mine")) return;
    this.setData({ counts: store.countByStatus() });
  },

  openOrders(e) {
    getApp().globalData.orderFilter = e.currentTarget.dataset.id;
    wx.switchTab({ url: "/pages/order/order" });
  },

  openAddress() {
    wx.navigateTo({ url: "/pages/address/address" });
  },

  callPhone() {
    wx.makePhoneCall({ phoneNumber: catalog.store.phone });
  },

  about() {
    const info = catalog.store;
    wx.showModal({
      title: "关于手之劤",
      content:
        info.name +
        " KANO\n精工制造，品质卓越。\n108V 无刷锂电工具。\n客服 " +
        info.phoneDisplay +
        "\n" +
        info.hours,
      showCancel: false,
    });
  },
});
