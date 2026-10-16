/*
 * AI SQL for DBeaver — тесты журнала аудита (JSONL + цепочка хешей + ротация).
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.tests;

import com.pka.dbeaver.aisql.core.AisqlAudit;
import com.pka.dbeaver.aisql.core.AisqlCore;

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

        cleanFiles(audit);
        AisqlCore.saveSettingsSafe(AisqlCore.defaultSettings());
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
