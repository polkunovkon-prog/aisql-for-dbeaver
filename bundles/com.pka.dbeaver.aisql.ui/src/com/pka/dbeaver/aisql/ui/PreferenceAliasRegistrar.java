/*
 * AI SQL for DBeaver — динамическая регистрация alias-страницы настроек.
 *
 * В кнопке «настроить инструменты» ИИ-чата DBeaver открывает страницу
 * с идентификатором com.dbeaver.preferences.ai.tools, которую вендор
 * не регистрирует. Мы регистрируем по этому id тот же класс страницы,
 * что и наша собственная страница com.pka.dbeaver.aisql.ui.preferences,
 * — но только если id ещё свободен (на случай, если DBeaver когда-нибудь
 * зарегистрирует её сам или это сделает другой плагин).
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.ui;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IContributor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.IStartup;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Регистрирует alias-страницу настроек при старте рабочей среды.
 */
public class PreferenceAliasRegistrar implements IStartup {

    /** Id, который ищет кнопка настройки инструментов ИИ-чата DBeaver. */
    public static final String ALIAS_PAGE_ID = "com.dbeaver.preferences.ai.tools";

    /** Наша собственная страница настроек. */
    public static final String OUR_PAGE_ID = "com.pka.dbeaver.aisql.ui.preferences";

    private static final String PREF_PAGES_EXT =
        "org.eclipse.ui.preferencePages";

    @Override
    public void earlyStartup() {
        try {
            IExtensionRegistry registry = Platform.getExtensionRegistry();
            for (IConfigurationElement element
                    : registry.getConfigurationElementsFor(PREF_PAGES_EXT)) {
                if (ALIAS_PAGE_ID.equals(element.getAttribute("id"))) {
                    AisqlUIPlugin.log().info(
                        "Страница настроек " + ALIAS_PAGE_ID + " уже зарегистрирована ("
                            + element.getContributor().getName() + "), alias не создаётся.");
                    return;
                }
            }

            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<plugin>\n"
                + "  <extension point=\"" + PREF_PAGES_EXT + "\">\n"
                + "    <page id=\"" + ALIAS_PAGE_ID + "\"\n"
                + "          class=\"" + AisqlPreferencePage.class.getName() + "\"\n"
                + "          name=\"AI SQL: инструменты SQL\"/>\n"
                + "  </extension>\n"
                + "</plugin>\n";

            IContributor contributor = new IContributor() {
                @Override
                public String getName() {
                    return "com.pka.dbeaver.aisql.ui.alias";
                }
            };
            boolean ok = registry.addContribution(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                contributor, false, "AI SQL preference page alias", null, null);
            if (ok) {
                AisqlUIPlugin.log().info("Зарегистрирован alias страницы настроек "
                    + ALIAS_PAGE_ID + " (кнопка настройки инструментов ИИ-чата).");
            } else {
                AisqlUIPlugin.log().warn("Не удалось зарегистрировать alias страницы "
                    + ALIAS_PAGE_ID + ".");
            }
        } catch (Throwable t) {
            AisqlUIPlugin.log().warn("Ошибка при регистрации alias страницы "
                + ALIAS_PAGE_ID, t);
        }
    }
}
