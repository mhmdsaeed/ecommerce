package com.unispark.ecommerce.merchantadmin.internal.web.settings;

import java.io.InputStream;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.unispark.ecommerce.media.ImageDiscarded;
import com.unispark.ecommerce.media.ImageStore;
import com.unispark.ecommerce.media.ImageStore.Reason;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreSettings;
import com.unispark.ecommerce.tenant.StoreTheme;

/**
 * Applies theme changes for the current store. A replaced or removed logo is discarded from storage after the
 * change commits, like product images.
 */
@Component
class ThemeEditor {

    private final StoreSettings settings;
    private final ImageStore images;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    ThemeEditor(StoreSettings settings, ImageStore images, ApplicationEventPublisher events,
            TransactionTemplate transaction) {
        this.settings = settings;
        this.images = images;
        this.events = events;
        this.transaction = transaction;
    }

    void save(ThemeForm form) {
        var current = settings.current().theme();
        settings.updateTheme(new StoreTheme(blankToNull(form.getPrimaryColor()), current.logoKey(),
                text(form.getHeroTitleAr(), form.getHeroTitleEn()), text(form.getHeroTextAr(), form.getHeroTextEn()),
                form.getFont(), form.isShowCategories(), form.isShowNewProducts()));
    }

    /** Null when stored, else why the upload was refused. */
    Reason replaceLogo(InputStream upload) {
        var result = images.storePublic(TenantContext.requireTenantId(), upload);
        if (result instanceof ImageStore.Rejected(var reason)) {
            return reason;
        }
        setLogo(((ImageStore.Stored) result).key());
        return null;
    }

    void removeLogo() {
        setLogo(null);
    }

    private void setLogo(String key) {
        transaction.executeWithoutResult(status -> {
            var theme = settings.current().theme();
            settings.updateTheme(new StoreTheme(theme.primaryColor(), key, theme.heroTitle(), theme.heroText(),
                    theme.font(), theme.showCategories(), theme.showNewProducts()));
            if (theme.logoKey() != null && !theme.logoKey().equals(key)) {
                events.publishEvent(new ImageDiscarded(theme.logoKey()));
            }
        });
    }

    private static LocalizedText text(String ar, String en) {
        return blankToNull(ar) == null && blankToNull(en) == null ? null : LocalizedText.of(ar, en);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
