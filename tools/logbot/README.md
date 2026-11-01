# mcarplay 日志诊断 QQ 机器人

接入 [QQ 官方机器人](https://q.qq.com)（API v2，Webhook 回调）。用户把 mcarplay 导出的诊断报告发给机器人，机器人回复
规则引擎结论；配置了 AI 接口时，再附一段 AI 补充分析。

- 规则引擎（`diagnose.py`、`rules.py`）复用 `tools/analyze_diagnostics.py` 解析连接时间线，再检查错误码、阶段、
  源帧/发送计数、导出时权限与组件、系统退出记录、未捕获异常和运行日志中的已知特征（ENONET、屏幕数据口关闭、熄屏断流等）。
  错误码含义与 `app/.../diagnostics/ConnectionTimeline.kt`、`docs/DIAGNOSTICS.md` 保持一致，新增代码时同步 `rules.py`。
- AI（`ai.py`）是可选项，任何 OpenAI 兼容的 `/chat/completions` 接口都可以（DeepSeek、通义千问、Kimi、OpenAI 等）。
  只发送规则结论和挑选过的日志片段（约 14KB 以内），发送前再做一次脱敏（`redact.py`）。AI 失败时仍回复规则结论。
- 日志只在内存中处理，不落盘。服务端日志只记录事件类型、大小和条数，不记录内容。

## 用户怎么用

1. mcarplay 首页点「导出脱敏诊断报告」，文件在 `下载/mcarplay/mcarplay-log-…-diagnostics.txt`。
2. 私聊把文件发给机器人；在群里「回复」那条文件消息并 @机器人（群里单独发文件时不会 @，机器人收不到）。
   如果机器人有 `GROUP_MESSAGE_CREATE`（群内全部消息）权限，群里直接发 `mcarplay-log-*.txt` 也会被处理。
3. 同一条消息里可以附一句现象描述，会作为 AI 的参考。

## 部署

1. 在 QQ 开放平台创建机器人，取得 AppID 和 AppSecret；开通单聊、群聊消息事件（`GROUP_AND_C2C_EVENT`）。
2. 服务器准备 Python 3.11+：
   ```bash
   python3 -m venv .venv && .venv/bin/python -m pip install -r tools/requirements.txt
   ```
3. 设置环境变量并启动（不要把密钥写进仓库；`.env` 已被 git 忽略）：
   ```bash
   export QQBOT_APP_ID=... QQBOT_APP_SECRET=...
   # 可选：OpenAI 兼容接口
   export LOGBOT_AI_BASE_URL=https://api.deepseek.com/v1 LOGBOT_AI_API_KEY=... LOGBOT_AI_MODEL=deepseek-chat
   cd tools && ../.venv/bin/python -m logbot serve
   ```
4. 平台只接受 80/443/8080/8443 端口的 HTTPS 回调。用 Nginx/Caddy 终止 TLS，反代到 `127.0.0.1:8080/qqbot`，
   在开放平台填 `https://你的域名/qqbot`。保存时平台会发 op=13 验证请求，服务会自动签名应答。

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `LOGBOT_HOST` / `LOGBOT_PORT` / `LOGBOT_PATH` | `127.0.0.1` / `8080` / `/qqbot` | 监听地址 |
| `LOGBOT_WORKERS` | `4` | 并发诊断线程 |
| `LOGBOT_COOLDOWN_S` | `15` | 同一用户两次诊断的最小间隔 |
| `LOGBOT_ATTACHMENT_HOSTS` | `qq.com,qq.com.cn,qpic.cn,gtimg.cn` | 允许下载附件的域名后缀 |
| `LOGBOT_AI_TIMEOUT` | `60` | AI 请求超时（秒） |

## 本地调试

不连 QQ，直接看机器人会回复什么：

```bash
cd tools && ../.venv/bin/python -m logbot diagnose ~/Downloads/mcarplay-log-xxx-diagnostics.txt
```

`--excerpt` 打印将发给 AI 的脱敏文本，`--ai --note "车机黑屏"` 实际调用 AI。测试随仓库的 Python 测试一起运行：

```bash
.venv/bin/python -m unittest discover -s tools -p 'test_*.py'
```

## 限制

- 结论只代表手机侧日志能证明的事实；不判定车型或手机「支持」。车机内部故障可能需要接收端日志。
- 被动回复有时效：群聊 5 分钟、单聊 60 分钟，诊断要在这之内完成（AI 超时默认 60 秒）。
- 平台会拦截消息里的链接，回复中的 URL 会被替换为「[链接已省略]」。
- 只接受 `.txt` / `.log`，单个文件不超过 16MB；不解压压缩包。
