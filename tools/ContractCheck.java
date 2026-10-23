/*
 * AI SQL for DBeaver — прогон заданий по контракту без нейросети и без GUI.
 *
 * Берёт JSON-описания заданий (docs/examples/crossdb/tasks/*.json), готовые
 * эталонные SQL (docs/examples/crossdb/sql/*.sql) и проверяет эталон теми же
 * правилами, которые действуют в плагине:
 *   1) SqlAstValidator — разбор дерева, JOIN без ON, SELECT INTO, заглушки,
 *      контракт выходных колонок;
 *   2) выполнение SQL в PostgreSQL и сверка строк/сумм с контрактом
 *      (SqlContract.verifyRows).
 *
 * Так проверяется, что контракт заданий корректен и что эталонные SQL ему
 * соответствуют — до того, как к делу подключится модель.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tools;

import com.aisql.dbeaver.core.SqlAstValidator;
import com.aisql.dbeaver.core.SqlTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Проверка контрактов заданий на эталонных SQL.
 *
 * Использование:
 *   java ... ContractCheck <каталог tasks> <каталог sql> <db-host> <db-user> <db-password>
 */
public final class ContractCheck {

    /** Соответствие «база из задания» → имя базы PostgreSQL. */
    private static final Map<String, String> DATABASES = Map.of(
        "hogwarts", "hogwarts",
        "cadia", "cadia"
    );

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Использование: ContractCheck <tasksDir> <sqlDir> <host> <user> <password>");
            System.exit(2);
        }
        Path tasksDir = Path.of(args[0]);
        Path sqlDir = Path.of(args[1]);
        String host = args[2];
        String user = args[3];
        String password = args[4];

        List<Path> taskFiles = new ArrayList<>();
        try (var stream = Files.list(tasksDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted()
                .forEach(taskFiles::add);
        }

        int total = 0;
        int failed = 0;
        for (Path f : taskFiles) {
            List<SqlTask> tasks = SqlTask.parseAll(Files.readString(f, StandardCharsets.UTF_8));
            for (SqlTask t : tasks) {
                total++;
                List<String> problems = check(t, sqlDir, host, user, password);
                if (problems.isEmpty()) {
                    System.out.println("OK   " + t.id + "  (" + t.contract.columns.size()
                        + " колонок, " + t.contract.placeholders + " заглушек)");
                } else {
                    failed++;
                    System.out.println("FAIL " + t.id);
                    for (String p : problems) {
                        System.out.println("       - " + p);
                    }
                }
            }
        }

        System.out.println();
        System.out.println("Заданий: " + total + ", с замечаниями: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static List<String> check(SqlTask t, Path sqlDir, String host, String user, String password)
        throws Exception {
        List<String> problems = new ArrayList<>(t.gaps());

        Path sqlFile = sqlDir.resolve(t.id + ".sql");
        if (!Files.exists(sqlFile)) {
            problems.add("нет файла эталонного SQL: " + sqlFile.getFileName());
            return problems;
        }
        String sql = Files.readString(sqlFile, StandardCharsets.UTF_8);

        // 1) Структурная проверка и контракт колонок.
        SqlAstValidator.Result ast = SqlAstValidator.validate(sql, t.contract.placeholders);
        SqlAstValidator.checkContract(ast, t.contract.columnSpecs());
        if (!ast.ok()) {
            problems.add("AST: " + ast.describe().replace("\n", " | "));
        }

        // 2) Выполнение и сверка контрольных значений.
        String db = DATABASES.get(t.datasource == null ? "" : t.datasource.toLowerCase(Locale.ROOT));
        if (db == null) {
            problems.add("неизвестная база в задании: " + t.datasource);
            return problems;
        }
        String url = "jdbc:postgresql://" + host + "/" + db;
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            c.setAutoCommit(true);
            String executed = bindPlaceholders(sql, t.contract.placeholders);
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(60);
                try (ResultSet rs = st.executeQuery(executed)) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<String> headers = new ArrayList<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        headers.add(md.getColumnLabel(i));
                    }
                    List<List<Object>> rows = new ArrayList<>();
                    while (rs.next() && rows.size() < 10000) {
                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.add(rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    problems.addAll(t.contract.verifyRows(rows.size(), headers, rows));
                }
            }
        }
        return problems;
    }

    /** Подставляет «%» вместо всех «?» — эталонные SQL написаны под «все значения». */
    private static String bindPlaceholders(String sql, int count) throws IOException {
        String out = sql;
        for (int i = 0; i < count; i++) {
            out = out.replaceFirst("\\?", "'%'");
        }
        return out;
    }
}
