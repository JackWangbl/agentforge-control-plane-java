const config = require("../config.js");

function openH5(hash) {
  const base = (config.h5Url || "").trim();
  if (!base) return false;
  let url = base.replace(/#.*$/, "");
  if (!/\.html?$/i.test(url) && url.charAt(url.length - 1) !== "/") url += "/";
  const src = url + (hash || "#/");
  wx.reLaunch({
    url: "/pages/webview/webview?src=" + encodeURIComponent(src),
  });
  return true;
}

module.exports = { openH5 };
