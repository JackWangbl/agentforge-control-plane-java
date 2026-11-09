const catalog = require("../../data/catalog.js");
const store = require("../../utils/store.js");

Page({
  data: {
    keyword: "",
    placeholder: catalog.store.searchPlaceholder,
    products: [],
    history: [],
    searched: false,
  },

  onShow() {
    this.setData({ history: store.history() });
  },

  onInput(e) {
    const keyword = e.detail.value;
    this.setData({
      keyword,
      searched: true,
      products: catalog.filterProducts({ keyword }),
    });
  },

  onSearch() {
    const keyword = (this.data.keyword || "").trim();
    store.pushHistory(keyword);
    this.setData({
      keyword,
      searched: true,
      history: store.history(),
      products: catalog.filterProducts({ keyword }),
    });
  },

  useHistory(e) {
    const keyword = e.currentTarget.dataset.word;
    this.setData({
      keyword,
      searched: true,
      products: catalog.filterProducts({ keyword }),
    });
    store.pushHistory(keyword);
    this.setData({ history: store.history() });
  },

  clearHistory() {
    store.clearHistory();
    this.setData({ history: [] });
  },

  openDetail(e) {
    wx.navigateTo({ url: "/pages/detail/detail?id=" + e.detail.id });
  },
});
