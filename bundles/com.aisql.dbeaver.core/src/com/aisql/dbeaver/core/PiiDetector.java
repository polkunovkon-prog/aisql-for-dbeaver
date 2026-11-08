/*
 * AI SQL for DBeaver — эвристическая детекция ПДн в значениях ячеек.
 *
 * Дополняет правила маскирования по именам колонок (MaskingRules): значения,
 * похожие на персональные данные, скрываются независимо от того, как названа
 * колонка. Это снижает риск утечки при SELECT *, JOIN и вычислимых колонках,
 * где имя колонки не подсказывает, что внутри — email или номер карты.
 *
 * Распознаваемые форматы (с проверкой контрольных сумм, чтобы не трогать
 * обычные числа):
 *   - email (RFC-подобная эвристика);
 *   - номер банковской карты: 13–19 цифр, группами или слитно, Луна обязателен;
 *   - ИНН (10/12 цифр, контрольная сумма);
 *   - СНИЛС (11 цифр, контрольная сумма);
 *   - телефон: +7/8 и международный E.164, допускаются пробелы/дефисы/скобки.
 *
 * Настройки:
 *   mask.detectPII=true          включить детекцию (по умолчанию — вкл)
 *   mask.piiReplacement=[ПДн]    замена
 *
 * Детекция действует в профилях READ_ONLY/STANDARD (как и маскирование
 * по правилам) и отключается в FULL.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core;

import java.util.regex.Pattern;

/**
 * Детекция и маскирование персональных данных в текстовых значениях.
 */
public final class PiiDetector {

    private static final Pattern EMAIL = Pattern.compile(
        "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** Телефон: РФ (+7/8 и разделители) либо международный E.164 строго с «+». */
    private static final Pattern PHONE = Pattern.compile(
        "(?:\\+7|8)[\\s(-]{0,2}\\d{3}[\\s)-]{0,2}\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}"
            + "|\\+\\d{7,15}");
    /** 13–19 цифр, разделённые пробелами/дефисами на группы по 4 (или слитно). */
    private static final Pattern CARD = Pattern.compile(
        "(?:\\d[\\s-]?){12,18}\\d");

    private PiiDetector() {
    }

    /**
     * Маскирует значение, если оно похоже на ПДн. Не-строковые значения
     * и null не трогаются. Возвращает исходное значение либо замену.
     */
    public static String mask(String value, String replacement) {
        if (value == null || value.length() < 7) {
            // короче 7 символов ничего распознаваемого нет (даже телефон РФ)
            return value;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return value;
        }
        if (isEmail(v) || isPhone(v) || isCardNumber(v) || isInn(v) || isSnils(v)) {
            return replacement;
        }
        return value;
    }

    /** Похоже ли значение на ПДн (для тестов и пометок в аудите). */
    public static boolean isPii(String value) {
        if (value == null || value.length() < 7) {
            return false;
        }
        String v = value.trim();
        return !v.isEmpty() && (isEmail(v) || isPhone(v) || isCardNumber(v) || isInn(v) || isSnils(v));
    }

    static boolean isEmail(String v) {
        return EMAIL.matcher(v).matches();
    }

    static boolean isPhone(String v) {
        if (!PHONE.matcher(v).matches()) {
            return false;
        }
        // Групым цифрам без «+»/8-префикса телефон не соответствовал бы вовсе;
        // дополнительно требуем ≥ 10 цифр, чтобы не трогать короткие коды.
        return countDigits(v) >= 10;
    }

    static boolean isCardNumber(String v) {
        String digits = digitsOnly(v);
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        // Группы по 4 (или слитно) — отсекает случайные 16-значные числа
        // с произвольной расстановкой разделителей.
        if (!v.matches("(?:\\d{4}[\\s-]?)+\\d{1,4}|\\d{13,19}")) {
            return false;
        }
        return luhnValid(digits);
    }

    static boolean isInn(String v) {
        String digits = digitsOnly(v);
        if (digits.length() == 10) {
            int[] c = digits.chars().map(ch -> ch - '0').toArray();
            int sum = c[0] * 2 + c[1] * 4 + c[2] * 10 + c[3] * 3 + c[4] * 5
                + c[5] * 9 + c[6] * 4 + c[7] * 6 + c[8] * 8;
            return sum % 11 % 10 == c[9];
        }
        if (digits.length() == 12) {
            int[] c = digits.chars().map(ch -> ch - '0').toArray();
            int n11 = (c[0] * 7 + c[1] * 2 + c[2] * 4 + c[3] * 10 + c[4] * 3
                + c[5] * 5 + c[6] * 9 + c[7] * 4 + c[8] * 6 + c[9] * 8) % 11 % 10;
            if (n11 != c[10]) {
                return false;
            }
            int n12 = (c[0] * 3 + c[1] * 7 + c[2] * 2 + c[3] * 4 + c[4] * 10
                + c[5] * 3 + c[6] * 5 + c[7] * 9 + c[8] * 4 + c[9] * 6 + c[10] * 8) % 11 % 10;
            return n12 == c[11];
        }
        return false;
    }

    static boolean isSnils(String v) {
        String digits = digitsOnly(v);
        if (digits.length() != 11) {
            return false;
        }
        int[] c = digits.chars().map(ch -> ch - '0').toArray();
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += c[i] * (9 - i);
        }
        if (sum == 100 || sum == 101) {
            sum = 0;
        } else if (sum > 101) {
            sum = sum % 101;
        }
        return sum == c[9] * 10 + c[10];
    }

    /** Алгоритм Луна для номеров банковских карт. */
    public static boolean luhnValid(String digits) {
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (d < 0 || d > 9) {
                return false;
            }
            if (alt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    private static String digitsOnly(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch >= '0' && ch <= '9') {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static int countDigits(String v) {
        int n = 0;
        for (int i = 0; i < v.length(); i++) {
            if (Character.isDigit(v.charAt(i))) {
                n++;
            }
        }
        return n;
    }
}
