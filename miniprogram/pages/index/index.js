const catalog = require("../../data/catalog.js");

Page({
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    menuWidth: 96,
    headerHeight: 160,
    products: catalog.products,
    banners: catalog.banners,
    bannerIndex: 0,
    placeholder: catalog.store.searchPlaceholder,
    title: catalog.store.navTitle,
  },

  onShow() {
    if (require("../../utils/open-h5.js").openH5("#/")) return;
  },

  onLoad() {
    const sys = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    const statusBarHeight = sys.statusBarHeight || 20;
    let menu = {
      top: statusBarHeight + 6,
      height: 32,
      left: sys.windowWidth - 96,
    };
    try {
      const rect = wx.getMenuButtonBoundingClientRect();
      if (rect && rect.height) menu = rect;
    } catch (err) {
      menu = menu;
    }
    const navBarHeight = Math.max(44, (menu.top - statusBarHeight) * 2 + menu.height);
    const searchBlock = (96 * sys.windowWidth) / 750;
    this.setData({
      statusBarHeight,
      navBarHeight,
      menuWidth: Math.max(88, sys.windowWidth - menu.left + 8),
      headerHeight: statusBarHeight + navBarHeight + searchBlock,
    });
  },

  onBanner(e) {
    this.setData({ bannerIndex: e.detail.current });
  },

  goSearch() {
    wx.navigateTo({ url: "/pages/search/search" });
  },

  openDetail(e) {
    wx.navigateTo({ url: "/pages/detail/detail?id=" + e.detail.id });
  },

  callPhone() {
    wx.makePhoneCall({ phoneNumber: catalog.store.phone });
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

  onShareAppMessage() {
    return {
      title: catalog.store.navTitle,
      path: "/pages/index/index",
    };
  },
});
