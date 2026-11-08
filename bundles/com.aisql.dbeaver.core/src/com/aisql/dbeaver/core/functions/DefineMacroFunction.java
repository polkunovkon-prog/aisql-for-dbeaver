/*
 * DBeaver Community — AI-функция db_defineMacro.
 *
 * Создаёт или обновляет макрос — сохранённый именованный SQL-скрипт, который
 * ИИ (или пользователь) может затем многократно выполнять через db_runMacro.
 * Это способ «обучить» ИИ новому инструменту.
 *
 * Тип — INFORMATION, режим — ASK.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core.functions;

import com.aisql.dbeaver.core.AisqlCore;
import com.aisql.dbeaver.core.PromptInjectionDefense;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;

import java.util.Map;

/**
 * Создание/обновление макроса.
 */
public class DefineMacroFunction implements AIFunction {

    @Override
    public AIFunctionResult callFunction(AIFunctionContext context, Map<String, Object> args) throws DBException {
        AisqlCore.guardToolEnabled("defineMacro", null);
        String name = AisqlCore.str(args, "name");
        String sql = AisqlCore.str(args, "sql");
        if (name == null) {
            throw new DBException("Параметр 'name' обязателен.");
        }
        if (sql == null) {
            throw new DBException("Параметр 'sql' обязателен.");
        }
        String id = AisqlCore.sanitizeId(name);
        if (id == null) {
            throw new DBException("Недопустимое имя макроса '" + name + "': разрешены латиница, цифры, "
                + "точка, дефис и подчёркивание; не длиннее " + AisqlCore.MAX_MACRO_ID_LEN + " символов.");
        }
        String title = AisqlCore.str(args, "title");
        String description = AisqlCore.str(args, "description");

        // Макрос сохраняется и потом выполняется много раз: инъекция
        // в теле макроса — постоянная угроза, поэтому в strict она не
        // сохраняется вовсе (мимо аудита/проверок при каждом запуске).
        PromptInjectionDefense.auditIfSuspicious("defineMacro", null, sql, "macro sql");
        PromptInjectionDefense.checkStrict("defineMacro", null, sql, "macro sql");

        boolean existed = AisqlCore.listMacroIds().contains(id);
        AisqlCore.saveMacro(id, title == null ? id : title, description == null ? "" : description, sql);

        AisqlCore.audit("defineMacro", null, sql, "write", "macro=" + id + " op=" + (existed ? "update" : "create"));

        return AisqlCore.info("Макрос '" + id + "' " + (existed ? "обновлён" : "создан") + ". "
            + "Выполнить: db_runMacro (name=\"" + id + "\").");
    }
}
