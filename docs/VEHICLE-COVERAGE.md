# 车机适配覆盖表

本表用于决定**先测哪些车机**，并记录每台车机走到了哪一步。它不是支持列表：只有按
`AGENTS.md` 要求在 `CARWITH-ENTRY-FINDINGS.md` 留下带日期的实机证据后，某一行才能标为通过。
「公开线索」列来自外部调研（2026-10-28 整理），本项目尚未逐条核实，只作测试优先级参考。

提交新结果请使用 GitHub issue 表单「Vehicle compatibility report / 车机适配报告」，并附导出的脱敏诊断报告。

## 为什么按供应链分族

同一品牌的不同车型可能来自不同 Tier-1；不同品牌也可能共用同一套 CarPlay 接收端中间件。
mcarplay 真正面对的是**接收端协议实现**，所以记录分三层，逐步把「未知」补成实据：

| 层级 | 字段 | 来源 |
| --- | --- | --- |
| 车辆 | OEM、车型、年款、市场 | 车主 |
| 车机 | HU Tier-1、SoC、操作系统、IVI 平台、车机软件版本 | 车机设置页、铭牌、公开资料、固件分析 |
| CarPlay 实现 | CarPlay 中间件（如 Tier-1 自研、AllGo、其他供应商、未知） | 固件中的库名/进程名/许可证；不能只凭外观或品牌推断 |
| 协议指纹 | iAP2 `0x1d01` 名称/型号/制造商/固件/EA 协议；mDNS `srcvers`/`features`；AirPlay `/info` 型号、`sourceVersion`、音频格式；`COMPAT:` 家族与规则 | mcarplay 运行日志与诊断报告（自动采集） |
| mcarplay 结果 | 蓝牙、iAP2、Wi-Fi、AirPlay 配对、视频、音频、触控/按键、重连 | 实测 + 诊断报告 |

协议指纹相同而车机品牌不同，往往说明共用中间件；指纹应当优先于品牌决定兼容策略
（见 [接收端兼容设计](RECEIVER-COMPATIBILITY.md)）。

## 目标族

优先级按「公开线索强度 × 国内保有量 × 对 mcarplay 的代表性」排序。状态均为本项目实测状态。

| 族编号 | 厂商 | 公开线索（待核实） | 已知 OEM 线索（待核实） | 优先级 | mcarplay 状态 |
| --- | --- | --- | --- | --- | --- |
| `DESAY_SV` | 德赛西威 | 自称国内首家申请 CarPlay 认证的汽车电子企业；奇瑞、荣威、MG 车型 CarPlay 车机配套（[1](https://www.desaysv.com/newsDetails/75.html)、[2](https://www.desaysv.com/newsDetails/76.html)、[3](https://www.desaysv.com/newsDetails/575.html)） | 奇瑞（瑞虎、艾瑞泽）、荣威、MG、理想海外车型 | 1 | 未测 |
| `ECARX_CLOUDPEAK` | 亿咖通 | Cloudpeak 软件栈声明原生 CarPlay/无线 CarPlay（[1](https://www.ecarxgroup.com/press/ecarx-hosts-2026-global-partner-conference-accelerating-innovation-led-global-expansion/)、[2](https://ir.ecarxgroup.com/news-releases/news-release-details/ecarx-cloudpeak-digital-cockpit-software-stack-will-be-essential)） | 吉利、领克、Volvo EX30、Galaxy 海外车型、VW/Škoda 部分平台 | 1 | 未测 |
| `NEUSOFT_NAGIVI` | 东软 | NAGIVI 全球 IVI 声明有线/无线 CarPlay、Android Auto、CarLife、HiCar（[1](https://www.neusoft.com/cn/products/%E6%99%BA%E8%83%BD%E6%B1%BD%E8%BD%A6%E4%BA%92%E8%81%94/%E6%99%BA%E8%83%BD%E5%BA%A7%E8%88%B1/nagivi%E5%85%A8%E7%90%83ivi%E8%BD%AF%E4%BB%B6%E4%BA%A7%E5%93%81/)、[2](https://www.neusoft.com/cn/%E4%B8%9C%E8%BD%AF%E8%8E%B7%E5%90%89%E5%88%A9%E6%B1%BD%E8%BD%A6%E6%96%B0%E8%AE%A2%E5%8D%95/)） | 吉利、领克、几何（经亿咖通定点）、一汽红旗 | 1 | 未测 |
| `ADAYO` | 华阳通用 | 智能座舱手机互联列出 CarPlay、Android Auto、HiCar、CarLife | 国内前装 OEM、海外 OEM/ODM | 1 | 未测 |
| `HARMAN` | HARMAN | 2017 年宣布首个商用无线 CarPlay 集成（[1](https://news.harman.com/releases/harman-pioneers-first-ever-integration-of-apple-carplay-through-wireless-connectivity)） | 德系豪华品牌、Suzuki、Toyota | 2 | 未测 |
| `VISTEON_ALLGO` | 伟世通（含 AllGo 中间件） | SmartCore 支持无线 CarPlay；2016 年收购做手机互联中间件的 AllGo | Mercedes A 级、Mahindra、VW | 2 | 未测 |
| `PANASONIC_SKIPGEN` | Panasonic Automotive | SkipGen IVI 声明深度集成 CarPlay（[1](https://automotive.panasonic.com/en/newsroom/20230104-02)、[2](https://news.panasonic.com/global/de/press/en260319-4)） | Toyota（2026 RAV4）、日系/北美 OEM | 3 | 未测 |
| `CONTINENTAL` | Continental Automotive | 官方称 Apple CarPlay 合作伙伴（[1](https://www.continental-automotive.com/en/career/what-we-are-working-on/infotainment.html)） | 欧洲 OEM、商用车 | 3 | 未测 |

**观察名单（暂不单独建族）：** Bosch、Denso、LG VS、Marelli、Aptiv、博泰 PATEO。它们做过带
CarPlay 的座舱项目，但「提供 IVI」不等于 CarPlay 接收端协议栈是自研的。找到具体车型的库名、
进程名、许可证或供应商材料后再升级为独立族。

## 覆盖矩阵

`✓` 通过（有实机证据）、`✗` 失败、`·` 未测、`—` 不适用。桌面接收端行作为对照基线。

| 车型 / 接收端 | 族 | Tier-1 / SoC / OS | 中间件 | 蓝牙 | iAP2 | Wi-Fi | 配对 | 视频 | 音频 | 触控 | 重连 | 证据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WinPlay 1.2.5（Windows） | 桌面基线 | — | WinPlay（开源） | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | [2026-10-27](CARWITH-ENTRY-FINDINGS.md) |
| DiPlay（Android 平板） | 桌面基线 | — | DiPlay（开源） | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | · | [2026-10-25/27](CARWITH-ENTRY-FINDINGS.md) |
| 小米 SU7 Max | 未知 | 小米（待查）/ 待查 / 待查 | 未知 | ✗ | · | · | · | · | · | · | · | [2026-10-28](CARWITH-ENTRY-FINDINGS.md)：iAP2 RFCOMM 未被接受，车机未回连，未拿到协议指纹；尚不能判定是否支持无线 CarPlay |
| 奇瑞 瑞虎 / 艾瑞泽（年款待定） | `DESAY_SV` | 德赛西威 / 待查 / 待查 | 未知 | · | · | · | · | · | · | · | · | — |
| 吉利 / 领克（车型待定） | `ECARX_CLOUDPEAK` 或 `NEUSOFT_NAGIVI` | 待查 | 未知 | · | · | · | · | · | · | · | · | — |
| Volvo EX30 | `ECARX_CLOUDPEAK` | 亿咖通 / 待查 / 待查 | 未知 | · | · | · | · | · | · | · | · | — |
| 华阳配套车型（待定） | `ADAYO` | 华阳 / 待查 / 待查 | 未知 | · | · | · | · | · | · | · | · | — |
| 德系车型（HARMAN 配套，待定） | `HARMAN` | HARMAN / 待查 / 待查 | 未知 | · | · | · | · | · | · | · | · | — |
| Mercedes A 级 | `VISTEON_ALLGO` | 伟世通 / 待查 / 待查 | 未知 | · | · | · | · | · | · | · | · | — |
| Audi MIB3（DELPHI 画像） | 既有 `CLASSIC_MIB3` | 待查 | 未知 | · | · | · | · | · | · | · | · | 早期 CarDeck 记录，未在 CarWith 桥接上复测 |

DiPlay 的重连尚未在当前版本上验证；WinPlay 与 DiPlay 都是桌面接收端，不能代表任何车机。

## 晋级规则

1. 新车型先按「车型 → Tier-1 → SoC → OS → 中间件」填已知信息，未知就写「待查」，不要按品牌推断。
2. 每次实测导出脱敏诊断报告，记录手机型号、HyperOS、CarWith 与小米 CarLife 版本（远程调试包会自动写 `ENV:` 行）。
3. 某一列标 `✓` 需要对应现象的日志证据与车主确认；只看到「已发送」计数不算通过。
4. 两台不同品牌车机的协议指纹一致时，在「中间件」列注明「与 X 指纹一致」，并在兼容策略里按指纹而不是品牌登记例外。

## 下一步

按「车型 → Tier-1 → SoC → OS → CarPlay 中间件」反查奇瑞、吉利、长安、上汽、广汽、比亚迪的常见车型，
补齐上表「待查」，得到最少需要实测的车机清单。
