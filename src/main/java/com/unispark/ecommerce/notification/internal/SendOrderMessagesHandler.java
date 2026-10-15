package com.unispark.ecommerce.notification.internal;

import java.util.ArrayList;

import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.identity.StaffContacts;
import com.unispark.ecommerce.order.Orders;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreSettings;

/**
 * Renders and sends the messages of one order event, in the event's store: the shopper's text, and for a new order
 * an email to each owner and manager. If any message fails, the job fails and JobRunr retries it with back-off;
 * messages already sent are skipped (see {@link Deliveries}). Public only because JobRunr invokes it.
 */
@Component
public class SendOrderMessagesHandler implements JobRequestHandler<SendOrderMessages> {

    private static final Logger log = LoggerFactory.getLogger(SendOrderMessagesHandler.class);

    private final Orders orders;
    private final StoreSettings settings;
    private final StaffContacts staff;
    private final NotificationTexts texts;
    private final Deliveries deliveries;

    SendOrderMessagesHandler(Orders orders, StoreSettings settings, StaffContacts staff, NotificationTexts texts,
            Deliveries deliveries) {
        this.orders = orders;
        this.settings = settings;
        this.staff = staff;
        this.texts = texts;
        this.deliveries = deliveries;
    }

    @Override
    public void run(SendOrderMessages job) {
        TenantContext.runAs(job.tenantId(), () -> send(job));
    }

    private void send(SendOrderMessages job) {
        var found = orders.findByNumber(job.number()).filter(order -> order.id().equals(job.orderId()));
        if (found.isEmpty()) {
            log.warn("Order {} of store {} not found; nothing to send", job.number(), job.tenantId());
            return;
        }
        var order = found.get();
        var store = settings.current();
        var messages = new ArrayList<Addressed>();
        messages.add(new Addressed(job.template(), texts.toShopper(job.template(), order, store, job.note())));
        if (job.template() == Template.ORDER_PLACED) {
            staff.managerEmails().forEach(email ->
                    messages.add(new Addressed(Template.NEW_ORDER, texts.newOrder(order, store, email))));
        }
        var failed = messages.stream()
                .filter(addressed -> !deliveries.deliver(job.messageId() + ":" + addressed.template().code() + ":"
                        + addressed.message().to(), addressed.template(), order.id(), addressed.message()))
                .count();
        if (failed > 0) {
            throw new IllegalStateException(failed + " of " + messages.size() + " messages about order "
                    + order.number() + " not sent; retrying");
        }
    }

    private record Addressed(Template template, Message message) {
    }
}
