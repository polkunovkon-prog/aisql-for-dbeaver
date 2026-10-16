/*
 * AI SQL for DBeaver — минимальные проверки без JUnit.
 *
 * Тесты запускаются обычной JVM с jar-ами DBeaver на classpath:
 *   ./releng/test.sh
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.tests;

import java.util.ArrayList;
import java.util.List;

/**
 * Простой сборщик результатов проверок.
 */
public final class T {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    private T() {
    }

    public static void section(String name) {
        System.out.println("--- " + name);
    }

    public static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            failures.add(message);
            System.out.println("  FAIL: " + message);
        }
    }

    public static void eq(Object expected, Object actual, String message) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(ok, message + " (ожидалось: " + expected + ", получено: " + actual + ")");
    }

    public static int summary() {
        System.out.println();
        System.out.println("Проверок: " + (passed + failed)
            + ", успешно: " + passed + ", ошибок: " + failed);
        for (String f : failures) {
            System.out.println("  • " + f);
        }
        return failed == 0 ? 0 : 1;
    }
}
