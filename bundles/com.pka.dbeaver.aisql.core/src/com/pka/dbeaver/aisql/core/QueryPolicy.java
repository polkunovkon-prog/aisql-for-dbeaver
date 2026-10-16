/*
 * AI SQL for DBeaver — политика выполнения SQL.
 *
 * Проверки перед выполнением скрипта:
 *   1) профиль READ_ONLY — запрет любых изменяющих операторов и EXPLAIN ANALYZE;
 *   2) policy.forbidden — список регулярных выражений, запрещённые операторы
 *      (применяется во всех профилях, кроме FULL).
 *
 * Это «рельсы» для ИИ, а не средство защиты от злоумышленника: настоящая
 * защита — права учётной записи СУБД и подтверждение пользователя.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Оценка допустимости выполнения SQL с учётом профиля и правил.
 */
public final class QueryPolicy {

    public static final String KEY_FORBIDDEN = "policy.forbidden";

    /** Решение политики. */
    public static final class Decision {
        public final boolean allowed;
        public final String reason;
        public final String rule;
        public final ConnectionProfiles.Profile profile;

        Decision(boolean allowed, String reason, String rule, ConnectionProfiles.Profile profile) {
            this.allowed = allowed;
            this.reason = reason;
            this.rule = rule;
            this.profile = profile;
        }
    }

    private QueryPolicy() {
    }

    /**
     * Проверяет скрипт (список классифицированных операторов) для подключения.
     */
    public static Decision evaluate(String connectionName, List<SqlClassifier.Stmt> stmts) {
        ConnectionProfiles.Profile profile = ConnectionProfiles.resolve(connectionName);
        String conn = connectionName == null || connectionName.isBlank()
            ? "(текущее подключение)"
            : "«" + connectionName + "»";

        if (profile == ConnectionProfiles.Profile.READ_ONLY) {
            for (SqlClassifier.Stmt st : stmts) {
                if (!st.readOnly) {
                    return deny(profile,
                        "Профиль READ_ONLY: подключение " + conn + " допускает только операторы чтения, "
                            + "а запрос содержит оператор категории " + st.categoryName
                            + " («" + st.firstKeyword + "»). "
                            + "Если изменение действительно нужно, измените профиль подключения "
                            + "в настройках плагина (Профили безопасности).",
                        "policy.profile");
                }
                if (SqlClassifier.isExplainAnalyze(st)) {
                    return deny(profile,
                        "Профиль READ_ONLY: EXPLAIN ANALYZE фактически ВЫПОЛНЯЕТ запрос, поэтому запрещён. "
                            + "Используйте обычный EXPLAIN (без ANALYZE).",
                        "policy.profile");
                }
            }
        }

        if (profile != ConnectionProfiles.Profile.FULL) {
            List<String> patterns = forbiddenPatterns();
            for (SqlClassifier.Stmt st : stmts) {
                for (String rx : patterns) {
                    try {
                        if (Pattern.compile(rx, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                                .matcher(st.text).find()) {
                            return deny(profile,
                                "Оператор запрещён правилом policy.forbidden («" + rx + "»). "
                                    + "Правило можно изменить в настройках плагина.",
                                "policy.forbidden(" + rx + ")");
                        }
                    } catch (Exception e) {
                        AisqlCorePlugin.log().debug("Неверное регулярное выражение в policy.forbidden: " + rx, e);
                    }
                }
            }
        }

        return new Decision(true, "", "", profile);
    }

    /** Список запрещающих регулярных выражений из настроек. */
    public static List<String> forbiddenPatterns() {
        String raw = AisqlCore.loadSettings().getProperty(KEY_FORBIDDEN, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String chunk : raw.split("[;\\n]")) {
            String s = chunk.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                out.add(s);
            }
        }
        return out;
    }

    private static Decision deny(ConnectionProfiles.Profile p, String reason, String rule) {
        return new Decision(false, reason, rule, p);
    }
}
