/*
 * AI SQL for DBeaver — тесты эвристической детекции ПДн (PiiDetector).
 *
 * Проверяются распознавание форматов с контрольными суммами (карта — Лун,
 * ИНН, СНИЛС), отсутствие ложных срабатываний на обычные числа и замена.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.core.PiiDetector;

/**
 * Проверки PiiDetector.
 */
public final class PiiDetectorTest {

    private PiiDetectorTest() {
    }

    public static void run() {
        T.section("PiiDetector (ПДн по значению)");

        // --- Email.
        checkPii("ivanov@example.com");
        checkPii("user.name+tag@sub.domain.org");
        checkClean("order_items");
        checkClean("id_12345");

        // --- Телефоны.
        checkPii("+7 (912) 345-67-89");
        checkPii("89123456789");
        checkPii("+442071838750");
        checkClean("20240101");          // дата как число — не телефон
        checkClean("42");                 // слишком коротко

        // --- Банковские карты (Лун).
        checkPii("4111111111111111");            // Visa test, Лун корректен
        checkPii("5500 0000 0000 0004");         // Mastercard test, с пробелами
        checkPii("4111-1111-1111-1111");
        checkClean("4111111111111112");           // Лун НЕ сходится
        checkClean("1234567812345678");           // Лун не сходится
        checkClean("123456789012345678901");      // > 19 цифр

        // --- ИНН (контрольная сумма).
        checkPii("7707083893");           // валидный ИНН-10
        checkPii("7830002293");           // валидный ИНН-10
        checkPii("500100732259");         // валидный ИНН-12
        checkClean("7707083894");         // битая контрольная
        checkClean("1234567890");         // битая контрольная
        checkClean("12345678901");        // 11 цифр — не ИНН

        // --- СНИЛС (контрольная сумма).
        checkPii("112-233-445 95");       // валидный СНИЛС
        checkPii("08765430300");          // валидный СНИЛС слитно
        checkClean("11223344596");        // битая контрольная

        // --- Обычные значения не трогаются.
        checkClean("ACTIVE");
        checkClean("2024-01-15 10:30:00");
        checkClean("RUB");
        checkClean(null);

        // --- Замена.
        T.eq("[ПДн]", PiiDetector.mask("ivanov@example.com", "[ПДн]"),
            "email заменяется на шаблон");
        T.eq("ACTIVE", PiiDetector.mask("ACTIVE", "[ПДн]"),
            "обычное значение не заменяется");
        T.eq(null, PiiDetector.mask(null, "[ПДн]"), "null не трогается");

        // --- Лун напрямую.
        T.check(PiiDetector.luhnValid("4111111111111111"), "Лун: корректный номер");
        T.check(!PiiDetector.luhnValid("4111111111111112"), "Лун: некорректный номер");
    }

    private static void checkPii(String value) {
        T.check(PiiDetector.isPii(value), "распознаётся как ПДн: " + value);
    }

    private static void checkClean(String value) {
        T.check(!PiiDetector.isPii(value), "НЕ распознаётся как ПДн: " + value);
    }
}
