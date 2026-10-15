package com.unispark.ecommerce.notification.internal;

import com.unispark.ecommerce.notification.NotificationLog.Channel;
import com.unispark.ecommerce.notification.internal.Message.Medium;

/**
 * A way of reaching people (SPEC 12). {@link ChannelRouter} picks, per medium, the first configured provider and
 * falls back to the logging channel.
 */
sealed interface NotificationChannel permits EmailChannel, WhatsAppChannel, SmsChannel, LoggingChannel {

    Channel kind();

    /** Whether this channel is configured to carry messages of the medium. */
    boolean carries(Medium medium);

    /**
     * Sends or throws; a thrown message counts as not sent and is retried.
     *
     * @return the provider's reference for the message, or null if it gives none
     */
    String send(Message message);
}
