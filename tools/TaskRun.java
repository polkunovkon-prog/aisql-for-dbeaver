/*
 * AI SQL for DBeaver — обёртка для офлайн-прогона: выполнение и сверка контракта.
 *
 * Аргументы: <db> <sql> <placeholders> <контракт колонок> <checks-json> <expectRows>
 *
 * Возвращает «OK …» и код 0 либо расхождения с контрактом и код 1.
 * Заглушки «?» подставляются как '%' — прогон идёт в режиме «все значения».
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tools;

import com.aisql.dbeaver.core.SqlContract;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Выполнение SQL задания и сверка результата с контрактом.
 */
public final class TaskRun {

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.out.println("FAIL: нужны аргументы <db> <sql> <placeholders> <columns> "
                + "<checks-json> <expectRows>");
            System.exit(2);
        }
        String db = args[0].trim();
        String sql = args[1];
        int placeholders;
        try {
            placeholders = Integer.parseInt(args[2].trim());
        } catch (NumberFormatException e) {
            System.out.println("FAIL: placeholders должно быть числом");
            System.exit(2);
            return;
        }
        String rawCols = args[3] == null ? "" : args[3].trim();
        String checksJson = args[4] == null ? "[]" : args[4].trim();
        String expectRaw = args[5] == null ? "" : args[5].trim();

        Integer expectRows = null;
        if (!expectRaw.isEmpty()) {
            try {
                expectRows = Integer.parseInt(expectRaw);
            } catch (NumberFormatException ignored) {
                // остаётся null — проверка числа строк не выполняется
            }
        }

        SqlContract contract = new SqlContract("task", db, "task", placeholders, expectRows);
        for (String chunk : rawCols.split(",")) {
            String item = chunk.trim();
            if (item.isEmpty()) {
                continue;
            }
            boolean required = item.endsWith("!");
            if (required) {
                item = item.substring(0, item.length() - 1).trim();
            }
            String name = item;
            String type = "any";
            int colon = item.indexOf(':');
            if (colon > 0) {
                name = item.substring(0, colon).trim();
                type = item.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            }
            contract.columns.add(new SqlContract.Column(name, type, required));
        }
        parseChecks(checksJson, contract);

        String executed = bind(sql, placeholders);
        String url = "jdbc:postgresql://127.0.0.1:5432/" + db;
        String user = System.getenv().getOrDefault("PGUSER", "agent_user");
        String password = System.getenv().getOrDefault("PGPASSWORD", "");

        try (Connection c = DriverManager.getConnection(url, user, password)) {
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(60);
                try (ResultSet rs = st.executeQuery(executed)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    List<String> headers = new ArrayList<>();
                    for (int i = 1; i <= n; i++) {
                        headers.add(md.getColumnLabel(i));
                    }
                    List<List<Object>> rows = new ArrayList<>();
                    while (rs.next() && rows.size() < 100000) {
                        List<Object> row = new ArrayList<>(n);
                        for (int i = 1; i <= n; i++) {
                            row.add(rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    List<String> problems = contract.verifyRows(rows.size(), headers, rows);
                    if (problems.isEmpty()) {
                        System.out.println("OK: строк " + rows.size()
                            + "; колонки: " + String.join(", ", headers));
                        System.exit(0);
                    }
                    for (String p : problems) {
                        System.out.println("- " + p);
                    }
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.out.println("- Ошибка выполнения: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Минимальный разбор [{«column»:…,«sum»:…,«rows»:…}, …] без внешних библиотек. */
    private static void parseChecks(String json, SqlContract contract) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return;
        }
        // Грубый, но достаточный разбор плоского массива объектов проверок.
        int i = 0;
        String s = json;
        while (true) {
            int objStart = s.indexOf('{', i);
            if (objStart < 0) {
                break;
            }
            int objEnd = s.indexOf('}', objStart);
            if (objEnd < 0) {
                break;
            }
            String obj = s.substring(objStart + 1, objEnd);
            String column = value(obj, "column");
            Double sum = number(obj, "sum");
            Integer rows = intNum(obj, "rows");
            if (column != null) {
                contract.checks.add(new SqlContract.Check(column, sum, rows));
            }
            i = objEnd + 1;
        }
    }

    private static String value(String obj, String key) {
        String needle = "\"" + key + "\"";
        int p = obj.indexOf(needle);
        if (p < 0) {
            return null;
        }
        int colon = obj.indexOf(':', p + needle.length());
        if (colon < 0) {
            return null;
        }
        int q1 = obj.indexOf('"', colon);
        if (q1 < 0) {
            return null;
        }
        int q2 = obj.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return null;
        }
        return obj.substring(q1 + 1, q2);
    }

    private static Double number(String obj, String key) {
        String needle = "\"" + key + "\"";
        int p = obj.indexOf(needle);
        if (p < 0) {
            return null;
        }
        int colon = obj.indexOf(':', p + needle.length());
        if (colon < 0) {
            return null;
        }
        int end = colon + 1;
        while (end < obj.length() && " ,}".indexOf(obj.charAt(end)) < 0) {
            end++;
        }
        try {
            return Double.parseDouble(obj.substring(colon + 1, end).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intNum(String obj, String key) {
        Double d = number(obj, key);
        return d == null ? null : (int) Math.round(d);
    }

    /** Подставляет '%' вместо всех «?» — эталонный режим «все значения». */
    private static String bind(String sql, int count) {
        String out = sql;
        for (int i = 0; i < count; i++) {
            int p = findPlaceholder(out);
            if (p < 0) {
                break;
            }
            out = out.substring(0, p) + "'%'" + out.substring(p + 1);
        }
        return out;
    }

    private static int findPlaceholder(String sql) {
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inString = !inString;
                continue;
            }
            if (!inString && c == '?') {
                return i;
            }
        }
        return -1;
    }
}
