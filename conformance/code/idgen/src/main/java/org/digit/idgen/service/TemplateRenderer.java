package org.digit.idgen.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.digit.idgen.model.ErrorCodes;
import org.digit.idgen.model.TemplateConfig;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;

/**
 * Pure template logic — token substitution, date formats, charset expansion,
 * padding, semantic validation. Direct port of the Go service's generator.go;
 * error messages are kept identical (they are part of the observable contract).
 */
public final class TemplateRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{([^}]+)}");

    /**
     * Spec date keywords (lowercase) → Java patterns. NB: spec "mm" is month, never
     * minutes, so each Java pattern is the keyword itself with "mm" uppercased.
     */
    private static final Map<String, DateTimeFormatter> DATE_FORMATS = buildDateFormats();

    private static Map<String, DateTimeFormatter> buildDateFormats() {
        List<String> keywords = List.of(
                "yyyymmdd", "ddmmyyyy", "mmddyyyy", "yymmdd", "ddmmyy", "mmddyy",
                "yyyy-mm-dd", "dd-mm-yyyy", "mm-dd-yyyy", "yy-mm-dd", "dd-mm-yy",
                "yyyy/mm/dd", "dd/mm/yyyy", "mm/dd/yyyy", "yy/mm/dd", "dd/mm/yy",
                "yyyy.mm.dd", "dd.mm.yyyy", "mm.dd.yyyy", "yy.mm.dd", "dd.mm.yy",
                "mmyyyy", "mm-yyyy", "mm/yyyy", "mm.yyyy",
                "yyyy-mm", "yyyy/mm", "yyyy.mm",
                "yyyy", "yy");
        Map<String, DateTimeFormatter> out = new LinkedHashMap<>();
        for (String keyword : keywords) {
            out.put(keyword, DateTimeFormatter.ofPattern(keyword.replace("mm", "MM")));
        }
        return out;
    }

    /** Substitutes all tokens; SEQ/RAND/DATE: are case-insensitive, variable names are not. */
    public static String render(String pattern, String seqValue, String randValue,
                                Map<String, String> variables, LocalDate today) {
        Matcher m = TOKEN.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String inner = m.group(1);
            String value;
            if (inner.equalsIgnoreCase("SEQ")) {
                value = seqValue;
            } else if (inner.equalsIgnoreCase("RAND")) {
                value = randValue;
            } else if (inner.regionMatches(true, 0, "DATE:", 0, 5)) {
                String key = inner.substring(5);
                DateTimeFormatter f = DATE_FORMATS.get(key.toLowerCase(Locale.ROOT));
                if (f == null) {
                    throw invalidTemplate("unknown date format \"" + key + "\"");
                }
                value = today.format(f);
            } else if (variables != null && variables.containsKey(inner)) {
                // JSON null values render as "" — Go's encoding/json did the same
                String v = variables.get(inner);
                value = v == null ? "" : v;
            } else {
                throw new CustomException(ErrorCodes.UNPROCESSABLE,
                        "id generation failed: no value supplied for variable {" + inner + "}",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** True when the pattern contains a bare {tokenName} token, case-insensitive. */
    public static boolean usesToken(String pattern, String tokenName) {
        Matcher m = TOKEN.matcher(pattern);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase(tokenName)) {
                return true;
            }
        }
        return false;
    }

    /** Left-pads with padding.char to padding.length; plain number when padding is null. */
    public static String formatSequence(long seq, TemplateConfig.Padding padding) {
        String s = Long.toString(seq);
        if (padding == null || s.length() >= padding.length()) {
            return s;
        }
        return padding.character().repeat(padding.length() - s.length()) + s;
    }

    public static String randomString(TemplateConfig.Random random) {
        String charset = expandCharset(random.charset());
        var rnd = ThreadLocalRandom.current();
        StringBuilder b = new StringBuilder(random.length());
        for (int i = 0; i < random.length(); i++) {
            b.append(charset.charAt(rnd.nextInt(charset.length())));
        }
        return b.toString();
    }

    /** Expands "A-Z0-9" style specs; same walk as Go (a trailing lone char after a range is literal). */
    static String expandCharset(String spec) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < spec.length()) {
            if (i + 2 < spec.length() && spec.charAt(i + 1) == '-') {
                char lo = spec.charAt(i);
                char hi = spec.charAt(i + 2);
                if (lo > hi) {
                    throw invalidTemplate("invalid charset range " + lo + "-" + hi + " (start > end)");
                }
                if (!sameCharClass(lo, hi)) {
                    throw invalidTemplate("cross-class charset range " + lo + "-" + hi + " not allowed");
                }
                for (char c = lo; c <= hi; c++) {
                    out.append(c);
                }
                i += 3;
            } else {
                out.append(spec.charAt(i));
                i++;
            }
        }
        if (out.isEmpty()) {
            throw invalidTemplate("charset is empty");
        }
        return out.toString();
    }

    private static boolean sameCharClass(char a, char b) {
        return (a >= 'A' && a <= 'Z' && b >= 'A' && b <= 'Z')
                || (a >= 'a' && a <= 'z' && b >= 'a' && b <= 'z')
                || (a >= '0' && a <= '9' && b >= '0' && b <= '9');
    }

    /** Semantic validation of a normalized config (charset, padding vs start, date keywords). */
    public static void validate(TemplateConfig config) {
        if (config.random() != null && config.random().charset() != null && !config.random().charset().isEmpty()) {
            expandCharset(config.random().charset());
        }
        if (config.sequence() != null && config.sequence().padding() != null) {
            int startDigits = String.valueOf(config.sequence().start()).length();
            if (config.sequence().padding().length() < startDigits) {
                throw invalidTemplate("padding length (" + config.sequence().padding().length()
                        + ") is shorter than start value digits (" + startDigits + ") — padding has no effect");
            }
        }
        Matcher m = TOKEN.matcher(config.template());
        while (m.find()) {
            String inner = m.group(1);
            if (inner.regionMatches(true, 0, "DATE:", 0, 5)) {
                String key = inner.substring(5);
                if (!DATE_FORMATS.containsKey(key.toLowerCase(Locale.ROOT))) {
                    throw invalidTemplate("unknown date format \"" + key + "\"");
                }
            }
        }
    }

    private static CustomException invalidTemplate(String detail) {
        return new CustomException(ErrorCodes.INVALID_REQUEST,
                "invalid template config: " + detail, HttpStatus.BAD_REQUEST);
    }

    private TemplateRenderer() {
    }
}
