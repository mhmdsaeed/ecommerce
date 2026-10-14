// Application-wide behaviour. Keep small: the storefront JS budget is 40 KB gzipped in total (SPEC 9.5).
// The CSRF header for HTMX requests is set declaratively via hx-headers on <body> (layouts/base.html).
// Loaded before Alpine, so components registered on alpine:init exist when Alpine starts.

// htmx does not swap 4xx/5xx responses, so a failed request would otherwise look like nothing happened.
// Pages that want feedback include an element with id "htmx-error" holding the localized message.
(function () {
  function showError(visible) {
    var banner = document.getElementById("htmx-error");
    if (banner) {
      banner.hidden = !visible;
    }
  }
  document.addEventListener("htmx:beforeRequest", function () { showError(false); });
  document.addEventListener("htmx:responseError", function () { showError(true); });
  document.addEventListener("htmx:sendError", function () { showError(true); });
})();

// Product page: picks the variant matching the chosen option values and shows its price.
// data-variants holds [{id, values: [label per option], price, compareAt}] for the available variants.
document.addEventListener("alpine:init", function () {
  window.Alpine.data("variantPicker", function () {
    return {
      variants: [],
      chosen: [],
      price: "",
      compareAt: "",
      variantId: null,
      unavailable: false,
      init: function () {
        this.variants = JSON.parse(this.$el.dataset.variants || "[]");
        if (this.variants.length > 0) {
          this.chosen = this.variants[0].values.slice();
        }
        this.update();
        this.$watch("chosen", this.update.bind(this));
      },
      update: function () {
        var chosen = this.chosen;
        var match = this.variants.find(function (variant) {
          return variant.values.every(function (value, index) { return value === chosen[index]; });
        });
        this.unavailable = !match;
        this.variantId = match ? match.id : null;
        if (match) {
          this.price = match.price;
          this.compareAt = match.compareAt || "";
        }
      }
    };
  });
});
