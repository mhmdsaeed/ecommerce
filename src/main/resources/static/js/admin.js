// Merchant admin behaviour (layouts/admin.html): the storefront look preview and printing reports.

// Storefront look settings: a small copy of the shop that follows the form (data-form) as the merchant types. The
// colour shades are the storefront's, worked out on the server (ThemePalette): data-colors holds them for the
// colour first shown, and data-palette answers with them for each colour picked. Fonts come from data-classic-fonts.
document.addEventListener("alpine:init", function () {
  window.Alpine.data("themePreview", function () {
    return {
      style: "",
      look: "",
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
          var look = form.querySelector("input[name=look]:checked");
          self.look = look ? look.value.toLowerCase() : "souq";
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

// Reports: figures folded away under "Show as a table" print too. They are opened for printing, then closed again.
(function () {
  var opened = [];
  window.addEventListener("beforeprint", function () {
    document.querySelectorAll("details.report-table-toggle:not([open])").forEach(function (details) {
      details.open = true;
      opened.push(details);
    });
  });
  window.addEventListener("afterprint", function () {
    opened.forEach(function (details) { details.open = false; });
    opened = [];
  });
})();
