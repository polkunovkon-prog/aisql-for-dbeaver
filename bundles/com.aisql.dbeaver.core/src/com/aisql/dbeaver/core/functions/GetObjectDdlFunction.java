/*
 * DBeaver Community — AI-функция db_getObjectDdl.
 *
 * Возвращает модели SQL-определение объекта БД (DDL): таблицы, представления,
 * функции, процедуры, триггеры, ограничения и т.п. Это позволяет ИИ «изучать»
 * существующие функции и объекты по их исходному коду.
 *
 * Тип — INFORMATION, режим — ALWAYS_ALLOW (только чтение метаданных).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core.functions;

import com.aisql.dbeaver.core.AisqlCore;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.ai.functions.GetTableDetailsFunction;
import org.jkiss.dbeaver.model.ai.utils.AIUtils;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Получение DDL (SQL-определения) объекта БД.
 */
public class GetObjectDdlFunction implements AIFunction {

    @Override
    public AIFunctionResult callFunction(AIFunctionContext context, Map<String, Object> args) throws DBException {
        DBCExecutionContext ctx = AisqlCore.execContext(context);
        DBRProgressMonitor monitor = context.getMonitor();
        DBPDataSource dataSource = ctx.getDataSource();

        String objectName = AisqlCore.str(args, "objectName");
        if (objectName == null) {
            objectName = AisqlCore.str(args, "tableName");
        }
        String schema = AisqlCore.str(args, "schema");

        if (objectName == null) {
            throw new DBException("Параметр 'objectName' обязателен (полное или краткое имя объекта).");
        }

        DBSObject object = resolveObject(context, ctx, dataSource, monitor, schema, objectName);
        if (object == null) {
            throw new DBException("Объект '" + objectName + "' не найден. "
                + "Проверьте имя и схему (можно получить список через db_listObjects).");
        }

        String ddl = null;
        String note = null;

        // 1) Сущности (таблицы/представления) — через штатный генератор DBeaver.
        if (object instanceof DBSEntity) {
            try {
                Map<String, Object> a = new HashMap<>();
                a.put("tableNames", objectName);
                AIFunctionResult r = new GetTableDetailsFunction().callFunction(context, a);
                if (r != null && r.getValue() != null) {
                    ddl = String.valueOf(r.getValue());
                }
            } catch (Throwable t) {
                note = "Штатный генератор DDL для сущности не сработал: " + t.getMessage();
            }
        }

        // 2) Прочие объекты (функции, процедуры, триггеры, ...) — через AIUtils.
        if (ddl == null) {
            try {
                ddl = AIUtils.getObjectDDL(object, monitor);
            } catch (Throwable t) {
                if (note == null) {
                    note = "Не удалось получить DDL: " + t.getMessage();
                }
            }
        }

        // 3) Универсальный fallback — DBPScriptObject.
        if (ddl == null && object instanceof org.jkiss.dbeaver.model.DBPScriptObject scriptObject) {
            try {
                ddl = scriptObject.getObjectDefinitionText(monitor, java.util.Collections.emptyMap());
            } catch (Throwable t) {
                if (note == null) {
                    note = "Не удалось получить определение: " + t.getMessage();
                }
            }
        }

        String fullName;
        try {
            fullName = DBUtils.getObjectFullName(dataSource, object, org.jkiss.dbeaver.model.DBPEvaluationContext.DDL);
        } catch (Exception e) {
            fullName = object.getName();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Определение объекта: ").append(fullName).append('\n');
        sb.append("Тип: ").append(object.getClass().getSimpleName()).append('\n');
        sb.append("---\n");
        if (ddl != null && !ddl.trim().isEmpty()) {
            sb.append(ddl);
            if (!ddl.endsWith("\n")) {
                sb.append('\n');
            }
        } else {
            sb.append("DDL недоступен для этого типа объекта.");
            if (note != null) {
                sb.append(' ').append(note);
            }
            sb.append('\n');
        }

        AisqlCore.audit("getObjectDdl", ctx, null, "read",
            "object=" + fullName + " status=" + (ddl != null ? "OK" : "EMPTY"));
        return AisqlCore.info(sb.toString());
    }

    private static DBSObject resolveObject(
        AIFunctionContext context,
        DBCExecutionContext ctx,
        DBPDataSource dataSource,
        DBRProgressMonitor monitor,
        String schema,
        String objectName
    ) {
        if (!(dataSource instanceof DBSObjectContainer root)) {
            return null;
        }
        // Точное разрешение по пути (schema.object или catalog.schema.object).
        try {
            List<String> parts = new java.util.ArrayList<>();
            if (schema != null && !schema.isEmpty()) {
                parts.add(schema);
            }
            for (String p : objectName.split("\\.")) {
                String t = p.trim();
                if (!t.isEmpty()) {
                    parts.add(t);
                }
            }
            DBSObject o = AIUtils.findObjectByFQN(monitor, ctx, root, parts.toArray(new String[0]));
            if (o != null) {
                return o;
            }
        } catch (Throwable ignored) {
        }
        // Таблица в области видимости AI.
        try {
            DBSObject o = AIUtils.findTableInContext(context, objectName);
            if (o != null) {
                return o;
            }
        } catch (Throwable ignored) {
        }
        // Поиск по имени среди детей корня/схемы.
        try {
            return findByName(monitor, root, schema, objectName);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static DBSObject findByName(
        DBRProgressMonitor monitor,
        DBSObjectContainer container,
        String schema,
        String objectName
    ) throws DBException {
        Iterable<? extends DBSObject> children = container.getChildren(monitor);
        if (children == null) {
            return null;
        }
        String simple = objectName.contains(".") ? objectName.substring(objectName.lastIndexOf('.') + 1) : objectName;
        for (DBSObject child : children) {
            if (child == null) {
                continue;
            }
            if (matches(schema, child) && simple.equalsIgnoreCase(child.getName())) {
                return child;
            }
            if (child instanceof DBSObjectContainer c) {
                DBSObject found = findByName(monitor, c, schema, objectName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean matches(String schema, DBSObject obj) {
        if (schema == null || schema.isEmpty()) {
            return true;
        }
        try {
            DBSObject parent = obj.getParentObject();
            return parent != null && schema.equalsIgnoreCase(parent.getName());
        } catch (Exception e) {
            return true;
        }
    }
}
