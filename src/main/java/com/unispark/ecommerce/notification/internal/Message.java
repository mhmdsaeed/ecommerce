package com.unispark.ecommerce.notification.internal;

/**
 * One rendered message for one recipient.
 *
 * @param to      an email address or an E.164 phone number, as {@code medium} says
 * @param subject for email; null for phone messages
 * @param html    the email body; null for phone messages, which are {@code text} only
 */
record Message(Medium medium, String to, String subject, String text, String html) {

    enum Medium {
        EMAIL, PHONE
    }

    static Message phone(String to, String text) {
        return new Message(Medium.PHONE, to, null, text, null);
    }

    static Message email(String to, String subject, String text, String html) {
        return new Message(Medium.EMAIL, to, subject, text, html);
    }
}
