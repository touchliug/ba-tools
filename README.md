# ba-tools

币安合约数据小工具集，配合 [ba-sync](https://github.com/touchliug/ba-sync) / [ba-analysis](https://github.com/touchliug/ba-analysis) 使用。

一个 jar 多个命令，只读写自己的库，不动生产配置。

## 目录

| 路径 | 内容 |
|---|---|
| `src/` | 工具本体（backfill / stage / backtest / events / pattern / db-probe） |
| [`docs/`](docs/) | 分析文档：[单币分析 SOP](docs/单币分析SOP.md)、[会话纪要](docs/币安数据与交易分析-会话纪要.md) |
| [`scripts/maintenance/`](scripts/maintenance/) | 一次性运维脚本（下架币清理、存活检查、表结构校验） |

## 构建

```bash
mvn -q package          # 产出 target/ba-tools.jar（已内置 MySQL 驱动）
```

要求 JDK 21+。

## 用法

```bash
java -jar ba-tools.jar <命令> [选项]
```

### 1. `backfill` — 补 K 线历史

从币安拉 K 线并幂等 UPSERT 进 `klines` 表。只补缺口，可反复跑。

```bash
# 补 3 年日线（需要代理时加 --proxy）
java -jar ba-tools.jar backfill --years 3 --proxy 127.0.0.1:7897

# 只体检，不访问币安
java -jar ba-tools.jar backfill --check

# 只测数据库写入通路（造 3 行测试数据后删除）
java -jar ba-tools.jar backfill --selftest

# 冒烟：两个币 + 不写库
java -jar ba-tools.jar backfill --symbols BTCUSDT,AINUSDT --dry-run
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `--years` | 3 | 补多少年（365 天/年） |
| `--interval` | 1d | 1d / 4h / 1h / 15m / 5m |
| `--rps` | 3 | 全局请求速率（limit=1500 时单次权重 10） |
| `--proxy` | 无 | `host:port`，访问币安需要代理时必填 |
| `--symbols` | 全部 | 逗号分隔 |
| `--max-symbols` | 0 | 只跑前 N 个 |
| `--check` | | 只体检 |
| `--selftest` | | 只测写库 |
| `--dry-run` | | 拉取但不写库 |
| `--force` | | 忽略"已覆盖"判断 |

### 2. `pattern` — 连跌后的反弹概率

统计"连续 5 天下跌后，第 6 天 / 第 7 天上涨"的概率，含：

- 基线对比（任意一天上涨的比例）
- 按日期聚类的 bootstrap 置信区间（事件高度同日聚集，不能按事件数算自由度）
- 逐月稳定性
- 按 5 天累计跌幅分位

```bash
java -jar ba-tools.jar pattern --out .
```

产出 `pattern_report.txt`。

> 注意：这个指标容易被误读。用 150 天数据算出来是 58%，补到 3 年后掉到 53.7%，
> 置信区间 [47%, 60%] 覆盖基线 47.1% —— 它不构成策略，只是描述。

### 3. `ain-review` — 单币逐小时复盘

识别插针/挤空/双向扫三类事件，输出 Markdown 表格。

```bash
java -jar ba-tools.jar ain-review --symbol AINUSDT --hours 120 --out .
```

产出 `ain_review_<SYMBOL>.md`。

判定规则：

| 标签 | 条件 |
|---|---|
| 挤空拉升（空头爆仓） | 涨幅 > 5% 且 OI 降幅 > 2% |
| 砸盘诱空（空头加仓） | 跌幅 > 4% 且 OI 增幅 > 2% |
| 双向扫 | 单小时振幅 > 15% |
| 多头止损/平仓 | 跌幅 > 4%，OI 未明显增加 |

### 4. `db-probe` — 数据库体检

```bash
java -jar ba-tools.jar db-probe --symbol BTCUSDT --interval 1h --limit 72
```

输出表规模、索引、四条典型查询的实测耗时。

### 5. `stage` — 阶段识别（吸筹 / 试盘 / 拉升 / 派发 / 砸盘 / 冷却）

把「庄家拉币」的六段流程翻译成可量化特征，逐币打分并选出当前最可能的阶段。

```bash
# 全市场扫描
java -jar ba-tools.jar stage --top 10 --min-match 60

# 单币详情（含证据与证伪条件）
java -jar ba-tools.jar stage --symbol AINUSDT
```

**v2 口径**（相对 v1 的修正）：

| 修正 | 说明 |
|---|---|
| 阈值 z-score 化 | 不再用「量比 >1.5」这种全市场绝对值，改用「今日量比相对自身 90 天的偏离倍数」 |
| 多周期校验 | 日线判定 + 7 天趋势校验，冲突时输出 `**冲突**` |
| 基准对比 | 同时输出相对 BTC 的 7 日超额收益 |
| 改名 | 「置信分」→「匹配度」，明确它不是概率 |
| 证伪条件 | 每个判定附「什么情况下这个判断算错」 |

| 阶段 | 主要得分项（z-score 口径） |
|---|---|
| 砸盘 | 日跌幅 z<-2（+35）、OI 降幅 z<-1.5（+30）、距 30 日高点 <-25%（+25） |
| 拉升 | 日涨幅 z>1.5（+30）、OI 增幅 z>1（+30）、放量 z>1（+20）；距高 <-40% 时扣 25 |
| 派发 | 距高 >-8%（+30）、OI 距峰 <-5%（+25）、缩量 z<-0.8（+25）、费率 >0.02%（+20） |
| 试盘 | 振幅 z>2（+30）、\|日涨跌 z\|<0.8（+25）、放量 z>1.5（+25）、\|OI z\|>1.5（+20） |
| 吸筹 | \|日涨跌 z\|<0.6（+25）、地量 z<-1（+25）、OI 距峰 >-10%（+20）、费率≈0（+15） |

匹配度低于 `--min-match`（默认 60）时归为「冷却」。

> ⚠️ **匹配度不是胜率。** 回测（`backtest`）显示各阶段的 7 日表现与基线相差都在 ±0.5pp 内 ——
> 也就是说，**阶段标签没有预测力，只能当状态描述用**。详见下一节。

### 6. `backtest` — 阶段回测

用与实盘相同的规则，对历史每个时点打标签，统计之后 N 天的收益分布并与基线对比。

```bash
java -jar ba-tools.jar backtest --lookback 120 --horizon 7
```

2026-11-06 实测（520 币 / 120 天 / 8729 个不重叠样本 / 100 个交易日）——
**已修正 retZ 计算**（此前误用价格水平的 z，而非日收益的 z，导致「日涨幅」特征失效）：

```
基线：7 日中位数 -1.96%，胜率 41.3%

阶段     样本    7d中位数   胜率     vs 基线   90%CI（日期聚类）
拉升      247    -0.92%   46.2%   +1.05 pp   [-1.90, +6.42]
试盘      227    -2.75%   43.2%   -0.78 pp   [-1.83, +1.66]
派发      975    -1.15%   42.3%   +0.82 pp   [-0.82, +2.65]
砸盘      103    -4.05%   34.0%   -2.09 pp   [-4.20, -0.04]   ← 唯一未跨 0
吸筹     3740    -2.59%   38.2%   -0.63 pp   [-1.59, +0.24]
冷却     3437    -1.49%   44.2%   +0.47 pp   [-0.10, +1.36]
```

**结论：没有区分度。** 所有阶段的 90% 置信区间都跨 0。

两个必须知道的细节：

1. **步长默认 = 前向天数**。用 `--stride 1` 会让相邻样本重叠，显著性被严重高估。
2. **按交易日整群重采样**。同一天几百个币同时触发、走势高度相关，按事件数算自由度会
   把「试盘 +2.18pp」误读成显著（按事件算约 2.9σ，按日期聚类后区间是 [-1.14, +4.66]）。

阶段识别的价值在于「描述当前状态 + 排除没有结构的标的」，不构成交易信号。

## 配置

| 项 | 来源 | 默认 |
|---|---|---|
| 密码 | `--db-pass` 或 `DB_PASSWORD` | 无（必填） |
| 主机 | `--db-host` 或 `DB_HOST` | 43.155.216.33 |
| 端口 | `--db-port` | 3306 |
| 库名 | `--db-name` 或 `DB_NAME` | ba |
| 用户 | `--db-user` 或 `DB_USERNAME` | root |

## 数据前提

这些工具读的是 ba-sync 写出来的表：

- `klines(symbol, interval, open_time)` — K 线，含 `taker_buy_quote` 供主动买占比使用
- `open_interest(symbol, period, timestamp)` — OI，**用 `sum_open_interest`（币数）判断仓位变化**，`sum_open_interest_value`（USD）会被价格带偏
- `long_short_ratio(symbol, ratio_type, period, timestamp)` — 四类多空比

`open_interest` 表的 `open_interest` 列恒为 NULL（写入路径只走 `openInterestHist`，没有该字段），请勿使用。

## 已知限制

- `pattern` 目前固定统计 5 连跌，未参数化 N。
- `ain-review` 的"事件"是启发式规则，阈值写死在 `isEvent()` 里，不同币种可能需要调整。
- `backfill` 对已下架合约不做处理（`exchangeInfo` 只返回 TRADING 的合约）。
- `stage` 的阈值是经验值，对小市值币种偏敏感；它是"当前状态快照"，不是预测。
