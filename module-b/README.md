# 模块 B · 算力共享（一期）

把同一局域网里愿意出力的设备组织成一个**共享推理池**，任何设备都能通过一个
**OpenAI 兼容端点**用池里的模型 —— 人人贡献、人人可用。

> **与模块 A（AISecLab）是两个独立模块，做在同一个 App 里，互不 import。**
> 唯一联动方式：本模块对外暴露一个 OpenAI 兼容的 `base_url` + key，
> 模块 A 把它当成「一个普通被测模型端点」填进 CONFIG 表单即可。
>
> 设计依据：`docs/模块B-算力共享-设计草案.md`。本目录只实现其中的**一期**。

---

## 现状：一期做到哪一步（**诚实清单**）

| 一期内容（草案 §10） | 状态 |
|---|---|
| 节点注册 + 心跳 | ✅ 已实现（`POST /admin/nodes`；二期手机上报走同一入口） |
| 模型注册表（字段口径按 §5 定死） | ✅ 已实现，`weights_hash` 是硬门槛 |
| 能力过滤 + 派单 | ✅ 已实现（硬条件过滤 + 负载/信任/延迟排序） |
| OpenAI 兼容端点 | ✅ `/v1/models`、`/v1/chat/completions`（非流式） |
| 贡献档位 0 / 3 | ✅ 0/1/2/3 的定义都在，一期上线 0 与 3 |
| 抽样复算 + credit 记账 | ✅ 机制已实现，**但一期单节点池必然只能记「待复算」**（见下） |
| **真实本地算力** | ✅ **已接**：llama.cpp `b11565` + Qwen2.5-1.5B-Instruct Q4_K_M，`--backend llama` 已实跑 |

三种推理后端都可切换，`stub` 与 `llama` 均已实跑：

| 后端 | 用途 | 现状 |
|---|---|---|
| `--backend stub` | 确定性桩，用来打通协议 / 调度 / 记账 | ✅ 已跑（**无算力**，输出是拼出来的字符串） |
| `--backend llama` | 本地 `llama-server`（一期目标形态） | ✅ **已实跑，真算力**（见「已验证」） |
| `--backend upstream` | 开发期借用外部端点；面板会标「⚠ 算力不在本机」 | 代码就绪，未跑 |

所以现在共享的**确实是算力本身**：PC 上 `llama-server` 出 token，手机经模块 B 节点拿到真实回答。
「接一个真跑 `llama-server` 的节点」这件事已经不是设计，是已经跑通的事实。

**本机依赖（不在仓库里，需自备）**：

| 东西 | 路径 | 说明 |
|---|---|---|
| llama.cpp CPU 版 | `C:\third_party\llama.cpp-bin\` | `llama-b11565-bin-win-cpu-x64.zip` 解压即得 |
| 对话权重 | `C:\models\qwen2.5-1.5b-instruct-q4_k_m.gguf` | 约 1.04 GiB，sha256 见 `configs/default.json` |

> 这两个路径是**本机的**，不是约定。换机器只要改 `--model-path` 和 `--llama-url`。

---

## 怎么跑

```bash
cd module-b
python -m mb.cli --help

# 桩后端（无算力，用于联调协议）
python -m mb.cli --port 8100 --tier 3 --node-id pc-node-1

# 本地 llama-server（一期目标形态；先自己起好 llama-server）
#   1) 起推理引擎
llama-server.exe -m C:\models\qwen2.5-1.5b-instruct-q4_k_m.gguf \
    --host 127.0.0.1 --port 18080 -c 4096 -t 8 -np 4
#   2) 起节点
python -m mb.cli --port 8100 --tier 3 --node-id pc-node-1 \
    --backend llama --llama-url http://127.0.0.1:18080/v1 \
    --model-id qwen2.5-1.5b-instruct-q4_k_m \
    --model-path C:/models/qwen2.5-1.5b-instruct-q4_k_m.gguf
```

给了 `--model-path` 时程序会**现场算权重 sha256 并与注册表比对**，不一致会明确告警
（那正是 §5 要拦的「同一模型名、不同量化版本混进一个池子」）。

启动后会打印：

```
端点      http://127.0.0.1:8100/v1   ← 填进模块 A 的 CONFIG 表单
面板      http://127.0.0.1:8100/admin/panel
```

**默认只绑 `127.0.0.1`**：真机联调用 `adb reverse tcp:8100 tcp:8100` 就够，
不必把服务暴露到整个局域网。需要别的机器直连时显式 `--host 0.0.0.0`。

> `--llama-url` 默认是 `8080`。本机 `8080` 被别的东西占了（探测超时），
> 所以这里用 `18080` —— 端口是参数，不是硬编码。

### 自检（不花钱、不触网）

```bash
cd module-b
python -m unittest discover -s tests -v      # 47 条离线单测
python tools/smoke.py --base http://127.0.0.1:8100   # 对运行中的节点打协议冒烟
```

---

## 端点的字段口径（与模块 A 的契约）

模块 A 端 `OpenAiCompatibleClient` 真正要求的只有三件事，本模块严格对齐：

1. `POST {base_url}/chat/completions`（`base_url` 一般以 `/v1` 结尾）；
2. 请求体 `{model, messages:[{role,content}], temperature, max_tokens}`；
3. 响应含 `choices[0].message.{role,content}` 与 `usage.{prompt,completion,total}_tokens`。

另外多挂了一个 `mb` 扩展字段（`served_by` / `backend` / `usage_estimated` /
`credit_state` / `queue_explain`）—— 模块 A 端 `ignoreUnknownKeys = true`，
多出来的字段会被忽略，但排障时能一眼看出「这一单落在哪个节点、为什么落在那里」。

**未实现的部分明确报错**：`stream=true` 返回 501，不静默按非流式处理
（静默处理会让端上收到一个不是 SSE 的响应体，表现成「模型返回空」，比 501 难查得多）。

`usage_estimated=true` 表示上游没回 usage、数字是本模块估的。用户会把 token 数当账单看，
所以估算值必须可见 —— 与模块 A `TokenMeter` 里那条「宁可不计也不估算」的取舍一致。

---

## 两个刻意的设计取舍（都来自草案，别改回去）

### 1. 不给设备写「打分逻辑」

设备按「能装下多大的模型」分层，调度器只用**硬条件**过滤
（装得下吗 / 权重指纹对得上吗 / 档位允许吗 / 触发条件满足吗）。
不叠一套 `device_score = f(cpu, ram, benchmark)` —— 那会得到两个都在漂移的排序源。
模型注册表按参数量天然实现了能力分派（草案 §2）。

### 2. 记账单位 = 「通过抽样复算验证的输出 token 数」

自报 FLOPs / 自报时长可以白嫖：把档位拉到最大然后摸鱼，成本为零。
所以 credit 只认**别人复算过、结果一致**的产出。而抽样复算本来就要做（§7），一套机制两用。

**一期的必然结果**：池里只有一台机器 → 没有第二个节点能复算 →
`验证贡献` 恒为 0、`待复算` 持续增长。**这不是 bug**，是草案 §11 风险 2
「冷启动空池」在数据上的样子。面板会把它单独列出来，绝不假装验证过了：

```
待复算 64 token 未发放额度 —— 池内没有第二个能接同模型的节点可以复算。
这是「冷启动空池」，不是缺陷。
```

---

## 档位（用户愿意出多少算力）

三个独立旋钮（速率 / 触发条件 / 资源上限）的预置组合：

| 档位 | 速率 | 触发条件 | 上限 | 默认给谁 |
|---|---|---|---|---|
| 0 · 关闭 | — | — | — | 手动关闭 |
| 1 · 零打扰 | 1 并发 | 充电 **且** Wi-Fi **且** 息屏 | 0.5B / 200MB 日 | **手机默认** |
| 2 · 全速 | 全核 | 前台 **或**（充电 + Wi-Fi） | 3B / 不限 | 用户主动升 |
| 3 · PC 档 | 4 并发 | 无条件 | 13B+ / 不限 | **PC 默认** |

一期只上线 0 / 3；1 / 2 的定义先留着 —— 它们在设计里拍过板，删掉会让人误以为
「手机只能全速或关闭」，而真实约束是**能力分层**不是二值开关。

档位 0 的拒绝理由会**最先**判定并直接告诉用户「你的档位是关闭」，
而不是兜到后面变成一句「注册表不允许档位 off 接单」（`off` 压根不该出现在模型的档位表里，
`ModelEntry.validate()` 会拦住这种配置）。

---

## 目录

```
module-b/
├── mb/
│   ├── tiers.py        贡献档位与门控（三个旋钮 + 预置组合）
│   ├── registry.py     模型注册表；weights_hash 是硬门槛
│   ├── pool.py         节点池：能力过滤 + 派单 + 复算节点挑选
│   ├── accounting.py   记账：单位是「通过复算验证的输出 token」
│   ├── backends.py     推理后端：stub / llama-server / upstream（可插拔）
│   ├── protocol.py     OpenAI 兼容协议（含明确 501 / 估算标记）
│   ├── coordinator.py  调度 + 记账 + 推理的编排层（不含 HTTP，便于离线测）
│   ├── server.py       HTTP 层 + 极简本地面板
│   └── cli.py          启动入口
├── configs/default.json  模型注册表（2 个桩 + 1 个真实模型，哈希都是真 sha256）
├── tools/smoke.py        对运行中的节点打协议冒烟
├── tests/test_mb.py      47 条离线单测（不触网、不需要权重）
└── run.bat               Windows 一键起节点
```

## 已验证 / 未验证

**已验证**（2026-10-12）：
- 47 条离线单测全绿：档位门控（含 0 / 触发与或 / 日流量 / 并发）、权重指纹硬门槛、
  能力过滤四条分支、派单排序、抽样复算的一致与不一致两条分支、软上限降级与 429、
  OpenAI 形状、501、Key 校验、面板渲染、档位热切换后立刻 503。
- 真起节点 + 真 HTTP：`/healthz`、`/v1/models`、`/v1/chat/completions`（按模块 A 的请求形状）。
- **真算力**：`llama-server` 加载 Qwen2.5-1.5B-Instruct 后 2.6s 就绪；
  经模块 B 节点返回真回答（PC 侧 676ms / 手机侧 362ms），`mb.backend == "llama-server"`。
- **手机侧可达**：`adb reverse tcp:8100` 后在**手机上**用 `curl` 打通节点，拿到真实回答。

**未验证**：
- 模块 A 端真正把本节点当被测模型**跑完一轮测评**（需真填 CONFIG 并发起，会消耗裁判模型费用）。
- 多节点调度与真实复算仲裁（需要至少两台真机器）。
- 手机档位 1 / 2（二期）。
