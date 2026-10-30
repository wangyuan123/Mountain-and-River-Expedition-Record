# 山河远征录游戏软件 V1.0 修订归档（r2）

这是 2026-10-30 的名称与文档一致性修订归档，软件版本仍为 V1.0。软著材料准备使用本目录，不与旧归档混用。

- `VERSION-MANIFEST.md`：固定提交、环境、源码包 SHA-256 及导出方法。
- `V1.0-SCOPE.md`、`OPERATIONS-MANUAL.md`：版本范围与操作手册。
- `NAME-CONSISTENCY-AUDIT.md`、`CODE-AUDIT.md`：名称与文档修订、代码审查边界。
- `TEST-REPORT.md`、`VALIDATION-SUMMARY.json`：实际全量回归与归档验证结果。
- `shanhe-yuanzhenglu-v1.0-source.tar.gz`：从 `v1.0-softcopyright-r2` 导出的源码包，通过 Git LFS 保存。
- `screenshots/`：原功能验收截图副本及来源说明。
- `SHA256SUMS.txt`：本目录其他所有文件的 SHA-256。

克隆后先在项目根目录执行 `git lfs pull`，再进入本目录校验：

```sh
shasum -a 256 -c SHA256SUMS.txt
```

原标签 `v1.0-softcopyright` 与原目录 `software-copyright-v1.0-20261030/` 保留用于追溯。本目录创建后不覆盖；后续修订使用新目录和新标签。
