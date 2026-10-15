package com.unispark.ecommerce.notification.internal;

import java.util.UUID;

import org.jobrunr.jobs.lambdas.JobRequest;

/**
 * The JobRunr job for one order event (SPEC 12, 14). Carries the store, since jobs run outside any request.
 * Public only because JobRunr stores and loads it.
 *
 * @param messageId names the event; the job's id and the prefix of each message's dedupe key
 * @param note      staff's reason, for a rejected payment proof; null otherwise
 */
public record SendOrderMessages(UUID messageId, UUID tenantId, UUID orderId, int number, Template template,
        String note) implements JobRequest {

    @Override
    public Class<SendOrderMessagesHandler> getJobRequestHandler() {
        return SendOrderMessagesHandler.class;
    }
}
