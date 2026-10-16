/*
 * AI SQL for DBeaver — активатор UI-бандла.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.ui;

import com.pka.dbeaver.aisql.core.AisqlCore;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.jkiss.dbeaver.Log;
import org.osgi.framework.BundleContext;

/**
 * UI-бандл плагина «ИИ-исполнитель SQL».
 */
public class AisqlUIPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.pka.dbeaver.aisql.ui";

    private static final Log log = Log.getLog(AisqlUIPlugin.class);

    private static volatile AisqlUIPlugin instance;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        log.info("AI SQL UI plugin started, version " + version());
        try {
            // Создаём файл настроек со значениями по умолчанию при первом запуске,
            // чтобы пользователю было что редактировать вручную.
            AisqlCore.ensureSettingsFile();
        } catch (Throwable t) {
            log.debug("Не удалось создать файл настроек плагина", t);
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        instance = null;
        super.stop(context);
    }

    public static AisqlUIPlugin getInstance() {
        return instance;
    }

    public static Log log() {
        return log;
    }

    /** Версия бандла. */
    public static String version() {
        try {
            return Platform.getBundle(PLUGIN_ID).getVersion().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static ImageDescriptor imageDescriptor(String path) {
        return AbstractUIPlugin.imageDescriptorFromPlugin(PLUGIN_ID, path);
    }
}
