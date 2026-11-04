import io
import json
import unittest

from logbot import ai
from logbot.qq import QQBotClient, attachment_url, sign, split_message, validation_response, verify
from logbot.server import HELP_TEXT, Bot, collect_attachments

SECRET = "DG5g3B4j9X2KOErG"
REPORT = (
    "mcarplay support report / 诊断报告 schema=1\nreportId=abcdef12\n"
    "=== CONNECTION TIMELINE / 最近进程与连接事件 (JSONL schema=1) ===\n"
    '{"process":"p","attempt":"a1","event":"END","stage":"WIFI","status":"failed","code":"WIFI_JOIN_FAILED"}\n'
    "=== RUNTIME LOG / 运行日志 ===\n"
)


class FakeClient:
    def __init__(self, payload=REPORT.encode()):
        self.payload = payload
        self.replies = []
        self.downloads = []

    def reply(self, target, text):
        self.replies.append((target, text))

    def download(self, url):
        self.downloads.append(url)
        return self.payload


def event(**extra):
    data = {"id": "m1", "author": {"user_openid": "u1"}, "content": ""}
    data.update(extra)
    return data


FILE = {"content_type": "file", "filename": "mcarplay-log-20261101-abcdef12-diagnostics.txt",
        "url": "multimedia.nt.qq.com.cn/download?fileid=1", "size": 1000}


class SigningTest(unittest.TestCase):
    def test_validation_matches_official_example(self):
        response = validation_response(SECRET, {"plain_token": "Arq0D5A61EgUu4OxUvOp", "event_ts": "1725442341"})
        self.assertEqual(
            "87befc99c42c651b3aac0278e71ada338433ae26fcb24307bdc5ad38c1adc2d0"
            "1bcfcadc0842edac85e85205028a1132afe09280305f13aa6909ffc2d652c706",
            response["signature"],
        )

    def test_verify_rejects_tampering(self):
        body = b'{"op":0,"t":"C2C_MESSAGE_CREATE","d":{}}'
        signature = sign(SECRET, b"1725442341" + body)
        self.assertTrue(verify(SECRET, "1725442341", body, signature))
        self.assertFalse(verify(SECRET, "1725442342", body, signature))
        self.assertFalse(verify(SECRET, "1725442341", body + b" ", signature))
        self.assertFalse(verify(SECRET, "1725442341", body, "zz"))


class BotTest(unittest.TestCase):
    def test_c2c_file_is_diagnosed_and_replied_once(self):
        client = FakeClient()
        bot = Bot(client)
        bot.handle_event("C2C_MESSAGE_CREATE", event(attachments=[FILE]))
        bot.handle_event("C2C_MESSAGE_CREATE", event(attachments=[FILE]))  # 重复推送
        self.assertEqual(1, len(client.replies))
        target, text = client.replies[0]
        self.assertEqual({"msg_id": "m1", "user_openid": "u1"}, target)
        self.assertIn("WIFI_JOIN_FAILED", text)
        self.assertEqual(["https://multimedia.nt.qq.com.cn/download?fileid=1"], client.downloads)

    def test_group_at_without_file_gets_help(self):
        client = FakeClient()
        Bot(client).handle_event("GROUP_AT_MESSAGE_CREATE", event(group_openid="g1", author={"member_openid": "u1"}))
        self.assertEqual([({"msg_id": "m1", "group_openid": "g1"}, HELP_TEXT)], client.replies)

    def test_quoted_file_in_group_is_found(self):
        client = FakeClient()
        data = event(group_openid="g1", message_type=103, msg_elements=[{"attachments": [FILE]}])
        Bot(client).handle_event("GROUP_AT_MESSAGE_CREATE", data)
        self.assertIn("WIFI_JOIN_FAILED", client.replies[0][1])

    def test_unmentioned_group_messages_only_take_report_files(self):
        client = FakeClient()
        bot = Bot(client)
        bot.handle_event("GROUP_MESSAGE_CREATE", event(id="m1", group_openid="g1", content="hi"))
        bot.handle_event("GROUP_MESSAGE_CREATE", event(id="m2", group_openid="g1", attachments=[dict(FILE, filename="notes.txt")]))
        self.assertEqual([], client.replies)
        bot.handle_event("GROUP_MESSAGE_CREATE", event(id="m3", group_openid="g1", attachments=[FILE]))
        self.assertEqual(1, len(client.replies))

    def test_cooldown_per_user(self):
        client = FakeClient()
        bot = Bot(client, cooldown_s=15, clock=lambda: 100.0)
        bot.handle_event("C2C_MESSAGE_CREATE", event(id="m1", attachments=[FILE]))
        bot.handle_event("C2C_MESSAGE_CREATE", event(id="m2", attachments=[FILE]))
        self.assertIn("太频繁", client.replies[1][1])

    def test_foreign_host_and_oversized_files_are_not_downloaded(self):
        client = FakeClient()
        bot = Bot(client)
        bot.handle_event("C2C_MESSAGE_CREATE", event(id="m1", attachments=[dict(FILE, url="https://evil.example/x.txt")]))
        bot.handle_event("C2C_MESSAGE_CREATE", event(id="m2", author={"user_openid": "u2"}, attachments=[dict(FILE, size=20_000_000)]))
        self.assertEqual([], client.downloads)
        self.assertEqual(2, len(client.replies))

    def test_ai_failure_still_returns_rule_reply(self):
        client = FakeClient()
        original = ai.ask
        ai.ask = lambda *args, **kwargs: (_ for _ in ()).throw(TimeoutError())
        try:
            Bot(client, ai.AiConfig("https://x", "k", "m")).handle_event("C2C_MESSAGE_CREATE", event(attachments=[FILE]))
        finally:
            ai.ask = original
        self.assertIn("WIFI_JOIN_FAILED", client.replies[0][1])
        self.assertNotIn("AI 补充分析", client.replies[0][1])


class HelpersTest(unittest.TestCase):
    def test_attachment_url_allowlist(self):
        self.assertEqual("https://a.qq.com/f", attachment_url("//a.qq.com/f"))
        self.assertIsNone(attachment_url("https://qq.com.evil.example/f"))
        self.assertIsNone(attachment_url("file:///etc/passwd"))

    def test_collect_attachments_includes_quotes(self):
        data = {"attachments": [{"filename": "a"}], "msg_elements": [{"attachments": [{"filename": "b"}]}]}
        self.assertEqual(["b", "a"], [a["filename"] for a in collect_attachments(data)])

    def test_split_message_respects_size_and_part_limit(self):
        parts = split_message("\n".join("x" * 100 for _ in range(200)), size=1500, max_parts=4)
        self.assertEqual(4, len(parts))
        self.assertTrue(all(len(p) <= 1500 for p in parts))
        self.assertIn("已截断", parts[-1])


class ClientTest(unittest.TestCase):
    def test_token_cached_and_reply_strips_urls(self):
        calls = []

        class Response(io.BytesIO):
            headers = {}

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

        def opener(request, timeout):
            calls.append((request.full_url, request.headers.get("Authorization"), json.loads(request.data)))
            if request.full_url.endswith("getAppAccessToken"):
                return Response(b'{"access_token":"T","expires_in":"7200"}')
            return Response(b"{}")

        client = QQBotClient("1", "s", opener=opener, clock=lambda: 0.0)
        client.reply({"msg_id": "m1", "user_openid": "u1"}, "see https://x.example/a")
        client.reply({"msg_id": "m2", "group_openid": "g1"}, "ok")
        self.assertEqual(1, sum(url.endswith("getAppAccessToken") for url, _, _ in calls))
        url, auth, body = calls[1]
        self.assertEqual("https://api.bot.qq.com/v2/users/u1/messages", url)
        self.assertEqual("QQBot T", auth)
        self.assertEqual({"content": "see [链接已省略]", "msg_type": 0, "msg_id": "m1", "msg_seq": 1}, body)
        self.assertEqual("https://api.bot.qq.com/v2/groups/g1/messages", calls[2][0])


if __name__ == "__main__":
    unittest.main()
