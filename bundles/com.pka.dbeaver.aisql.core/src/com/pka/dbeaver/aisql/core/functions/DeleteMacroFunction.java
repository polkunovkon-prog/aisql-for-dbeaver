/*
 * DBeaver Community — AI-функция db_deleteMacro.
 *
 * Удаляет сохранённый макрос.
 *
 * Тип — INFORMATION, режим — ASK.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.core.functions;

import com.pka.dbeaver.aisql.core.AisqlCore;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;

import java.util.Map;

/**
 * Удаление макроса.
 */
public class DeleteMacroFunction implements AIFunction {

    @Override
    public AIFunctionResult callFunction(AIFunctionContext context, Map<String, Object> args) throws DBException {
        String name = AisqlCore.str(args, "name");
        if (name == null) {
            throw new DBException("Параметр 'name' обязателен.");
        }
        String id = AisqlCore.sanitizeId(name);
        if (id == null) {
            throw new DBException("Недопустимый идентификатор макроса '" + name + "'.");
        }
        boolean removed = AisqlCore.deleteMacro(id);
        AisqlCore.audit("deleteMacro", null, null, "write", "macro=" + id + " removed=" + removed);
        return AisqlCore.info(removed
            ? "Макрос '" + id + "' удалён."
            : "Макрос '" + id + "' не найден.");
    }
}
