# 山河远征录游戏软件 V1.0 修订测试报告

## 基线与环境

- 固定标签：`v1.0-softcopyright-r2`；源码提交：`84ec73a947d43d92834a4d350c6239a0e8724da4`。
- 执行日期：2026-10-30（Asia/Shanghai）。
- 环境：OpenJDK 17.0.20、Node.js v22.12.0；后端测试使用 H2。
- 本次仅修改文档与归档规则，游戏业务源码、测试用例和数据库迁移与原固定版相同。

## 实际执行结果

执行 `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 bash scripts/verify.sh`，前端与后端全量检查通过。

| 范围 | 结果 |
| --- | --- |
| 前端回归 | 总计 1267 项，通过 1267，失败 0，跳过 0 |
| 后端编译与全量测试 | 总计 2097 项，通过 2094，失败 0，错误 0，跳过 3；BUILD SUCCESS |
| 内容清单 | JSON 有效，461 个条目完整，T026 标题与当前名称一致 |
| 源码包名称扫描 | 1465 个文本文件，旧项目全名及 URL 编码命中 0 |
| 原归档保护 | 原标签与原归档校验值保持一致 |

后端跳过项为需要专门条件的测试：`AttackDesignTest` 1 项、`MySqlMigrationTest` 2 项。本次未提供独立 MySQL 测试库。机器可读结果保存在 `VALIDATION-SUMMARY.json`。

## 验证边界

本次没有重新进行浏览器功能验收，五张截图沿用原功能验收证据，不作为本次新截图。未执行真实支付、第三方邮件、正式实名/防沉迷、公网压力或生产 MySQL 验收；商标查询也未在本次执行。推送前仍按项目钩子要求执行全量检查。
