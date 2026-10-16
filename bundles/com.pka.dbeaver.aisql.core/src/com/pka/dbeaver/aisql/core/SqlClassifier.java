/*
 * AI SQL for DBeaver — классификация SQL-операторов.
 *
 * Использует синтаксический анализатор DBeaver (SQLScriptParser + SQLQuery),
 * а не токенные эвристики:
 *   - корректно разбирает скрипты с $$-блоками, кавычками и комментариями;
 *   - различает SELECT (read-only), DML (INSERT/UPDATE/DELETE/MERGE),
 *     DDL, TCL (COMMIT/ROLLBACK);
 *   - для операторов, которые JSQLParser относит к UNKNOWN (SHOW, DESCRIBE,
 *     EXPLAIN, VALUES, TABLE, PRAGMA), решает по первому ключевому слову.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.core;

import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLQueryCategory;
import org.jkiss.dbeaver.model.sql.SQLQueryType;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.model.sql.parser.SQLScriptParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Классификатор SQL-операторов на основе парсера DBeaver.
 */
public final class SqlClassifier {

    /** Ключевые слова операторов категории UNKNOWN, которые считаются только чтением. */
    public static final Set<String> READONLY_KEYWORDS = Set.of(
        "SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN",
        "VALUES", "TABLE", "PRAGMA"
    );

    private SqlClassifier() {
    }

    /** Разобранный и классифицированный оператор. */
    public static final class Stmt {
        public String text = "";
        public SQLQueryCategory category = SQLQueryCategory.UNKNOWN;
        public String categoryName = "UNKNOWN";
        public String firstKeyword = "";
        public String secondKeyword = "";
        /** Содержит ли оператор токен INTO (SELECT ... INTO создаёт таблицу). */
        public boolean containsInto;
        /** Оператор гарантированно только читает данные. */
        public boolean readOnly;
        /** Оператор может изменять данные/схему (DML, DDL, неизвестные команды). */
        public boolean mayWrite;

        @Override
        public String toString() {
            return categoryName + ":" + firstKeyword;
        }
    }

    /** Результат анализа скрипта. */
    public static final class ScriptInfo {
        public final List<Stmt> stmts;
        public final boolean parseFailed;

        public ScriptInfo(List<Stmt> stmts, boolean parseFailed) {
            this.stmts = stmts;
            this.parseFailed = parseFailed;
        }

        public boolean isEmpty() {
            return stmts.isEmpty();
        }

        public boolean isSingle() {
            return stmts.size() == 1;
        }

        public boolean isReadOnly() {
            if (stmts.isEmpty()) {
                return false;
            }
            for (Stmt s : stmts) {
                if (!s.readOnly) {
                    return false;
                }
            }
            return true;
        }

        /** Есть ли оператор, для которого стоит отключать автокоммит (DML/DDL/прочее изменяющее). */
        public boolean needsTxProtection() {
            for (Stmt s : stmts) {
                if (s.mayWrite) {
                    return true;
                }
            }
            return false;
        }

        public boolean hasCategory(SQLQueryCategory category) {
            for (Stmt s : stmts) {
                if (s.category == category) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Разбирает SQL на операторы (средствами SQLScriptParser — учитываются
     * кавычки, комментарии, dollar-quoted блоки) и классифицирует каждый.
     */
    public static ScriptInfo analyze(DBPDataSource dataSource, String sql) {
        List<Stmt> out = new ArrayList<>();
        boolean parseFailed = false;
        try {
            List<SQLScriptElement> elements = SQLScriptParser.parseScript(dataSource, sql);
            if (elements != null) {
                for (SQLScriptElement el : elements) {
                    if (el == null) {
                        continue;
                    }
                    String text = el.getText();
                    if (text == null || text.trim().isEmpty()) {
                        continue;
                    }
                    out.add(buildStmt(el, text.trim()));
                }
            }
        } catch (Exception e) {
            parseFailed = true;
            AisqlCorePlugin.log().debug("Не удалось разобрать SQL средствами DBeaver, используется один оператор", e);
        }
        if (out.isEmpty()) {
            String t = sql == null ? "" : sql.trim();
            if (!t.isEmpty()) {
                out.add(classify(t, SQLQueryCategory.UNKNOWN));
            }
        }
        return new ScriptInfo(out, parseFailed);
    }

    private static Stmt buildStmt(SQLScriptElement el, String text) {
        SQLQueryCategory category = SQLQueryCategory.UNKNOWN;
        if (el instanceof SQLQuery query) {
            try {
                SQLQueryType type = query.getType();
                if (type != null && type.getCategory() != null) {
                    category = type.getCategory();
                }
            } catch (Exception ignored) {
                // не удалось классифицировать — останется UNKNOWN
            }
        }
        return classify(text, category);
    }

    /**
     * Создаёт классифицированный оператор по тексту и категории, полученной
     * от парсера DBeaver. Публичный метод — точка расширения для тестов
     * (в рабочей среде категорию даёт {@code SQLQuery.getType()}).
     */
    public static Stmt classify(String text, SQLQueryCategory category) {
        Stmt st = new Stmt();
        st.text = text == null ? "" : text.trim();
        st.category = category == null ? SQLQueryCategory.UNKNOWN : category;
        st.categoryName = st.category.name();
        fillKeywords(st);
        computeFlags(st);
        return st;
    }

    private static void fillKeywords(Stmt st) {
        List<String> tokens = AisqlCore.tokenize(st.text);
        st.firstKeyword = tokens.isEmpty() ? "" : tokens.get(0);
        st.secondKeyword = tokens.size() > 1 ? tokens.get(1) : "";
        st.containsInto = tokens.contains("INTO");
    }

    private static void computeFlags(Stmt st) {
        boolean sql = st.category == SQLQueryCategory.SQL;
        boolean tcl = st.category == SQLQueryCategory.TCL;
        boolean readonlyUnknown = st.category == SQLQueryCategory.UNKNOWN
            && READONLY_KEYWORDS.contains(st.firstKeyword);
        // SELECT ... INTO new_table (PostgreSQL) парсится как SELECT, но создаёт таблицу
        boolean selectInto = sql && st.containsInto
            && ("SELECT".equals(st.firstKeyword) || "WITH".equals(st.firstKeyword));
        st.readOnly = (sql || readonlyUnknown) && !selectInto;
        st.mayWrite = !(st.readOnly || tcl);
    }

    /**
     * {@code EXPLAIN ANALYZE ...} формально «план выполнения», но фактически
     * ВЫПОЛНЯЕТ запрос. В профиле READ_ONLY такие операторы запрещаются.
     */
    public static boolean isExplainAnalyze(Stmt st) {
        return "EXPLAIN".equals(st.firstKeyword) && "ANALYZE".equals(st.secondKeyword);
    }
}
