package com.ba.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 --key value 解析器。
 * <p>支持 {@code --key value}、{@code --key=value}、以及无值的 {@code --flag}（记为 "true"）。
 */
final class Args {

    private final Map<String, String> opts = new LinkedHashMap<>();

    Args(String[] raw) {
        for (int i = 0; i < raw.length; i++) {
            String a = raw[i];
            if (a == null || !a.startsWith("--")) continue;
            String key = a.substring(2);
            int eq = key.indexOf('=');
            if (eq > 0) {
                opts.put(key.substring(0, eq), key.substring(eq + 1));
            } else if (i + 1 < raw.length && raw[i + 1] != null && !raw[i + 1].startsWith("--")) {
                opts.put(key, raw[++i]);
            } else {
                opts.put(key, "true");
            }
        }
    }

    String get(String key, String def) {
        return opts.getOrDefault(key, def);
    }

    int getInt(String key, int def) {
        String v = opts.get(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    double getDouble(String key, double def) {
        String v = opts.get(key);
        if (v == null) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    boolean has(String key) {
        return opts.containsKey(key);
    }
}
