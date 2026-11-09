Component({
  properties: {
    product: {
      type: Object,
      value: {},
    },
  },
  methods: {
    onTap() {
      const product = this.data.product || {};
      if (!product.id) return;
      this.triggerEvent("select", { id: product.id });
    },
  },
});
