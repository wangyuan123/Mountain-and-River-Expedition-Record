# 山河远征录游戏软件 V1.0 版本清单

| 项目 | 内容 |
| --- | --- |
| 软件名称 | 山河远征录游戏软件 |
| 版本号 | V1.0 |
| 归档修订号 | r2（名称与文档一致性修订，软件版本号未变） |
| 固定标签 | `v1.0-softcopyright-r2` |
| 固定提交 | `84ec73a947d43d92834a4d350c6239a0e8724da4` |
| 远程分支 | `release/v1.0-softcopyright` |
| 固定日期 | 2026-10-30（Asia/Shanghai） |
| 归档生成时间 | 2026-10-30T09:08:32+08:00 |
| 构建环境 | Java 17、MySQL 8、Node.js 22 |
| Java 实际验证 | OpenJDK 17.0.20 |
| Node.js 实际验证 | v22.12.0 |
| 测试数据库 | H2；本次未执行独立 MySQL 8 迁移验收 |
| 源码包 | `shanhe-yuanzhenglu-v1.0-source.tar.gz` |
| 源码包 SHA-256 | `70745da1e19bd7bb6bd1caf8fbd69572ea6ac6d1ce48253f6e50355ed6ce78f4` |
| 源码包内容 | 固定提交中的后端、前端、脚本、配置及文档；排除 `output/`、`docs/archive/`，不含 Git 元数据、构建产物及未跟踪的私有配置 |

## 固定与追溯规则

`git rev-parse v1.0-softcopyright-r2` 的结果应与本清单的固定提交一致。标签指向源码提交；后续新增归档材料的提交位于发布分支头部，不要求分支头等于源码标签。源码包按以下命令导出：

```sh
git archive --format=tar.gz --prefix=shanhe-yuanzhenglu-v1.0/ v1.0-softcopyright-r2 -- . ':(exclude)output' ':(exclude)docs/archive' > shanhe-yuanzhenglu-v1.0-source.tar.gz
```

这是本次软著材料采用的修订归档。原标签 `v1.0-softcopyright`（`41390b39eea7de69991ff0e45af8d98d96ff0cde`）及原目录 `software-copyright-v1.0-20261030/` 保留用于追溯，不与本清单混用。

本目录中的源码包、清单、手册、测试报告及截图作为一组材料保存，创建后不再覆盖。后续修订必须使用新的目录及标签。
