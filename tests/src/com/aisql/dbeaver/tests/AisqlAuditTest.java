/*
 * AI SQL for DBeaver — тесты журнала аудита (JSONL + цепочка хешей + ротация).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.core.AisqlAudit;
import com.aisql.dbeaver.core.AisqlCore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Проверки аудита v2.
 */
public final class AisqlAuditTest {

    private static final Pattern HASH = Pattern.compile("\"hash\":\"([0-9a-f]{64})\"");
    private static final Pattern PREV = Pattern.compile("\"prev\":\"((?:[0-9a-f]{64})|genesis)\"");

    private AisqlAuditTest() {
    }

    public static void run() throws Exception {
        T.section("AisqlAudit (цепочка, редактирование, ротация)");

        AisqlCore.saveSettingsSafe(AisqlCore.defaultSettings());
        Path audit = AisqlAudit.file();
        cleanFiles(audit);
        // Кэш последнего хеша мог остаться от предыдущих тестов, писавших
        // в журнал; после очистки файла цепочка должна начинаться с genesis.
        AisqlAudit.resetChainCache();

        for (int i = 1; i <= 3; i++) {
            AisqlAudit.Event event = new AisqlAudit.Event();
            event.function = "test" + i;
            event.decision = "exec";
            event.conn = "conn [PostgreSQL]";
            event.category = "DML";
            event.outcome = "OK";
            event.sql = "UPDATE t SET a = 'x" + i + "' WHERE id = " + i;
            event.detail = "кавычки \" и перевод\nстроки";
            event.rows = i;
            event.durationMs = 10L * i;
            AisqlAudit.write(event);
        }

        List<String> lines = Files.readAllLines(audit, StandardCharsets.UTF_8);
        T.eq(3, lines.size(), "три записи в журнале");
        T.check(AisqlAudit.verify(audit), "цепочка хешей валидна");
        T.check(lines.get(0).contains("\"prev\":\"genesis\""),
            "первая запись ссылается на genesis");
        T.eq(extract(HASH, lines.get(0)), extract(PREV, lines.get(1)),
            "prev второй записи — хеш первой");
        T.eq(extract(HASH, lines.get(1)), extract(PREV, lines.get(2)),
            "prev третьей записи — хеш второй");
        T.check(lines.get(1).contains("\\\"") && lines.get(1).contains("\\n"),
            "кавычки и перевод строки экранированы в JSON");

        // --- Поля enterprise-формата: seq, host, session.
        T.check(lines.get(0).contains("\"seq\":1"), "seq первой записи = 1");
        T.check(lines.get(1).contains("\"seq\":2"), "seq второй записи = 2");
        T.check(lines.get(2).contains("\"seq\":3"), "seq третьей записи = 3");
        for (String line : lines) {
            T.check(line.contains("\"host\":\"") && !line.contains("\"host\":\"\""),
                "host заполнен: " + line.substring(0, Math.min(80, line.length())));
            T.check(Pattern.compile("\"session\":\"[0-9a-f-]{8,64}\"").matcher(line).find(),
                "session заполнен UUID");
        }
        // seq входит в хеш: цепочка валидна уже проверена выше — значит, поля
        // не выбиваются из контрольной суммы.

        // Подмена содержимого должна ломать цепочку.
        List<String> broken = new ArrayList<>(lines);
        broken.set(1, broken.get(1).replace("\"rows\":2", "\"rows\":999"));
        Files.write(audit, broken, StandardCharsets.UTF_8);
        T.check(!AisqlAudit.verify(audit), "подмена значения обнаружена");

        // Редактирование литералов.
        cleanFiles(audit);
        AisqlCore.setSettingSafe(AisqlAudit.KEY_REDACT_LITERALS, "true");
        AisqlAudit.Event redact = new AisqlAudit.Event();
        redact.function = "redact";
        redact.decision = "read";
        redact.sql = "SELECT * FROM t WHERE name = 'Иван' AND note = 'a''b'";
        AisqlAudit.write(redact);
        String text = Files.readString(audit, StandardCharsets.UTF_8);
        T.check(!text.contains("Иван"), "литерал не записан в журнал");
        T.check(text.contains("'?'"), "литерал заменён на '?'");
        T.check(AisqlAudit.verify(audit), "цепочка после редактирования валидна");
        AisqlCore.setSettingSafe(AisqlAudit.KEY_REDACT_LITERALS, "false");

        // Ротация.
        cleanFiles(audit);
        AisqlCore.setSettingSafe(AisqlAudit.KEY_MAX_BYTES, "400");
        AisqlCore.setSettingSafe(AisqlAudit.KEY_MAX_FILES, "3");
        for (int i = 0; i < 8; i++) {
            AisqlAudit.Event event = new AisqlAudit.Event();
            event.function = "rotate" + i;
            event.decision = "exec";
            event.sql = "INSERT INTO t VALUES (" + i + ") /* padding padding padding */";
            AisqlAudit.write(event);
        }
        int fileCount = countFiles(audit);
        T.check(fileCount > 1, "созданы повёрнутые файлы (найдено: " + fileCount + ")");
        T.check(fileCount <= 3, "число файлов не превышает audit.maxFiles=3");
        T.check(AisqlAudit.verify(audit), "цепочка текущего файла валидна после ротации");

        Path rotated = audit.resolveSibling(audit.getFileName() + ".1");
        T.check(Files.exists(rotated), "файл .1 существует");
        T.check(AisqlAudit.verify(rotated), "цепочка повёрнутого файла валидна");
        String firstLine = Files.readAllLines(audit, StandardCharsets.UTF_8).get(0);
        String rotatedTail = lastLine(rotated);
        T.eq(extract(HASH, rotatedTail), extract(PREV, firstLine),
            "цепочка продолжается после ротации (prev = хвост .1)");

        // --- Syslog: отправка события в локальный UDP-приёмник.
        testSyslogShipping(audit);

        cleanFiles(audit);
        AisqlCore.saveSettingsSafe(AisqlCore.defaultSettings());
    }

    /** Проверка отправки событий аудита в syslog (UDP, локальный приёмник). */
    private static void testSyslogShipping(Path audit) throws Exception {
        T.section("AisqlAudit → syslog (экспорт в SIEM)");

        cleanFiles(audit);
        AisqlAudit.resetChainCache();
        // Сброс лимитов ротации (после предыдущего раздела теста): иначе
        // события уходят в повёрнутые файлы и подсчёт строк ломается.
        AisqlCore.setSettingSafe(AisqlAudit.KEY_MAX_BYTES,
            String.valueOf(AisqlAudit.DEFAULT_MAX_BYTES));
        AisqlCore.setSettingSafe(AisqlAudit.KEY_MAX_FILES,
            String.valueOf(AisqlAudit.DEFAULT_MAX_FILES));

        try (java.net.DatagramSocket receiver = new java.net.DatagramSocket(new java.net.InetSocketAddress("127.0.0.1", 0))) {
            receiver.setSoTimeout(5000);
            int port = receiver.getLocalPort();

            AisqlCore.setSettingSafe(com.aisql.dbeaver.core.AuditSyslog.KEY_ENABLED, "true");
            AisqlCore.setSettingSafe(com.aisql.dbeaver.core.AuditSyslog.KEY_HOST, "127.0.0.1");
            AisqlCore.setSettingSafe(com.aisql.dbeaver.core.AuditSyslog.KEY_PORT, String.valueOf(port));
            AisqlCore.setSettingSafe(com.aisql.dbeaver.core.AuditSyslog.KEY_FACILITY, "16");

            // Обычное событие → severity 6 (informational): PRI = 16*8+6 = 134.
            AisqlAudit.Event ok = new AisqlAudit.Event();
            ok.function = "syslog_ok_test";
            ok.decision = "read";
            ok.outcome = "OK";
            AisqlAudit.write(ok);

            byte[] buf = new byte[65536];
            java.net.DatagramPacket packet = new java.net.DatagramPacket(buf, buf.length);
            receiver.receive(packet);
            String message = new String(buf, 0, packet.getLength(), StandardCharsets.UTF_8);
            T.check(message.startsWith("<134>"), "PRI=134 (local0, informational): " + message.substring(0, Math.min(40, message.length())));
            T.check(message.contains(" aisql "), "имя приложения aisql");
            T.check(message.contains("\"function\":\"syslog_ok_test\""), "JSON события в сообщении");
            T.check(message.contains("\"hash\":\""), "хеш цепочки передан в SIEM");
            T.check(!message.contains("\n"), "сообщение — одна строка (датаграмма)");

            // Событие отказа → severity 4 (warning): PRI = 16*8+4 = 132.
            AisqlAudit.Event denied = new AisqlAudit.Event();
            denied.function = "syslog_deny_test";
            denied.decision = "deny";
            denied.outcome = "DENIED";
            AisqlAudit.write(denied);

            receiver.receive(packet);
            String warning = new String(buf, 0, packet.getLength(), StandardCharsets.UTF_8);
            T.check(warning.startsWith("<132>"), "отказ → PRI=132 (warning)");
            T.check(warning.contains("\"outcome\":\"DENIED\""), "отказ передан в SIEM");
        } finally {
            AisqlCore.setSettingSafe(com.aisql.dbeaver.core.AuditSyslog.KEY_ENABLED, "false");
            AisqlCore.setSettingSafe(com.aisql.dbeaver.core.AuditSyslog.KEY_HOST, "");
        }

        // При выключенном syslog отправки нет — и это не ломает запись в файл.
        int linesBefore;
        Path file = AisqlAudit.file();
        linesBefore = Files.readAllLines(file, StandardCharsets.UTF_8).size();
        AisqlAudit.Event quiet = new AisqlAudit.Event();
        quiet.function = "quiet_test";
        quiet.outcome = "OK";
        AisqlAudit.write(quiet);
        T.eq(linesBefore + 1, Files.readAllLines(file, StandardCharsets.UTF_8).size(),
            "запись в файл продолжается при выключенном syslog");
    }

    private static void cleanFiles(Path audit) throws Exception {
        Files.deleteIfExists(audit);
        for (int i = 1; i <= 6; i++) {
            Files.deleteIfExists(audit.resolveSibling(audit.getFileName() + "." + i));
        }
    }

    private static int countFiles(Path audit) throws Exception {
        int count = 0;
        if (Files.exists(audit)) {
            count++;
        }
        for (int i = 1; i <= 6; i++) {
            if (Files.exists(audit.resolveSibling(audit.getFileName() + "." + i))) {
                count++;
            }
        }
        return count;
    }

    private static String lastLine(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        return lines.get(lines.size() - 1);
    }

    private static String extract(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
