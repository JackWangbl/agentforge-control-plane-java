const catalog = require("../../data/catalog.js");

Page({
  data: {
    categories: catalog.categories,
    current: "all",
    products: catalog.products,
    placeholder: catalog.store.searchPlaceholder,
  },

  onShow() {
    if (require("../../utils/open-h5.js").openH5("#/category")) return;
  },

  selectCategory(e) {
    const id = e.currentTarget.dataset.id;
    this.setData({
      current: id,
      products: catalog.filterProducts({ category: id }),
    });
  },

  goSearch() {
    wx.navigateTo({ url: "/pages/search/search" });
  },

  openDetail(e) {
    wx.navigateTo({ url: "/pages/detail/detail?id=" + e.detail.id });
  },
});
