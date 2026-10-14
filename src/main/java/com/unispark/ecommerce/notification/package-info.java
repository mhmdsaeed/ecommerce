/**
 * Messages to shoppers and merchants (SPEC 3, 12). M8 sends one-time codes through the logging channel; templates,
 * email and queued delivery with a log table arrive in M11.
 */
@ApplicationModule(displayName = "Notification")
package com.unispark.ecommerce.notification;

import org.springframework.modulith.ApplicationModule;
