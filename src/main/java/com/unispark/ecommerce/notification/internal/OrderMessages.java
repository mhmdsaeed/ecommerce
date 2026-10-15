package com.unispark.ecommerce.notification.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.jobrunr.scheduling.JobRequestScheduler;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.order.OrderStatusChanged;
import com.unispark.ecommerce.order.PaymentProofRejected;

/**
 * Turns order events into JobRunr jobs (SPEC 12): placed, confirmed, out for delivery, delivered, and a rejected
 * payment proof. The job id is derived from the event, so an event delivered twice (Spring Modulith republishes
 * incomplete ones after a restart) is queued once. If queuing fails, the event stays incomplete and is retried.
 */
@Component
class OrderMessages {

    private final JobRequestScheduler jobs;

    OrderMessages(JobRequestScheduler jobs) {
        this.jobs = jobs;
    }

    @ApplicationModuleListener
    void on(OrderStatusChanged event) {
        var template = event.from() == null ? Template.ORDER_PLACED : switch (event.to()) {
            case CONFIRMED -> Template.ORDER_CONFIRMED;
            case OUT_FOR_DELIVERY -> Template.OUT_FOR_DELIVERY;
            case DELIVERED -> Template.DELIVERED;
            default -> null;
        };
        if (template != null) {
            var id = id("status", event.tenantId(), event.orderId(), event.to().name());
            jobs.enqueue(id, new SendOrderMessages(id, event.tenantId(), event.orderId(), event.number(), template,
                    null));
        }
    }

    @ApplicationModuleListener
    void on(PaymentProofRejected event) {
        var id = id("proof-rejected", event.tenantId(), event.orderId(), String.valueOf(event.at().toEpochMilli()));
        jobs.enqueue(id, new SendOrderMessages(id, event.tenantId(), event.orderId(), event.number(),
                Template.PAYMENT_PROOF_REJECTED, event.note()));
    }

    /** The same event always gives the same id; an order reaches each status once. */
    private static UUID id(String kind, UUID tenantId, UUID orderId, String occurrence) {
        return UUID.nameUUIDFromBytes((kind + ":" + tenantId + ":" + orderId + ":" + occurrence)
                .getBytes(StandardCharsets.UTF_8));
    }
}
