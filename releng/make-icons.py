#!/usr/bin/env python3
"""Генерация иконок плагина AI SQL for DBeaver (16x16 PNG, без зависимостей)."""
import os
import struct
import zlib


def write_png(path, width, height, rows):
    """rows: список строк, каждая — список кортежей (r, g, b, a)."""
    raw = b"".join(
        b"\x00" + b"".join(struct.pack("4B", *px) for px in row)
        for row in rows
    )

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", header))
        f.write(chunk(b"IDAT", zlib.compress(raw, 9)))
        f.write(chunk(b"IEND", b""))


def blank():
    return [[(0, 0, 0, 0) for _ in range(16)] for _ in range(16)]


def circle(rows, cx, cy, r, color, x0=0, x1=15, y0=0, y1=15):
    for y in range(max(0, y0), min(15, y1) + 1):
        for x in range(max(0, x0), min(15, x1) + 1):
            dx, dy = x - cx, y - cy
            if dx * dx + dy * dy <= r * r:
                rows[y][x] = color


def draw_mask(rows, mask, ox, oy, color):
    for dy, line in enumerate(mask):
        for dx, ch in enumerate(line):
            if ch != " ":
                x, y = ox + dx, oy + dy
                if 0 <= x < 16 and 0 <= y < 16:
                    rows[y][x] = color


def make_ask_icon(path):
    """Синий круг с белым знаком вопроса."""
    rows = blank()
    blue = (43, 116, 216, 255)
    white = (255, 255, 255, 255)
    circle(rows, 7, 7, 7, blue)
    question = [
        " ##### ",
        "##   ##",
        "##   ##",
        "     ##",
        "   ### ",
        "   ##  ",
        "   ##  ",
        "       ",
        "   ##  ",
        "   ##  ",
    ]
    draw_mask(rows, question, 4, 2, white)
    write_png(path, 16, 16, rows)


def make_settings_icon(path):
    """Три горизонтальных ползунка (настройки)."""
    rows = blank()
    gray = (96, 96, 96, 255)
    knob = (43, 116, 216, 255)
    for y in (3, 8, 13):
        for x in range(1, 15):
            rows[y][x] = gray
    for cx in (4, 11, 7):
        pass
    for kx, ky in ((4, 3), (11, 8), (7, 13)):
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                x, y = kx + dx, ky + dy
                if 0 <= x < 16 and 0 <= y < 16:
                    rows[y][x] = knob
    write_png(path, 16, 16, rows)


def main():
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "..", "bundles", "com.pka.dbeaver.aisql.ui", "icons")
    os.makedirs(base, exist_ok=True)
    make_ask_icon(os.path.join(base, "aisql_ask.png"))
    make_settings_icon(os.path.join(base, "aisql_settings.png"))
    print("Иконки созданы в", os.path.abspath(base))


if __name__ == "__main__":
    main()
