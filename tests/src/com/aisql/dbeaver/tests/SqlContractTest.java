/*
 * AI SQL for DBeaver — тесты контракта результата SQL-запроса.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.core.SqlContract;

import java.util.List;

/**
 * Проверки контракта: описание для промпта и сверка фактических строк.
 */
public final class SqlContractTest {

    private SqlContractTest() {
    }

    public static void run() {
        T.section("SqlContract");

        SqlContract c = new SqlContract("hw-2", "hogwarts", "Студенты по факультетам", 2, 4);
        c.columns.add(new SqlContract.Column("house_code", "string", true));
        c.columns.add(new SqlContract.Column("students_total", "integer", true));
        c.checks.add(new SqlContract.Check("students_total", 900.0, null));

        T.eq(2, c.columnSpecs().size(), "две спецификации колонок");
        String d = c.describe();
        T.check(d.contains("house_code string"), "имя и тип в описании");
        T.check(d.contains("ровно 4"), "ожидаемое число строк");
        T.check(d.contains("ровно 2"), "ожидаемое число заглушек");
        T.check(d.contains("900"), "контрольная сумма в описании");

        // Верные строки — замечаний нет.
        List<String> headers = List.of("house_code", "students_total");
        List<List<Object>> rows = List.of(
            List.of("H-GRA", 226), List.of("H-SLY", 225),
            List.of("H-HUF", 224), List.of("H-RAV", 225));
        T.check(c.verifyRows(4, headers, rows).isEmpty(), "верные данные проходят");

        // Не то число строк.
        List<String> p1 = c.verifyRows(3, headers, rows.subList(0, 3));
        T.check(p1.stream().anyMatch(s -> s.contains("Ожидалось строк: 4") && s.contains("получено: 3")),
            "замечание по числу строк");

        // Не та сумма (семантическая ошибка: hw-1 дал 1908 вместо 900).
        List<List<Object>> wrong = List.of(
            List.of("H-GRA", 500), List.of("H-SLY", 500),
            List.of("H-HUF", 454), List.of("H-RAV", 454));
        List<String> p2 = c.verifyRows(4, headers, wrong);
        T.eq(1, p2.size(), "одно замечание по сумме");
        T.check(p2.get(0).contains("1908"), "фактическая сумма в сообщении");

        // Числа строкой и с запятой.
        List<List<Object>> asText = List.of(
            List.of("A", "226"), List.of("B", "225,5"),
            List.of("C", "224"), List.of("D", "224,5"));
        T.check(c.verifyRows(4, headers, asText).isEmpty(), "числа строкой и с запятой");

        // Колонки нет в результате.
        List<String> p3 = c.verifyRows(4, List.of("house_code"), List.of(List.of("A")));
        T.check(!p3.isEmpty() && p3.get(0).contains("students_total"), "нет контрольной колонки");

        // Пустой результат при ожидаемой сумме.
        List<String> p4 = c.verifyRows(0, headers, List.of());
        T.check(p4.size() >= 2, "пустой результат — и строки, и сумма");

        // NULL-значения не ломают подсчёт.
        List<List<Object>> withNull = java.util.Arrays.asList(
            java.util.Arrays.asList("A", 450), java.util.Arrays.asList("B", (Object) null),
            java.util.Arrays.asList("C", 450), java.util.Arrays.asList("D", 0));
        T.check(c.verifyRows(4, headers, withNull).isEmpty(), "NULL игнорируется");

        // Контракт без проверок ничего не требует.
        SqlContract free = new SqlContract("x", null, null, 0, null);
        T.check(free.verifyRows(7, List.of(), List.of()).isEmpty(), "без контрольных значений");
        T.check(free.describe().contains("заглушек «?» быть не должно"), "описание без заглушек");
    }
}
