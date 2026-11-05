# 运维脚本（一次性 / 排障用）

这里是单文件 Java 程序，用 JDK 21 的**源码直接运行**模式执行，不需要 Maven 编译：

```bash
java -cp mysql-connector-j-8.3.0.jar DumpAndPurge.java <DB_PASSWORD>
```

依赖：`mysql-connector-j-8.3.0.jar`（在任一模块的本地 maven 仓库里，
或从 https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.3.0/ 下载）。

## 连接参数

全部通过环境变量，代码里不含任何主机地址与口令：

| 变量 | 默认 | 说明 |
|---|---|---|
| `DB_HOST` | `127.0.0.1` | MySQL 主机 |
| `DB_USER` | `root` | 用户名 |
| 密码 | 第 1 个参数 | 也可以走 `DB_PASSWORD` |

```bash
# Windows PowerShell
$env:DB_HOST='your-host'; $env:DB_USER='root'
java -cp mysql-connector-j-8.3.0.jar DumpAndPurge.java 'your-password'
```

## 脚本说明

| 脚本 | 用途 | 参数 |
|---|---|---|
| `DumpAndPurge.java` | **下架币清理（主工具）**：把 `status<>'TRADING'` 的币导出为 gz 的 INSERT 语句，再事务删除 | `--dump` / `--delete` / `--verify` |
| `PurgeDelisted.java` | 早期版本，只做审计与删除，不含备份 | `--delete` |
| `PurgeFixture.java` | 造 / 清测试数据（假币 `ZZPURGETEST`），用于验证定时清理链路 | `--setup` / `--cleanup` |
| `AfterPurge.java` | 清理后的表状态总览（各表行数、合约数） | 无 |
| `AliveCheck.java` | 服务存活检查：看 `ticker_24h` / `klines` 最近写入时间；SSH 连不上时用它判断进程是否还在 | 无 |
| `SchemaCheck.java` | 校验 `symbols` 列（含 `delisted_at`）与 `purge_log` 表是否存在 | 无 |
| `WifDetail.java` | 单币（WIFUSDT）逐日明细：价格 / OI / 持仓比 / 费率 | 无 |
| `WifOi.java` | 单币 OI 构成分析 | 无 |

> `DumpAndPurge` 与 `PurgeDelisted` 功能重叠，后者仅作历史保留。
> 线上已由 `ba-sync` 的 `RetentionService` 自动执行同类清理（每天 04:30），
> 这两个脚本现在的用途是**手工排查与恢复**。

## 安全提醒

- `--delete` 是不可逆操作，且下架币的数据**币安已无法重新拉取**，务必先 `--dump`
- 备份文件是标准 `INSERT` 语句，恢复方式：`gunzip -c xxx.sql.gz | mysql -u root -p ba`
- 脚本默认只删 `status<>'TRADING'` 的币，不会碰在交易的合约
