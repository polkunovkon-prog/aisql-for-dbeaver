/*
 * AI SQL for DBeaver — AI-функция db_getContext.
 *
 * Возвращает модели сведения о текущем подключении: СУБД и версия, драйвер,
 * пользователь, БД/схема по умолчанию, режим read-only, транзакции,
 * а также действующий профиль безопасности плагина.
 *
 * Тип — INFORMATION, режим — ALWAYS_ALLOW (безопасно, только чтение).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core.functions;

import com.aisql.dbeaver.core.AisqlCore;
import com.aisql.dbeaver.core.ConnectionProfiles;
import com.aisql.dbeaver.core.MaskingRules;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.DBPDataSourceInfo;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;
import org.jkiss.dbeaver.model.exec.DBCTransactionManager;

import java.util.Map;

/**
 * Контекст текущего подключения.
 */
public class GetContextFunction implements AIFunction {

    @Override
    public AIFunctionResult callFunction(AIFunctionContext context, Map<String, Object> args) throws DBException {
        DBCExecutionContext ctx = AisqlCore.execContext(context);

        StringBuilder sb = new StringBuilder();
        sb.append("Текущее подключение к базе данных:\n");

        try {
            var ds = ctx.getDataSource();
            var container = ds == null ? null : ds.getContainer();
            DBPConnectionConfiguration cfg = container == null ? null : container.getConnectionConfiguration();

            sb.append("- Соединение: ").append(container == null ? ctx.getContextName() : container.getName()).append('\n');
            if (cfg != null) {
                if (cfg.getUrl() != null) {
                    sb.append("- URL: ").append(cfg.getUrl()).append('\n');
                }
                sb.append("- Пользователь (учётная запись): ")
                    .append(cfg.getUserName() == null ? "<не задан>" : cfg.getUserName()).append('\n');
                if (cfg.getDatabaseName() != null) {
                    sb.append("- База данных: ").append(cfg.getDatabaseName()).append('\n');
                }
            }

            DBPDataSourceInfo info = ds == null ? null : ds.getInfo();
            if (info != null) {
                sb.append("- СУБД: ").append(str(info.getDatabaseProductName()))
                    .append(' ').append(str(info.getDatabaseProductVersion())).append('\n');
                sb.append("- Драйвер: ").append(str(info.getDriverName()))
                    .append(' ').append(str(info.getDriverVersion())).append('\n');
                sb.append("- Поддержка транзакций: ").append(info.supportsTransactions() ? "да" : "нет")
                    .append(", savepoints: ").append(info.supportsSavepoints() ? "да" : "нет").append('\n');
            }

            DBCExecutionContextDefaults<?, ?> defs = ctx.getContextDefaults();
            if (defs != null) {
                sb.append("- Схема по умолчанию: ").append(objName(defs.getDefaultSchema())).append('\n');
                sb.append("- Каталог/БД по умолчанию: ").append(objName(defs.getDefaultCatalog())).append('\n');
            }

            if (container != null) {
                sb.append("- Соединение «только чтение» (DBeaver): ").append(container.isConnectionReadOnly() ? "да" : "нет").append('\n');
                sb.append("- Автокоммит по умолчанию: ").append(container.isDefaultAutoCommit() ? "включён" : "выключен").append('\n');
            }

            DBCTransactionManager txn = DBUtils.getTransactionManager(ctx);
            if (txn != null) {
                try {
                    sb.append("- Поддержка транзакций драйвером: ").append(txn.isSupportsTransactions() ? "да" : "нет").append('\n');
                    if (txn.isSupportsTransactions()) {
                        sb.append("- Автокоммит сейчас: ").append(txn.isAutoCommit() ? "включён" : "выключен").append('\n');
                        sb.append("- Уровень изоляции: ").append(str(String.valueOf(txn.getTransactionIsolation()))).append('\n');
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            sb.append("\n(часть сведений недоступна: ").append(e.getMessage()).append(")\n");
        }

        sb.append('\n');
        sb.append("Безопасность плагина AI-SQL:\n");
        sb.append("- Профиль подключения: ").append(ConnectionProfiles.describe(AisqlCore.profileOf(ctx))).append('\n');
        try {
            if (AisqlCore.getBool(MaskingRules.KEY_ENABLED, true)) {
                int ruleCount = MaskingRules.loadRules().size();
                sb.append("- Маскирование данных: ")
                    .append(ruleCount > 0 ? "включено, правил: " + ruleCount : "включено, правил нет")
                    .append(AisqlCore.profileOf(ctx) == ConnectionProfiles.Profile.FULL
                        ? " (не действует при профиле FULL)" : "")
                    .append('\n');
            } else {
                sb.append("- Маскирование данных: выключено\n");
            }
        } catch (Exception ignored) {
        }
        sb.append("- Журнал аудита: ")
            .append(AisqlCore.getBool(AisqlCore.KEY_AUDIT_ENABLED, true) ? "включён" : "выключен")
            .append('\n');

        AisqlCore.auditExec("getContext", ctx, null, "read", "status=OK",
            null, "OK", -1, -1, -1, null);
        return AisqlCore.info(sb.toString());
    }

    private static String objName(Object o) {
        if (o == null) {
            return "<нет>";
        }
        if (o instanceof org.jkiss.dbeaver.model.DBPNamedObject n) {
            return n.getName();
        }
        return String.valueOf(o);
    }

    private static String str(String s) {
        return s == null || s.isEmpty() ? "<неизвестно>" : s;
    }
}
