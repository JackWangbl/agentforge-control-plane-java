(function (root, factory) {
  var api = factory();
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  } else {
    root.KANO = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  var products = [
    {
      id: "szr-108",
      model: "SZR-108",
      name: "108无刷角磨机（108V）",
      price: 185,
      sold: 0,
      categoryId: "grinder",
      category: "角磨机",
      image: "/assets/products/szr-108.jpg",
      keywords: "角磨 打磨 切割 无刷",
      subtitle: "无刷电机，金属机头，可与 108V 电池包互换",
      highlights: ["无刷电机", "108V锂电", "金属机头", "侧手柄"],
      specs: [
        ["型号", "SZR-108"],
        ["额定电压", "108V"],
        ["电机", "无刷"],
        ["类型", "角磨机"],
        ["电池平台", "手之劤 108V"]
      ],
      detail: "适合金属打磨、除锈和切断。机头为金属壳体，握把做了防滑处理。"
    },
    {
      id: "szr-106",
      model: "SZR-106",
      name: "106无刷扳手（108V）",
      price: 180,
      sold: 0,
      categoryId: "wrench",
      category: "扳手",
      image: "/assets/products/szr-106.jpg",
      keywords: "扳手 冲击 套筒 无刷",
      subtitle: "无刷冲击扳手，适合拆装螺栓",
      highlights: ["无刷电机", "108V锂电", "金属机身", "防滑握把"],
      specs: [
        ["型号", "SZR-106"],
        ["额定电压", "108V"],
        ["电机", "无刷"],
        ["类型", "冲击扳手"],
        ["电池平台", "手之劤 108V"]
      ],
      detail: "用于螺栓拆装和装配紧固。无刷电机持续输出，握持部位做了防滑。"
    },
    {
      id: "szr-107",
      model: "SZR-107",
      name: "12寸无刷链条锯",
      price: 268,
      sold: 0,
      categoryId: "chainsaw",
      category: "链条锯",
      image: "/assets/products/szr-107.jpg",
      keywords: "链条锯 电链锯 12寸 切割",
      subtitle: "12寸导板，适合枝条和小型木料切断",
      highlights: ["12寸导板", "无刷电机", "108V锂电", "前护手"],
      specs: [
        ["型号", "SZR-107"],
        ["导板", "12寸"],
        ["额定电压", "108V"],
        ["电机", "无刷"],
        ["电池平台", "手之劤 108V"]
      ],
      detail: "导板长度 12 寸，适合庭院枝条和小型木料。使用前请安装导板护罩，并按说明书张紧链条。"
    },
    {
      id: "szr-1033",
      model: "SZR-1033",
      name: "1033无刷扳手（108V）",
      price: 198,
      sold: 0,
      categoryId: "wrench",
      category: "扳手",
      image: "/assets/products/szr-1033.jpg",
      keywords: "扳手 冲击 LED 散热",
      subtitle: "前置 LED 照明，后置散热",
      highlights: ["前置LED", "后置散热", "无刷电机", "108V锂电"],
      specs: [
        ["型号", "SZR-1033"],
        ["额定电压", "108V"],
        ["电机", "无刷"],
        ["照明", "前置 LED"],
        ["电池平台", "手之劤 108V"]
      ],
      detail: "在 SZR 扳手平台上增加了前置照明和后置散热，方便光线不足时作业。"
    },
    {
      id: "szr-105",
      model: "SZR-105",
      name: "105无刷电钻（108V）",
      price: 158,
      sold: 6,
      categoryId: "drill",
      category: "电钻",
      image: "/assets/products/szr-105.jpg",
      keywords: "电钻 钻孔 螺丝",
      subtitle: "夹头快换，家装钻孔和拧螺丝都能用",
      highlights: ["无刷电机", "快换夹头", "108V锂电", "两档调速"],
      specs: [
        ["型号", "SZR-105"],
        ["额定电压", "108V"],
        ["电机", "无刷"],
        ["类型", "电钻"],
        ["电池平台", "手之劤 108V"]
      ],
      detail: "用于木材、金属薄板钻孔和拧螺丝。夹头支持常用钻头规格。"
    },
    {
      id: "szr-104",
      model: "SZR-104",
      name: "104无刷冲击钻（108V）",
      price: 168,
      sold: 4,
      categoryId: "drill",
      category: "电钻",
      image: "/assets/products/szr-104.jpg",
      keywords: "冲击钻 钻孔 混凝土",
      subtitle: "冲击模式，适合砌体和混凝土轻载钻孔",
      highlights: ["冲击模式", "无刷电机", "108V锂电", "防滑握把"],
      specs: [
        ["型号", "SZR-104"],
        ["额定电压", "108V"],
        ["电机", "无刷"],
        ["类型", "冲击钻"],
        ["电池平台", "手之劤 108V"]
      ],
      detail: "带冲击档，适合砌体和混凝土上的轻载钻孔。重载开孔请改用电锤。"
    },
    {
      id: "szr-110",
      model: "SZR-110",
      name: "110无刷电锤（108V）",
      price: 329,
      sold: 2,
      categoryId: "hammer",
      category: "电锤",
      image: "/assets/products/szr-110.jpg",
      keywords: "电锤 开孔 混凝土",
      subtitle: "电锤冲击，适合混凝土开孔",
      highlights: ["电锤冲击", "无刷电机", "108V锂电", "辅助手柄"],
      specs: [
        ["型号", "SZR-110"],
        ["额定电压", "108V"],
        ["电机", "无刷"],
        ["类型", "电锤"],
        ["电池平台", "手之劤 108V"]
      ],
      detail: "用于混凝土、砖墙开孔。作业时请装上辅助手柄，并佩戴护目镜。"
    },
    {
      id: "szr-112",
      model: "SZR-112",
      name: "112无刷往复锯（108V）",
      price: 246,
      sold: 5,
      categoryId: "saw",
      category: "锯切",
      image: "/assets/products/szr-112.jpg",
      keywords: "往复锯 马刀锯 切割",
      subtitle: "快换锯条，适合管材和板材切断",
      highlights: ["快换锯条", "无刷电机", "108V锂电", "变速扳扣"],
      specs: [
        ["型号", "SZR-112"],
        ["额定电压", "108V"],
        ["电机", "无刷"],
        ["类型", "往复锯"],
        ["电池平台", "手之劤 108V"]
      ],
      detail: "适合金属管、PVC 和木板切断。请按材料更换对应锯条。"
    },
    {
      id: "szr-109",
      model: "SZR-109",
      name: "109无刷鼓风机（108V）",
      price: 219,
      sold: 3,
      categoryId: "clean",
      category: "清洁",
      image: "/assets/products/szr-109.jpg",
      keywords: "鼓风机 吹风机 除尘",
      subtitle: "清理木屑、灰尘和工位碎屑",
      highlights: ["无刷电机", "108V锂电", "集中出风", "轻握持"],
      specs: [
        ["型号", "SZR-109"],
        ["额定电压", "108V"],
        ["电机", "无刷"],
        ["类型", "鼓风机"],
        ["电池平台", "手之劤 108V"]
      ],
      detail: "用于清理工位木屑和灰尘。出风口不要朝向人或宠物。"
    }
  ];

  var categories = [
    { id: "all", name: "全部" },
    { id: "grinder", name: "角磨机" },
    { id: "wrench", name: "扳手" },
    { id: "chainsaw", name: "链条锯" },
    { id: "drill", name: "电钻" },
    { id: "hammer", name: "电锤" },
    { id: "saw", name: "锯切" },
    { id: "clean", name: "清洁" }
  ];

  var banners = [
    { id: "craft", image: "/assets/banners/craft.jpg" },
    { id: "series", image: "/assets/banners/series.jpg" },
    { id: "torque", image: "/assets/banners/torque.jpg" },
    { id: "cut", image: "/assets/banners/cut.jpg" }
  ];

  var store = {
    name: "手之劤",
    slogan: "精工制造 品质卓越",
    navTitle: "手之劤产品 精工制造 品质卓越",
    phone: "4001080888",
    phoneDisplay: "400-108-0888",
    wechat: "kano-hand-tools",
    hours: "每天 9:00-21:00",
    searchPlaceholder: "请输入你想要查找的商品名称"
  };

  function getProduct(id) {
    for (var i = 0; i < products.length; i += 1) {
      if (products[i].id === id) return products[i];
    }
    return null;
  }

  function filterProducts(options) {
    var opts = options || {};
    var keyword = (opts.keyword || "").trim().toLowerCase();
    return products.filter(function (item) {
      if (opts.category && opts.category !== "all" && item.categoryId !== opts.category) return false;
      if (!keyword) return true;
      var blob = [item.name, item.model, item.category, item.keywords, item.subtitle].join(" ").toLowerCase();
      return blob.indexOf(keyword) >= 0;
    });
  }

  return {
    products: products,
    categories: categories,
    banners: banners,
    store: store,
    getProduct: getProduct,
    filterProducts: filterProducts
  };
});
