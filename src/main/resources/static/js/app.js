// Application-wide behaviour. Keep small: the storefront JS budget is 40 KB gzipped in total (SPEC 9.5).
// Loaded before Alpine, so components registered on alpine:init exist when Alpine starts.

function readCookie(name) {
  var match = document.cookie.match(new RegExp("(?:^|;\\s*)" + name + "=([^;]*)"));
  return match ? decodeURIComponent(match[1]) : null;
}

// CSRF for HTMX requests. Admin pages set the header via hx-headers on <body> (layouts/base.html). Storefronts
// keep the token in the XSRF-TOKEN cookie instead (no session per shopper), so copy it into the header here.
document.addEventListener("htmx:configRequest", function (event) {
  var token = readCookie("XSRF-TOKEN");
  if (token) {
    event.detail.headers["X-XSRF-TOKEN"] = token;
  }
});

// Storefront header: the cart's item count, from the cart_count cookie the server sets on every cart change.
// Refreshed on the cart-changed event (HX-Trigger) and when the page comes back from the back/forward cache.
document.addEventListener("alpine:init", function () {
  window.Alpine.data("cartBadge", function () {
    return {
      count: 0,
      init: function () { this.refresh(); },
      refresh: function () { this.count = parseInt(readCookie("cart_count") || "0", 10) || 0; }
    };
  });
});

// Sign-in, landing and signup pages: the shop sign (fragments/auth.html) shows the store address as it is typed
// into #store (or the input named by data-input), and on signup the store name typed into data-name-input.
// Without those inputs (e.g. after "send link") it keeps what the server rendered.
document.addEventListener("alpine:init", function () {
  window.Alpine.data("storeSign", function () {
    function follow(id, set) {
      var input = id ? document.getElementById(id) : null;
      if (input) {
        var update = function () { set(input.value); };
        input.addEventListener("input", update);
        update();
      }
    }
    return {
      slug: "",
      name: "",
      init: function () {
        var self = this;
        var shownSlug = this.$el.querySelector(".shop-sign__slug");
        var shownName = this.$el.querySelector(".shop-sign__name");
        this.slug = shownSlug ? shownSlug.textContent : "";
        this.name = shownName ? shownName.textContent : "";
        follow(this.$el.dataset.input || "store", function (value) { self.slug = value.trim().toLowerCase(); });
        follow(this.$el.dataset.nameInput, function (value) { self.name = value.trim(); });
      }
    };
  });
});

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

// Product page: picks the variant matching the chosen option values and shows its price and stock.
// data-variants holds [{id, values: [label per option], price, compareAt, inStock}] for the available variants.
// Starts on the first variant in stock.
document.addEventListener("alpine:init", function () {
  window.Alpine.data("variantPicker", function () {
    return {
      variants: [],
      chosen: [],
      price: "",
      compareAt: "",
      variantId: null,
      unavailable: false,
      soldOut: false,
      init: function () {
        this.variants = JSON.parse(this.$el.dataset.variants || "[]");
        var first = this.variants.find(function (variant) { return variant.inStock; }) || this.variants[0];
        if (first) {
          this.chosen = first.values.slice();
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
        this.soldOut = !!match && !match.inStock;
        this.variantId = match ? match.id : null;
        if (match) {
          this.price = match.price;
          this.compareAt = match.compareAt || "";
        }
      }
    };
  });
});
