(function (root) {
  var pages = [];

  function esc(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function asset(src) {
    if (!src) return "";
    if (/^(https?:|data:|blob:)/.test(src)) return src;
    var base = (root.KANO_H5_CONFIG && root.KANO_H5_CONFIG.assetBase) || "../";
    return new URL(String(src).replace(/^\/+/, ""), new URL(base, document.baseURI)).href;
  }

  root.KanoH5 = {
    pages: pages,
    catalog: root.KANO,
    page: function (definition) {
      pages.push(definition);
    },
    esc: esc,
    asset: asset,
    card: function (item) {
      return (
        '<button type="button" class="card" data-act="open-product" data-id="' +
        esc(item.id) +
        '">' +
        '<img src="' +
        esc(asset(item.image)) +
        '" alt="' +
        esc(item.name) +
        '">' +
        '<div class="name">' +
        esc(item.name) +
        "</div>" +
        '<div class="meta"><span class="price">¥' +
        esc(item.price) +
        '</span><span class="sold">已售' +
        esc(item.sold) +
        "</span></div></button>"
      );
    },
    grid: function (list) {
      if (!list.length) return "";
      return '<div class="grid">' + list.map(root.KanoH5.card).join("") + "</div>";
    }
  };
})(window);
