/*
 * AI SQL for DBeaver — запуск всех офлайн-тестов.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.tests;

import com.pka.dbeaver.aisql.core.AisqlCore;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Точка входа тестов. Каталог настроек подменяется через системное
 * свойство {@code aisql.config.dir}, чтобы тесты не трогали рабочие настройки.
 */
public final class AllTests {

    private AllTests() {
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("aisql-tests");
        Path config = temp.resolve("config");
        System.setProperty(AisqlCore.CONFIG_DIR_PROPERTY, config.toString());
        System.out.println("AI SQL for DBeaver — офлайн-тесты");
        System.out.println("Каталог настроек тестов: " + config);
        System.out.println();

        GlobsTest.run();
        SqlClassifierTest.run();
        MaskingRulesTest.run();
        ConnectionProfilesTest.run();
        QueryPolicyTest.run();
        AisqlAuditTest.run();
        PromptBuilderTest.run();
        AiEditLogicTest.run();
        AiEditDiffTest.run();

        int code = T.summary();
        System.out.println(code == 0 ? "ИТОГ: все проверки пройдены." : "ИТОГ: есть ошибки.");
        System.exit(code);
    }
}
