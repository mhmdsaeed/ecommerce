package com.unispark.ecommerce.report;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Report tables as CSV files: UTF-8 with a byte order mark, which Excel needs to read Arabic; CRLF line ends; cells
 * quoted when they hold a comma, quote or line break.
 */
public final class Csv {

    private Csv() {
    }

    public static byte[] encode(List<List<String>> rows) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        var text = rows.stream()
                .map(row -> row.stream().map(Csv::cell).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\r\n", "", "\r\n"));
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /**
     * Quoted when needed. A cell starting with =, +, - or @ gets a leading apostrophe, so a spreadsheet shows it as
     * text instead of running it as a formula (names and reasons are typed by shoppers and staff).
     */
    public static String cell(String value) {
        var text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0 && !text.matches("-?\\d+(\\.\\d+)?")) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
