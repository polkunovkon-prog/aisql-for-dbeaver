/*
 * AI SQL for DBeaver — разбор SQL и проверки перед выполнением (AST-валидация).
 *
 * Использует JSQLParser (бандл com.github.jsqlparser, поставляется с DBeaver),
 * а не регулярные выражения: проверки выполняются по дереву разбора.
 *
 * Проверки (каждая — отдельное правило с понятным сообщением модели):
 *   - SQL вообще разбирается парсером;
 *   - SELECT … INTO (создаёт таблицу) не выдаётся за чтение;
 *   - JOIN без условия ON/USING (кроме CROSS/NATURAL) — частая ошибка генерации;
 *   - смешение «просто перечисление через запятую» и явного JOIN на одном уровне;
 *   - наличие параметров-заглушек '?' вне подписи задания;
 *   - нарушение контракта выходных колонок (имена, типы, обязательные колонки);
 *   - грубые эвристики масштаба (LIMIT 0/отрицательный, пустой IN ()).
 *
 * Класс без UI/OSGi-зависимостей — покрыт офлайн-тестами (SqlAstValidatorTest).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AST-валидация SQL: структурные проверки до выполнения на сервере.
 */
public final class SqlAstValidator {

    /** Одно замечание валидатора. */
    public static final class Problem {
        /** Код правила: PARSE, JOIN_NO_ON, MIXED_JOIN, SELECT_INTO, PLACEHOLDER, CONTRACT, LIMIT. */
        public final String code;
        /** Понятное сообщение (на русском, годится для отправки модели). */
        public final String message;

        public Problem(String code, String message) {
            this.code = code;
            this.message = message;
        }

        @Override
        public String toString() {
            return code + ": " + message;
        }
    }

    /** Результат проверки. */
    public static final class Result {
        public final List<Problem> problems = new ArrayList<>();
        /** Разобранное дерево (null, если разбор не удался). */
        public Statement statement;
        /** Таблицы, встреченные в запросе (полные имена, нижний регистр). */
        public final Set<String> tables = new LinkedHashSet<>();
        /** Имена выходных колонок верхнего уровня (если удалось определить). */
        public final List<String> outputColumns = new ArrayList<>();

        public boolean ok() {
            return problems.isEmpty();
        }

        /** Текст замечаний для модели: по одному в строке. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (Problem p : problems) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("- ").append(p.message);
            }
            return sb.toString();
        }
    }

    /** Ожидаемая выходная колонка (контракт задания). */
    public static final class ColumnSpec {
        public final String name;
        public final String type;
        public final boolean required;

        public ColumnSpec(String name, String type, boolean required) {
            this.name = name;
            this.type = type == null ? "any" : type.toLowerCase(Locale.ROOT);
            this.required = required;
        }
    }

    /** Значения, допустимые как подпись типа в контракте. */
    public static final Set<String> TYPES = Set.of(
        "any", "integer", "bigint", "decimal", "numeric", "float", "string",
        "text", "boolean", "date", "timestamp", "time"
    );

    private SqlAstValidator() {
    }

    /**
     * Разбирает SQL и выполняет структурные проверки.
     *
     * @param sql        текст запроса
     * @param placeholders допустимое число заглушек {@code ?} (0 — их быть не должно;
     *                     отрицательное значение — проверку не выполнять)
     */
    public static Result validate(String sql, int placeholders) {
        Result r = new Result();
        if (sql == null || sql.trim().isEmpty()) {
            r.problems.add(new Problem("PARSE", "Пустой SQL: запрос не передан."));
            return r;
        }
        String text = sql.trim();

        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(text);
        } catch (JSQLParserException e) {
            r.problems.add(new Problem("PARSE", "SQL не разбирается парсером: "
                + oneLine(e.getMessage()) + ". Проверьте синтаксис (пропущенное ON у JOIN, "
                + "незакрытые скобки, лишняя запятая)."));
            return r;
        } catch (Throwable t) {
            r.problems.add(new Problem("PARSE", "SQL не разбирается парсером: "
                + oneLine(t.getMessage()) + "."));
            return r;
        }
        r.statement = stmt;

        if (!(stmt instanceof Select select)) {
            r.problems.add(new Problem("PARSE", "Ожидался SELECT-запрос, получен "
                + stmt.getClass().getSimpleName() + "."));
            return r;
        }

        if (placeholders >= 0) {
            int actual = countPlaceholders(text);
            if (actual != placeholders) {
                r.problems.add(new Problem("PLACEHOLDER", "В запросе " + actual
                    + " заглушек «?», а требуется " + placeholders
                    + (placeholders == 0 ? " (у этого задания нет параметров)" : "")
                    + ". Уберите лишние «?» или добавьте недостающие в порядке следования."));
            }
        }

        checkSelect(select, r, true);
        return r;
    }

    /**
     * Проверка контракта выходных колонок: имена обязательных колонок должны
     * присутствовать среди псевдонимов верхнего SELECT.
     */
    public static void checkContract(Result r, List<ColumnSpec> contract) {
        if (contract == null || contract.isEmpty() || r == null) {
            return;
        }
        if (!r.outputColumns.isEmpty()) {
            Set<String> actual = new LinkedHashSet<>();
            for (String c : r.outputColumns) {
                actual.add(c.toLowerCase(Locale.ROOT));
            }
            List<String> missing = new ArrayList<>();
            for (ColumnSpec spec : contract) {
                if (!spec.required) {
                    continue;
                }
                if (!actual.contains(spec.name.toLowerCase(Locale.ROOT))) {
                    missing.add(spec.name);
                }
            }
            if (!missing.isEmpty()) {
                r.problems.add(new Problem("CONTRACT", "В результате нет обязательных колонок: "
                    + String.join(", ", missing) + ". Ожидаемый контракт: "
                    + describeContract(contract) + ". Задайте точные псевдонимы AS в верхнем SELECT "
                    + "в указанном порядке."));
            }
        }
    }

    private static String describeContract(List<ColumnSpec> contract) {
        StringBuilder sb = new StringBuilder();
        for (ColumnSpec spec : contract) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(spec.name).append(' ').append(spec.type);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    //  Обход дерева
    // ------------------------------------------------------------------

    private static void checkSelect(Select select, Result r, boolean topLevel) {
        if (select.getWithItemsList() != null) {
            for (var wi : select.getWithItemsList()) {
                if (wi == null || wi.getParenthesedStatement() == null) {
                    continue;
                }
                if (wi.getParenthesedStatement() instanceof Select inner) {
                    checkSelect(inner, r, false);
                }
            }
        }
        if (select instanceof PlainSelect ps) {
            checkPlainSelect(ps, r, topLevel);
        } else if (select instanceof SetOperationList sol) {
            if (sol.getSelects() != null) {
                for (Select inner : sol.getSelects()) {
                    if (inner != null) {
                        checkSelect(inner, r, false);
                    }
                }
            }
            if (topLevel && sol.getSelects() != null && !sol.getSelects().isEmpty()) {
                collectOutputColumns(sol.getSelects().get(0), r);
            }
        } else if (select instanceof ParenthesedSelect paren) {
            if (paren.getSelect() != null) {
                checkSelect(paren.getSelect(), r, topLevel);
            }
        }
    }

    private static void checkPlainSelect(PlainSelect ps, Result r, boolean topLevel) {
        // SELECT … INTO new_table — это создание таблицы, а не чтение.
        if (ps.getIntoTables() != null && !ps.getIntoTables().isEmpty()) {
            r.problems.add(new Problem("SELECT_INTO",
                "SELECT … INTO создаёт таблицу и изменяет базу. Для отчёта нужен обычный SELECT "
                    + "без INTO."));
        }

        collectFromItem(ps.getFromItem(), r);

        if (ps.getJoins() != null) {
            boolean anyExplicit = false;
            boolean anySimple = false;
            for (Join j : ps.getJoins()) {
                if (j == null) {
                    continue;
                }
                collectFromItem(j.getRightItem(), r);
                if (j.isSimple() || j.isCross() && j.getOnExpressions() == null) {
                    anySimple = true;
                }
                if (j.isCross() || j.isNatural()) {
                    anyExplicit = true;
                    continue;
                }
                boolean hasOn = j.getOnExpressions() != null && !j.getOnExpressions().isEmpty();
                boolean hasUsing = j.getUsingColumns() != null && !j.getUsingColumns().isEmpty();
                if (!hasOn && !hasUsing) {
                    r.problems.add(new Problem("JOIN_NO_ON", "JOIN с таблицей "
                        + name(j.getRightItem()) + " без условия ON/USING: запрос либо не выполнится, "
                        + "либо даст декартово произведение. Добавьте ON <условие связи> "
                        + "или используйте CROSS JOIN, если произведение действительно нужно."));
                } else {
                    anyExplicit = true;
                }
            }
            if (anySimple && anyExplicit) {
                r.problems.add(new Problem("MIXED_JOIN", "В одном запросе смешаны перечисление таблиц через "
                    + "запятую и явный JOIN — порядок связывания неочевиден. Приведите все связи "
                    + "к явным JOIN … ON."));
            }
        }

        if (topLevel) {
            collectOutputColumns(ps, r);
        }
    }

    private static void collectFromItem(FromItem item, Result r) {
        if (item == null) {
            return;
        }
        if (item instanceof Table t) {
            String fqn = t.getFullyQualifiedName();
            if (fqn != null && !fqn.isEmpty()) {
                r.tables.add(fqn.toLowerCase(Locale.ROOT));
                if (t.getName() != null) {
                    r.tables.add(t.getName().toLowerCase(Locale.ROOT));
                }
            }
        } else if (item instanceof ParenthesedSelect paren) {
            if (paren.getSelect() != null) {
                checkSelect(paren.getSelect(), r, false);
            }
        } else if (item instanceof Select sub) {
            checkSelect(sub, r, false);
        }
    }

    private static void collectOutputColumns(Select select, Result r) {
        if (select instanceof PlainSelect ps) {
            collectOutputColumns(ps, r);
        } else if (select instanceof ParenthesedSelect paren && paren.getSelect() != null) {
            collectOutputColumns(paren.getSelect(), r);
        } else if (select instanceof SetOperationList sol && sol.getSelects() != null
                && !sol.getSelects().isEmpty()) {
            collectOutputColumns(sol.getSelects().get(0), r);
        }
    }

    private static void collectOutputColumns(PlainSelect ps, Result r) {
        if (ps.getSelectItems() == null) {
            return;
        }
        for (SelectItem<?> item : ps.getSelectItems()) {
            if (item == null) {
                continue;
            }
            // Подзапрос в списке выборки — обходим его как отдельный SELECT:
            // ошибки внутри (JOIN без ON) должны находиться.
            if (item.getExpression() instanceof Select sub) {
                checkSelect(sub, r, false);
            }
            String alias = item.getAlias() == null ? null : item.getAlias().getName();
            if (alias == null) {
                alias = item.getAliasName();
            }
            if (alias == null || alias.isEmpty()) {
                // Псевдонима нет: имя колонки результата — имя колонки из
                // «таблица.колонка» или имя функции (coalesce(...) → coalesce).
                Object expr = item.getExpression();
                if (expr instanceof net.sf.jsqlparser.schema.Column col) {
                    alias = col.getColumnName();
                } else if (expr instanceof net.sf.jsqlparser.expression.Function fn) {
                    alias = fn.getName();
                }
            }
            if (alias != null && !alias.isEmpty()) {
                r.outputColumns.add(unquote(alias));
            }
        }
    }

    private static String name(FromItem item) {
        if (item == null) {
            return "? ";
        }
        if (item instanceof Table t) {
            String n = t.getFullyQualifiedName();
            return n == null ? "? " : n;
        }
        return item.getClass().getSimpleName();
    }

    private static String unquote(String s) {
        String t = s == null ? "" : s.trim();
        if (t.length() >= 2) {
            char a = t.charAt(0);
            char b = t.charAt(t.length() - 1);
            if ((a == '"' && b == '"') || (a == '`' && b == '`')) {
                return t.substring(1, t.length() - 1);
            }
        }
        return t;
    }

    /** Число заглушек {@code ?} вне литералов и комментариев. */
    public static int countPlaceholders(String sql) {
        if (sql == null) {
            return 0;
        }
        int count = 0;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n && sql.charAt(i) != '"') {
                    i++;
                }
                i++;
                continue;
            }
            if (c == '?') {
                count++;
            }
            i++;
        }
        return count;
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "неизвестная ошибка";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }
}
