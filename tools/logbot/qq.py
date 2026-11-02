"""QQ official bot (bot.q.qq.com, API v2) webhook signing, access token and passive replies."""

from __future__ import annotations

import json
import re
import threading
import time
import urllib.parse
import urllib.request

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

API_BASE = "https://api.bot.qq.com"
TOKEN_URL = "https://api.bot.qq.com/app/getAppAccessToken"
MAX_DOWNLOAD_BYTES = 16_000_000
# 附件地址不一定带协议头；只下载 QQ 自己的文件域名。
DEFAULT_ATTACHMENT_HOSTS = ("qq.com", "qq.com.cn", "qpic.cn", "gtimg.cn")
URL_IN_TEXT = re.compile(r"(?i)\b(?:https?://|www\.)\S+")


def _signing_key(secret: str) -> Ed25519PrivateKey:
    seed = secret
    while len(seed) < 32:
        seed *= 2
    return Ed25519PrivateKey.from_private_bytes(seed[:32].encode())


def sign(secret: str, message: bytes) -> str:
    return _signing_key(secret).sign(message).hex()


def verify(secret: str, timestamp: str, body: bytes, signature_hex: str) -> bool:
    try:
        signature = bytes.fromhex(signature_hex)
    except ValueError:
        return False
    if not timestamp or len(signature) != 64 or signature[63] & 224:
        return False
    public: Ed25519PublicKey = _signing_key(secret).public_key()
    try:
        public.verify(signature, timestamp.encode() + body)
        return True
    except Exception:
        return False


def validation_response(secret: str, data: dict) -> dict:
    """op=13 回调地址验证：对 event_ts + plain_token 签名。"""
    plain = str(data.get("plain_token", ""))
    return {"plain_token": plain, "signature": sign(secret, (str(data.get("event_ts", "")) + plain).encode())}


def split_message(text: str, size: int = 1500, max_parts: int = 4) -> list[str]:
    parts, current = [], ""
    for line in text.splitlines():
        while len(line) > size:
            if current:
                parts.append(current)
                current = ""
            parts.append(line[:size])
            line = line[size:]
        if len(current) + len(line) + 1 > size:
            parts.append(current)
            current = line
        else:
            current = f"{current}\n{line}" if current else line
    if current:
        parts.append(current)
    if len(parts) > max_parts:
        parts = parts[:max_parts]
        parts[-1] = parts[-1][: size - 10] + "\n…（已截断）"
    return parts


def attachment_url(raw: str, allowed_hosts=DEFAULT_ATTACHMENT_HOSTS) -> str | None:
    url = raw.strip()
    if url.startswith("//"):
        url = "https:" + url
    elif not re.match(r"(?i)^https?://", url):
        url = "https://" + url
    parsed = urllib.parse.urlsplit(url)
    host = (parsed.hostname or "").lower()
    if parsed.scheme.lower() not in ("http", "https") or not any(host == h or host.endswith("." + h) for h in allowed_hosts):
        return None
    return url


class QQBotClient:
    def __init__(self, app_id: str, secret: str, opener=urllib.request.urlopen, clock=time.monotonic):
        self.app_id = app_id
        self.secret = secret
        self._opener = opener
        self._clock = clock
        self._lock = threading.Lock()
        self._token = ""
        self._expires_at = 0.0

    def access_token(self) -> str:
        with self._lock:
            if self._token and self._clock() < self._expires_at - 60:
                return self._token
            data = self._post_json(TOKEN_URL, {"appId": self.app_id, "clientSecret": self.secret}, auth=False)
            self._token = data["access_token"]
            self._expires_at = self._clock() + int(data.get("expires_in", 7200))
            return self._token

    def reply(self, target: dict, text: str) -> None:
        """被动回复：单聊 60 分钟内最多 4 次，群聊 5 分钟内最多 5 次。"""
        path = f"/v2/groups/{target['group_openid']}/messages" if target.get("group_openid") else f"/v2/users/{target['user_openid']}/messages"
        for seq, part in enumerate(split_message(URL_IN_TEXT.sub("[链接已省略]", text)), start=target.get("seq_start", 1)):
            self._post_json(API_BASE + path, {"content": part, "msg_type": 0, "msg_id": target["msg_id"], "msg_seq": seq})

    def download(self, url: str, limit: int = MAX_DOWNLOAD_BYTES) -> bytes:
        request = urllib.request.Request(url, headers={"User-Agent": "mcarplay-logbot"})
        with self._opener(request, timeout=30) as response:
            length = response.headers.get("Content-Length")
            if length and int(length) > limit:
                raise ValueError("attachment too large")
            data = response.read(limit + 1)
        if len(data) > limit:
            raise ValueError("attachment too large")
        return data

    def _post_json(self, url: str, payload: dict, auth: bool = True) -> dict:
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = f"QQBot {self.access_token()}"
        request = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers, method="POST")
        with self._opener(request, timeout=20) as response:
            body = response.read().decode("utf-8") or "{}"
        return json.loads(body)
