/*
 * DBeaver Community — AI-функция db_listObjects.
 *
 * Возвращает модели объекты базы данных (таблицы, представления, схемы,
 * последовательности, функции, процедуры, триггеры и т.п.) с фильтрацией
 * по типу и имени.
 *
 * Тип — INFORMATION, режим — ALWAYS_ALLOW (только чтение метаданных).
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
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Инвентаризация объектов БД.
 */
public class ListObjectsFunction implements AIFunction {

    private static final int MAX_DEPTH = 5;
    private static final int MAX_OBJECTS = 5000;

    @Override
    public AIFunctionResult callFunction(AIFunctionContext context, Map<String, Object> args) throws DBException {
        DBCExecutionContext ctx = AisqlCore.execContext(context);
        DBRProgressMonitor monitor = context.getMonitor();
        DBPDataSource dataSource = ctx.getDataSource();
        if (!(dataSource instanceof DBSObjectContainer root)) {
            throw new DBException("Драйвер не поддерживает обзор объектов базы данных.");
        }

        String typeFilter = AisqlCore.str(args, "type");
        String nameFilter = AisqlCore.str(args, "nameFilter");
        String schema = AisqlCore.str(args, "schema");
        boolean includeSystem = "true".equalsIgnoreCase(AisqlCore.str(args, "includeSystem"));

        List<DBSObjectContainer> starts = new ArrayList<>();
        if (schema != null) {
            try {
                DBSObject o = DBUtils.getObjectByPath(monitor, ctx, root, null, schema, null);
                if (o instanceof DBSObjectContainer c) {
                    starts.add(c);
                }
            } catch (Exception e) {
                throw new DBException("Не удалось найти схему '" + schema + "': " + e.getMessage(), e);
            }
            if (starts.isEmpty()) {
                throw new DBException("Схема '" + schema + "' не найдена.");
            }
        } else {
            starts.add(root);
        }

        Map<String, List<String>> byType = new LinkedHashMap<>();
        int total = 0;

        Deque<Item> queue = new ArrayDeque<>();
        for (DBSObjectContainer c : starts) {
            queue.add(new Item(c, 0));
        }
        List<DBSObject> seen = new ArrayList<>();

        while (!queue.isEmpty() && total < MAX_OBJECTS) {
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
                boolean system;
                try {
                    system = DBUtils.isSystemObject(child) || DBUtils.isHiddenObject(child);
                } catch (Exception e) {
                    system = false;
                }
                if (system && !includeSystem) {
                    if (child instanceof DBSObjectContainer c && item.depth < MAX_DEPTH) {
                        queue.add(new Item(c, item.depth + 1));
                    }
                    continue;
                }

                if (matchesType(child, typeFilter) && matchesName(child, nameFilter)) {
                    String type = typeName(child);
                    String fullName;
                    try {
                        fullName = DBUtils.getObjectFullName(dataSource, child, DBPEvaluationContext.DDL);
                    } catch (Exception e) {
                        fullName = child.getName();
                    }
                    byType.computeIfAbsent(type, k -> new ArrayList<>()).add(fullName);
                    total++;
                    if (total >= MAX_OBJECTS) {
                        break;
                    }
                }

                if (child instanceof DBSObjectContainer c && item.depth < MAX_DEPTH) {
                    queue.add(new Item(c, item.depth + 1));
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Объекты базы данных");
        if (typeFilter != null) {
            sb.append(" (тип ~ '").append(typeFilter).append("')");
        }
        if (nameFilter != null) {
            sb.append(" (имя ~ '").append(nameFilter).append("')");
        }
        if (schema != null) {
            sb.append(" (схема '").append(schema).append("')");
        }
        sb.append(". Найдено: ").append(total);
        if (total >= MAX_OBJECTS) {
            sb.append(" (достигнут предел ").append(MAX_OBJECTS).append(")");
        }
        sb.append('\n');
        if (total == 0) {
            sb.append("Ничего не найдено. Уточните type или nameFilter, либо укажите schema.");
        } else {
            for (Map.Entry<String, List<String>> e : byType.entrySet()) {
                sb.append('\n').append(e.getKey()).append(" (").append(e.getValue().size()).append("):\n");
                for (String n : e.getValue()) {
                    sb.append("  ").append(n).append('\n');
                }
            }
        }

        AisqlCore.audit("listObjects", ctx, null, "read",
            "type=" + typeFilter + " name=" + nameFilter + " found=" + total);
        return AisqlCore.info(sb.toString());
    }

    private static boolean contains(List<DBSObject> list, DBSObject o) {
        for (DBSObject x : list) {
            if (x == o) {
                return true;
            }
        }
        return false;
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
        if (obj instanceof DBSEntity entity) {
            try {
                return entity.getEntityType() == null ? "ENTITY" : entity.getEntityType().getName();
            } catch (Exception ignored) {
            }
        }
        try {
            String t = DBUtils.getObjectTypeName(obj);
            if (t != null && !t.isEmpty()) {
                return t;
            }
        } catch (Exception ignored) {
        }
        return obj.getClass().getSimpleName();
    }

    private static boolean matchesName(DBSObject obj, String nameFilter) {
        if (nameFilter == null || nameFilter.isEmpty()) {
            return true;
        }
        String name = obj.getName();
        if (name == null) {
            return false;
        }
        if (nameFilter.startsWith("/") && nameFilter.length() > 1) {
            try {
                return name.matches(nameFilter.substring(1));
            } catch (Exception e) {
                // неверная регулярка — трактуем как подстроку
            }
        }
        return name.toLowerCase(Locale.ROOT).contains(nameFilter.toLowerCase(Locale.ROOT));
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
