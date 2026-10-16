#!/usr/bin/env python3
"""Проверка целостности журнала аудита AI SQL for DBeaver.

Журнал — JSON Lines: каждая строка связана с предыдущей цепочкой SHA-256
(hash = SHA256(prev + "\n" + строка без поля "hash")).

Использование:
    ./releng/verify-audit.py [файл журнала]
Без аргумента ищет ai-sql-audit.jsonl в типичных рабочих средах DBeaver.
Код возврата: 0 — цепочка валидна, 1 — нарушение целостности, 2 — ошибка.
"""
import glob
import hashlib
import json
import os
import sys


def verify_file(path):
    """Возвращает (ok, сообщение, число записей)."""
    prev_expected = None
    count = 0
    with open(path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line:
                continue
            count += 1
            idx = line.rfind(',"hash":"')
            if idx < 0 or not line.endswith("}"):
                return False, f"строка {lineno}: нет поля hash", count
            content = line[:idx] + "}"
            try:
                obj = json.loads(content)
            except Exception as e:  # noqa: BLE001
                return False, f"строка {lineno}: некорректный JSON: {e}", count
            stated_prev = obj.get("prev")
            if stated_prev is None:
                return False, f"строка {lineno}: нет поля prev", count
            try:
                stated_hash = json.loads(line)["hash"]
            except Exception as e:  # noqa: BLE001
                return False, f"строка {lineno}: не удалось прочитать hash: {e}", count
            calc = hashlib.sha256(
                (stated_prev + "\n" + content).encode("utf-8")
            ).hexdigest()
            if calc != stated_hash:
                return False, f"строка {lineno}: хеш не совпадает с содержимым", count
            if prev_expected is not None and stated_prev != prev_expected:
                return False, f"строка {lineno}: разрыв цепочки (prev ≠ hash предыдущей)", count
            prev_expected = stated_hash
    return True, "цепочка валидна", count


def find_default():
    patterns = [
        os.path.expanduser("~/.local/share/DBeaverData/workspace*/.metadata/.config/ai-sql-audit.jsonl"),
        os.path.expanduser("~/.dbeaver*/workspace*/.metadata/.config/ai-sql-audit.jsonl"),
    ]
    for pattern in patterns:
        found = sorted(glob.glob(pattern))
        if found:
            return found[-1]
    return None


def main():
    if len(sys.argv) > 1:
        files = sys.argv[1:]
    else:
        default = find_default()
        if default is None:
            print("Журнал не найден. Укажите файл: verify-audit.py <файл>", file=sys.stderr)
            return 2
        files = [default]
    result = 0
    for path in files:
        if not os.path.exists(path):
            print(f"{path}: файл не найден", file=sys.stderr)
            result = 2
            continue
        ok, message, count = verify_file(path)
        print(f"{path}: записей {count}, {message}")
        if not ok:
            result = 1
    return result


if __name__ == "__main__":
    sys.exit(main())
