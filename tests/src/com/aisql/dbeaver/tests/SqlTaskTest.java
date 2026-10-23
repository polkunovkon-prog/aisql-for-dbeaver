/*
 * AI SQL for DBeaver — тесты каркаса задания (этап 1 конвейера).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.core.SqlTask;

import java.util.List;

/**
 * Проверки разбора задания и поиска пробелов в требованиях.
 */
public final class SqlTaskTest {

    private SqlTaskTest() {
    }

    private static final String JSON = ""
        + "{\n"
        + "  \"id\": \"hw-2\",\n"
        + "  \"datasource\": \"hogwarts\",\n"
        + "  \"task\": \"Студенты по факультетам\",\n"
        + "  \"grain\": \"одна строка = факультет\",\n"
        + "  \"entity\": \"students\",\n"
        + "  \"placeholders\": 2,\n"
        + "  \"expectRows\": 4,\n"
        + "  \"columns\": [\n"
        + "    {\"name\": \"house_code\", \"type\": \"string\", \"required\": true},\n"
        + "    {\"name\": \"students_total\", \"type\": \"integer\", \"required\": true},\n"
        + "    {\"name\": \"note\", \"type\": \"text\"}\n"
        + "  ],\n"
        + "  \"checks\": [ {\"column\": \"students_total\", \"sum\": 900} ],\n"
        + "  \"openQuestions\": [\"Считать всех студентов или только активных?\", \" \"]\n"
        + "}";

    public static void run() {
        T.section("SqlTask");

        List<SqlTask> tasks = SqlTask.parseAll(JSON);
        T.eq(1, tasks.size(), "одно задание");
        SqlTask t = tasks.get(0);
        T.eq("hw-2", t.id, "id задания");
        T.eq("students", t.entity, "целевая сущность");
        T.eq(2, t.contract.placeholders, "число заглушек");
        T.eq(Integer.valueOf(4), t.contract.expectRows, "ожидаемое число строк");
        T.eq(3, t.contract.columns.size(), "три колонки");
        T.check(t.contract.columns.get(0).required, "колонка обязательна");
        T.check(!t.contract.columns.get(2).required, "колонка необязательна");
        T.eq(1, t.contract.checks.size(), "одна контрольная проверка");
        T.eq(Double.valueOf(900.0), t.contract.checks.get(0).sum, "контрольная сумма");
        T.eq(1, t.openQuestions.size(), "пустой вопрос отброшен");
        T.check(t.needsClarification(), "есть что уточнять");
        T.check(t.gaps().isEmpty(), "полное задание без пробелов: " + t.gaps());

        String d = t.describe();
        T.check(d.contains("Задание hw-2"), "заголовок описания");
        T.check(d.contains("Зерно результата"), "зерно в описании");
        T.check(d.contains("students_total integer"), "контракт в описании");
        T.check(d.contains("900"), "контрольная сумма в описании");

        SqlTask bare = SqlTask.parseAll("{\"id\":\"x\",\"task\":\"что-то\"}").get(0);
        List<String> gaps = bare.gaps();
        T.check(gaps.size() >= 4, "пробелы найдены: " + gaps);
        T.check(gaps.stream().anyMatch(g -> g.contains("зерно")), "пробел про зерно");
        T.check(gaps.stream().anyMatch(g -> g.contains("контрольных значений")), "пробел про контроль");
        T.check(!bare.needsClarification(), "вопросов нет");

        List<SqlTask> many = SqlTask.parseAll("[{\"id\":\"a\"},{\"id\":\"b\",\"placeholders\":3}]");
        T.eq(2, many.size(), "два задания");
        T.eq("b", many.get(1).id, "id второго");
        T.eq(3, many.get(1).contract.placeholders, "заглушки второго");

        T.eq("task-2", SqlTask.parseAll("[{\"id\":\"a\"},{}]").get(1).id, "имя по умолчанию");

        T.check(throwsOn("{\"id\":\"a\""), "незакрытый объект — ошибка");
        T.check(throwsOn("{\"id\": } "), "пропущенное значение — ошибка");
        T.check(throwsOn("{} {} "), "лишние данные — ошибка");

        T.eq(0, SqlTask.parseAll("  ").size(), "пустой JSON — нет заданий");
        T.eq(0, SqlTask.parseAll(null).size(), "null — нет заданий");

        SqlTask esc = SqlTask.parseAll(
            "{\"id\":\"e\",\"task\":\"в кавычках: \\\"да\\\" и \\u0416\"}").get(0);
        T.check(esc.task.contains("\"да\""), "кавычки разобраны");
        T.check(esc.task.contains("Ж"), "\\u разобран");

        SqlTask num = SqlTask.parseAll("{\"id\":\"n\",\"expectRows\":12.0}").get(0);
        T.eq(Integer.valueOf(12), num.contract.expectRows, "дробное число строк округлено");
    }

    private static boolean throwsOn(String json) {
        try {
            SqlTask.parseAll(json);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
