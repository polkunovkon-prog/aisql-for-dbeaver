/*
 * DBeaver Community — AI-функция db_listMacros.
 *
 * Список сохранённых макросов (именованных SQL-скриптов) — «пользовательских
 * инструментов», которые ИИ может создавать и переиспользовать.
 *
 * Тип — INFORMATION, режим — ALWAYS_ALLOW.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.core.functions;

import com.pka.dbeaver.aisql.core.AisqlCore;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;

import java.util.List;
import java.util.Map;

/**
 * Список макросов.
 */
public class ListMacrosFunction implements AIFunction {

    @Override
    public AIFunctionResult callFunction(AIFunctionContext context, Map<String, Object> args) throws DBException {
        List<String> ids = AisqlCore.listMacroIds();
        StringBuilder sb = new StringBuilder();
        sb.append("Сохранённые макросы (").append(ids.size()).append("):\n");
        if (ids.isEmpty()) {
            sb.append("Пока нет макросов. Создайте их через db_defineMacro.");
        } else {
            for (String id : ids) {
                String[] meta = AisqlCore.readMacroMeta(id);
                sb.append("- ").append(id);
                if (meta[0] != null && !meta[0].isEmpty() && !meta[0].equals(id)) {
                    sb.append(" — ").append(meta[0]);
                }
                if (meta[1] != null && !meta[1].isEmpty()) {
                    sb.append(" (").append(AisqlCore.oneLine(meta[1], 200)).append(')');
                }
                sb.append('\n');
            }
        }
        return AisqlCore.info(sb.toString());
    }
}
