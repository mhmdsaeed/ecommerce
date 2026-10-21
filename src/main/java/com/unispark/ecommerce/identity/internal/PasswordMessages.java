package com.unispark.ecommerce.identity.internal;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.unispark.ecommerce.identity.ResetLinkSender;
import com.unispark.ecommerce.identity.ResetLinkSender.PasswordChanged;
import com.unispark.ecommerce.identity.ResetLinkSender.ResetLink;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * Sends staff password messages on another thread, once the transaction that made them has committed: a reset
 * request then takes as long whether or not the account exists, and a message never goes out for a change that was
 * rolled back. Not through application events: those are stored, and a reset link must not be.
 */
@Component
class PasswordMessages {

    private static final Logger log = LoggerFactory.getLogger(PasswordMessages.class);

    private final ResetLinkSender sender;
    private final Executor executor;

    PasswordMessages(ResetLinkSender sender, @Qualifier("applicationTaskExecutor") Executor executor) {
        this.sender = sender;
        this.executor = executor;
    }

    /** Work a request should not wait for, such as making a reset link, so its timing reveals nothing. */
    void inBackground(Runnable work) {
        executor.execute(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                log.warn("Password work in the background failed", e);
            }
        });
    }

    void resetLink(ResetLink link) {
        later(() -> sender.send(link));
    }

    void passwordChanged(PasswordChanged notice) {
        later(() -> sender.passwordChanged(notice));
    }

    /** In the current store, which the message is logged under. */
    private void later(Runnable send) {
        var storeId = TenantContext.requireTenantId();
        Runnable task = () -> {
            try {
                TenantContext.runAs(storeId, send);
            } catch (RuntimeException e) {
                log.warn("Could not send a password message for store {}", storeId, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(task);
                }
            });
        } else {
            executor.execute(task);
        }
    }
}
