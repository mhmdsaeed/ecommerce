/**
 * Messages to shoppers and merchants (SPEC 3, 12). Order events are turned into JobRunr jobs that render the
 * templates in the store's language and send them through the configured channel (email over SMTP; SMS and
 * WhatsApp adapters once a provider is chosen; the application log until then). Every message is recorded in
 * {@code notification_log}. Phone verification codes are sent at once through the same channels.
 */
@ApplicationModule(displayName = "Notification")
package com.unispark.ecommerce.notification;

import org.springframework.modulith.ApplicationModule;
