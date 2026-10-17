/*
 * AI SQL for DBeaver — простые glob-шаблоны (* и ?) без учёта регистра.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core;

import java.util.regex.Pattern;

/**
 * Сопоставление имён с glob-шаблонами: {@code *} — любая последовательность,
 * {@code ?} — один символ. Сопоставление без учёта регистра.
 */
public final class Globs {

    private Globs() {
    }

    public static boolean match(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        StringBuilder re = new StringBuilder(pattern.length() + 8);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*':
                    re.append(".*");
                    break;
                case '?':
                    re.append('.');
                    break;
                default:
                    re.append(Pattern.quote(String.valueOf(c)));
                    break;
            }
        }
        return Pattern.compile("^" + re + "$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            .matcher(value).matches();
    }

    /**
     * Сопоставление «имя таблицы или колонки» с шаблоном вида {@code schema.table}.
     * Шаблон без схемы ({@code users}) совпадает и с {@code users}, и с {@code public.users}.
     */
    public static boolean matchQualified(String pattern, String entityName) {
        if (pattern == null || entityName == null) {
            return false;
        }
        if (match(pattern, entityName)) {
            return true;
        }
        int dot = entityName.lastIndexOf('.');
        if (dot >= 0) {
            return match(pattern, entityName.substring(dot + 1));
        }
        return false;
    }
}
