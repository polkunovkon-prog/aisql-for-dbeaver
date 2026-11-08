/*
 * AI SQL for DBeaver — AI-функция db_searchObjects.
 *
 * Поиск объектов БД по подстроке имени (или регулярному выражению
 * «/pattern»): таблицы, представления, функции, процедуры, колонки
 * не перечисляются — только точные имена объектов.
 *
 * Зачем: в больших базах (сотни и тысячи таблиц) db_listObjects
 * возвращает слишком много, а модели нужно быстро найти точное имя
 * («пользовательская таблица с заказами» → «sales.orders»). Это
 * аналог «schema linking» — первый шаг индустрии text-to-SQL.
 *
 * Безопасность: только чтение метаданных; имя подключения пишется
 * в аудит; описания объектов не возвращаются (только имена и типы).
 *
 * Тип — INFORMATION, режим — ALWAYS_ALLOW.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core.functions;

import com.aisql.dbeaver.core.AisqlCore;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Поиск объектов БД по имени (schema linking для больших схем).
 */
public class SearchObjectsFunction implements AIFunction {

    private static final int MAX_DEPTH = 5;
    /** Потолок просмотра объектов (как в db_listObjects). */
    private static final int MAX_TRAVERSE = 5000;
    /** Максимум совпадений в ответе по умолчанию. */
    public static final int DEFAULT_LIMIT = 30;
    /** Жёсткий потолок совпадений в ответе. */
    public static final int HARD_LIMIT = 200;

    @Override
    public AIFunctionResult callFunction(AIFunctionContext context, Map<String, Object> args) throws DBException {
        DBCExecutionContext ctx = AisqlCore.execContext(context);
        AisqlCore.guardToolEnabled("searchObjects", ctx);
        DBRProgressMonitor monitor = context.getMonitor();
        DBPDataSource dataSource = ctx.getDataSource();
        if (!(dataSource instanceof DBSObjectContainer root)) {
            throw new DBException("Драйвер не поддерживает обзор объектов базы данных.");
        }

        String pattern = AisqlCore.str(args, "pattern");
        if (pattern == null) {
            throw new DBException("Параметр 'pattern' обязателен (подстрока имени или /регулярное-выражение).");
        }
        String typeFilter = AisqlCore.str(args, "type");
        int limit = limitOf(args);

        long start = System.currentTimeMillis();
        List<Hit> hits = search(monitor, dataSource, root, pattern, typeFilter, limit);
        long duration = System.currentTimeMillis() - start;

        StringBuilder sb = new StringBuilder();
        sb.append("Поиск объектов по «").append(pattern).append('»');
        if (typeFilter != null) {
            sb.append(" (тип ~ '").append(typeFilter).append("')");
        }
        sb.append(": найдено ").append(hits.size());
        if (hits.size() >= limit) {
            sb.append(" (показаны первые ").append(limit);
            sb.append(", уточните шаблон)");
        }
        sb.append('\n');
        if (hits.isEmpty()) {
            sb.append("Ничего не найдено. Попробуйте другую подстроку или снимите фильтр типа.");
        } else {
            for (Hit h : hits) {
                sb.append("  ").append(h.fullName).append(" — ").append(h.type).append('\n');
            }
        }
        sb.append("\nПодсказка: точную структуру объекта смотрите через db_getObjectDdl, ");
        sb.append("примеры строк — через db_getTableSample.");

        AisqlCore.auditExec("searchObjects", ctx, null, "read",
            "pattern=" + AisqlCore.oneLine(pattern, 100) + "; found=" + hits.size(),
            null, "OK", hits.size(), -1, duration, null);
        return AisqlCore.info(sb.toString());
    }

    /** Лимит совпадений: параметр limit, по умолчанию 30, потолок 200. */
    public static int limitOf(java.util.Map<String, Object> args) {
        int v = AisqlCore.intArg(args, "limit", DEFAULT_LIMIT);
        if (v <= 0) {
            v = DEFAULT_LIMIT;
        }
        return Math.min(v, HARD_LIMIT);
    }

    /** Одно совпадение: полное имя и тип объекта. */
    public record Hit(String fullName, String type) {
    }

    private static List<Hit> search(
        DBRProgressMonitor monitor,
        DBPDataSource dataSource,
        DBSObjectContainer root,
        String pattern,
        String typeFilter,
        int limit
    ) {
        List<Hit> hits = new ArrayList<>();
        java.util.regex.Pattern regex = compilePattern(pattern);
        int traversed = 0;

        Deque<Item> queue = new ArrayDeque<>();
        queue.add(new Item(root, 0));
        List<DBSObject> seen = new ArrayList<>();

        while (!queue.isEmpty() && hits.size() < limit && traversed < MAX_TRAVERSE) {
            Item item = queue.poll();
            Iterable<? extends DBSObject> children;
            try {
                children = item.container.getChildren(monitor);
            } catch (Exception e) {
                continue;
            }
            if (children == null) {
                continue;
            }
            for (DBSObject child : children) {
                if (child == null || contains(seen, child)) {
                    continue;
                }
                seen.add(child);
                traversed++;
                if (traversed >= MAX_TRAVERSE) {
                    break;
                }
                boolean system;
                try {
                    system = DBUtils.isSystemObject(child) || DBUtils.isHiddenObject(child);
                } catch (Exception e) {
                    system = false;
                }
                if (!system && matchesPattern(child.getName(), pattern, regex)
                    && matchesType(child, typeFilter)) {
                    String fullName;
                    try {
                        fullName = DBUtils.getObjectFullName(dataSource, child, DBPEvaluationContext.DDL);
                    } catch (Exception e) {
                        fullName = child.getName();
                    }
                    hits.add(new Hit(fullName, typeName(child)));
                    if (hits.size() >= limit) {
                        break;
                    }
                }
                if (child instanceof DBSObjectContainer c && item.depth < MAX_DEPTH) {
                    queue.add(new Item(c, item.depth + 1));
                }
            }
        }
        return hits;
    }

    /** Компиляция «/regex/» → Pattern (косые черты с обеих сторон); иначе null. */
    public static java.util.regex.Pattern compilePattern(String pattern) {
        if (pattern != null && pattern.startsWith("/") && pattern.length() > 2) {
            String body = pattern.endsWith("/")
                ? pattern.substring(1, pattern.length() - 1)
                : pattern.substring(1);
            try {
                return java.util.regex.Pattern.compile(body,
                    java.util.regex.Pattern.CASE_INSENSITIVE);
            } catch (Exception e) {
                return null; // неверная регулярка — трактуем как подстроку
            }
        }
        return null;
    }

    /** Подстрока (без учёта регистра) либо регулярное выражение «/...». */
    public static boolean matchesPattern(String name, String pattern, java.util.regex.Pattern regex) {
        if (name == null || pattern == null) {
            return false;
        }
        if (regex != null) {
            try {
                return regex.matcher(name).find();
            } catch (Exception ignored) {
            }
        }
        String needle = pattern;
        if (pattern.startsWith("/") && pattern.length() > 1) {
            // Битая регулярка — откат к подстроке без косых черт.
            needle = pattern.endsWith("/") && pattern.length() > 2
                ? pattern.substring(1, pattern.length() - 1)
                : pattern.substring(1);
        }
        return name.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean matchesType(DBSObject obj, String typeFilter) {
        if (typeFilter == null || typeFilter.isEmpty()) {
            return true;
        }
        String want = typeFilter.toUpperCase(Locale.ROOT);
        String have = typeName(obj).toUpperCase(Locale.ROOT);
        return have.contains(want) || want.contains(have);
    }

    private static String typeName(DBSObject obj) {
        try {
            String t = DBUtils.getObjectTypeName(obj);
            if (t != null && !t.isEmpty()) {
                return t;
            }
        } catch (Exception ignored) {
        }
        return obj.getClass().getSimpleName();
    }

    private static boolean contains(List<DBSObject> list, DBSObject o) {
        for (DBSObject x : list) {
            if (x == o) {
                return true;
            }
        }
        return false;
    }

    private static final class Item {
        final DBSObjectContainer container;
        final int depth;

        Item(DBSObjectContainer container, int depth) {
            this.container = container;
            this.depth = depth;
        }
    }
}
