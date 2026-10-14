package com.unispark.ecommerce.shared;

/** Arabic-Indic (٠-٩) and Persian (۰-۹) digits, as typed on many Syrian keyboards, to ASCII digits. */
public final class Digits {

    private Digits() {
    }

    public static String toAscii(String value) {
        var result = new StringBuilder(value.length());
        for (var ch : value.toCharArray()) {
            if (ch >= '٠' && ch <= '٩') {
                result.append((char) ('0' + ch - '٠'));
            } else if (ch >= '۰' && ch <= '۹') {
                result.append((char) ('0' + ch - '۰'));
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }
}
