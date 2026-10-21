package com.unispark.ecommerce.notification.internal;

import java.util.HashMap;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import com.unispark.ecommerce.identity.ResetLinkSender.PasswordChanged;
import com.unispark.ecommerce.identity.ResetLinkSender.ResetLink;
import com.unispark.ecommerce.identity.StaffPasswords;
import com.unispark.ecommerce.order.Orders.Address;
import com.unispark.ecommerce.order.Orders.OrderView;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.pricing.PriceFormatter;
import com.unispark.ecommerce.tenant.StoreSettings.Settings;

/**
 * Renders the templates (SPEC 12) in the store's language and digits: short texts from the message bundles for
 * phones, and the merchants' new-order email from {@code templates/notification/new-order.html}. Shoppers have no
 * language of their own on record, so they get the store's default.
 */
@Component
class NotificationTexts {

    private final MessageSource messages;
    private final PriceFormatter prices;
    private final ITemplateEngine templates;
    private final String adminOrders;
    private final String passwordReset;

    NotificationTexts(MessageSource messages, PriceFormatter prices, ITemplateEngine templates,
            NotificationProperties properties, @Value("${app.base-domain}") String baseDomain) {
        this.messages = messages;
        this.prices = prices;
        this.templates = templates;
        this.adminOrders = properties.linkScheme() + "://" + baseDomain.strip() + "/admin/orders/";
        this.passwordReset = properties.linkScheme() + "://" + baseDomain.strip() + "/admin/password/reset";
    }

    /** The language and digits messages of the store are written in. */
    static Locale localeOf(Settings settings) {
        return settings.numberDigits().apply(settings.defaultLocale());
    }

    Message code(String phone, String code, String storeName, Locale locale) {
        return Message.phone(phone, text("notification.otp", locale, code, storeName));
    }

    /** A password reset link, by email or text: whichever the member asked with. Plain text, no HTML. */
    Message resetLink(ResetLink link) {
        var url = passwordReset + "?store=" + link.storeSlug() + "&token=" + link.token();
        var minutes = StaffPasswords.LINK_LIFETIME.toMinutes();
        if (link.email() != null) {
            return Message.email(link.email(),
                    text("notification.password_reset.subject", link.locale(), link.storeName()),
                    text("notification.password_reset.email", link.locale(), link.storeName(), url, minutes), null);
        }
        return Message.phone(link.phone(),
                text("notification.password_reset", link.locale(), link.storeName(), url, minutes));
    }

    /** That a member's password changed, by email or text. Plain text, no HTML. */
    Message passwordChanged(PasswordChanged notice) {
        if (notice.email() != null) {
            return Message.email(notice.email(),
                    text("notification.password_changed.subject", notice.locale(), notice.storeName()),
                    text("notification.password_changed.email", notice.locale(), notice.storeName()), null);
        }
        return Message.phone(notice.phone(), text("notification.password_changed", notice.locale(), notice.storeName()));
    }

    /**
     * @param note staff's reason, for a rejected payment proof; null for none
     */
    Message toShopper(Template template, OrderView order, Settings settings, String note) {
        var locale = localeOf(settings);
        var store = settings.name().in(locale);
        var number = String.valueOf(order.number());
        var text = switch (template) {
            case ORDER_PLACED -> text("notification.order_placed", locale, store, number, total(order, locale));
            case ORDER_CONFIRMED -> text("notification.order_confirmed", locale, store, number);
            case OUT_FOR_DELIVERY -> order.paymentMethod() == PaymentMethod.COD
                    ? text("notification.out_for_delivery.cod", locale, store, number, total(order, locale))
                    : text("notification.out_for_delivery", locale, store, number);
            case DELIVERED -> text("notification.delivered", locale, store, number);
            case PAYMENT_PROOF_REJECTED -> note == null
                    ? text("notification.payment_proof_rejected", locale, store, number)
                    : text("notification.payment_proof_rejected.reason", locale, store, number, note);
            case OTP, NEW_ORDER, PASSWORD_RESET, PASSWORD_CHANGED ->
                    throw new IllegalArgumentException("Not a shopper message about an order: " + template);
        };
        return Message.phone(order.customerPhone(), text);
    }

    /** The merchants' alert about a new order, with what to prepare and a link to it in the admin. */
    Message newOrder(OrderView order, Settings settings, String email) {
        var locale = localeOf(settings);
        var store = settings.name().in(locale);
        var number = String.valueOf(order.number());
        var total = total(order, locale);
        var method = messages.getMessage("payment.method." + order.paymentMethod().name(), null, locale);
        var link = adminOrders + order.number();
        var subject = text("notification.new_order.subject", locale, store, number, total);
        var plain = text("notification.new_order.text", locale, store, number, order.customerName(),
                order.customerPhone(), total, method, link);

        var variables = new HashMap<String, Object>();
        variables.put("store", store);
        variables.put("order", order);
        variables.put("total", total);
        variables.put("subtotal", prices.format(order.subtotal(), locale));
        variables.put("deliveryFee", prices.format(order.deliveryFee(), locale));
        variables.put("lines", order.items().stream().map(item -> new Line(item.name().in(locale),
                item.options().stream().map(option -> option.value().in(locale)).collect(Collectors.joining(" / ")),
                item.quantity(), prices.format(item.lineTotal(), locale))).toList());
        variables.put("address", address(order.address(), locale));
        variables.put("link", link);
        var html = templates.process("notification/new-order", new Context(locale, variables));
        return Message.email(email, subject, plain, html);
    }

    /** An order line as the email shows it. */
    record Line(String name, String options, int quantity, String total) {
    }

    /** Governorate, city, area, landmark and details, as far as given, in one line. */
    private String address(Address address, Locale locale) {
        var parts = Stream.of(messages.getMessage("governorate." + address.governorate().name(), null, locale),
                address.city(), address.area(), address.landmark(), address.details());
        return parts.filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(messages.getMessage("notification.new_order.separator", null, locale) + " "));
    }

    private String total(OrderView order, Locale locale) {
        return prices.format(order.total(), locale);
    }

    private String text(String key, Locale locale, Object... args) {
        return messages.getMessage(key, args, locale);
    }
}
