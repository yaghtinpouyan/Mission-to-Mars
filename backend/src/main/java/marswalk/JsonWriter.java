package marswalk;

import java.util.ArrayDeque;

/** Tiny JSON writer (no external libraries). NaN and infinity are written as null. */
public final class JsonWriter {
    private final StringBuilder sb = new StringBuilder(4096);
    private final ArrayDeque<Boolean> needComma = new ArrayDeque<>();
    private boolean afterName = false;

    private void sep() {
        if (afterName) { afterName = false; return; }
        if (!needComma.isEmpty()) { if (needComma.peek()) sb.append(','); needComma.pop(); needComma.push(true); }
    }
    public JsonWriter beginObject() { sep(); sb.append('{'); needComma.push(false); return this; }
    public JsonWriter endObject() { sb.append('}'); needComma.pop(); return this; }
    public JsonWriter beginArray() { sep(); sb.append('['); needComma.push(false); return this; }
    public JsonWriter endArray() { sb.append(']'); needComma.pop(); return this; }
    public JsonWriter name(String n) { sep(); string(n); sb.append(':'); afterName = true; return this; }

    public JsonWriter value(String s) { sep(); if (s == null) sb.append("null"); else string(s); return this; }
    public JsonWriter value(boolean b) { sep(); sb.append(b); return this; }
    public JsonWriter value(long v) { sep(); sb.append(v); return this; }
    public JsonWriter value(double v, int decimals) { sep(); fixed(v, decimals); return this; }
    public JsonWriter nul() { sep(); sb.append("null"); return this; }

    public JsonWriter field(String n, String v) { return name(n).value(v); }
    public JsonWriter field(String n, boolean v) { return name(n).value(v); }
    public JsonWriter field(String n, long v) { return name(n).value(v); }
    public JsonWriter field(String n, double v, int decimals) { return name(n).value(v, decimals); }

    private void fixed(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) { sb.append("null"); return; }
        double scale = Math.pow(10, decimals);
        long scaled = Math.round(Math.abs(v) * scale);
        if (v < 0 && scaled != 0) sb.append('-');
        String digits = Long.toString(scaled);
        if (decimals == 0) { sb.append(digits); return; }
        while (digits.length() <= decimals) digits = "0" + digits;
        sb.append(digits, 0, digits.length() - decimals).append('.').append(digits.substring(digits.length() - decimals));
    }

    private void string(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }

    @Override public String toString() { return sb.toString(); }
}
