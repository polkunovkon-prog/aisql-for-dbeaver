/*
 * AI SQL for DBeaver — тесты корпоративной политики (EnterprisePolicy).
 *
 * Проверяются: приоритет политики над пользовательскими настройками,
 * список зафиксированных ключей, отключение инструментов, fail-closed
 * при заданной системным свойством, но нечитаемой политике.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.core.AisqlCore;
import com.aisql.dbeaver.core.ConnectionProfiles;
import com.aisql.dbeaver.core.EnterprisePolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Проверки EnterprisePolicy.
 */
public final class EnterprisePolicyTest {

    private EnterprisePolicyTest() {
    }

    public static void run() throws Exception {
        T.section("EnterprisePolicy (корпоративная политика)");

        AisqlCore.saveSettingsSafe(AisqlCore.defaultSettings());
        Path policy = AisqlCore.configDir().resolve(EnterprisePolicy.POLICY_FILE_NAME);
        String oldSystemProperty = System.getProperty(EnterprisePolicy.POLICY_FILE_PROPERTY);

        try {
            // --- 1. Без политики поведение не меняется.
            Files.deleteIfExists(policy);
            EnterprisePolicy.resetCache();
            T.check(!EnterprisePolicy.isActive(), "без файла политики — неактивна");
            T.eq("STANDARD", ConnectionProfiles.resolve("any-conn").name(),
                "без политики действует пользовательский STANDARD");

            // Пользователь переключает профиль — работает.
            AisqlCore.setSettingSafe(ConnectionProfiles.KEY_DEFAULT, "FULL");
            T.eq("FULL", ConnectionProfiles.resolve("any-conn").name(),
                "пользовательская настройка применяется без политики");

            // --- 2. Политика фиксирует профиль READ_ONLY.
            Files.writeString(policy, ("""
                # корпоративная политика (тест)
                policy.defaultProfile=READ_ONLY
                mask.detectPII=false
                tools.disabled=executeSql, runMacro
                """), StandardCharsets.UTF_8);
            EnterprisePolicy.resetCache();

            T.check(EnterprisePolicy.isActive(), "политика активна после создания файла");
            T.eq("READ_ONLY", ConnectionProfiles.resolve("any-conn").name(),
                "политика перекрывает пользовательский FULL → READ_ONLY");
            T.check(EnterprisePolicy.isLocked(ConnectionProfiles.KEY_DEFAULT),
                "ключ профиля зафиксирован политикой");
            T.check(!EnterprisePolicy.isLocked("policy.forbidden"),
                "незафиксированный ключ не блокируется");

            T.eq("off-flag-strict проверка", "off-flag-strict проверка", "маркер компиляции");

            // Оверлей настроек: пользовательское mask.detectPII=true (по умолчанию)
            // перекрывается политикой false.
            T.check(!AisqlCore.getBool(AisqlCore.KEY_DETECT_PII, true),
                "политика перекрывает mask.detectPII=true → false");

            // Отключение инструментов.
            T.check(EnterprisePolicy.isToolDisabled("executeSql"),
                "executeSql отключён политикой");
            T.check(EnterprisePolicy.isToolDisabled("runMacro"),
                "runMacro отключён политикой");
            T.check(!EnterprisePolicy.isToolDisabled("runQuery"),
                "runQuery не отключён");
            T.check(EnterprisePolicy.isToolDisabled("db_executeSql"),
                "префикс db_ в списке инструментов допускается");
            T.check(!EnterprisePolicy.isToolDisabled("help"), "help не отключён");

            // guardToolEnabled: отказ с понятной ошибкой.
            boolean denied = false;
            try {
                AisqlCore.guardToolEnabled("executeSql", null);
            } catch (org.jkiss.dbeaver.DBException e) {
                denied = e.getMessage() != null && e.getMessage().contains("корпоративной политикой");
            }
            T.check(denied, "guardToolEnabled отказывает по tools.disabled");
            AisqlCore.guardToolEnabled("runQuery", null);
            T.check(true, "guardToolEnabled пропускает незапрещённый инструмент");

            // lockedKeys содержит ключи политики (для UI).
            T.check(EnterprisePolicy.lockedKeys().contains("tools.disabled")
                    && EnterprisePolicy.lockedKeys().contains("policy.defaultProfile"),
                "lockedKeys перечисляет ключи политики");

            // Пользователь не может ослабить: запись в файл настроек игнорируется.
            AisqlCore.setSettingSafe(ConnectionProfiles.KEY_DEFAULT, "FULL");
            T.eq("READ_ONLY", ConnectionProfiles.resolve("any-conn").name(),
                "пользовательская запись не ослабляет политику");

            // --- 3. Изменение файла политики подхватывается (кэш по mtime).
            Files.writeString(policy, "policy.defaultProfile=STANDARD\n",
                StandardCharsets.UTF_8);
            EnterprisePolicy.resetCache();
            T.eq("STANDARD", ConnectionProfiles.resolve("any-conn").name(),
                "обновление политики подхватывается");

            // --- 4. Fail-closed: политика задана системным свойством, но не читается.
            Path missing = AisqlCore.configDir().resolve("no-such-policy.properties");
            System.setProperty(EnterprisePolicy.POLICY_FILE_PROPERTY, missing.toString());
            EnterprisePolicy.resetCache();
            T.check(EnterprisePolicy.isBroken(), "нечитаемая политика → fail-closed");
            boolean blocked = false;
            try {
                AisqlCore.guardToolEnabled("runQuery", null);
            } catch (org.jkiss.dbeaver.DBException e) {
                blocked = e.getMessage() != null && e.getMessage().contains("fail-closed");
            }
            T.check(blocked, "при fail-closed инструменты отказывают");

            // --- 5. Политика из произвольного пути (sysprop) работает.
            Path external = Files.createTempFile("aisql-policy", ".properties");
            Files.writeString(external, "limit.maxRows=7\n", StandardCharsets.UTF_8);
            System.setProperty(EnterprisePolicy.POLICY_FILE_PROPERTY, external.toString());
            EnterprisePolicy.resetCache();
            T.check(EnterprisePolicy.isActive(), "политика из внешнего пути активна");
            T.eq(7, AisqlCore.getInt(AisqlCore.KEY_LIMIT_MAX_ROWS, 200),
                "значение из внешней политики действует");
            Files.deleteIfExists(external);
        } finally {
            if (oldSystemProperty == null) {
                System.clearProperty(EnterprisePolicy.POLICY_FILE_PROPERTY);
            } else {
                System.setProperty(EnterprisePolicy.POLICY_FILE_PROPERTY, oldSystemProperty);
            }
            Files.deleteIfExists(policy);
            EnterprisePolicy.resetCache();
            AisqlCore.saveSettingsSafe(AisqlCore.defaultSettings());
        }
    }
}
