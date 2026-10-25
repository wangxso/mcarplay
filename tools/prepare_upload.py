#!/usr/bin/env python3
"""Create a source-only upload folder and ZIP without requiring a Git checkout."""

from __future__ import annotations

import hashlib
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = {
    ".editorconfig", ".gitattributes", ".gitignore", "AGENTS.md", "LICENSE", "NOTICE",
    "README.md", "README.zh-CN.md", "DISCLAIMER.md", "THIRD_PARTY_NOTICES.md",
    "IMPLEMENTATION_PLAN.md", "OcarPlay-analysis.md", "build.gradle.kts",
    "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
    "keystore.properties.example", "app/build.gradle.kts", "app/lint-baseline.xml",
    "gradle/wrapper/gradle-wrapper.jar",
}
SOURCE_TREES = (".github", "app/src", "gradle", "tools", "docs")
SUFFIXES = {".md", ".txt", ".kt", ".kts", ".java", ".aidl", ".xml", ".py",
            ".properties", ".yml", ".yaml", ".png", ".jpg", ".webp", ".svg", ".pro"}
PRIVATE_KEY_MARKERS = (b"-----BEGIN PRIVATE KEY-----", b"-----BEGIN RSA PRIVATE KEY-----",
                       b"-----BEGIN EC PRIVATE KEY-----", b"-----BEGIN OPENSSH PRIVATE KEY-----")


def source_files() -> list[Path]:
    selected = {ROOT / name for name in ROOT_FILES}
    for directory in SOURCE_TREES:
        for path in (ROOT / directory).rglob("*"):
            if path.is_symlink():
                raise SystemExit(f"Refusing symlink: {path.relative_to(ROOT)}")
            if path.is_file() and path.suffix in SUFFIXES:
                if not any(part in {"__pycache__", ".venv", "build", ".gradle", ".git"} for part in path.relative_to(ROOT).parts):
                    selected.add(path)
    for path in sorted(selected):
        if path.is_symlink() or not path.is_file():
            raise SystemExit(f"Missing or unsafe source file: {path.relative_to(ROOT)}")
        if any(marker in path.read_bytes() for marker in PRIVATE_KEY_MARKERS):
            # This script itself lists the markers it checks for.
            if path != Path(__file__).resolve():
                raise SystemExit(f"Private-key marker found: {path.relative_to(ROOT)}")
    return sorted(selected)


def main() -> None:
    files = source_files()
    output = ROOT / "dist" / "github-upload"
    archive = ROOT / "dist" / "mcarplay-source.zip"
    if output.exists() or output.is_symlink() or archive.exists() or archive.is_symlink():
        raise SystemExit("Upload output already exists; move or remove dist/github-upload and dist/mcarplay-source.zip first.")
    output.mkdir(parents=True)
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for path in files:
            relative = path.relative_to(ROOT)
            target = output / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
            bundle.write(target, str(relative))
    print(f"Prepared {len(files)} source files in {output}")
    print(f"Source ZIP: {archive}")
    print(f"SHA-256: {hashlib.sha256(archive.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
