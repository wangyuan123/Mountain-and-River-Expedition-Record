# V1.0 测试报告

## 测试基线

- 固定提交：`41390b39eea7de69991ff0e45af8d98d96ff0cde`
- 日期：2026-10-30（Asia/Shanghai）
- Java：OpenJDK 17.0.20
- Node.js：v22.12.0
- 后端测试数据库：H2（测试 profile）

## 结果

| 范围 | 命令/方式 | 结果 |
| --- | --- | --- |
| 前端回归 | `node --test frontend/tests/*.test.cjs` | 1267 通过，0 失败，0 跳过 |
| 后端编译 | `mvn -f backend/pom.xml -B test-compile -DskipTests` | 成功，主源码 439 个、测试源码 267 个完成编译 |
| 后端全量 | `mvn -f backend/pom.xml -B test -Dmaven.compiler.useIncrementalCompilation=false -DargLine=-Dfile.encoding=UTF-8` | 2097 通过，0 失败，0 错误，3 跳过 |
| 迁移专项 | 全量后端测试中的 Flyway/H2 迁移测试 | 随全量结果通过 |

## 环境说明

直接在中文工作区执行 Surefire 时，macOS/JDK 子进程曾把工作目录显示为 `??`，导致无法启动测试进程；这不是测试断言失败。随后将固定源码复制到 ASCII 临时路径，并为 Surefire fork 显式设置 UTF-8，完成全量复跑并得到上表结果。该临时副本不属于归档。

本次未连接生产 MySQL，也未执行真实支付、第三方邮件、ICP备案、HTTPS 或公网压力验收。MySQL 8 的迁移和部署演练应在独立测试库中另行完成。
