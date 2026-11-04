"""Webhook server: ack QQ events at once, diagnose attached logs on a worker thread, reply passively."""

from __future__ import annotations

import json
import logging
import os
import re
import threading
import time
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from logbot import ai
from logbot.diagnose import diagnose, format_reply
from logbot.qq import DEFAULT_ATTACHMENT_HOSTS, QQBotClient, attachment_url, validation_response, verify
from logbot.redact import redact

log = logging.getLogger("logbot")

MESSAGE_EVENTS = {"C2C_MESSAGE_CREATE", "GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE"}
LOG_FILE = re.compile(r"(?i)\.(txt|log)$")
REPORT_FILE = re.compile(r"(?i)^mcarplay-log-.*\.txt$")
SAFE_TOKEN = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
MAX_BODY_BYTES = 1_000_000

HELP_TEXT = """我是 mcarplay 日志诊断机器人。
1. 在 mcarplay 首页点「导出脱敏诊断报告」，在 下载/mcarplay/ 找到 mcarplay-log-…-diagnostics.txt。
2. 私聊直接把这个文件发给我；在群里可以「回复」那条文件消息并 @我。
3. 可以在同一条消息里简单描述现象，例如「车机黑屏但有声音」。
我只读取文件做诊断，不保存日志。分享前请自行检查报告内容。"""


class Bot:
    def __init__(self, client: QQBotClient, ai_config: ai.AiConfig | None = None, cooldown_s: float = 15.0,
                 attachment_hosts=DEFAULT_ATTACHMENT_HOSTS, clock=time.monotonic):
        self.client = client
        self.ai_config = ai_config
        self.cooldown_s = cooldown_s
        self.attachment_hosts = attachment_hosts
        self._clock = clock
        self._lock = threading.Lock()
        self._seen: OrderedDict[str, None] = OrderedDict()
        self._last_by_user: dict[str, float] = {}

    def first_delivery(self, msg_id: str) -> bool:
        with self._lock:
            if msg_id in self._seen:
                return False
            self._seen[msg_id] = None
            if len(self._seen) > 2000:
                self._seen.popitem(last=False)
            return True

    def handle_event(self, event_type: str, data: dict) -> None:
        if event_type not in MESSAGE_EVENTS or not self.first_delivery(str(data.get("id", ""))):
            return
        author = data.get("author") or {}
        target = {"msg_id": data.get("id", "")}
        if data.get("group_openid"):
            target["group_openid"] = data["group_openid"]
        else:
            target["user_openid"] = author.get("user_openid") or author.get("id", "")
        user = author.get("member_openid") or author.get("user_openid") or author.get("id", "")
        files = [a for a in collect_attachments(data) if LOG_FILE.search(str(a.get("filename", "")))]
        text = str(data.get("content", "")).strip()

        if event_type == "GROUP_MESSAGE_CREATE":
            # 未 @ 的群消息只处理明显是诊断报告的文件，避免刷屏。
            files = [a for a in files if REPORT_FILE.match(str(a.get("filename", "")))]
            if not files:
                return
        if not files:
            self.client.reply(target, HELP_TEXT)
            return
        if not self._allow(user):
            self.client.reply(target, "处理太频繁，请稍后再发。")
            return
        self.client.reply(target, self.diagnose_attachment(files[-1], text))

    def diagnose_attachment(self, attachment: dict, note: str = "") -> str:
        size = attachment.get("size")
        if isinstance(size, int) and size > 16_000_000:
            return "文件超过 16MB，请导出默认大小的诊断报告。"
        url = attachment_url(str(attachment.get("url", "")), self.attachment_hosts)
        if url is None:
            log.warning("attachment host not allowed")
            return "无法下载这个附件，请重新发送文件。"
        try:
            raw = self.client.download(url)
        except Exception as error:  # noqa: BLE001 - 网络错误只回友好提示
            log.warning("download failed: %s", type(error).__name__)
            return "下载附件失败，请稍后重新发送。"
        diagnosis = diagnose(raw.decode("utf-8", errors="replace"))
        log.info("diagnosed kind=%s bytes=%d findings=%d", diagnosis.kind, len(raw), len(diagnosis.findings))
        ai_text = ""
        if self.ai_config and diagnosis.kind != "unknown":
            try:
                ai_text = redact(ai.ask(self.ai_config, diagnosis.excerpt, note))
            except Exception as error:  # noqa: BLE001 - AI 失败时仍返回规则结论
                log.warning("ai failed: %s", type(error).__name__)
        return format_reply(diagnosis, ai_text)

    def _allow(self, user: str) -> bool:
        now = self._clock()
        with self._lock:
            last = self._last_by_user.get(user)
            if last is not None and now - last < self.cooldown_s:
                return False
            self._last_by_user[user] = now
            return True


def collect_attachments(data: dict, depth: int = 0) -> list[dict]:
    """Top-level attachments plus those inside quoted messages (message_type=103)."""
    found = [a for a in data.get("attachments") or [] if isinstance(a, dict)]
    if depth < 3:
        for element in data.get("msg_elements") or []:
            if isinstance(element, dict):
                found = collect_attachments(element, depth + 1) + found
    return found


def make_handler(bot: Bot, secret: str, path: str, executor: ThreadPoolExecutor):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):  # noqa: N802 - http.server API
            if self.path.split("?")[0] != path:
                return self._send(404, {})
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_BODY_BYTES:
                return self._send(413, {})
            body = self.rfile.read(length)
            signature = self.headers.get("X-Signature-Ed25519", "")
            timestamp = self.headers.get("X-Signature-Timestamp", "")
            try:
                payload = json.loads(body)
            except ValueError:
                return self._send(400, {})
            if not isinstance(payload, dict):
                return self._send(400, {})
            op, data = payload.get("op"), payload.get("d") or {}
            if op == 13:
                # 无签名头时只签纯字母数字，确保本服务不能被当作任意内容的签名器来伪造事件。
                if signature and not verify(secret, timestamp, body, signature):
                    return self._send(401, {})
                if not SAFE_TOKEN.match(str(data.get("plain_token", ""))) or not str(data.get("event_ts", "")).isdigit():
                    return self._send(400, {})
                return self._send(200, validation_response(secret, data))
            if not verify(secret, timestamp, body, signature):
                return self._send(401, {})
            if op == 0 and isinstance(data, dict):
                executor.submit(_safe_handle, bot, str(payload.get("t", "")), data)
            return self._send(200, {"op": 12})

        def _send(self, status: int, body: dict):
            raw = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, fmt, *args):
            log.debug("http " + fmt, *args)

    return Handler


def _safe_handle(bot: Bot, event_type: str, data: dict) -> None:
    try:
        bot.handle_event(event_type, data)
    except Exception:
        log.exception("event %s failed", event_type)


def serve() -> None:
    app_id = os.environ["QQBOT_APP_ID"]
    secret = os.environ["QQBOT_APP_SECRET"]
    hosts = tuple(h.strip() for h in os.environ.get("LOGBOT_ATTACHMENT_HOSTS", "").split(",") if h.strip())
    bot = Bot(
        QQBotClient(app_id, secret),
        ai.AiConfig.from_env(),
        cooldown_s=float(os.environ.get("LOGBOT_COOLDOWN_S", "15")),
        attachment_hosts=hosts or DEFAULT_ATTACHMENT_HOSTS,
    )
    host = os.environ.get("LOGBOT_HOST", "127.0.0.1")
    port = int(os.environ.get("LOGBOT_PORT", "8080"))
    path = os.environ.get("LOGBOT_PATH", "/qqbot")
    executor = ThreadPoolExecutor(max_workers=int(os.environ.get("LOGBOT_WORKERS", "4")))
    server = ThreadingHTTPServer((host, port), make_handler(bot, secret, path, executor))
    log.info("listening on %s:%d%s ai=%s", host, port, path, "on" if bot.ai_config else "off")
    server.serve_forever()
