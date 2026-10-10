#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
errors: list[str] = []

def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")

def expect(path: str, pattern: str, label: str, expected: str = version) -> None:
    text = read(path)
    match = re.search(pattern, text)
    if not match:
        errors.append(f"{label}: version marker not found in {path}")
        return
    actual = match.group(1)
    if actual != expected:
        errors.append(f"{label}: expected {expected}, found {actual} in {path}")

expect("desktop/app.py", r'APP_VERSION\s*=\s*"([^"]+)"', "Windows app")
expect("desktop/windows_runtime.py", r'APP_VERSION\s*=\s*"([^"]+)"', "Windows runtime")
expect("desktop/installer.iss", r'#define MyAppVersion\s+"([^"]+)"', "Windows installer")
expect("desktop/web/index.html", r'v(\d+\.\d+\.\d+)', "Windows/PWA UI")
expect("pwa/index.html", r'v(\d+\.\d+\.\d+)', "PWA gateway")
expect("app/build.gradle", r"versionName\s+'([^']+)'", "Android package")
expect("app/src/main/java/com/explapp/sendvialocalnet/StableMainActivity.java", r'text\("v([^"]+)"', "Android UI")
expect("ios/SendViaLocalNet/ContentView.swift", r'Text\("v([^"]+)"\)', "iPhone UI")

gradle = read("app/build.gradle")
code = re.search(r"versionCode\s+(\d+)", gradle)
if not code or int(code.group(1)) <= 0:
    errors.append("Android versionCode must be a positive integer")

installer = read("desktop/installer.iss")
expected_file_version = version + ".0"
m = re.search(r"VersionInfoVersion=([0-9.]+)", installer)
if not m or m.group(1) != expected_file_version:
    errors.append(f"Windows VersionInfoVersion must be {expected_file_version}")

# Critical transfer-safety invariants that protect the large iPhone -> Windows job.
windows_app = read("desktop/app.py")
web_ui = read("desktop/web/index.html")

for marker, label in [
    ("WINDOWS_CHAR_REPLACEMENTS", "Windows incompatible-name repair"),
    ("max_component = 180", "Windows overlong-name protection"),
    ("renamed_for_windows", "Windows rename reporting"),
]:
    if marker not in windows_app:
        errors.append(f"{label}: required marker missing in desktop/app.py")

for marker, label in [
    ("probeTarget", "receiver reachability probe"),
    ("load failed", "Safari Load failed handling"),
    ("RenamedForWindowsCompatibility", "rename diagnostics report"),
    ("targetHealth", "target circuit breaker"),
]:
    if marker not in web_ui:
        errors.append(f"{label}: required marker missing in desktop/web/index.html")

if errors:
    print("QA FAILED")
    for item in errors:
        print(" -", item)
    sys.exit(1)

print(f"QA OK: all visible/package versions are synchronized at {version}")
