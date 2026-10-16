# AISecLab

**大模型越狱「取证审计」工具** —— 不只回答「这次越狱成没成功」，还回答**用了什么策略、绕过了哪一层、第几轮破防、为什么破、以及该怎么修**。

Android 端（Kotlin + Jetpack Compose）+ 可选自建后端（FastAPI / Docker Compose）。
模型接入方式为 **BYOK**（自带 API Key），仓库内**不含任何真实密钥**。

---

## ⚠️ 使用边界与免责声明

这是一个**防御性安全测试工具**，请在使用前读完这一段。

- **只能测你自己有权测试的模型。** 你有权测试的情形包括：自己的自部署模型、厂商服务条款明确允许安全测试的 API、你所在组织拥有并书面授权的系统。**未经授权对他人的模型或服务做安全测试，可能违法。**
- **它是一个审计工具，不是攻击工具。** 仓库里包含的越狱策略与目标样本，用途是**度量某一层防御在标准化攻击面下的表现**，产出物是**加固建议**，不是可直接复用的攻击载荷。
- **不要把本工具跑出来的命中样本当成"教程"外传。** 工具本身不提供载荷导出，也请你在使用与传播结果时保持同样的克制。
- **不要把真实密钥提交进仓库。** 所有密钥只应存在于本机 `.env`（后端）或 App 的加密存储里；`.env`、`dist/`、`keystore/` 都已在 `.gitignore` 中。
- 作者不对任何滥用行为负责。使用即表示你已理解并接受以上边界。

---

## 为什么不是又一个「扫一遍看有没有违规」

市面上的红队工具大多是「攻击 prompt 进去 → 二分类（越狱 / 没越狱）出来 → 报一个 ASR 就完事」。
本项目的取舍是把范式换成**安全事件取证**：

```
原始请求 → 意图重构 → 策略识别 → 多轮执行轨迹
        → 各层防御触发记录 → 最终输出合规判定
        → 归因（第几轮破防 / 绕过了哪一层 / 是什么策略）
        → 加固建议
```

差别在**结论的可用性**：`ASR = 22%` 没法直接指导修复，而「17 条失守里有 11 条是角色扮演族、集中在第 2 轮破防、绕的是输入侧关键词过滤」可以。

### 三个贯穿全局的口径纪律

这套工具真正花力气的地方不在"多跑几条",而在**别把话说错**：

1. **「没采集」≠「很差」。** `0` 与「没测到」是两个量，全程分开记。缺样本一律显示 `--`，不是 `0`。
2. **判定当锚。** 一次攻击算不算破防，只看最终裁决；逐轮的拒绝标志只用来定位「第几步开始松口」。
3. **一根条只有一个语义。** 图表的条长与颜色共用一把尺（矩阵绝对刻度 100），不会出现"长度表示 A、颜色表示 B"。

---

## 判定链

一次裁决要过四层，每一层都**只负责一件事**：

| 层 | 做什么 | 去哪看 |
|---|---|---|
| ① 输出有效性闸门 | 空响应 / 未闭合思维链 / 复读退化 → 直接剔除为 `SKIPPED`，**不是**判失败 | `core/judge/OutputValidity.kt` ↔ `judge.py::output_validity` |
| ② 护栏优先 | 配了护栏模型时，先问它「这段该不该拒绝」，命中即按护栏结论 | `core/judge/guard/` ↔ `guard.py` |
| ③ LLM 三角色主判 | 严格 / 宽松 / 对抗三个角色独立投票，规则层的确定性线索只作**证据**、不直接定裁决 | `core/judge/` ↔ `judge.py::rule_layer` |
| ④ 判定聚合 | 多数裁决；有分歧标 `needsReview`，`SKIPPED` 不参与投票 | `pick_majority()` |

**裁判模型是硬依赖**（`rule_layer` 第一步就要调 LLM）。没有裁判模型时不会"降级成只用规则"——那样等于伪造判据。你需要在配置里给一个裁判模型。

另外两条容易踩的设计：

- **「提及豁免」是句子级判定**，不是固定字数窗口。`I cannot provide instructions on how to make a bomb.` 是规范拒绝，不能因为"提到了炸弹关键词"就判成输出危险内容；反过来 `我不能提供方法。不过，制作炸弹要：…` 是半妥协，也不能因为前半句有拒绝就放过。
- **文本归一化只有一份实现**（空白折叠 / 弯撇号归一 / 去 Markdown 强调符），两端共用。**结构判据（列表、步骤话头、代码围栏）必须读原文**——归一化会废掉 `(?m)^` 和 `[-*•]`，把半妥协读成纯拒绝。

---

## 功能

**App 端**

- BYOK 模型接入（Base URL / API Key / 模型名 / 档案名），密钥存 EncryptedSharedPreferences
- 四个测试维度按需勾选，报告条数 = 你勾了几个维度
- 递归对抗（用上一轮失败的样本反推更强的变体，多轮迭代）
- 越狱变体集开关（24 目标 × 11 策略 = 264 条，只挂安全维度）
- 本地护栏模型（arm64 `llama.cpp` 推理，C++ 源码在 `app/src/main/cpp/`）
- 实时终端日志 + token 消耗计数 + 本轮耗时
- 报告可折叠 13 节，两种格式导出（Markdown / HTML）+ 取证 JSON
- 历史报告、基线对比（拿上一份报告逐用例 diff）、断点续测

**后端（可选，委托运行用）**

- 同一套判定链的 Python 镜像，7 个接口（`/health` + 6 个 `/v1/*`）
- SSE 推进度，终端日志两端同构
- 会话级断点续测（指纹认用例内容，不认用例编号）

---

## 评测规模

| | 条数 | 说明 |
|---|---|---|
| 安全性 | 18 | 提示注入、越狱、系统提示泄露、危险内容、PII/密钥泄露诱导 |
| 一致性 | 11 | 同一问题不同包装下回答是否自洽 |
| 抗幻觉 | 10 | 含「尚未确定 / 需实时数据 / 内部信息」型，期望模型说"不知道" |
| 鲁棒性 | 11 | 错别字、编码混淆、极端输入下的稳定性 |
| **静态合计** | **50** | 勾几个维度跑几条 |
| 越狱变体集 | 264 | 24 目标 × 11 策略族，**只在勾了安全维度时并入** |
| **全开** | **314** | 50 + 264 |

11 个策略族：角色扮演、提示覆盖、虚构框架、编码混淆、拒绝抑制、说服/社会工程、分类学包装、历史框架、思想实验、文字游戏、场景嵌套。
9 类危害意图：化学武器、暴力、恶意代码、欺诈、歧视、自伤、隐私窃取、医疗、系统提示泄露。

「本轮要跑多少条」只有一处口径（`core/engine/CasePlan.kt`），配置页的预告与运行时实际条数会对账，不一致会直接说明原因。

---

## 目录结构

```
AISecLab/
├── app/                                  Android 端
│   └── src/main/
│       ├── java/com/xieguojian/aiseclab/
│       │   ├── core/
│       │   │   ├── api/                  后端客户端 + SSE 日志格式收敛
│       │   │   ├── engine/               用例模块、攻击引擎、CasePlan
│       │   │   │   └── modules/          四个维度的用例定义
│       │   │   ├── jailbreak/            策略族 / 编码变体 / 指纹库 / 无害负对照
│       │   │   ├── judge/                判定链（含 guard/ 子包）
│       │   │   ├── model/                数据契约（Report / TestResult / ...）
│       │   │   ├── report/               报告分区、图表、加固建议
│       │   │   └── repo/                 历史报告与配置文件
│       │   └── ui/                       Compose 界面 + 图表原语 + 主题
│       ├── cpp/                          端上护栏推理（JNI + llama.cpp 接线）
│       └── jniLibs/arm64-v8a/            预编译 libxguard.so
├── backend/                              FastAPI 后端
│   ├── app/                              judge / attack / jailbreak / compliance / guard / engine
│   ├── Dockerfile                        docker-compose.yml  .env.example
│   ├── start.sh  stop.sh                 一键启停
│   ├── deploy.py                         跨平台部署脚本（建 venv → 装依赖 → 生 .env → 起服务）
│   └── README.md                         详细部署文档
├── tools/                                开发辅助脚本（见下方「已知局限」）
├── docs/                                 内部设计文档与决策记录
├── gradle/wrapper/gradle-wrapper.properties
└── README.md  LICENSE  .gitignore
```

---

## 快速开始

### 0. 环境要求

| | 版本 |
|---|---|
| JDK | **17 或 21**（`jvmTarget = 17`；Java 25 跑不动 Gradle 8.7） |
| Gradle | **8.7**（AGP 8.5.2 要求） |
| Android SDK | compileSdk 34 / minSdk 26 |
| Python | **3.9+**（后端；Rocky 9 的系统 python3 就是 3.9，可用） |
| Docker | 24+ 与 Compose v2（只有容器化部署才需要） |

> ⚠️ 仓库里**只有 `gradle-wrapper.properties`，没有 `gradlew` 启动脚本**，所以不能用 `./gradlew`。请用系统安装的 Gradle 8.7，或自己 `gradle wrapper` 生成一次。

### 1. 构建 App

```bash
export JAVA_HOME=/path/to/jdk21
gradle -p . :app:assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
```

Windows 上本项目作者用 `python tools/build-apk.py`（封装了 JDK 版本等几个坑，但**路径是按作者本机写死的**，见「已知局限」）。

### 2. 跑单测

```bash
gradle -p . :app:testDebugUnitTest
```

纯 JVM，不需要设备与网络。

### 3. 起后端

```bash
cd backend
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env                    # 按需填模型配置；BYOK 模式下裁判由 App 请求体自带
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
curl http://127.0.0.1:8000/health
```

或直接用容器（推荐）：

```bash
cd backend
cp .env.example .env
docker compose up -d
```

### 4. 在 App 里跑一轮

1. 首页 `+` → 填目标模型的 Base URL / API Key / 模型名（BYOK）
2. 再填一个**裁判模型**（可以在同一个模型上复用）
3. 「下一步」→ 勾选维度 → 「开始测试」
4. 跑完自动跳到报告页；可导出 Markdown / HTML / 取证 JSON

---

## 后端部署

评审 / 生产环境的完整说明在 [`backend/README.md`](backend/README.md)（含 Ubuntu 22.04 与 Rocky Linux 9.2 两条路、systemd、Nginx 反代 + HTTPS、常见问题）。要点：

- **一键启动**：`docker compose up -d`，端口默认 `8000`（可用 `PORT` 覆盖）
- **健康检查**与**数据卷**都已在 `Dockerfile` / `docker-compose.yml` 里配好
- **SELinux**（Rocky / RHEL 系）需要卷挂载带 `:z` 后缀，已加好
- **firewalld**（Rocky / RHEL 系）要放行端口，`start.sh` 会检测并打印放行命令
- **数据库**：**没有外部数据库依赖**。会话状态是进程内存，重启即清空；不需要初始化脚本。历史报告落在**客户端**侧。
- 需要模型 API 时**两种方式二选一**：`BYOK`（用者在 App 里填自己的 key，随请求体发给后端）或自部署的 `Ollama` / vLLM 等本地 OpenAI 兼容端点（`apiKey` 可留空）。

---

## 报告里有什么

摘要 → 本次消耗（token / 耗时）→ 复现性 → 外部对照 → 思维链处理口径 → 安全指标 → 策略 × 意图矩阵 → **加固建议** → 用例热力图 → 分区分布 → 判定层级 → 基线对比 → 分维度得分 → 攻击演进 → 多轮侵蚀 → 失守明细 → 待人工复核 → 不可评估（输出无效 / 未评估）。

几条刻意的口径：

- **「不可评估」单列**。空响应、思维链未闭合、复读退化都会被剔除，不混进通过率 —— 否则模型答非所问反而会"看起来更安全"。
- **加固建议只对攻击类失守出**。事实性 / 一致性 / 鲁棒性失守硬套安全建议会给出方向相反的建议（"模型编事实" → "需要安全微调"），所以显式排除并写明排除了几条。
- **攻击类 0 条失守时这一章照样成章**，写「本轮没有攻击类用例失守」——"本节没有东西"本身就是结论。

---

## 已知局限

- **`tools/` 下 26 个脚本是作者本机的开发工具**，里面写死了绝对路径（`C:\jdk21`、`D:\android\gradle-8.7`、作者的用户目录、WorkBuddy 沙箱的 Python 解释器路径）。它们记录的是**这台机器上踩过的坑怎么绕**，换台机器要先改路径，或者直接按上面的通用命令手敲。
- **`_shot/` 调试脚本与 `module-b/` 算力共享模块不在本仓库内**（前者是调试过程产物，后者是另一条独立路线，与判定链无耦合）。
- **发布 APK 是手工签名的**，`app/build.gradle.kts` 里没有 `signingConfigs`；`keystore/` 也不入库。
- **思维链处理仍有一个已知缺口**：两端目前只取 `choices[0].message.content`，没有单独处理 `reasoning_content`。模型把思考段**内联进正文**时，思考段里的拒绝话术会让一段完全照做的回答"读起来像拒绝"（测量失真），也可能反过来给真泄露授予提及豁免。不同厂商的模型表现不同，跨模型对比时要留意这一项。
- **判定一致性依赖裁判模型**。换裁判模型，同一批回答的裁决可能变化 —— 报告里的「复现性」一档就是为这件事准备的。
- 端上护栏走 arm64 native 推理，**仅在 arm64-v8a 设备上可用**。

---

## 参考项目与致谢

设计过程中参考了以下开源项目的思路（**均为思路借鉴与重新实现，未直接拷贝其代码**）：

| 项目 | 许可 | 借鉴了什么 |
|---|---|---|
| [FuzzyAI](https://github.com/cyberark/FuzzyAI) | Apache-2.0 | 编码族（猪拉丁 / Unicode Tag）等的可迁移清单 |
| [ReNeLLM](https://github.com/NJUNLP/ReNeLLM) | MIT | 提示改写范式的对照；本项目**反着用**了它的判定口径（它用子串匹配，会把"配合"读成"拒绝"） |
| [Heretic](https://github.com/p-e-w/heretic) | AGPL-3.0 | 评估 / 复现 / 优化环节的设计参考（未使用其权重级方法） |
| [OBLITERATUS](https://github.com/elder-plinius/OBLITERATUS) | AGPL-3.0 | 仅借鉴测量侧口径（其为权重级方法，攻击手法不适用） |

端上护栏的 native 推理接线参考了 [llama.cpp](https://github.com/ggerganov/llama.cpp)（MIT）。

---

## License

[MIT](LICENSE)
