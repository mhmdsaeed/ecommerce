/**
 * SaaS billing (SPEC 3, 8): plans, one subscription per store, invoices and payments the platform admin records by
 * hand (no gateway). Every store starts on a trial; a daily job bills each month in advance, gives a grace period
 * and suspends stores that have not paid, and recording the payment reopens them. Platform tables only; merchants
 * see their own store's subscription and invoices.
 */
@ApplicationModule(displayName = "Billing")
package com.unispark.ecommerce.billing;

import org.springframework.modulith.ApplicationModule;
