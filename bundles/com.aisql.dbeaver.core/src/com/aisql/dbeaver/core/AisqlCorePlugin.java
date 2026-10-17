/*
 * AI SQL for DBeaver — активатор core-бандла.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Plugin;
import org.jkiss.dbeaver.Log;
import org.osgi.framework.BundleContext;

/**
 * Активатор core-бандла плагина «ИИ-исполнитель SQL».
 */
public class AisqlCorePlugin extends Plugin {

    public static final String PLUGIN_ID = "com.aisql.dbeaver.core";

    private static final Log log = Log.getLog(AisqlCorePlugin.class);

    private static volatile AisqlCorePlugin instance;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        log.info("AI SQL core plugin started, version " + version());
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        instance = null;
        super.stop(context);
    }

    public static AisqlCorePlugin getInstance() {
        return instance;
    }

    public static Log log() {
        return log;
    }

    /** Версия бандла (например «1.0.0»). */
    public static String version() {
        try {
            return Platform.getBundle(PLUGIN_ID).getVersion().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
