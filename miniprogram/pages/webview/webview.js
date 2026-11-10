const config = require("../../config.js");

Page({
  data: {
    src: "",
  },

  onLoad(query) {
    const src = query.src ? decodeURIComponent(query.src) : (config.h5Url || "");
    this.setData({ src: src });
  },
});
