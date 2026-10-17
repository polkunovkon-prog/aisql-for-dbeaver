/*
 * AI SQL for DBeaver — профили безопасности подключений.
 *
 * Настройки (ai-sql-plugin.properties):
 *   policy.defaultProfile=STANDARD          профиль по умолчанию
 *   policy.profile.<glob>=READ_ONLY         профиль для подключений по маске
 *                                           (поддерживаются * и ?)
 *
 * Профили:
 *   READ_ONLY  — разрешены только операторы чтения;
 *   STANDARD   — все операции, но действуют правила policy.forbidden
 *                и маскирование данных; (по умолчанию)
 *   FULL       — никаких дополнительных ограничений со стороны плагина
 *                (правила и маскирование не применяются).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Разрешение профиля безопасности для подключения.
 */
public final class ConnectionProfiles {

    public static final String KEY_DEFAULT = "policy.defaultProfile";
    public static final String KEY_PREFIX = "policy.profile.";
    public static final Profile DEFAULT_PROFILE = Profile.STANDARD;

    /** Профиль безопасности подключения. */
    public enum Profile {
        READ_ONLY,
        STANDARD,
        FULL;

        public static Profile parse(String value, Profile def) {
            if (value == null) {
                return def;
            }
            switch (value.trim().toUpperCase(Locale.ROOT)) {
                case "READ_ONLY":
                case "READONLY":
                case "READ-ONLY":
                    return READ_ONLY;
                case "STANDARD":
                    return STANDARD;
                case "FULL":
                    return FULL;
                default:
                    return def;
            }
        }
    }

    private ConnectionProfiles() {
    }

    /** Профиль для указанного подключения (по имени подключения в DBeaver). */
    public static Profile resolve(String connectionName) {
        Properties p = AisqlCore.loadSettings();
        Profile def = Profile.parse(p.getProperty(KEY_DEFAULT), DEFAULT_PROFILE);
        if (connectionName == null || connectionName.isBlank()) {
            return def;
        }
        Profile direct = exact(p, connectionName);
        if (direct != null) {
            return direct;
        }
        // Шаблоны: применяется первый по алфавиту подходящий;
        // точное совпадение обработано выше.
        List<String> keys = new ArrayList<>(p.stringPropertyNames());
        Collections.sort(keys);
        for (String key : keys) {
            if (!key.startsWith(KEY_PREFIX)) {
                continue;
            }
            String pattern = key.substring(KEY_PREFIX.length());
            if (pattern.isEmpty() || pattern.equals(connectionName)) {
                continue;
            }
            if (Globs.match(pattern, connectionName)) {
                return Profile.parse(p.getProperty(key), def);
            }
        }
        return def;
    }

    public static String describe(Profile profile) {
        if (profile == null) {
            return "STANDARD";
        }
        switch (profile) {
            case READ_ONLY:
                return "READ_ONLY (только чтение: SELECT/SHOW/EXPLAIN/VALUES; любые изменения запрещены)";
            case FULL:
                return "FULL (ограничения плагина отключены: правила и маскирование не применяются)";
            case STANDARD:
            default:
                return "STANDARD (все операции; действуют policy.forbidden и маскирование данных)";
        }
    }

    private static Profile exact(Properties p, String name) {
        String v = p.getProperty(KEY_PREFIX + name);
        if (v == null) {
            return null;
        }
        return Profile.parse(v, null);
    }
}
