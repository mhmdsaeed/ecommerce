package com.unispark.ecommerce.merchantadmin.internal.web.settings;

import java.io.IOException;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.unispark.ecommerce.media.ImageSize;
import com.unispark.ecommerce.media.ImageStore;
import com.unispark.ecommerce.tenant.StoreSettings;
import com.unispark.ecommerce.tenant.StoreTheme.FontChoice;

/** Storefront look (SPEC 9.3, 10): colour, logo, hero text, font and home sections. Owners and managers only. */
@Controller
@RequestMapping("/admin/settings/theme")
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
class ThemeSettingsController {

    static final String PAGE = "merchantadmin/settings/theme";

    private final StoreSettings settings;
    private final ThemeEditor editor;
    private final ImageStore images;

    ThemeSettingsController(StoreSettings settings, ThemeEditor editor, ImageStore images) {
        this.settings = settings;
        this.editor = editor;
        this.images = images;
    }

    @GetMapping
    String page(Model model) {
        model.addAttribute("form", ThemeForm.of(settings.current().theme()));
        return render(model);
    }

    @PostMapping
    String save(@Valid @ModelAttribute("form") ThemeForm form, BindingResult binding, Model model) {
        if (binding.hasErrors()) {
            return render(model);
        }
        editor.save(form);
        return "redirect:/admin/settings/theme?saved";
    }

    @PostMapping("/logo")
    String uploadLogo(@RequestParam(name = "logo", required = false) MultipartFile logo, RedirectAttributes redirect)
            throws IOException {
        if (logo == null || logo.isEmpty()) {
            redirect.addFlashAttribute("logoError", "image.none-chosen");
            return "redirect:/admin/settings/theme";
        }
        try (var input = logo.getInputStream()) {
            var refused = editor.replaceLogo(input);
            if (refused != null) {
                redirect.addFlashAttribute("logoError", "image." + refused.name());
                return "redirect:/admin/settings/theme";
            }
        }
        return "redirect:/admin/settings/theme?logo";
    }

    @PostMapping("/logo/delete")
    String removeLogo() {
        editor.removeLogo();
        return "redirect:/admin/settings/theme?logo";
    }

    private String render(Model model) {
        var logoKey = settings.current().theme().logoKey();
        model.addAttribute("logoUrl", logoKey == null ? null : images.url(logoKey, ImageSize.SMALL));
        model.addAttribute("fonts", FontChoice.values());
        return PAGE;
    }
}
