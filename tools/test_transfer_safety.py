#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "desktop" / "app.py"

spec = importlib.util.spec_from_file_location("svln_app_for_tests", APP)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

safe_filename = module.safe_filename

valid = [
    "photo.jpg",
    "ملف عربي.pdf",
    "name with spaces.txt",
    "a.b.c",
    "fullwidth？already-valid.png",
]
for name in valid:
    assert safe_filename(name) == name, (name, safe_filename(name))

cases = {
    "thumb.png?1591494522": "thumb.png？1591494522",
    "Closure: test?.pdf": "Closure： test？.pdf",
    "bad|name*.txt": "bad｜name＊.txt",
    "trail.": "trail．",
    "trail ": "trail␠",
    "CON": "_CON",
}
for source, expected in cases.items():
    assert safe_filename(source) == expected, (source, safe_filename(source), expected)

long_name = "x" * 260 + ".txt"
a = safe_filename(long_name)
b = safe_filename(long_name)
assert a == b
assert len(a) <= 180
assert a.endswith(".txt")
assert "~" in a

long_name_2 = "x" * 259 + "y.txt"
assert safe_filename(long_name_2) != a

print("TRANSFER SAFETY TESTS OK")
