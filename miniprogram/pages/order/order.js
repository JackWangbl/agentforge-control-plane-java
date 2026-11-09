const store = require("../../utils/store.js");

Page({
  data: {
    tabs: [
      { id: "all", name: "全部" },
      { id: "pending_ship", name: "待发货" },
      { id: "pending_receive", name: "待收货" },
      { id: "done", name: "已完成" },
    ],
    filter: "all",
    orders: [],
  },

  onShow() {
    const preset = getApp().globalData.orderFilter;
    if (preset) {
      getApp().globalData.orderFilter = "";
      this.setData({ filter: preset });
    }
    this.refresh();
  },

  refresh() {
    const filter = this.data.filter;
    const orders = store
      .orders()
      .filter((item) => filter === "all" || item.status === filter)
      .map((item) =>
        Object.assign({}, item, {
          statusText: store.STATUS[item.status] || item.status,
          timeText: store.formatTime(item.createdAt),
        })
      );
    this.setData({ orders });
  },

  switchFilter(e) {
    this.setData({ filter: e.currentTarget.dataset.id }, () => this.refresh());
  },

  openDetail(e) {
    wx.navigateTo({ url: "/pages/detail/detail?id=" + e.currentTarget.dataset.pid });
  },

  cancel(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: "取消订单",
      content: "确定取消这张订单吗？",
      success: (res) => {
        if (!res.confirm) return;
        store.updateStatus(id, "cancelled");
        this.refresh();
      },
    });
  },

  ship(e) {
    store.updateStatus(e.currentTarget.dataset.id, "pending_receive");
    wx.showToast({ title: "已标记发货", icon: "success" });
    this.refresh();
  },

  receive(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: "确认收货",
      content: "确认已经收到商品吗？",
      success: (res) => {
        if (!res.confirm) return;
        store.updateStatus(id, "done");
        this.refresh();
      },
    });
  },
});
