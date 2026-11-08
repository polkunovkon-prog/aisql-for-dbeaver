/*
 * AI SQL for DBeaver — корпоративная политика (enterprise policy).
 *
 * Позволяет администратору зафиксировать настройки безопасности так, чтобы
 * конечный пользователь не мог их ослабить через файл настроек или страницу
 * настроек плагина.
 *
 * Источник политики (по приоритету):
 *   1) системное свойство -Daisql.enterprise.policy=<путь к файлу>
 *      (администратор задаёт в dbeaver.ini; файл читается только для чтения);
 *   2) <config>/ai-sql-enterprise.properties рядом с настройками плагина.
 *
 * Любой ключ из файла политики переопределяет значение пользователя.
 * Поддерживаются те же ключи, что и в ai-sql-plugin.properties, плюс:
 *   tools.disabled=executeSql,runMacro,...   — заблокированные инструменты
 *     (идентификаторы как в plugin.xml, без префикса db_).
 *
 * Fail-closed: если файл политики задан системным свойством и не читается,
 * или в нём синтаксическая ошибка, — все инструменты плагина отказываются
 * работать (ошибка с понятным текстом). Ошибку «прятать» небезопасно:
 * отсутствие политики не должно молчно превращаться в «всё разрешено».
 *
 * Политика не защищает от пользователя с правами на файл политики:
 * администратор размещает файл вне досягаемости пользователя (например,
 * /etc/dbeaver/ai-sql-enterprise.properties, чтение только root).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Корпоративная политика поверх пользовательских настроек.
 */
public final class EnterprisePolicy {

    /** Системное свойство: путь к файлу политики (задаёт администратор). */
    public static final String POLICY_FILE_PROPERTY = "aisql.enterprise.policy";
    /** Имя файла политики в каталоге настроек (вариант без -D). */
    public static final String POLICY_FILE_NAME = "ai-sql-enterprise.properties";

    /** Ключ политики: список заблокированных инструментов (через запятую). */
    public static final String KEY_TOOLS_DISABLED = "tools.disabled";

    private static final Object LOCK = new Object();
    private static volatile Properties cache;
    private static volatile long mtime = -1;
    private static volatile long size = -1;
    private static volatile Path cachedFile;
    /** Сбой загрузки при заданном системном свойстве → fail-closed. */
    private static volatile boolean broken;

    private EnterprisePolicy() {
    }

    /** Путь к файлу политики ({@code null} — политики нет). */
    public static Path policyFile() {
        try {
            String viaProperty = System.getProperty(POLICY_FILE_PROPERTY);
            if (viaProperty != null && !viaProperty.isBlank()) {
                return Path.of(viaProperty.trim());
            }
            Path f = AisqlCore.configDir().resolve(POLICY_FILE_NAME);
            return Files.exists(f) ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Политика активна (файл существует и загрузился). */
    public static boolean isActive() {
        refresh();
        return cache != null && !cache.isEmpty();
    }

    /**
     * Fail-closed: политика задана системным свойством, но не читается/не
     * разбирается. В этом состоянии инструменты должны отказывать в работе.
     */
    public static boolean isBroken() {
        refresh();
        return broken;
    }

    /** Значение ключа из политики либо {@code null} (в политике ключа нет). */
    public static String value(String key) {
        refresh();
        return cache == null ? null : cache.getProperty(key);
    }

    /** Ключ зафиксирован политикой (пользовательское значение игнорируется). */
    public static boolean isLocked(String key) {
        return value(key) != null;
    }

    /** Все ключи, зафиксированные политикой (для отображения в UI). */
    public static List<String> lockedKeys() {
        refresh();
        if (cache == null || cache.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(cache.stringPropertyNames());
        Collections.sort(keys);
        return keys;
    }

    /**
     * Инструмент заблокирован политикой (tools.disabled).
     * Идентификаторы сравниваются без учёта регистра; префикс {@code db_}
     * в записи списка допускается.
     */
    public static boolean isToolDisabled(String functionId) {
        String raw = value(KEY_TOOLS_DISABLED);
        if (raw == null || raw.isBlank() || functionId == null || functionId.isBlank()) {
            return false;
        }
        String want = functionId.toLowerCase(Locale.ROOT);
        if (want.startsWith("db_")) {
            want = want.substring(3);
        }
        for (String chunk : raw.split("[,;\\s]+")) {
            String t = chunk.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("db_")) {
                t = t.substring(3);
            }
            if (t.equals(want)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Наложение политики на пользовательские настройки: значения политики
     * выигрывают. Вызывается из {@link AisqlCore#loadSettings()}.
     */
    static Properties overlay(Properties userSettings) {
        refresh();
        if (cache == null || cache.isEmpty()) {
            return userSettings;
        }
        Properties effective = new Properties();
        if (userSettings != null) {
            effective.putAll(userSettings);
        }
        effective.putAll(cache);
        return effective;
    }

    /** Сброс кэша (для тестов). */
    public static void resetCache() {
        synchronized (LOCK) {
            cache = null;
            cachedFile = null;
            mtime = -1;
            size = -1;
            broken = false;
        }
    }

    private static void refresh() {
        Path f = policyFile();
        if (f == null) {
            synchronized (LOCK) {
                cache = null;
                cachedFile = null;
                mtime = -1;
                size = -1;
                broken = false;
            }
            return;
        }
        boolean viaProperty = System.getProperty(POLICY_FILE_PROPERTY) != null
            && !System.getProperty(POLICY_FILE_PROPERTY).isBlank();
        try {
            long m = Files.getLastModifiedTime(f).toMillis();
            long s = Files.size(f);
            synchronized (LOCK) {
                if (cache != null && f.equals(cachedFile) && m == mtime && s == size) {
                    return;
                }
            }
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            synchronized (LOCK) {
                cache = p;
                cachedFile = f;
                mtime = m;
                size = s;
                broken = false;
            }
        } catch (Exception e) {
            // Файл задан явно, но недоступен/битый — fail-closed.
            synchronized (LOCK) {
                cache = null;
                cachedFile = null;
                mtime = -1;
                size = -1;
                broken = viaProperty;
            }
            AisqlCorePlugin.log().debug("Не удалось прочитать корпоративную политику: " + f, e);
        }
    }
}
