/*
 * AI SQL for DBeaver — отправка событий аудита в syslog (SIEM).
 *
 * Каждая запись журнала аудита дублируется датаграммой UDP в формате
 * RFC 5424-подобной строки:
 *
 *   <PRI>TIMESTAMP HOST APP PID MSGID MSG
 *
 * где MSG — сама JSON-строка события. Назначение настраивается:
 *
 *   audit.syslog.enabled=true
 *   audit.syslog.host=syslog.corp.example     # или IP
 *   audit.syslog.port=514
 *   audit.syslog.facility=16                  # local0 (0..23)
 *   audit.syslog.appname=aisql
 *   audit.syslog.hostname=                    # пусто — имя хоста ОС
 *
 * Отправка «fire-and-forget»: ошибка сети не ломает работу плагина
 * и не мешает записи в локальный файл. UDP не шифруется — для передачи
 * через недоверенные сети используйте локальный релей (rsyslog на
 * localhost с TLS-выходом), что является стандартной практикой.
 *
 * Внешняя копия событий (SIEM) — рекомендация NIST SP 800-92: локальная
 * цепочка хешей не защищает от злоумышленника с полным доступом к файлу;
 * вынос событий за пределы хоста закрывает этот разрыв.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Отправка событий аудита в syslog по UDP (RFC 5424-подобный формат).
 */
public final class AuditSyslog {

    public static final String KEY_ENABLED = "audit.syslog.enabled";
    public static final String KEY_HOST = "audit.syslog.host";
    public static final String KEY_PORT = "audit.syslog.port";
    public static final String KEY_FACILITY = "audit.syslog.facility";
    public static final String KEY_APPNAME = "audit.syslog.appname";
    public static final String KEY_HOSTNAME = "audit.syslog.hostname";

    public static final String DEFAULT_APPNAME = "aisql";
    public static final int DEFAULT_PORT = 514;
    public static final int DEFAULT_FACILITY = 16; // local0
    private static final int MAX_FACILITY = 23;

    /** Серьёзность: informational. */
    private static final int SEV_INFO = 6;
    /** Серьёзность: warning (отказы, блокировки). */
    private static final int SEV_WARNING = 4;

    private static volatile String cachedHost;

    private AuditSyslog() {
    }

    /** Включена ли отправка (и указан ли хост). */
    public static boolean enabled() {
        return AisqlCore.getBool(KEY_ENABLED, false)
            && AisqlCore.getSetting(KEY_HOST, "").trim().length() > 0;
    }

    /**
     * Отправляет событие (JSON-строка журнала без завершающего перевода
     * строки). Ошибки проглатываются: syslog не должен влиять на работу.
     *
     * @param deniedOrError событие отказа/ошибки → seriousity warning
     */
    public static void ship(String jsonLine, boolean deniedOrError) {
        if (jsonLine == null || jsonLine.isBlank() || !enabled()) {
            return;
        }
        try {
            String host = AisqlCore.getSetting(KEY_HOST, "").trim();
            int port = AisqlCore.getInt(KEY_PORT, DEFAULT_PORT);
            if (port <= 0 || port > 65535) {
                port = DEFAULT_PORT;
            }
            int facility = AisqlCore.getInt(KEY_FACILITY, DEFAULT_FACILITY);
            if (facility < 0 || facility > MAX_FACILITY) {
                facility = DEFAULT_FACILITY;
            }
            String appname = AisqlCore.getSetting(KEY_APPNAME, DEFAULT_APPNAME).trim();
            if (appname.isEmpty()) {
                appname = DEFAULT_APPNAME;
            }
            int severity = deniedOrError ? SEV_WARNING : SEV_INFO;
            int pri = facility * 8 + severity;
            String hostname = AisqlCore.getSetting(KEY_HOSTNAME, "").trim();
            if (hostname.isEmpty()) {
                hostname = localHostName();
            }
            String msg = "<" + pri + ">" + Instant.now().toString() + " "
                + hostname + " " + appname + " " + pid() + " audit "
                + jsonLine;
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            if (data.length > 65000) {
                return; // защита от превышения размера датаграммы
            }
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(1000);
                InetAddress address = InetAddress.getByName(host);
                socket.send(new DatagramPacket(data, data.length, address, port));
            }
        } catch (Exception e) {
            AisqlCorePlugin.log().debug("Не удалось отправить событие аудита в syslog", e);
        }
    }

    private static String localHostName() {
        String h = cachedHost;
        if (h != null) {
            return h;
        }
        try {
            h = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            h = "unknown";
        }
        cachedHost = h;
        return h;
    }

    private static String pid() {
        try {
            return String.valueOf(ProcessHandle.current().pid());
        } catch (Exception e) {
            return "-";
        }
    }
}
