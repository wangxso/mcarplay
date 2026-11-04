"""Second redaction pass before any text leaves the bot. Raw runtime logs are not redacted by the app."""

from __future__ import annotations

import re

_RULES = [
    (re.compile(r"-----BEGIN [A-Z ]*-----[\s\S]*?-----END [A-Z ]*-----"), "[key material omitted]"),
    (re.compile(r"(?i)\b(password|passwd|passphrase|psk|pin|token|secret|auth_token|apikey|api_key|key)\b(\s*[:=]\s*)\S+"), r"\1\2<redacted>"),
    (re.compile(r"(?i)\b(b?ssid)\b(\s*[:=]\s*)(\"[^\"]*\"|\S+)"), r"\1\2<ssid>"),
    (re.compile(r"\b[0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5}\b"), "<mac>"),
    (re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b"), "<ip>"),
    (re.compile(r"\b(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{1,4}\b"), "<ip6>"),
    (re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+"), "<email>"),
    (re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)"), "<phone>"),
    (re.compile(r"\b[0-9A-Fa-f]{40,}\b"), "<hex>"),
    (re.compile(r"(?i)\bhttps?://\S+"), "<url>"),
]


def redact(text: str) -> str:
    for pattern, replacement in _RULES:
        text = pattern.sub(replacement, text)
    return text
