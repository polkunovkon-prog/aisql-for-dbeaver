/*
 * AI SQL for DBeaver — тесты профилей безопасности подключений.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.core.AisqlCore;
import com.aisql.dbeaver.core.ConnectionProfiles;

/**
 * Проверки разрешения профиля подключения.
 */
public final class ConnectionProfilesTest {

    private ConnectionProfilesTest() {
    }

    public static void run() {
        T.section("ConnectionProfiles");

        AisqlCore.saveSettingsSafe(AisqlCore.defaultSettings());

        T.eq(ConnectionProfiles.Profile.STANDARD, ConnectionProfiles.resolve("любое"),
            "профиль по умолчанию — STANDARD");

        AisqlCore.setSettingSafe(ConnectionProfiles.KEY_DEFAULT, "READ_ONLY");
        T.eq(ConnectionProfiles.Profile.READ_ONLY, ConnectionProfiles.resolve("conn"),
            "policy.defaultProfile=READ_ONLY");

        AisqlCore.setSettingSafe(ConnectionProfiles.KEY_PREFIX + "Sakila", "STANDARD");
        T.eq(ConnectionProfiles.Profile.STANDARD, ConnectionProfiles.resolve("Sakila"),
            "точное совпадение имени подключения");
        T.eq(ConnectionProfiles.Profile.STANDARD, ConnectionProfiles.resolve("sakila"),
            "точное совпадение без учёта регистра (через mask)");

        AisqlCore.setSettingSafe(ConnectionProfiles.KEY_PREFIX + "prod-*", "FULL");
        T.eq(ConnectionProfiles.Profile.FULL, ConnectionProfiles.resolve("prod-db"),
            "маска prod-* → FULL");
        T.eq(ConnectionProfiles.Profile.READ_ONLY, ConnectionProfiles.resolve("dev-db"),
            "остальные — профиль по умолчанию");

        AisqlCore.setSettingSafe(ConnectionProfiles.KEY_PREFIX + "broken", "NOT_A_PROFILE");
        T.eq(ConnectionProfiles.Profile.READ_ONLY, ConnectionProfiles.resolve("broken"),
            "неизвестное значение → профиль по умолчанию");

        T.eq(ConnectionProfiles.Profile.READ_ONLY,
            ConnectionProfiles.Profile.parse("read-only", null),
            "вариант записи «read-only»");
        T.eq(ConnectionProfiles.Profile.FULL,
            ConnectionProfiles.Profile.parse(" full ", null),
            "значение с пробелами");
        T.eq(null, ConnectionProfiles.Profile.parse("нет", null),
            "неизвестная строка → null при def=null");

        AisqlCore.saveSettingsSafe(AisqlCore.defaultSettings());
    }
}
