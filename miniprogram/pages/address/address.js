const store = require("../../utils/store.js");

Page({
  data: {
    contact: "",
    phone: "",
    address: "",
  },

  onShow() {
    const saved = store.address();
    this.setData(saved);
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

  save() {
    const contact = (this.data.contact || "").trim();
    const phone = (this.data.phone || "").trim();
    const address = (this.data.address || "").trim();
    if (!contact || !/^1\d{10}$/.test(phone) || address.length < 5) {
      wx.showToast({ title: "请完整填写收货信息", icon: "none" });
      return;
    }
    store.saveAddress({ contact, phone, address });
    wx.showToast({ title: "已保存", icon: "success" });
    setTimeout(() => wx.navigateBack(), 400);
  },
});
