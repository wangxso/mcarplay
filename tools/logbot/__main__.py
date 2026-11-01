"""python -m logbot serve | python -m logbot diagnose FILE [--ai] [--note TEXT]"""

from __future__ import annotations

import argparse
import logging
from pathlib import Path

from logbot import ai
from logbot.diagnose import diagnose, format_reply


def main() -> None:
    parser = argparse.ArgumentParser(prog="logbot", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("serve", help="run the QQ webhook server (env: QQBOT_APP_ID, QQBOT_APP_SECRET)")
    local = commands.add_parser("diagnose", help="print the reply the bot would send for a local file")
    local.add_argument("file", type=Path)
    local.add_argument("--ai", action="store_true", help="also ask the configured LOGBOT_AI_* endpoint")
    local.add_argument("--note", default="", help="user description passed to the AI")
    local.add_argument("--excerpt", action="store_true", help="print the redacted text that would be sent to the AI")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

    if args.command == "serve":
        from logbot.server import serve

        serve()
        return
    if args.file.stat().st_size > 16_000_000:
        parser.error("file exceeds the 16MB limit")
    diagnosis = diagnose(args.file.read_text(encoding="utf-8", errors="replace"))
    if args.excerpt:
        print("\n".join(m["content"] for m in ai.build_messages(diagnosis.excerpt, args.note)[1:]))
        return
    ai_text = ""
    if args.ai:
        config = ai.AiConfig.from_env()
        if config is None:
            parser.error("set LOGBOT_AI_BASE_URL, LOGBOT_AI_API_KEY and LOGBOT_AI_MODEL")
        ai_text = ai.ask(config, diagnosis.excerpt, args.note)
    print(format_reply(diagnosis, ai_text))


if __name__ == "__main__":
    main()
