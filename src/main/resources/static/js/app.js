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

// Storefront look settings: a small copy of the shop that follows the form (data-form) as the merchant types. The
// colour shades are the storefront's, worked out on the server (ThemePalette): data-colors holds them for the
// colour first shown, and data-palette answers with them for each colour picked. Fonts come from data-classic-fonts.
document.addEventListener("alpine:init", function () {
  window.Alpine.data("themePreview", function () {
    return {
      style: "",
      title: "",
      text: "",
      init: function () {
        var self = this;
        var dataset = this.$el.dataset;
        var form = document.getElementById(dataset.form);
        if (!form) {
          return;
        }
        var english = dataset.lang === "en";
        var value = function (name) {
          var field = form.elements[name];
          return field ? field.value.trim() : "";
        };
        var colors = dataset.colors || "";
        var known = {};
        known[value("primaryColor")] = colors;
        var timer = null;
        var either = function (ar, en) { return english ? (value(en) || value(ar)) : (value(ar) || value(en)); };
        var render = function () {
          var font = form.querySelector("input[name=font]:checked");
          var classic = font && font.value === "CLASSIC";
          self.style = colors + (classic ? "font-family:" + dataset.classicFonts + ";" : "");
        };
        var pick = function (color) {
          clearTimeout(timer);
          if (color in known) {
            colors = known[color];
            return;
          }
          timer = setTimeout(function () {
            fetch(dataset.palette + "?color=" + encodeURIComponent(color), { headers: { Accept: "application/json" } })
              .then(function (response) { return response.ok ? response.json() : null; })
              .then(function (palette) {
                if (!palette) {
                  return;
                }
                known[color] = "--p-50:" + palette.shade50 + ";--p-600:" + palette.shade600
                  + ";--p-700:" + palette.shade700 + ";";
                if (value("primaryColor") === color) {
                  colors = known[color];
                  render();
                }
              })
              .catch(function () { /* keeps the colour shown last */ });
          }, 120);
        };
        var update = function () {
          self.title = either("heroTitleAr", "heroTitleEn");
          self.text = either("heroTextAr", "heroTextEn");
          pick(value("primaryColor"));
          render();
        };
        form.addEventListener("input", update);
        form.addEventListener("change", update);
        update();
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
