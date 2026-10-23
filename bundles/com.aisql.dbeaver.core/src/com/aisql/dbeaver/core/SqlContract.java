/*
 * AI SQL for DBeaver — контракт выходных колонок для задач генерации SQL.
 *
 * Позволяет заранее (до обращения к модели) описать, какой результат должен
 * вернуть запрос: имена колонок, типы, обязательность. Контракт передаётся
 * модели в промпте и проверяется по дереву разбора (SqlAstValidator) — так
 * ловятся «тихие» дефекты: запрос валиден, но отвечает не на тот вопрос
 * или теряет обязательную колонку (например, CASE с русскими подписями).
 *
 * Формат задания (JSON-объект, по одному заданию в файле):
 * {
 *   "id": "hw-2",
 *   "datasource": "hogwarts",
 *   "task": "Студенты по факультетам",
 *   "placeholders": 2,
 *   "expectRows": 4,
 *   "columns": [{"name": "house_code", "type": "string", "required": true}, …],
 *   "checks": [{"column": "students_total", "sum": 900}]
 * }
 *
 * Класс без UI/OSGi-зависимостей — покрыт офлайн-тестами (SqlContractTest).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Описание ожидаемого результата SQL-запроса (контракт задания).
 */
public final class SqlContract {

    /** Ожидаемая колонка результата. */
    public static final class Column {
        public final String name;
        public final String type;
        public final boolean required;

        public Column(String name, String type, boolean required) {
            this.name = name == null ? "" : name.trim();
            this.type = type == null ? "any" : type.trim().toLowerCase(Locale.ROOT);
            this.required = required;
        }

        /** Спецификация для валидатора дерева разбора. */
        public SqlAstValidator.ColumnSpec toSpec() {
            return new SqlAstValidator.ColumnSpec(name, type, required);
        }
    }

    /**
     * Контрольное значение: сумма/количество по колонке. Сверяется с фактическим
     * результатом запроса — сигнал, который ловит семантические ошибки
     * (запрос исполняется, но число не то).
     */
    public static final class Check {
        /** Колонка, по которой считается агрегат. */
        public final String column;
        /** Ожидаемая сумма значений (null — не проверяется). */
        public final Double sum;
        /** Ожидаемое число строк (null — не проверяется). */
        public final Integer rows;

        public Check(String column, Double sum, Integer rows) {
            this.column = column;
            this.sum = sum;
            this.rows = rows;
        }
    }

    public final String id;
    public final String datasource;
    public final String task;
    /** Сколько заглушек «?» должно быть в запросе (0 — ни одной). */
    public final int placeholders;
    /** Ожидаемое число строк (null — не проверяется). */
    public final Integer expectRows;
    public final List<Column> columns = new ArrayList<>();
    public final List<Check> checks = new ArrayList<>();

    public SqlContract(String id, String datasource, String task, int placeholders, Integer expectRows) {
        this.id = id;
        this.datasource = datasource;
        this.task = task;
        this.placeholders = Math.max(0, placeholders);
        this.expectRows = expectRows;
    }

    /** Спецификации колонок для AST-валидатора. */
    public List<SqlAstValidator.ColumnSpec> columnSpecs() {
        List<SqlAstValidator.ColumnSpec> out = new ArrayList<>(columns.size());
        for (Column c : columns) {
            out.add(c.toSpec());
        }
        return out;
    }

    /**
     * Проверяет фактические строки результата (после выполнения запроса) на
     * соответствие контрольным значениям контракта.
     *
     * @param rowsCount число полученных строк
     * @param headers   имена колонок результата
     * @param values    значения по строкам (список списков; null — колонка NULL)
     */
    public List<String> verifyRows(int rowsCount, List<String> headers, List<List<Object>> values) {
        List<String> problems = new ArrayList<>();
        if (expectRows != null && expectRows != rowsCount) {
            problems.add("Ожидалось строк: " + expectRows + ", получено: " + rowsCount
                + ". Проверьте фильтры, группировку и соединения таблиц.");
        }
        for (Check check : checks) {
            if (check.sum != null) {
                int idx = indexOf(headers, check.column);
                if (idx < 0) {
                    problems.add("Контрольная колонка «" + check.column + "» отсутствует в результате.");
                    continue;
                }
                double actual = 0;
                boolean hasValue = false;
                for (List<Object> row : values) {
                    Object v = row == null || idx >= row.size() ? null : row.get(idx);
                    Double d = toDouble(v);
                    if (d != null) {
                        actual += d;
                        hasValue = true;
                    }
                }
                if (!hasValue) {
                    problems.add("Контрольная сумма по «" + check.column + "»: значений нет.");
                } else if (Math.abs(actual - check.sum) > 0.05) {
                    problems.add("Контрольная сумма по «" + check.column + "»: ожидалось "
                        + fmt(check.sum) + ", получено " + fmt(actual)
                        + ". Запрос исполняется, но отвечает не на тот вопрос "
                        + "(проверьте выбор сущности, DISTINCT и дубли от JOIN).");
                }
            }
        }
        return problems;
    }

    /** Краткое описание контракта для модели (в промпт). */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Контракт результата");
        if (id != null && !id.isEmpty()) {
            sb.append(" (").append(id).append(')');
        }
        sb.append(":\n");
        for (Column c : columns) {
            sb.append("- ").append(c.name).append(' ').append(c.type);
            if (c.required) {
                sb.append(" — обязательная");
            }
            sb.append('\n');
        }
        if (expectRows != null) {
            sb.append("- строк в результате: ровно ").append(expectRows).append('\n');
        }
        if (placeholders > 0) {
            sb.append("- заглушек «?» в тексте запроса: ровно ").append(placeholders).append('\n');
        } else {
            sb.append("- заглушек «?» быть не должно\n");
        }
        for (Check c : checks) {
            if (c.sum != null) {
                sb.append("- сумма по «").append(c.column).append("» = ")
                    .append(fmt(c.sum)).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private static int indexOf(List<String> headers, String column) {
        if (headers == null || column == null) {
            return -1;
        }
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            if (h != null && h.equalsIgnoreCase(column)) {
                return i;
            }
        }
        return -1;
    }

    private static Double toDouble(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        String s = String.valueOf(v).trim().replace(',', '.').replace(' ', '\u00A0');
        if (s.isEmpty() || "NULL".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
