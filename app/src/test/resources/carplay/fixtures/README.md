# 车型重放 fixtures

由 `tools/extract_car_fixtures.py` 从真实运行日志提取（`*.jsonl`），供
`replay/BtLinkReplayTest`、`replay/TunnelReplayTest` 做跨车型回归：改某车型的
协议后，其它车型的录制刺激必须得到与录制一致的应答。

```bash
python3 tools/extract_car_fixtures.py ~/logs/mcarplay -o app/src/test/resources/carplay/fixtures
./gradlew testStandardDebugUnitTest --tests '*Replay*'
```

**`*.jsonl` 默认 gitignore**：含车机蓝牙地址、热点名、配对公钥等真实数据。需要入册
作为公共回归样本时逐个评审、脱敏（SSID 可化名；MAC 参与协议校验勿改）后再解除忽略。
无 fixture 时两个测试自动跳过（新鲜克隆不受影响）。

## 断言分级（提取时自动判定，写在每轮的 rxHasIdent 字段）

- RAW 转储预算旧值 512B/方向，常把识别帧（奥迪 575B、吉利 489B）拦腰截断；更隐蔽的是
  截断会把**不同 read 的尾巴拼成「长度恰好完整」的伪帧**（载荷校验和不过、引擎静默
  丢弃——重放调试实测发现）。提取器按引擎同款校验（帧头/载荷求和为零）逐帧验证，
  第一个坏帧起全部裁掉并标 `rxHasIdent`，此类轮只做**链路层断言**（SYN/采纳会话表/
  识别启动 0x1D00 必须与录制一致）；
- 识别帧完整幸存（`rxHasIdent=true`）→ **全量断言**：录制 distinct CSM 序列必须是
  回放的有序前缀；
- 隧道层：录制序列必须是回放的**有序子序列**（放行有意新增的应答，如 MIB3 突发，
  捕捉少答/乱序）；
- 预算已提至 4096B（`Iap2LinkChannel.RAW_DUMP_LIMIT*`）——新日志的识别/MFi/凭据
  全程可录，fixture 自动升级为全量断言。
