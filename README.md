# ba-tools

币安合约数据小工具集，配合 [ba-sync](https://github.com/touchliug/ba-sync) / [ba-analysis](https://github.com/touchliug/ba-analysis) 使用。

四个命令，一个 jar，只读/只写自己的库，不动生产配置。

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
