package games.brennan.dungeontrain.client.localization.edit;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 CSV, because the translator round trip has to interoperate with
 * {@code scripts/localization/build-review-package.py} / {@code apply-review-csv.py} — which
 * use Python's {@code csv} module — and with whatever spreadsheet a translator opens the file
 * in.
 *
 * <p>Handles the three things that actually matter for this data: embedded commas (English
 * prose is full of them), embedded quotes, and embedded newlines (book variants run to several
 * paragraphs). Everything else a full CSV library offers is unused here.</p>
 */
public final class Csv {

    private static final char DELIMITER = ',';
    private static final char QUOTE = '"';

    private Csv() {}

    /** One CSV row, quoting only the fields that need it. No trailing newline. */
    public static String writeRow(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(DELIMITER);
            }
            sb.append(escape(fields.get(i)));
        }
        return sb.toString();
    }

    private static String escape(String field) {
        String value = field == null ? "" : field;
        boolean needsQuotes = value.indexOf(DELIMITER) >= 0 || value.indexOf(QUOTE) >= 0
            || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return QUOTE + value.replace("\"", "\"\"") + QUOTE;
    }

    /**
     * Parse a whole CSV document into rows of fields.
     *
     * <p>A single scan over the text rather than a split on newlines, because a quoted field may
     * contain them — splitting first is the classic way to mangle exactly the multi-paragraph
     * book text this has to survive.</p>
     */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return rows;
        }
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean rowHasContent = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == QUOTE) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == QUOTE) {
                        field.append(QUOTE);
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            switch (c) {
                case QUOTE -> {
                    inQuotes = true;
                    rowHasContent = true;
                }
                case DELIMITER -> {
                    row.add(field.toString());
                    field.setLength(0);
                    rowHasContent = true;
                }
                case '\r' -> { /* swallowed; the \n that follows ends the row */ }
                case '\n' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    if (rowHasContent || row.size() > 1) {
                        rows.add(row);
                    }
                    row = new ArrayList<>();
                    rowHasContent = false;
                }
                default -> {
                    field.append(c);
                    rowHasContent = true;
                }
            }
        }
        if (rowHasContent || !field.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
