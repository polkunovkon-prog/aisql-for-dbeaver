/*
 * AI SQL for DBeaver — каркас задания на генерацию SQL (этап 1 конвейера).
 *
 * Разбор ТЗ на задания до обращения к модели: для каждого задания — целевая
 * сущность, зерно (grain), контракт колонок, контрольные значения, число
 * заглушек и список неоднозначностей, которые надо уточнить у человека
 * (HITL), а не угадывать.
 *
 * Задание задаётся человеком/системой в JSON-файле; класс разбирает его
 * и формирует текст задания для промпта. Ничего не выполняет в БД,
 * без UI/OSGi — покрыт офлайн-тестами (SqlTaskTest).
 *
 * Формат файла задания (JSON-массив или один объект):
 * {
 *   "id": "hw-2",
 *   "datasource": "hogwarts",
 *   "task": "Студенты по факультетам: код, название, число студентов…",
 *   "grain": "одна строка = факультет",
 *   "entity": "students",
 *   "placeholders": 2,
 *   "expectRows": 4,
 *   "columns": [{"name": "house_code", "type": "string", "required": true}],
 *   "checks": [{"column": "students_total", "sum": 900}],
 *   "openQuestions": ["Считать всех студентов или только активных?"]
 * }
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Задание на генерацию SQL: требования, контракт результата и открытые вопросы.
 */
public final class SqlTask {

    public final String id;
    public final String datasource;
    public final String task;
    /** Зерно результата: «одна строка = …». */
    public final String grain;
    /** Целевая сущность (таблица-факт), от которой строится запрос. */
    public final String entity;
    /** Открытые вопросы к человеку: без ответов генерировать нельзя. */
    public final List<String> openQuestions = new ArrayList<>();
    /** Контракт результата (колонки, контрольные значения, число строк). */
    public final SqlContract contract;

    public SqlTask(String id, String datasource, String task, String grain, String entity,
                   int placeholders, Integer expectRows) {
        this.id = id;
        this.datasource = datasource;
        this.task = task;
        this.grain = grain;
        this.entity = entity;
        this.contract = new SqlContract(id, datasource, task, placeholders, expectRows);
    }

    /** Есть ли неоднозначности, которые надо уточнить до генерации. */
    public boolean needsClarification() {
        return !openQuestions.isEmpty();
    }

    /** Полнота задания: без контракта колонок и контрольных значений проверять нечего. */
    public List<String> gaps() {
        List<String> gaps = new ArrayList<>();
        if (task == null || task.isBlank()) {
            gaps.add("не описан текст задания");
        }
        if (grain == null || grain.isBlank()) {
            gaps.add("не указано зерно результата (что такое одна строка)");
        }
        if (entity == null || entity.isBlank()) {
            gaps.add("не указана целевая сущность (таблица-факт)");
        }
        if (contract.columns.isEmpty()) {
            gaps.add("не задан контракт выходных колонок");
        }
        if (contract.checks.isEmpty() && contract.expectRows == null) {
            gaps.add("нет ни контрольных значений, ни ожидаемого числа строк — "
                + "семантические ошибки будут неотличимы от верного ответа");
        }
        return gaps;
    }

    /** Текст задания для промпта модели. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Задание ").append(id == null ? "" : id).append(':');
        if (datasource != null && !datasource.isBlank()) {
            sb.append(" база ").append(datasource).append(',');
        }
        sb.append('\n').append(task == null ? "" : task).append('\n');
        if (grain != null && !grain.isBlank()) {
            sb.append("Зерно результата: ").append(grain).append('\n');
        }
        if (entity != null && !entity.isBlank()) {
            sb.append("Считать от сущности: ").append(entity).append('\n');
        }
        sb.append('\n').append(contract.describe());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    //  Разбор JSON (без внешних библиотек: только плоская структура задания)
    // ------------------------------------------------------------------

    /**
     * Разбирает JSON-файл задания. Поддерживает один объект или массив объектов.
     * Ошибки формата — {@link IllegalArgumentException} с номером позиции.
     */
    public static List<SqlTask> parseAll(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        MiniJson value = MiniJson.parse(json.trim());
        List<SqlTask> out = new ArrayList<>();
        if (value.isArray()) {
            for (MiniJson item : value.items()) {
                out.add(fromMap(item.asObject(), out.size()));
            }
        } else {
            out.add(fromMap(value.asObject(), 0));
        }
        return out;
    }

    private static SqlTask fromMap(Map<String, MiniJson> m, int index) {
        String id = str(m, "id", "task-" + (index + 1));
        String datasource = str(m, "datasource", null);
        String task = str(m, "task", null);
        String grain = str(m, "grain", null);
        String entity = str(m, "entity", null);
        int placeholders = num(m, "placeholders", 0);
        Integer expectRows = m.containsKey("expectRows") ? num(m, "expectRows", 0) : null;

        SqlTask t = new SqlTask(id, datasource, task, grain, entity, placeholders, expectRows);

        MiniJson cols = m.get("columns");
        if (cols != null && cols.isArray()) {
            for (MiniJson c : cols.items()) {
                Map<String, MiniJson> cm = c.asObject();
                t.contract.columns.add(new SqlContract.Column(
                    str(cm, "name", ""),
                    str(cm, "type", "any"),
                    bool(cm, "required", false)));
            }
        }

        MiniJson checks = m.get("checks");
        if (checks != null && checks.isArray()) {
            for (MiniJson c : checks.items()) {
                Map<String, MiniJson> cm = c.asObject();
                Double sum = cm.containsKey("sum") ? dbl(cm, "sum") : null;
                Integer rows = cm.containsKey("rows") ? num(cm, "rows", 0) : null;
                t.contract.checks.add(new SqlContract.Check(str(cm, "column", ""), sum, rows));
            }
        }

        MiniJson qs = m.get("openQuestions");
        if (qs != null && qs.isArray()) {
            for (MiniJson q : qs.items()) {
                String s = q.asString();
                if (s != null && !s.isBlank()) {
                    t.openQuestions.add(s.trim());
                }
            }
        }
        return t;
    }

    private static String str(Map<String, MiniJson> m, String key, String def) {
        MiniJson v = m.get(key);
        String s = v == null ? null : v.asString();
        return s == null ? def : s;
    }

    private static int num(Map<String, MiniJson> m, String key, int def) {
        MiniJson v = m.get(key);
        Double d = v == null ? null : v.asNumber();
        return d == null ? def : (int) Math.round(d);
    }

    private static Double dbl(Map<String, MiniJson> m, String key) {
        MiniJson v = m.get(key);
        return v == null ? null : v.asNumber();
    }

    private static boolean bool(Map<String, MiniJson> m, String key, boolean def) {
        MiniJson v = m.get(key);
        Boolean b = v == null ? null : v.asBoolean();
        return b == null ? def : b;
    }

    /** Минимальный JSON-парсер: объекты, массивы, строки, числа, true/false/null. */
    static final class MiniJson {
        private final Object value;

        private MiniJson(Object value) {
            this.value = value;
        }

        boolean isArray() {
            return value instanceof List;
        }

        @SuppressWarnings("unchecked")
        List<MiniJson> items() {
            return (List<MiniJson>) value;
        }

        @SuppressWarnings("unchecked")
        Map<String, MiniJson> asObject() {
            return (Map<String, MiniJson>) value;
        }

        String asString() {
            return value instanceof String s ? s : null;
        }

        Double asNumber() {
            return value instanceof Number n ? n.doubleValue() : null;
        }

        Boolean asBoolean() {
            return value instanceof Boolean b ? b : null;
        }

        static MiniJson parse(String text) {
            Cursor c = new Cursor(text);
            MiniJson v = c.readValue();
            c.skipWs();
            if (!c.atEnd()) {
                throw new IllegalArgumentException("Лишние данные в JSON после значения, позиция " + c.pos);
            }
            return v;
        }

        private static final class Cursor {
            final String s;
            int pos;

            Cursor(String s) {
                this.s = s;
            }

            boolean atEnd() {
                return pos >= s.length();
            }

            void skipWs() {
                while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                    pos++;
                }
            }

            char peek() {
                if (atEnd()) {
                    throw new IllegalArgumentException("Неожиданный конец JSON");
                }
                return s.charAt(pos);
            }

            void expect(char c) {
                if (atEnd() || s.charAt(pos) != c) {
                    throw new IllegalArgumentException("Ожидался «" + c + "», позиция " + pos);
                }
                pos++;
            }

            MiniJson readValue() {
                skipWs();
                char c = peek();
                if (c == '{') {
                    return readObject();
                }
                if (c == '[') {
                    return readArray();
                }
                if (c == '"') {
                    return new MiniJson(readString());
                }
                if (s.startsWith("true", pos)) {
                    pos += 4;
                    return new MiniJson(Boolean.TRUE);
                }
                if (s.startsWith("false", pos)) {
                    pos += 5;
                    return new MiniJson(Boolean.FALSE);
                }
                if (s.startsWith("null", pos)) {
                    pos += 4;
                    return new MiniJson(null);
                }
                return new MiniJson(readNumber());
            }

            MiniJson readObject() {
                expect('{');
                Map<String, MiniJson> map = new LinkedHashMap<>();
                skipWs();
                if (!atEnd() && peek() == '}') {
                    pos++;
                    return new MiniJson(map);
                }
                while (true) {
                    skipWs();
                    String key = readString();
                    skipWs();
                    expect(':');
                    map.put(key, readValue());
                    skipWs();
                    char c = peek();
                    if (c == ',') {
                        pos++;
                        continue;
                    }
                    expect('}');
                    return new MiniJson(map);
                }
            }

            MiniJson readArray() {
                expect('[');
                List<MiniJson> list = new ArrayList<>();
                skipWs();
                if (!atEnd() && peek() == ']') {
                    pos++;
                    return new MiniJson(list);
                }
                while (true) {
                    list.add(readValue());
                    skipWs();
                    char c = peek();
                    if (c == ',') {
                        pos++;
                        continue;
                    }
                    expect(']');
                    return new MiniJson(list);
                }
            }

            String readString() {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (true) {
                    if (atEnd()) {
                        throw new IllegalArgumentException("Незакрытая строка в JSON");
                    }
                    char c = s.charAt(pos++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c == '\\') {
                        if (atEnd()) {
                            throw new IllegalArgumentException("Обрыв escape-последовательности в JSON");
                        }
                        char e = s.charAt(pos++);
                        switch (e) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            case 'r' -> sb.append('\r');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'u' -> {
                                if (pos + 4 > s.length()) {
                                    throw new IllegalArgumentException("Обрыв \\uXXXX в JSON");
                                }
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                                pos += 4;
                            }
                            default -> sb.append(e);
                        }
                        continue;
                    }
                    sb.append(c);
                }
            }

            Number readNumber() {
                int start = pos;
                while (pos < s.length()) {
                    char c = s.charAt(pos);
                    if (Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                        pos++;
                    } else {
                        break;
                    }
                }
                if (start == pos) {
                    throw new IllegalArgumentException("Ожидалось значение JSON, позиция " + pos);
                }
                String n = s.substring(start, pos);
                try {
                    return n.contains(".") || n.contains("e") || n.contains("E")
                        ? Double.parseDouble(n) : Long.parseLong(n);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Некорректное число «" + n + "» в JSON");
                }
            }
        }
    }
}
