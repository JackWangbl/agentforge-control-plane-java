const ORDER_KEY = "kano_orders_v1";
const ADDRESS_KEY = "kano_address_v1";
const HISTORY_KEY = "kano_search_history_v1";

const STATUS = {
  pending_ship: "待发货",
  pending_receive: "待收货",
  done: "已完成",
  cancelled: "已取消",
};

function read(key, fallback) {
  try {
    const value = wx.getStorageSync(key);
    if (value === "" || value === undefined || value === null) return fallback;
    return value;
  } catch (err) {
    return fallback;
  }
}

function orders() {
  const list = read(ORDER_KEY, []);
  return Array.isArray(list) ? list : [];
}

function saveOrders(list) {
  wx.setStorageSync(ORDER_KEY, list);
}

function createOrder(input) {
  const qty = Math.max(1, Number(input.qty) || 1);
  const order = {
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
    remark: input.remark || "",
  };
  const list = orders();
  list.unshift(order);
  saveOrders(list);
  return order;
}

function updateStatus(id, status) {
  if (!STATUS[status]) return;
  saveOrders(orders().map((item) => (item.id === id ? Object.assign({}, item, { status: status }) : item)));
}

function emptyAddress() {
  return { contact: "", phone: "", address: "" };
}

function address() {
  const value = read(ADDRESS_KEY, emptyAddress());
  if (!value || typeof value !== "object") return emptyAddress();
  return {
    contact: value.contact || "",
    phone: value.phone || "",
    address: value.address || "",
  };
}

function saveAddress(value) {
  wx.setStorageSync(ADDRESS_KEY, {
    contact: value.contact || "",
    phone: value.phone || "",
    address: value.address || "",
  });
}

function history() {
  const list = read(HISTORY_KEY, []);
  return Array.isArray(list) ? list : [];
}

function pushHistory(keyword) {
  const text = (keyword || "").trim();
  if (!text) return;
  const next = [text].concat(history().filter((item) => item !== text)).slice(0, 8);
  wx.setStorageSync(HISTORY_KEY, next);
}

function clearHistory() {
  wx.setStorageSync(HISTORY_KEY, []);
}

function formatTime(ts) {
  const date = new Date(ts);
  const pad = (n) => (n < 10 ? "0" + n : "" + n);
  return (
    date.getFullYear() +
    "-" +
    pad(date.getMonth() + 1) +
    "-" +
    pad(date.getDate()) +
    " " +
    pad(date.getHours()) +
    ":" +
    pad(date.getMinutes())
  );
}

function countByStatus() {
  const tally = { pending_ship: 0, pending_receive: 0, done: 0, cancelled: 0, all: 0 };
  orders().forEach((item) => {
    tally.all += 1;
    if (tally[item.status] !== undefined) tally[item.status] += 1;
  });
  return tally;
}

module.exports = {
  STATUS,
  orders,
  createOrder,
  updateStatus,
  address,
  saveAddress,
  history,
  pushHistory,
  clearHistory,
  formatTime,
  countByStatus,
};
