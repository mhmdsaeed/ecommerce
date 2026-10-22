// Storefront behaviour (layouts/storefront.html): the cart badge, the checkout slip, the product page's variant
// picker and photos. Uses readCookie from app.js.

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

// Checkout: the delivery slip's address as the driver will read it, following the form's name, governorate, city,
// area and landmark fields as they are typed.
document.addEventListener("alpine:init", function () {
  window.Alpine.data("slipPreview", function () {
    return {
      name: "",
      landmark: "",
      place: "",
      init: function () {
        var self = this;
        var field = function (id) { return document.getElementById(id); };
        var value = function (id) { var input = field(id); return input ? input.value.trim() : ""; };
        var update = function () {
          var governorate = field("governorate");
          var chosen = governorate && governorate.value ? governorate.options[governorate.selectedIndex].text : "";
          self.name = value("name");
          self.landmark = value("landmark");
          self.place = [chosen, value("city"), value("area")].filter(Boolean).join(" › ");
        };
        ["name", "governorate", "city", "area", "landmark"].forEach(function (id) {
          var input = field(id);
          if (input) {
            input.addEventListener("input", update);
            input.addEventListener("change", update);
          }
        });
        update();
      }
    };
  });
});

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
      quantity: 1,
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
      },
      // Whether a choice (its radio input) can be bought with the other options as chosen: a variant in stock has it.
      buyable: function (input) {
        var index = parseInt(input.dataset.index, 10);
        var chosen = this.chosen;
        return this.variants.some(function (variant) {
          return variant.inStock && variant.values.every(function (value, i) {
            return i === index ? value === input.value : value === chosen[i];
          });
        });
      }
    };
  });
});

// Product page photos: a strip that scrolls one photo at a time, and thumbnails that bring a photo into view. The
// current photo is the one mostly in view, so swiping updates the thumbnails too.
document.addEventListener("alpine:init", function () {
  window.Alpine.data("gallery", function () {
    return {
      current: 0,
      init: function () {
        var self = this;
        var slides = Array.prototype.slice.call(this.$refs.strip.children);
        if (slides.length < 2 || !("IntersectionObserver" in window)) {
          return;
        }
        var observer = new IntersectionObserver(function (entries) {
          entries.forEach(function (entry) {
            if (entry.isIntersecting) {
              self.current = slides.indexOf(entry.target);
            }
          });
        }, { root: this.$refs.strip, threshold: 0.6 });
        slides.forEach(function (slide) { observer.observe(slide); });
      },
      show: function (index) {
        var slide = this.$refs.strip.children[index];
        var smooth = !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        slide.scrollIntoView({ behavior: smooth ? "smooth" : "auto", block: "nearest", inline: "start" });
        this.current = index;
      }
    };
  });
});
