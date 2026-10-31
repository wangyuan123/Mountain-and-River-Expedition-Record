# 游戏合理性排查 · 第六轮（2026-10-31）

修复状态：本报告的 6 项与第五轮 11 项均已完成修复及定向验证，见[第五、六轮修复记录](GAMEPLAY_AUDIT_20261031_ROUND5_6_FIXES.md)。以下保留排查时的现象与历史验证记录。

接续第五轮，本轮记录 **6 类新增问题或规则冲突**：3 类涉及归降期间资产保留，2 类涉及恢复计时及治疗计费，1 类为规则说明错误。第五轮的 11 类问题仍未修复，继续保留在原报告中，不重复计数。用户暂缓的重复击败低级 NPC 晋升不处理。

本轮只排查，未修改业务代码、界面样式、数据库迁移或实际游戏数据。隔离观察使用独立 H2 内存数据库和明确标识的合成账号，每项观察事务回滚；不启动 HTTP 服务，WebSocket 推送及邮件发送使用替身。

## 新增清单

### R6-01 · P1 · 其他城市科研完成时，可能继续扣冻结城的粮食

**证据：粮账回调隔离运行及源码调用链。** 普通经济时间轴跳过归降冻结城市，但科研完成另行调用 `beforeGlobalChange`，该方法遍历账号全部城市并直接结算粮账，底层没有冻结判断。已经启用驻军粮账的冻结城市因此仍可能产生粮食收支；当前没有外来驻军，也不意味着该城市已经退出这套粮账。

隔离合成城市冻结一小时，100 名步兵、无农场，调用正常的共享科技变更前粮账回调后，粮食由 10,000 变成 9,900。运行检查验证的是回调结果；科研完工到回调的连接由源码确认，未额外搭建完整科研完工场景。

建议：在共同的粮账结算边界处理冻结状态，保留冻结前已产生的应结区间，跳过冻结期间的收入及消耗。只修普通 Tick 或只修库存巡检都不能覆盖此入口。此项与 R5-01 的定期库存损耗入口不同，应合并检查冻结规则的所有后台调用方。

位置：[GarrisonFoodService.java:42](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/GarrisonFoodService.java:42)、[GarrisonFoodService.java:126](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/GarrisonFoodService.java:126)、[TechService.java:333](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TechService.java:333)。

### R6-02 · P1 · 和平移交后可能追扣冻结期间的粮耗

**证据：隔离运行及源码。** 和平移交把城市经济 `lastTick` 更新到移交时刻，却没有同步恢复 `garrisonFoodAt`。随后新城主的第一次经济结算仍从旧粮账起点扣粮，把应当暂停的冻结区间计入消耗。

合成城市有 100 名步兵、10,000 粮食、无农场，冻结 24 小时后和平移交，在同一游戏时刻结算新城主的该城经济，粮食变成 7,600。接收方尚未经营这座城市，已经被补扣 2,400 粮食。

建议：移交及解冻统一恢复经济和粮账时钟，保证冻结前余额及有效小数继续保留，冻结期间不补产、不补扣。此项与 R5-06 的离线补结窗口问题不同：本项的这段 24 小时本来就不应计费，无关补结窗口选 8 小时还是其他长度。

位置：[CityConquestService.java:202](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/CityConquestService.java:202)、[CityConquestService.java:208](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/CityConquestService.java:208)、[GarrisonFoodService.java:132](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/GarrisonFoodService.java:132)。

### R6-03 · P1 · 冻结期间不能救治伤兵，但伤兵仍会到期删除

**证据：隔离运行、源码及现有归降约定。** 伤兵定时清理只按 `expiresAt` 全局删除，不区分城市冻结状态；移交顺延方法也只处理建设、征兵和科研队列，没有处理伤兵剩余救治时间。因此冻结前仍可救治的伤兵，可能在玩家无法操作的移交期内消失，和归降设计中伤兵原记录随城保留的约定冲突。

合成批次在冻结开始时仍有效，共 10 名伤兵，在冻结期间到期；执行既有清理方法后该批次被删除。没有对真实伤兵记录执行清理。

建议：明确归降冻结对救治期限的规则。按现有资产保留约定，应保存冻结时剩余期限，冻结期间跳过到期清理，移交或异常解冻时恢复剩余救治时间；普通离线继续沿用原来的倒计时规则。不能只在移交时修改期限，因为此前批次可能已经被定时任务删除。

位置：[WoundedService.java:232](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/WoundedService.java:232)、[WoundedUnitRepository.java:18](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/repository/WoundedUnitRepository.java:18)、[CityConquestService.java:228](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/CityConquestService.java:228)。规则依据见 [归降设计文档](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/docs/CITY_SURRENDER_DESIGN_20261012.md:137)。

### R6-04 · P2 · 系统异常取消归降时，没有恢复暂停的队列

**证据：源码确认，未运行异常取消场景。** 归属变化或参与账号失效时，系统会取消已接受的移交。`cancelLocked` 直接清除冻结标记和截止时间，没有按实际冻结时长顺延建设、征兵及科研，也没有统一恢复粮账时钟。正常完成移交至少会顺延三类队列，异常取消则完全没有这一步。

影响：原本应暂停的工程可能在解冻后立即完工，冻结期间被误认为已施工；启用驻军粮账的城市还可能补扣被冻结时段的粮食。常规 Tick 会推进冻结城市的普通经济时钟，因此不能笼统声称所有经济都必然补结整个冻结期。

建议：完成移交和系统异常取消共用一个恢复冻结计时的入口，先保存冻结起点，再恢复各项时间边界，最后清除冻结标记；重复执行不得重复顺延。本项不建议增加玩家主动撤销入口，现有双方接受后不可撤销的规则保持不变。

位置：[CitySurrenderService.java:174](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/CitySurrenderService.java:174)、[CitySurrenderService.java:241](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/CitySurrenderService.java:241)、[CityConquestService.java:228](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/CityConquestService.java:228)。

### R6-05 · P2 · VIP 伤兵治疗的显示价格和 MAX 与后端计费不一致

**证据：后端真实报价方法、前端真实计算函数的隔离运行及源码。** 后端黄金折扣在总价上取整，钻石使用未折扣的黄金基价换算；前端却使用已经取整的折后单价乘数量，并继续用这个价格换算钻石。两种差异属于同一计费规则未共用的问题，不分拆计数。

| 合成报价场景 | 前端 | 后端 |
| --- | --- | --- |
| VIP8，220 名步兵，黄金减免 40% | 显示 1 颗钻石；1 颗钻石的 MAX 可选 220 人 | 报价为 2 颗钻石 |
| VIP5，10 名步兵，黄金减免 30% | 显示 40 黄金；余额 35 时黄金 MAX 为 8 人 | 总价 35 黄金，可以治疗 10 人 |

影响：钻石按钮可能显示可支付，但提交时余额不足；黄金按钮也可能把本可支付的治疗置灰。观察只调用报价和前端计算函数，未提交付费治疗。

建议：服务端给出可共用的精确定价规则或按数量报价，前端的价格、MAX 和不足提示使用同一口径。按当前 VIP 设计，优惠只作用于黄金；如要扩展到钻石，应另行统一产品规则，不能仅修改前端显示。

位置：[WoundedService.java:123](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/WoundedService.java:123)、[WoundedService.java:135](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/WoundedService.java:135)、[wounded.js:146](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/wounded.js:146)、[wounded.js:219](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/wounded.js:219)。

### R6-06 · P3 · 伤兵页仍将总回收上限写成 50%，并保留技能升级措辞

**证据：后端回收率函数隔离运行及前端源码。** 页面摘要写“最高 50% 回收”，实际医疗技术满级提供 50%，战地急救满级再提供 15%，合计可达 65%。详情仍写急救“每级”和“最高 Lv.5”，与已经取消技能升级、学习即满级的规则不一致。

隔离调用实际回收率函数，医疗技术 10 级、拥有满级急救的参战军官，返回 65%。此项是说明错误，不建议把现有 65% 效果削减为 50%。

建议：区分医疗技术贡献与技能加成，说明当前总回收上限；急救直接说明固定满级效果，并明确技能无需升级。

位置：[wounded.js:36](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/wounded.js:36)、[wounded.js:180](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/wounded.js:180)、[WoundedService.java:51](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/WoundedService.java:51)。

## 验证记录与范围

| 检查 | 结果及证据性质 |
| --- | --- |
| 接续核对本轮已完成的后端定向回归 | 6 类共 160 项，0 失败、0 错误、0 跳过；从现有 Surefire XML 核对，未重复执行 |
| 接续核对本轮已完成的前端定向回归 | 36 项通过；城市上下文、军团页面、战俘及伤兵流程，未重复执行 |
| 本次隔离后端观察 | 6 项成功执行并记录结果；包含缺陷现象及报价，不代表修复验收通过 |
| 本次前端报价观察 | 2 个后端真实报价样本输入前端实际函数，得到上表差异 |

后端定向类为 GuildSupportTest、PlayerCityConquestTest、WoundedServiceTest、RankPromotionTest、PrisonerServiceTest 和 QuestStageSystemTest。既有回归未覆盖上述冻结计时和 VIP 报价边界，因此测试通过与这些问题存在并不矛盾。

本次实际运行的观察命令：

```sh
python3 output/gameplay-audit-round6-20261031/run-audit.py
node output/gameplay-audit-round6-20261031/healing-view.cjs
```

检查重点为归降、接管、粮账、伤兵、战俘、军官、军衔及相关前端状态；另外复核了科研完成、工事容量、野地名额、动员规则、外交和独立个人战争的关联实现。和平移交科研队列采用最高等级合并已有明确设计依据，不作为新增缺陷；战斗技能保留内部数值等级也不等于恢复了玩家升级入口。

本轮没有运行后端或前端全量、真实 MySQL 迁移、真实客户端完整玩法或所有战斗组合，不能得出“整个项目已无问题”。当前第五轮及本轮都保留未修复项。

详细记录：[第六轮观察与日志](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/output/gameplay-audit-round6-20261031)。其中 `observations.json` 为独立 H2 观察结果，`healing-view.json` 为真实前端函数计算结果；`backend-regression.log` 和 `frontend-regression.log` 为接续的本轮前序验证记录。
