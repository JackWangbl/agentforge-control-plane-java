(function (app) {
  var ORDER_KEY = "kano_orders_v1";
  var ADDRESS_KEY = "kano_address_v1";
  var HISTORY_KEY = "kano_search_history_v1";
  var STATUS = {
    pending_ship: "待发货",
    pending_receive: "待收货",
    done: "已完成",
    cancelled: "已取消"
  };

  function read(key, fallback) {
    try {
      var raw = localStorage.getItem(key);
      if (!raw) return fallback;
      return JSON.parse(raw);
    } catch (err) {
      return fallback;
    }
  }

  function write(key, value) {
    localStorage.setItem(key, JSON.stringify(value));
  }

  function orders() {
    var list = read(ORDER_KEY, []);
    return Array.isArray(list) ? list : [];
  }

  function emptyAddress() {
    return { contact: "", phone: "", address: "" };
  }

  app.STATUS = STATUS;

  app.orders = orders;

  app.createOrder = function (input) {
    var qty = Math.max(1, Number(input.qty) || 1);
    var order = {
      id: "SZ" + Date.now(),
      status: "pending_ship",
      createdAt: Date.now(),
      productId: input.productId,
      name: input.name,
      image: input.image,
      model: input.model,
      price: input.price,
      qty: qty,
      amount: input.price * qty,
      contact: input.contact,
      phone: input.phone,
      address: input.address,
      remark: input.remark || ""
    };
    var list = orders();
    list.unshift(order);
    write(ORDER_KEY, list);
    return order;
  };

  app.updateStatus = function (id, status) {
    if (!STATUS[status]) return;
    write(
      ORDER_KEY,
      orders().map(function (item) {
        return item.id === id ? Object.assign({}, item, { status: status }) : item;
      })
    );
  };

  app.address = function () {
    var value = read(ADDRESS_KEY, emptyAddress());
    if (!value || typeof value !== "object") return emptyAddress();
    return {
      contact: value.contact || "",
      phone: value.phone || "",
      address: value.address || ""
    };
  };

  app.saveAddress = function (value) {
    write(ADDRESS_KEY, {
      contact: value.contact || "",
      phone: value.phone || "",
      address: value.address || ""
    });
  };

  app.history = function () {
    var list = read(HISTORY_KEY, []);
    return Array.isArray(list) ? list : [];
  };

  app.pushHistory = function (keyword) {
    var text = (keyword || "").trim();
    if (!text) return;
    var next = [text].concat(app.history().filter(function (item) { return item !== text; })).slice(0, 8);
    write(HISTORY_KEY, next);
  };

  app.clearHistory = function () {
    write(HISTORY_KEY, []);
  };

  app.formatTime = function (ts) {
    var date = new Date(ts);
    function pad(n) { return n < 10 ? "0" + n : "" + n; }
    return (
      date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate()) +
      " " + pad(date.getHours()) + ":" + pad(date.getMinutes())
    );
  };

  app.countByStatus = function () {
    var tally = { pending_ship: 0, pending_receive: 0, done: 0, cancelled: 0, all: 0 };
    orders().forEach(function (item) {
      tally.all += 1;
      if (tally[item.status] !== undefined) tally[item.status] += 1;
    });
    return tally;
  };

  app.validPhone = function (phone) {
    return /^1\d{10}$/.test(phone);
  };
})(window.KanoH5);
