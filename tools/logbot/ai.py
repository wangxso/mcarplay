"""Optional second opinion from any OpenAI-compatible chat-completions endpoint (DeepSeek, Qwen, Kimi, OpenAI, ...)."""

from __future__ import annotations

import json
import os
import urllib.request
from dataclasses import dataclass

from logbot.redact import redact

SYSTEM_PROMPT = """你是 mcarplay 的日志诊断助手。mcarplay 是安卓 APK：从小米 CarWith 取画面/声音/触控，再作为 CarPlay 手机端
（蓝牙 iAP2 + Wi-Fi/AirPlay）连接无线 CarPlay 接收端（车机或盒子）。连接阶段依次为：连接准备、等待车机蓝牙、连接车机网络、
解析接收端、AirPlay 配对、建立 CarPlay 会话、等待 CarWith 首帧、传输画面。

规则引擎已给出结论（[rule:*] 行）。你的任务：结合日志片段补充最可能的原因和用户可以自己做的排查步骤。要求：
- 用简体中文，纯文本，不用 Markdown 标题和链接，不超过 8 行。
- 只依据给出的日志；证据不足就直说，不要编造车机型号、版本或协议细节。
- 区分证据层级：「收到源数据」「已发送」不等于接收端已显示或有声；缺少 END 不等于崩溃；连接重置不等于接收端崩溃。
- 不要声称某车型或手机「支持」或「不支持」。
- 不要复述规则结论，只补充新的信息；没有新的就回答「无补充」。"""


@dataclass(frozen=True)
class AiConfig:
    base_url: str
    api_key: str
    model: str
    timeout: float = 60.0

    @staticmethod
    def from_env() -> AiConfig | None:
        key = os.environ.get("LOGBOT_AI_API_KEY", "")
        base = os.environ.get("LOGBOT_AI_BASE_URL", "")
        model = os.environ.get("LOGBOT_AI_MODEL", "")
        if not (key and base and model):
            return None
        return AiConfig(base.rstrip("/"), key, model, float(os.environ.get("LOGBOT_AI_TIMEOUT", "60")))


def build_messages(excerpt: str, user_note: str = "") -> list[dict]:
    content = redact(excerpt)
    if user_note:
        content = "用户描述：" + redact(user_note)[:500] + "\n\n" + content
    return [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": content}]


def ask(config: AiConfig, excerpt: str, user_note: str = "", opener=urllib.request.urlopen) -> str:
    body = json.dumps(
        {"model": config.model, "messages": build_messages(excerpt, user_note), "temperature": 0.2, "max_tokens": 600}
    ).encode()
    request = urllib.request.Request(
        config.base_url + "/chat/completions",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {config.api_key}"},
        method="POST",
    )
    with opener(request, timeout=config.timeout) as response:
        data = json.loads(response.read().decode("utf-8"))
    text = data["choices"][0]["message"]["content"] or ""
    return "" if text.strip() in ("", "无补充", "无补充。") else text.strip()
