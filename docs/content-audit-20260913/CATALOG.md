# 内容来源逐项清单

静态快照时间：2026-09-13T22:54:54.048926+08:00。共 467 个审查条目。编号用于反馈；全部保持“待评估”。本清单已按用户要求删减部分历史描述，不再是未经编辑的快照摘录。

“来源不明”只表示仓库中缺少可核验来源，不能直接认定为复制或侵权。所有数值为快照中代码/配置的现状；未对每个公式做运行验证，也未与原游戏逐条比对。

原始字段与完整实现摘录见 [inventory.json](inventory.json)，历史及外部证据见 [EVIDENCE.md](EVIDENCE.md)。

## 任务

### T001 · 第一章 · 开荒奠基（ch1）

建设主城根基，建立第一支部队。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

最早可见备份已包含任务目录；章节措辞的原稿或生成记录未归档。

位置：[QuestCatalog.java:28](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:28>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T002 · 第二章 · 站稳脚跟（ch2）

走出主城，侦察、采集并清理周边威胁。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

最早可见备份已包含任务目录；章节措辞的原稿或生成记录未归档。

位置：[QuestCatalog.java:36](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:36>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T003 · 第三章 · 开疆扩土（ch3）

补齐资源产能，打造攻守兼备的基地。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

最早可见备份已包含任务目录；章节措辞的原稿或生成记录未归档。

位置：[QuestCatalog.java:44](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:44>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T004 · 第四章 · 阵营争锋（ch4）

完成从备战到宣战的第一次战争循环。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

最早可见备份已包含任务目录；章节措辞的原稿或生成记录未归档。

位置：[QuestCatalog.java:51](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:51>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T005 · 升级民居（q1_1）

把民居升到 2 级，提升人口上限

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:29](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:29>)

```json
{
  "事件": "BUILD_UPGRADE_DONE",
  "目标": "house",
  "数量": 2,
  "奖励": "r(0,0,0,0,200)",
  "前置": null
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T006 · 扩建农田（q1_2）

建造第 2 座农田，保障粮食供应

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:30](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:30>)

```json
{
  "事件": "BUILD_COUNT",
  "目标": "farm",
  "数量": 2,
  "奖励": "r(3000,0,0,0,200)",
  "前置": "q1_1"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T007 · 炼钢起步（q1_3）

把炼钢厂升到 2 级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:31](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:31>)

```json
{
  "事件": "BUILD_UPGRADE_DONE",
  "目标": "refinery",
  "数量": 2,
  "奖励": "r(0,3000,0,0,250)",
  "前置": "q1_2"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T008 · 招兵买马（q1_4）

训练 30 个步兵

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:32](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:32>)

```json
{
  "事件": "ARMY_RECRUIT",
  "目标": "infantry",
  "数量": 30,
  "奖励": "r(3000,0,0,0,300)",
  "前置": "q1_3"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T009 · 统帅初现（q1_5）

招募 1 名军官

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:33](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:33>)

```json
{
  "事件": "OFFICER_RECRUIT",
  "目标": null,
  "数量": 1,
  "奖励": "new Reward(0,0,0,0,300,0,\"1\",null,null,0)",
  "前置": "q1_4"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T010 · 委以重任（q1_6）

任命一名军官为市长或指挥官

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:34](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:34>)

```json
{
  "事件": "OFFICER_APPOINT",
  "目标": null,
  "数量": 1,
  "奖励": "r(2000,2000,1000,0,300)",
  "前置": "q1_5"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T011 · 扩建民居（q2_1）

民居总等级达到 5

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:37](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:37>)

```json
{
  "事件": "BUILD_LEVEL_SUM",
  "目标": "house",
  "数量": 5,
  "奖励": "r(4000,2000,0,0,500)",
  "前置": "q1_6"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T012 · 炮兵连（q2_2）

训练 20 个炮兵

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:38](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:38>)

```json
{
  "事件": "ARMY_RECRUIT",
  "目标": "artillery",
  "数量": 20,
  "奖励": "r(0,3000,1000,0,500)",
  "前置": "q2_1"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T013 · 前线侦察（q2_3）

派出侦察兵完成 1 次侦查

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:39](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:39>)

```json
{
  "事件": "SCOUT_COMPLETE",
  "目标": null,
  "数量": 1,
  "奖励": "r(1000,1000,500,0,400)",
  "前置": "q2_2"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T014 · 远征采集（q2_4）

完成 1 次野外资源采集并运回主城

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:40](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:40>)

```json
{
  "事件": "GATHER_COMPLETE",
  "目标": null,
  "数量": 1,
  "奖励": "r(3000,2000,1000,200,500)",
  "前置": "q2_3"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T015 · 肃清流寇（q2_5）

击败 1 个流寇据点

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:41](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:41>)

```json
{
  "事件": "BANDIT_DEFEAT",
  "目标": null,
  "数量": 1,
  "奖励": "new Reward(0,0,0,0,500,0,\"1\",null,null,0)",
  "前置": "q2_4"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T016 · 占领野地（q2_6）

占领 1 块资源野地

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:42](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:42>)

```json
{
  "事件": "WILD_CLAIM",
  "目标": null,
  "数量": 1,
  "奖励": "r(5000,2000,0,0,500)",
  "前置": "q2_5"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T017 · 稀矿起步（q3_1）

将稀有矿升到 2 级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:45](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:45>)

```json
{
  "事件": "BUILD_UPGRADE_DONE",
  "目标": "raremine",
  "数量": 2,
  "奖励": "r(0,0,0,500,600)",
  "前置": "q2_6"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T018 · 油田上马（q3_2）

将油田升到 2 级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:46](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:46>)

```json
{
  "事件": "BUILD_UPGRADE_DONE",
  "目标": "oilfield",
  "数量": 2,
  "奖励": "r(0,0,1000,500,700)",
  "前置": "q3_1"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T019 · 兵强马壮（q3_3）

总兵力达到 100

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:47](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:47>)

```json
{
  "事件": "ARMY_TOTAL",
  "目标": null,
  "数量": 100,
  "奖励": "r(5000,3000,2000,0,800)",
  "前置": "q3_2"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T020 · 城防初具（q3_4）

将城墙升到 2 级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:48](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:48>)

```json
{
  "事件": "BUILD_UPGRADE_DONE",
  "目标": "wall",
  "数量": 2,
  "奖励": "r(0,3000,0,0,600)",
  "前置": "q3_3"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T021 · 名将加盟（q3_5）

招募 1 名 3 星或以上军官

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:49](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:49>)

```json
{
  "事件": "OFFICER_RECRUIT_STAR",
  "目标": null,
  "数量": 3,
  "奖励": "new Reward(0,0,0,0,1500,0,\"1\",\"1\",null,0)",
  "前置": "q3_4"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T022 · 高级兵工厂（q4_1）

军工厂总等级达到 4

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:52](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:52>)

```json
{
  "事件": "BUILD_LEVEL_SUM",
  "目标": "factory",
  "数量": 4,
  "奖励": "r(0,5000,2000,0,1000)",
  "前置": "q3_5"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T023 · 雄狮之师（q4_2）

总兵力达到 300

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:53](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:53>)

```json
{
  "事件": "ARMY_TOTAL",
  "目标": null,
  "数量": 300,
  "奖励": "new Reward(5000,5000,3000,500,1500,0,null,\"1\",null,0)",
  "前置": "q4_1"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T024 · 正式宣战（q4_3）

向其他玩家主城宣战

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:54](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:54>)

```json
{
  "事件": "WAR_DECLARE",
  "目标": null,
  "数量": 1,
  "奖励": "new Reward(0,0,0,0,2000,0,\"2\",null,\"shield\",1)",
  "前置": "q4_2"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T025 · 首战告捷（q4_4）

赢得 1 场玩家城战斗

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:55](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:55>)

```json
{
  "事件": "PLAYER_WIN",
  "目标": null,
  "数量": 1,
  "奖励": "new Reward(10000,5000,2000,500,3000,0,\"2\",\"1\",\"renameCard\",1)",
  "前置": "q4_3"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T026 · 欢迎来到烽原战策（g_welcome）

我是您的作战参谋。完成训练营后，您将拥有一座能生产、能防守的主城。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:60](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:60>)

```json
{
  "目标文案": "开始新手训练营",
  "目标": null,
  "数量": 0,
  "奖励": "new Reward(0,0,0,0,0,0,null,null,null,0)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T027 · 第一步 · 升级市政厅（g_upgrade_command）

升级市政厅到 2 级，解锁更高等级的建设。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:61>)

```json
{
  "目标文案": "市政厅等级 ≥ 2",
  "目标": "command",
  "数量": 2,
  "奖励": "r(2000,1500,0,0,100)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T028 · 第二步 · 建造民居（g_build_house）

把民居升到 2 级，增加人口上限。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:62](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:62>)

```json
{
  "目标文案": "民居等级 ≥ 2",
  "目标": "house",
  "数量": 2,
  "奖励": "r(1500,500,0,0,50)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T029 · 第三步 · 建造农田（g_build_farm）

建造并升级农田，为军队提供粮食。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:63](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:63>)

```json
{
  "目标文案": "农田等级 ≥ 2",
  "目标": "farm",
  "数量": 2,
  "奖励": "r(2000,0,0,0,50)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T030 · 第四步 · 建造炼钢厂（g_build_refinery）

钢铁是建造和训练的核心资源。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:64](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:64>)

```json
{
  "目标文案": "炼钢厂等级 ≥ 2",
  "目标": "refinery",
  "数量": 2,
  "奖励": "r(0,2500,0,0,50)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T031 · 第五步 · 建造石油基地（g_build_oilfield）

石油支撑机动部队和高级军工生产。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:65](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:65>)

```json
{
  "目标文案": "石油基地等级 ≥ 1",
  "目标": "oilfield",
  "数量": 1,
  "奖励": "r(0,0,1500,0,50)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T032 · 第六步 · 建造军工厂（g_build_factory）

军工厂是训练地面部队的前置建筑。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:66](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:66>)

```json
{
  "目标文案": "军工厂等级 ≥ 1",
  "目标": "factory",
  "数量": 1,
  "奖励": "r(0,1500,500,0,80)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T033 · 第七步 · 训练步兵（g_recruit_infantry）

训练 20 个步兵，建立第一支守军。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:67](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:67>)

```json
{
  "目标文案": "步兵累计 ≥ 20",
  "目标": "infantry",
  "数量": 20,
  "奖励": "r(0,0,0,0,150)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T034 · 第八步 · 招募军官（g_recruit_officer）

军官可以显著提升部队与城市能力。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:68](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:68>)

```json
{
  "目标文案": "拥有 ≥ 1 名军官",
  "目标": null,
  "数量": 1,
  "奖励": "new Reward(0,0,0,0,200,0,\"1\",null,null,0)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T035 · 第九步 · 任命市长（g_appoint_mayor）

任命军官管理主城，提升发展效率。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:69](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:69>)

```json
{
  "目标文案": "已任命 1 名市长",
  "目标": "mayor",
  "数量": 1,
  "奖励": "r(2000,2000,1000,0,200)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T036 · 新手训练营 · 毕业（g_done）

恭喜您掌握了主城建设、军队训练和军官任用。接下来沿主线任务扩张领土吧。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[QuestCatalog.java:70](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:70>)

```json
{
  "目标文案": "已毕业",
  "目标": null,
  "数量": 0,
  "奖励": "new Reward(5000,5000,2000,0,500,0,\"1\",\"1\",null,0)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T037 · 欢迎弹窗第1条（legacy_tutorial_1）

资源([1]): 升级农场/炼油厂/钢/稀矿,提升每小时产量。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

另一套独立的欢迎弹窗文案；部分建筑叫法与当前配置不同，不能作为独立创作证据。

位置：[main.js:452](</Users/chenjuan/Documents/游戏/frontend/js/main.js:452>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T038 · 欢迎弹窗第2条（legacy_tutorial_2）

军事([2]): 建造兵营、兵工厂、解锁高级兵种。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

另一套独立的欢迎弹窗文案；部分建筑叫法与当前配置不同，不能作为独立创作证据。

位置：[main.js:453](</Users/chenjuan/Documents/游戏/frontend/js/main.js:453>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T039 · 欢迎弹窗第3条（legacy_tutorial_3）

军官学院: 消耗 200 黄金/次 刷新候选,招幕将领出战。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

另一套独立的欢迎弹窗文案；部分建筑叫法与当前配置不同，不能作为独立创作证据。

位置：[main.js:454](</Users/chenjuan/Documents/游戏/frontend/js/main.js:454>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T040 · 欢迎弹窗第4条（legacy_tutorial_4）

地图([6]): 扫描周围资源/流寇/玩家主城。宣战前需 6 小时备战 + 24 小时战争。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

另一套独立的欢迎弹窗文案；部分建筑叫法与当前配置不同，不能作为独立创作证据。

位置：[main.js:455](</Users/chenjuan/Documents/游戏/frontend/js/main.js:455>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T041 · 欢迎弹窗第5条（legacy_tutorial_5）

军饷: 系统按每小时从黄金中扣除军官薪资总额,金币不足时武将忠诚度会下降,请保持税源。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

另一套独立的欢迎弹窗文案；部分建筑叫法与当前配置不同，不能作为独立创作证据。

位置：[main.js:456](</Users/chenjuan/Documents/游戏/frontend/js/main.js:456>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T042 · 每日任务占位（daily）

暂无每日任务数据

**状态：通用占位文案；评估：待评估；建议次序：低优先核对。**

当前没有对应的任务内容定义；不将早期文档里的任务示例算成已上线任务。

位置：[task-quests.js:44](</Users/chenjuan/Documents/游戏/frontend/js/task-quests.js:44>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T043 · 活动占位（activities）

暂无活动与任务数据

**状态：通用占位文案；评估：待评估；建议次序：低优先核对。**

当前没有对应的任务内容定义；不将早期文档里的任务示例算成已上线任务。

位置：[task.js:28](</Users/chenjuan/Documents/游戏/frontend/js/task.js:28>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### T044 · 今日战果占位（daily_stats）

暂无今日战果数据

**状态：通用占位文案；评估：待评估；建议次序：低优先核对。**

当前没有对应的任务内容定义；不将早期文档里的任务示例算成已上线任务。

位置：[task.js:164](</Users/chenjuan/Documents/游戏/frontend/js/task.js:164>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

## 道具

### I001 · 经验书（expBook）

后端：军官使用,获得10000经验；前端：军官使用,获得10000经验；商城：军官使用,获得10000经验

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:29](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:29>) · [data.js:202](</Users/chenjuan/Documents/游戏/frontend/js/data.js:202>) · [shop.js:20](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:20>)

```json
{
  "backend": {
    "key": "expBook",
    "name": "经验书",
    "icon": "📘",
    "cat": "CAT_OFFICER",
    "desc": "军官使用,获得10000经验",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "经验书",
    "icon": "📘",
    "desc": "军官使用,获得10000经验",
    "cat": "officer"
  },
  "shop": {
    "id": "expBook",
    "cat": "officer",
    "name": "经验书",
    "icon": "📘",
    "desc": "军官使用,获得10000经验",
    "price": 30,
    "stock": null,
    "tag": "热销"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I002 · 高级经验书（expBookAdv）

后端：军官使用,获得100000经验；前端：军官使用,获得100000经验；商城：军官使用,获得100000经验

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:30](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:30>) · [data.js:203](</Users/chenjuan/Documents/游戏/frontend/js/data.js:203>) · [shop.js:21](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:21>)

```json
{
  "backend": {
    "key": "expBookAdv",
    "name": "高级经验书",
    "icon": "📕",
    "cat": "CAT_OFFICER",
    "desc": "军官使用,获得100000经验",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "高级经验书",
    "icon": "📕",
    "desc": "军官使用,获得100000经验",
    "cat": "officer"
  },
  "shop": {
    "id": "expBookAdv",
    "cat": "officer",
    "name": "高级经验书",
    "icon": "📕",
    "desc": "军官使用,获得100000经验",
    "price": 150,
    "stock": null,
    "tag": "推荐"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I003 · 满级经验书（expBookMax）

后端：军官使用,直接升至满级(Lv.100)；前端：军官使用,直接升至满级(Lv.100)；商城：军官使用,直接升至满级(Lv.100)

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:31](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:31>) · [data.js:204](</Users/chenjuan/Documents/游戏/frontend/js/data.js:204>) · [shop.js:22](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:22>)

```json
{
  "backend": {
    "key": "expBookMax",
    "name": "满级经验书",
    "icon": "📙",
    "cat": "CAT_OFFICER",
    "desc": "军官使用,直接升至满级(Lv.100)",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "满级经验书",
    "icon": "📙",
    "desc": "军官使用,直接升至满级(Lv.100)",
    "cat": "officer"
  },
  "shop": {
    "id": "expBookMax",
    "cat": "officer",
    "name": "满级经验书",
    "icon": "📙",
    "desc": "军官使用,直接升至满级(Lv.100)",
    "price": 1000,
    "stock": null,
    "tag": "极品"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I004 · 技能书（skillBook）

后端：为军官学习新技能；前端：军官学习新技能；商城：为军官学习新技能

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:32](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:32>) · [data.js:205](</Users/chenjuan/Documents/游戏/frontend/js/data.js:205>) · [shop.js:23](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:23>)

```json
{
  "backend": {
    "key": "skillBook",
    "name": "技能书",
    "icon": "📗",
    "cat": "CAT_OFFICER",
    "desc": "为军官学习新技能",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "技能书",
    "icon": "📗",
    "desc": "军官学习新技能",
    "cat": "officer"
  },
  "shop": {
    "id": "skillBook",
    "cat": "officer",
    "name": "技能书",
    "icon": "📗",
    "desc": "为军官学习新技能",
    "price": 80,
    "stock": null,
    "tag": ""
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I005 · 忠诚宝箱（loyaltyBox）

后端：军官忠诚度+20；前端：军官使用,忠诚度+20；商城：军官忠诚度+20,提升留任意愿

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:33](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:33>) · [data.js:206](</Users/chenjuan/Documents/游戏/frontend/js/data.js:206>) · [shop.js:24](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:24>)

```json
{
  "backend": {
    "key": "loyaltyBox",
    "name": "忠诚宝箱",
    "icon": "🎁",
    "cat": "CAT_OFFICER",
    "desc": "军官忠诚度+20",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "忠诚宝箱",
    "icon": "🎁",
    "desc": "军官使用,忠诚度+20",
    "cat": "officer"
  },
  "shop": {
    "id": "loyaltyBox",
    "cat": "officer",
    "name": "忠诚宝箱",
    "icon": "🎁",
    "desc": "军官忠诚度+20,提升留任意愿",
    "price": 50,
    "stock": null,
    "tag": ""
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I006 · 改名卡（renameCard）

后端：为军官更换新名字；前端：为军官更换新名字；商城：为军官更换新名字

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:34](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:34>) · [data.js:207](</Users/chenjuan/Documents/游戏/frontend/js/data.js:207>) · [shop.js:25](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:25>)

```json
{
  "backend": {
    "key": "renameCard",
    "name": "改名卡",
    "icon": "🏷️",
    "cat": "CAT_OFFICER",
    "desc": "为军官更换新名字",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "改名卡",
    "icon": "🏷️",
    "desc": "为军官更换新名字",
    "cat": "officer"
  },
  "shop": {
    "id": "renameCard",
    "cat": "officer",
    "name": "改名卡",
    "icon": "🏷️",
    "desc": "为军官更换新名字",
    "price": 60,
    "stock": null,
    "tag": ""
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I007 · 征募令（recruitOrd）

后端：刷新军校,保底出现一名五星军官；前端：刷新军校,保底出现一名五星军官；商城：刷新军校,保底出现一名五星军官

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:35](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:35>) · [data.js:208](</Users/chenjuan/Documents/游戏/frontend/js/data.js:208>) · [shop.js:26](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:26>)

```json
{
  "backend": {
    "key": "recruitOrd",
    "name": "征募令",
    "icon": "🎖️",
    "cat": "CAT_OFFICER",
    "desc": "刷新军校,保底出现一名五星军官",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "征募令",
    "icon": "🎖️",
    "desc": "刷新军校,保底出现一名五星军官",
    "cat": "officer"
  },
  "shop": {
    "id": "recruitOrd",
    "cat": "officer",
    "name": "征募令",
    "icon": "🎖️",
    "desc": "刷新军校,保底出现一名五星军官",
    "price": 500,
    "stock": 3,
    "tag": "稀有"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I008 · 星耀符（starUp）

后端：军官升星,属性大幅成长；前端：军官升星,属性大幅成长；商城：军官升星,属性大幅成长

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:36](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:36>) · [data.js:209](</Users/chenjuan/Documents/游戏/frontend/js/data.js:209>) · [shop.js:27](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:27>)

```json
{
  "backend": {
    "key": "starUp",
    "name": "星耀符",
    "icon": "✨",
    "cat": "CAT_OFFICER",
    "desc": "军官升星,属性大幅成长",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "星耀符",
    "icon": "✨",
    "desc": "军官升星,属性大幅成长",
    "cat": "officer"
  },
  "shop": {
    "id": "starUp",
    "cat": "officer",
    "name": "星耀符",
    "icon": "✨",
    "desc": "军官升星,属性大幅成长",
    "price": 300,
    "stock": null,
    "tag": ""
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I009 · 列兵军事装备箱（box_recruit_military）

后端：开启获得整套列兵军事装备(军刀/臂章/作训服)；前端：开启获得整套列兵军事装备(军刀/臂章/作训服)；商城：开启获得整套列兵军事装备(军刀/臂章/作训服)，激活军事+3

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:38](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:38>) · [data.js:210](</Users/chenjuan/Documents/游戏/frontend/js/data.js:210>) · [shop.js:30](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:30>)

```json
{
  "backend": {
    "key": "box_recruit_military",
    "name": "列兵军事装备箱",
    "icon": "📦",
    "cat": "CAT_OFFICER",
    "desc": "开启获得整套列兵军事装备(军刀/臂章/作训服)",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "列兵军事装备箱",
    "icon": "📦",
    "desc": "开启获得整套列兵军事装备(军刀/臂章/作训服)",
    "cat": "officer",
    "isBox": true
  },
  "shop": {
    "id": "box_recruit_military",
    "cat": "officer",
    "name": "列兵军事装备箱",
    "icon": "📦",
    "desc": "开启获得整套列兵军事装备(军刀/臂章/作训服)，激活军事+3",
    "price": 200,
    "stock": null,
    "tag": "低级套装"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I010 · 列兵后勤装备箱（box_recruit_logistics）

后端：开启获得整套列兵后勤装备(工具包/通行证/工作服)；前端：开启获得整套列兵后勤装备(工具包/通行证/工作服)；商城：开启获得整套列兵后勤装备(工具包/通行证/工作服)，激活后勤+3

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:39](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:39>) · [data.js:211](</Users/chenjuan/Documents/游戏/frontend/js/data.js:211>) · [shop.js:31](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:31>)

```json
{
  "backend": {
    "key": "box_recruit_logistics",
    "name": "列兵后勤装备箱",
    "icon": "📦",
    "cat": "CAT_OFFICER",
    "desc": "开启获得整套列兵后勤装备(工具包/通行证/工作服)",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "列兵后勤装备箱",
    "icon": "📦",
    "desc": "开启获得整套列兵后勤装备(工具包/通行证/工作服)",
    "cat": "officer",
    "isBox": true
  },
  "shop": {
    "id": "box_recruit_logistics",
    "cat": "officer",
    "name": "列兵后勤装备箱",
    "icon": "📦",
    "desc": "开启获得整套列兵后勤装备(工具包/通行证/工作服)，激活后勤+3",
    "price": 200,
    "stock": null,
    "tag": "低级套装"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I011 · 列兵学识装备箱（box_recruit_knowledge）

后端：开启获得整套列兵学识装备(笔记本/学员章/学员服)；前端：开启获得整套列兵学识装备(笔记本/学员章/学员服)；商城：开启获得整套列兵学识装备(笔记本/学员章/学员服)，激活学识+3

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:40](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:40>) · [data.js:212](</Users/chenjuan/Documents/游戏/frontend/js/data.js:212>) · [shop.js:32](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:32>)

```json
{
  "backend": {
    "key": "box_recruit_knowledge",
    "name": "列兵学识装备箱",
    "icon": "📦",
    "cat": "CAT_OFFICER",
    "desc": "开启获得整套列兵学识装备(笔记本/学员章/学员服)",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "列兵学识装备箱",
    "icon": "📦",
    "desc": "开启获得整套列兵学识装备(笔记本/学员章/学员服)",
    "cat": "officer",
    "isBox": true
  },
  "shop": {
    "id": "box_recruit_knowledge",
    "cat": "officer",
    "name": "列兵学识装备箱",
    "icon": "📦",
    "desc": "开启获得整套列兵学识装备(笔记本/学员章/学员服)，激活学识+3",
    "price": 200,
    "stock": null,
    "tag": "低级套装"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I012 · 校官军事装备箱（box_officer_military）

后端：开启获得整套校官军事装备(军刀/勋章/军服)；前端：开启获得整套校官军事装备(军刀/勋章/军服)；商城：开启获得整套校官军事装备(军刀/勋章/军服)，激活军事+15

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:41](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:41>) · [data.js:213](</Users/chenjuan/Documents/游戏/frontend/js/data.js:213>) · [shop.js:33](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:33>)

```json
{
  "backend": {
    "key": "box_officer_military",
    "name": "校官军事装备箱",
    "icon": "🎁",
    "cat": "CAT_OFFICER",
    "desc": "开启获得整套校官军事装备(军刀/勋章/军服)",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "校官军事装备箱",
    "icon": "🎁",
    "desc": "开启获得整套校官军事装备(军刀/勋章/军服)",
    "cat": "officer",
    "isBox": true
  },
  "shop": {
    "id": "box_officer_military",
    "cat": "officer",
    "name": "校官军事装备箱",
    "icon": "🎁",
    "desc": "开启获得整套校官军事装备(军刀/勋章/军服)，激活军事+15",
    "price": 1200,
    "stock": null,
    "tag": "中级套装"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I013 · 校官后勤装备箱（box_officer_logistics）

后端：开启获得整套校官后勤装备(补给箱/调度章/军需服)；前端：开启获得整套校官后勤装备(补给箱/调度章/军需服)；商城：开启获得整套校官后勤装备(补给箱/调度章/军需服)，激活后勤+15

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:42](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:42>) · [data.js:214](</Users/chenjuan/Documents/游戏/frontend/js/data.js:214>) · [shop.js:34](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:34>)

```json
{
  "backend": {
    "key": "box_officer_logistics",
    "name": "校官后勤装备箱",
    "icon": "🎁",
    "cat": "CAT_OFFICER",
    "desc": "开启获得整套校官后勤装备(补给箱/调度章/军需服)",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "校官后勤装备箱",
    "icon": "🎁",
    "desc": "开启获得整套校官后勤装备(补给箱/调度章/军需服)",
    "cat": "officer",
    "isBox": true
  },
  "shop": {
    "id": "box_officer_logistics",
    "cat": "officer",
    "name": "校官后勤装备箱",
    "icon": "🎁",
    "desc": "开启获得整套校官后勤装备(补给箱/调度章/军需服)，激活后勤+15",
    "price": 1200,
    "stock": null,
    "tag": "中级套装"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I014 · 校官学识装备箱（box_officer_knowledge）

后端：开启获得整套校官学识装备(战术罗盘/参谋章/参谋服)；前端：开启获得整套校官学识装备(战术罗盘/参谋章/参谋服)；商城：开启获得整套校官学识装备(战术罗盘/参谋章/参谋服)，激活学识+15

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:43](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:43>) · [data.js:215](</Users/chenjuan/Documents/游戏/frontend/js/data.js:215>) · [shop.js:35](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:35>)

```json
{
  "backend": {
    "key": "box_officer_knowledge",
    "name": "校官学识装备箱",
    "icon": "🎁",
    "cat": "CAT_OFFICER",
    "desc": "开启获得整套校官学识装备(战术罗盘/参谋章/参谋服)",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "校官学识装备箱",
    "icon": "🎁",
    "desc": "开启获得整套校官学识装备(战术罗盘/参谋章/参谋服)",
    "cat": "officer",
    "isBox": true
  },
  "shop": {
    "id": "box_officer_knowledge",
    "cat": "officer",
    "name": "校官学识装备箱",
    "icon": "🎁",
    "desc": "开启获得整套校官学识装备(战术罗盘/参谋章/参谋服)，激活学识+15",
    "price": 1200,
    "stock": null,
    "tag": "中级套装"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I015 · 元帅军事装备箱（box_marshal_military）

后端：开启获得整套元帅军事装备(佩剑/将星/礼服)；前端：开启获得整套元帅军事装备(佩剑/将星/礼服)；商城：开启获得整套元帅军事装备(佩剑/将星/礼服)，激活军事+30+全属性+5

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:44](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:44>) · [data.js:216](</Users/chenjuan/Documents/游戏/frontend/js/data.js:216>) · [shop.js:36](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:36>)

```json
{
  "backend": {
    "key": "box_marshal_military",
    "name": "元帅军事装备箱",
    "icon": "👑",
    "cat": "CAT_OFFICER",
    "desc": "开启获得整套元帅军事装备(佩剑/将星/礼服)",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "元帅军事装备箱",
    "icon": "👑",
    "desc": "开启获得整套元帅军事装备(佩剑/将星/礼服)",
    "cat": "officer",
    "isBox": true
  },
  "shop": {
    "id": "box_marshal_military",
    "cat": "officer",
    "name": "元帅军事装备箱",
    "icon": "👑",
    "desc": "开启获得整套元帅军事装备(佩剑/将星/礼服)，激活军事+30+全属性+5",
    "price": 5000,
    "stock": null,
    "tag": "满级套装"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I016 · 元帅后勤装备箱（box_marshal_logistics）

后端：开启获得整套元帅后勤装备(辎重车/军需印/长袍)；前端：开启获得整套元帅后勤装备(辎重车/军需印/长袍)；商城：开启获得整套元帅后勤装备(辎重车/军需印/长袍)，激活后勤+30+全属性+5

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:45](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:45>) · [data.js:217](</Users/chenjuan/Documents/游戏/frontend/js/data.js:217>) · [shop.js:37](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:37>)

```json
{
  "backend": {
    "key": "box_marshal_logistics",
    "name": "元帅后勤装备箱",
    "icon": "👑",
    "cat": "CAT_OFFICER",
    "desc": "开启获得整套元帅后勤装备(辎重车/军需印/长袍)",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "元帅后勤装备箱",
    "icon": "👑",
    "desc": "开启获得整套元帅后勤装备(辎重车/军需印/长袍)",
    "cat": "officer",
    "isBox": true
  },
  "shop": {
    "id": "box_marshal_logistics",
    "cat": "officer",
    "name": "元帅后勤装备箱",
    "icon": "👑",
    "desc": "开启获得整套元帅后勤装备(辎重车/军需印/长袍)，激活后勤+30+全属性+5",
    "price": 5000,
    "stock": null,
    "tag": "满级套装"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I017 · 元帅学识装备箱（box_marshal_knowledge）

后端：开启获得整套元帅学识装备(望远镜/军师印/军礼服)；前端：开启获得整套元帅学识装备(望远镜/军师印/军礼服)；商城：开启获得整套元帅学识装备(望远镜/军师印/军礼服)，激活学识+30+全属性+5

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:46](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:46>) · [data.js:218](</Users/chenjuan/Documents/游戏/frontend/js/data.js:218>) · [shop.js:38](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:38>)

```json
{
  "backend": {
    "key": "box_marshal_knowledge",
    "name": "元帅学识装备箱",
    "icon": "👑",
    "cat": "CAT_OFFICER",
    "desc": "开启获得整套元帅学识装备(望远镜/军师印/军礼服)",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "元帅学识装备箱",
    "icon": "👑",
    "desc": "开启获得整套元帅学识装备(望远镜/军师印/军礼服)",
    "cat": "officer",
    "isBox": true
  },
  "shop": {
    "id": "box_marshal_knowledge",
    "cat": "officer",
    "name": "元帅学识装备箱",
    "icon": "👑",
    "desc": "开启获得整套元帅学识装备(望远镜/军师印/军礼服)，激活学识+30+全属性+5",
    "price": 5000,
    "stock": null,
    "tag": "满级套装"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I018 · 列兵军刀（recruit_military_weapon）

后端：列兵军事套装·武器：军事+5，其余+1，集齐3件军事+3

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:49](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:49>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "recruit_military_weapon",
    "name": "列兵军刀",
    "icon": "🗡️",
    "cat": "CAT_OFFICER",
    "desc": "列兵军事套装·武器：军事+5，其余+1，集齐3件军事+3",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 1,
    "主属性": 5,
    "副属性": 1,
    "集齐3件主属性": 3,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I019 · 列兵臂章（recruit_military_badge）

后端：列兵军事套装·徽章：军事+5，其余+1，集齐3件军事+3

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:50](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:50>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "recruit_military_badge",
    "name": "列兵臂章",
    "icon": "🎗️",
    "cat": "CAT_OFFICER",
    "desc": "列兵军事套装·徽章：军事+5，其余+1，集齐3件军事+3",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 1,
    "主属性": 5,
    "副属性": 1,
    "集齐3件主属性": 3,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I020 · 列兵作训服（recruit_military_coat）

后端：列兵军事套装·外套：军事+5，其余+1，集齐3件军事+3

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:51](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:51>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "recruit_military_coat",
    "name": "列兵作训服",
    "icon": "🦺",
    "cat": "CAT_OFFICER",
    "desc": "列兵军事套装·外套：军事+5，其余+1，集齐3件军事+3",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 1,
    "主属性": 5,
    "副属性": 1,
    "集齐3件主属性": 3,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I021 · 列兵工具包（recruit_logistics_weapon）

后端：列兵后勤套装·武器：后勤+5，其余+1，集齐3件后勤+3

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:52](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:52>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "recruit_logistics_weapon",
    "name": "列兵工具包",
    "icon": "🛠️",
    "cat": "CAT_OFFICER",
    "desc": "列兵后勤套装·武器：后勤+5，其余+1，集齐3件后勤+3",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 1,
    "主属性": 5,
    "副属性": 1,
    "集齐3件主属性": 3,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I022 · 列兵通行证（recruit_logistics_badge）

后端：列兵后勤套装·徽章：后勤+5，其余+1，集齐3件后勤+3

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:53](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:53>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "recruit_logistics_badge",
    "name": "列兵通行证",
    "icon": "🪪",
    "cat": "CAT_OFFICER",
    "desc": "列兵后勤套装·徽章：后勤+5，其余+1，集齐3件后勤+3",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 1,
    "主属性": 5,
    "副属性": 1,
    "集齐3件主属性": 3,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I023 · 列兵工作服（recruit_logistics_coat）

后端：列兵后勤套装·外套：后勤+5，其余+1，集齐3件后勤+3

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:54](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:54>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "recruit_logistics_coat",
    "name": "列兵工作服",
    "icon": "👕",
    "cat": "CAT_OFFICER",
    "desc": "列兵后勤套装·外套：后勤+5，其余+1，集齐3件后勤+3",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 1,
    "主属性": 5,
    "副属性": 1,
    "集齐3件主属性": 3,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I024 · 列兵笔记本（recruit_knowledge_weapon）

后端：列兵学识套装·武器：学识+5，其余+1，集齐3件学识+3

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:55](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:55>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "recruit_knowledge_weapon",
    "name": "列兵笔记本",
    "icon": "📓",
    "cat": "CAT_OFFICER",
    "desc": "列兵学识套装·武器：学识+5，其余+1，集齐3件学识+3",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 1,
    "主属性": 5,
    "副属性": 1,
    "集齐3件主属性": 3,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I025 · 列兵学员章（recruit_knowledge_badge）

后端：列兵学识套装·徽章：学识+5，其余+1，集齐3件学识+3

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:56](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:56>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "recruit_knowledge_badge",
    "name": "列兵学员章",
    "icon": "📛",
    "cat": "CAT_OFFICER",
    "desc": "列兵学识套装·徽章：学识+5，其余+1，集齐3件学识+3",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 1,
    "主属性": 5,
    "副属性": 1,
    "集齐3件主属性": 3,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I026 · 列兵学员服（recruit_knowledge_coat）

后端：列兵学识套装·外套：学识+5，其余+1，集齐3件学识+3

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:57](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:57>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "recruit_knowledge_coat",
    "name": "列兵学员服",
    "icon": "🎓",
    "cat": "CAT_OFFICER",
    "desc": "列兵学识套装·外套：学识+5，其余+1，集齐3件学识+3",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 1,
    "主属性": 5,
    "副属性": 1,
    "集齐3件主属性": 3,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I027 · 校官军刀（officer_military_weapon）

后端：校官军事套装·武器：军事+15，其余+3，集齐3件军事+15

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:60](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:60>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "officer_military_weapon",
    "name": "校官军刀",
    "icon": "⚔️",
    "cat": "CAT_OFFICER",
    "desc": "校官军事套装·武器：军事+15，其余+3，集齐3件军事+15",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 40,
    "主属性": 15,
    "副属性": 3,
    "集齐3件主属性": 15,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I028 · 校官勋章（officer_military_badge）

后端：校官军事套装·徽章：军事+15，其余+3，集齐3件军事+15

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:61>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "officer_military_badge",
    "name": "校官勋章",
    "icon": "🎖️",
    "cat": "CAT_OFFICER",
    "desc": "校官军事套装·徽章：军事+15，其余+3，集齐3件军事+15",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 40,
    "主属性": 15,
    "副属性": 3,
    "集齐3件主属性": 15,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I029 · 校官军服（officer_military_coat）

后端：校官军事套装·外套：军事+15，其余+3，集齐3件军事+15

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:62](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:62>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "officer_military_coat",
    "name": "校官军服",
    "icon": "🧥",
    "cat": "CAT_OFFICER",
    "desc": "校官军事套装·外套：军事+15，其余+3，集齐3件军事+15",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 40,
    "主属性": 15,
    "副属性": 3,
    "集齐3件主属性": 15,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I030 · 校官补给箱（officer_logistics_weapon）

后端：校官后勤套装·武器：后勤+15，其余+3，集齐3件后勤+15

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:63](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:63>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "officer_logistics_weapon",
    "name": "校官补给箱",
    "icon": "📦",
    "cat": "CAT_OFFICER",
    "desc": "校官后勤套装·武器：后勤+15，其余+3，集齐3件后勤+15",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 40,
    "主属性": 15,
    "副属性": 3,
    "集齐3件主属性": 15,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I031 · 校官调度章（officer_logistics_badge）

后端：校官后勤套装·徽章：后勤+15，其余+3，集齐3件后勤+15

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:64](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:64>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "officer_logistics_badge",
    "name": "校官调度章",
    "icon": "🚚",
    "cat": "CAT_OFFICER",
    "desc": "校官后勤套装·徽章：后勤+15，其余+3，集齐3件后勤+15",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 40,
    "主属性": 15,
    "副属性": 3,
    "集齐3件主属性": 15,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I032 · 校官军需服（officer_logistics_coat）

后端：校官后勤套装·外套：后勤+15，其余+3，集齐3件后勤+15

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:65](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:65>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "officer_logistics_coat",
    "name": "校官军需服",
    "icon": "🦺",
    "cat": "CAT_OFFICER",
    "desc": "校官后勤套装·外套：后勤+15，其余+3，集齐3件后勤+15",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 40,
    "主属性": 15,
    "副属性": 3,
    "集齐3件主属性": 15,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I033 · 校官战术罗盘（officer_knowledge_weapon）

后端：校官学识套装·武器：学识+15，其余+3，集齐3件学识+15

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:66](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:66>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "officer_knowledge_weapon",
    "name": "校官战术罗盘",
    "icon": "🧭",
    "cat": "CAT_OFFICER",
    "desc": "校官学识套装·武器：学识+15，其余+3，集齐3件学识+15",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 40,
    "主属性": 15,
    "副属性": 3,
    "集齐3件主属性": 15,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I034 · 校官参谋章（officer_knowledge_badge）

后端：校官学识套装·徽章：学识+15，其余+3，集齐3件学识+15

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:67](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:67>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "officer_knowledge_badge",
    "name": "校官参谋章",
    "icon": "📋",
    "cat": "CAT_OFFICER",
    "desc": "校官学识套装·徽章：学识+15，其余+3，集齐3件学识+15",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 40,
    "主属性": 15,
    "副属性": 3,
    "集齐3件主属性": 15,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I035 · 校官参谋服（officer_knowledge_coat）

后端：校官学识套装·外套：学识+15，其余+3，集齐3件学识+15

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:68](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:68>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "officer_knowledge_coat",
    "name": "校官参谋服",
    "icon": "🧑‍🎓",
    "cat": "CAT_OFFICER",
    "desc": "校官学识套装·外套：学识+15，其余+3，集齐3件学识+15",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 40,
    "主属性": 15,
    "副属性": 3,
    "集齐3件主属性": 15,
    "元帅另加全属性": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I036 · 元帅佩剑（marshal_military_weapon）

后端：元帅军事套装·武器：军事+30，其余+5，集齐3件军事+30+全属性+5

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:71](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:71>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "marshal_military_weapon",
    "name": "元帅佩剑",
    "icon": "👑",
    "cat": "CAT_OFFICER",
    "desc": "元帅军事套装·武器：军事+30，其余+5，集齐3件军事+30+全属性+5",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 100,
    "主属性": 30,
    "副属性": 5,
    "集齐3件主属性": 30,
    "元帅另加全属性": 5
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I037 · 元帅将星（marshal_military_badge）

后端：元帅军事套装·徽章：军事+30，其余+5，集齐3件军事+30+全属性+5

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:72](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:72>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "marshal_military_badge",
    "name": "元帅将星",
    "icon": "⭐",
    "cat": "CAT_OFFICER",
    "desc": "元帅军事套装·徽章：军事+30，其余+5，集齐3件军事+30+全属性+5",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 100,
    "主属性": 30,
    "副属性": 5,
    "集齐3件主属性": 30,
    "元帅另加全属性": 5
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I038 · 元帅礼服（marshal_military_coat）

后端：元帅军事套装·外套：军事+30，其余+5，集齐3件军事+30+全属性+5

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:73](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:73>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "marshal_military_coat",
    "name": "元帅礼服",
    "icon": "🤴",
    "cat": "CAT_OFFICER",
    "desc": "元帅军事套装·外套：军事+30，其余+5，集齐3件军事+30+全属性+5",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 100,
    "主属性": 30,
    "副属性": 5,
    "集齐3件主属性": 30,
    "元帅另加全属性": 5
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I039 · 元帅辎重车（marshal_logistics_weapon）

后端：元帅后勤套装·武器：后勤+30，其余+5，集齐3件后勤+30+全属性+5

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:74](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:74>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "marshal_logistics_weapon",
    "name": "元帅辎重车",
    "icon": "🐴",
    "cat": "CAT_OFFICER",
    "desc": "元帅后勤套装·武器：后勤+30，其余+5，集齐3件后勤+30+全属性+5",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 100,
    "主属性": 30,
    "副属性": 5,
    "集齐3件主属性": 30,
    "元帅另加全属性": 5
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I040 · 元帅军需印（marshal_logistics_badge）

后端：元帅后勤套装·徽章：后勤+30，其余+5，集齐3件后勤+30+全属性+5

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:75](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:75>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "marshal_logistics_badge",
    "name": "元帅军需印",
    "icon": "📜",
    "cat": "CAT_OFFICER",
    "desc": "元帅后勤套装·徽章：后勤+30，其余+5，集齐3件后勤+30+全属性+5",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 100,
    "主属性": 30,
    "副属性": 5,
    "集齐3件主属性": 30,
    "元帅另加全属性": 5
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I041 · 元帅长袍（marshal_logistics_coat）

后端：元帅后勤套装·外套：后勤+30，其余+5，集齐3件后勤+30+全属性+5

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:76](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:76>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "marshal_logistics_coat",
    "name": "元帅长袍",
    "icon": "👘",
    "cat": "CAT_OFFICER",
    "desc": "元帅后勤套装·外套：后勤+30，其余+5，集齐3件后勤+30+全属性+5",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 100,
    "主属性": 30,
    "副属性": 5,
    "集齐3件主属性": 30,
    "元帅另加全属性": 5
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I042 · 元帅望远镜（marshal_knowledge_weapon）

后端：元帅学识套装·武器：学识+30，其余+5，集齐3件学识+30+全属性+5

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:77](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:77>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "marshal_knowledge_weapon",
    "name": "元帅望远镜",
    "icon": "🔭",
    "cat": "CAT_OFFICER",
    "desc": "元帅学识套装·武器：学识+30，其余+5，集齐3件学识+30+全属性+5",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 100,
    "主属性": 30,
    "副属性": 5,
    "集齐3件主属性": 30,
    "元帅另加全属性": 5
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I043 · 元帅军师印（marshal_knowledge_badge）

后端：元帅学识套装·徽章：学识+30，其余+5，集齐3件学识+30+全属性+5

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:78](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:78>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "marshal_knowledge_badge",
    "name": "元帅军师印",
    "icon": "🪧",
    "cat": "CAT_OFFICER",
    "desc": "元帅学识套装·徽章：学识+30，其余+5，集齐3件学识+30+全属性+5",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 100,
    "主属性": 30,
    "副属性": 5,
    "集齐3件主属性": 30,
    "元帅另加全属性": 5
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I044 · 元帅军礼服（marshal_knowledge_coat）

后端：元帅学识套装·外套：学识+30，其余+5，集齐3件学识+30+全属性+5

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。 同一装备在仓库和后端还按模板生成穿戴等级、主副属性和套装说明，不能只改静态道具表。

位置：[ItemDef.java:79](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:79>) · [OfficerEquipmentDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:61>) · [depot.js:19](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:19>)

```json
{
  "backend": {
    "key": "marshal_knowledge_coat",
    "name": "元帅军礼服",
    "icon": "👔",
    "cat": "CAT_OFFICER",
    "desc": "元帅学识套装·外套：学识+30，其余+5，集齐3件学识+30+全属性+5",
    "speedUpSeconds": 0
  },
  "装备模板": {
    "等级": 100,
    "主属性": 30,
    "副属性": 5,
    "集齐3件主属性": 30,
    "元帅另加全属性": 5
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I045 · 黄金箱（goldBox）

后端：开启获得1000-5000黄金；前端：开启获得1000-5000黄金；商城：开启获得1000~5000黄金

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:82](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:82>) · [data.js:219](</Users/chenjuan/Documents/游戏/frontend/js/data.js:219>) · [shop.js:41](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:41>)

```json
{
  "backend": {
    "key": "goldBox",
    "name": "黄金箱",
    "icon": "🪙",
    "cat": "CAT_RESOURCE",
    "desc": "开启获得1000-5000黄金",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "黄金箱",
    "icon": "🪙",
    "desc": "开启获得1000-5000黄金",
    "cat": "resource"
  },
  "shop": {
    "id": "goldBox",
    "cat": "resource",
    "name": "黄金箱",
    "icon": "🪙",
    "desc": "开启获得1000~5000黄金",
    "price": 80,
    "stock": null,
    "tag": ""
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I046 · 资源箱（resBox）

后端：开启获得粮钢油稀各500；前端：开启获得粮钢油稀各500；商城：开启获得粮钢油稀各500

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:83](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:83>) · [data.js:220](</Users/chenjuan/Documents/游戏/frontend/js/data.js:220>) · [shop.js:42](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:42>)

```json
{
  "backend": {
    "key": "resBox",
    "name": "资源箱",
    "icon": "📦",
    "cat": "CAT_RESOURCE",
    "desc": "开启获得粮钢油稀各500",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "资源箱",
    "icon": "📦",
    "desc": "开启获得粮钢油稀各500",
    "cat": "resource"
  },
  "shop": {
    "id": "resBox",
    "cat": "resource",
    "name": "资源箱",
    "icon": "📦",
    "desc": "开启获得粮钢油稀各500",
    "price": 120,
    "stock": null,
    "tag": "热销"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I047 · 钢铁大礼包（steelPack）

后端：立即获得20000钢铁；前端：立即获得20000钢铁；商城：立即获得20000钢铁

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:84](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:84>) · [data.js:221](</Users/chenjuan/Documents/游戏/frontend/js/data.js:221>) · [shop.js:43](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:43>)

```json
{
  "backend": {
    "key": "steelPack",
    "name": "钢铁大礼包",
    "icon": "🔩",
    "cat": "CAT_RESOURCE",
    "desc": "立即获得20000钢铁",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "钢铁大礼包",
    "icon": "🔩",
    "desc": "立即获得20000钢铁",
    "cat": "resource"
  },
  "shop": {
    "id": "steelPack",
    "cat": "resource",
    "name": "钢铁大礼包",
    "icon": "🔩",
    "desc": "立即获得20000钢铁",
    "price": 200,
    "stock": null,
    "tag": ""
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I048 · 战备补给包（supplyPack）

后端：粮钢油稀各8000,适合长期发展；前端：粮钢油稀各8000,适合长期发展；商城：粮钢油稀各8000,适合长期发展

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:85](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:85>) · [data.js:222](</Users/chenjuan/Documents/游戏/frontend/js/data.js:222>) · [shop.js:44](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:44>)

```json
{
  "backend": {
    "key": "supplyPack",
    "name": "战备补给包",
    "icon": "🌾",
    "cat": "CAT_RESOURCE",
    "desc": "粮钢油稀各8000,适合长期发展",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "战备补给包",
    "icon": "🌾",
    "desc": "粮钢油稀各8000,适合长期发展",
    "cat": "resource"
  },
  "shop": {
    "id": "supplyPack",
    "cat": "resource",
    "name": "战备补给包",
    "icon": "🌾",
    "desc": "粮钢油稀各8000,适合长期发展",
    "price": 350,
    "stock": null,
    "tag": "超值"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I049 · 资源大礼包（resourcePack500w）

后端：粮食/钢铁/石油/稀矿各500万；前端：粮食/钢铁/石油/稀矿各500万；商城：粮食/钢铁/石油/稀矿各500万

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:86](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:86>) · [data.js:223](</Users/chenjuan/Documents/游戏/frontend/js/data.js:223>) · [shop.js:45](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:45>)

```json
{
  "backend": {
    "key": "resourcePack500w",
    "name": "资源大礼包",
    "icon": "🎁",
    "cat": "CAT_RESOURCE",
    "desc": "粮食/钢铁/石油/稀矿各500万",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "资源大礼包",
    "icon": "🎁",
    "desc": "粮食/钢铁/石油/稀矿各500万",
    "cat": "resource"
  },
  "shop": {
    "id": "resourcePack500w",
    "cat": "resource",
    "name": "资源大礼包",
    "icon": "🎁",
    "desc": "粮食/钢铁/石油/稀矿各500万",
    "price": 5000,
    "stock": null,
    "tag": "豪华"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I050 · 10分加速符（speedUp10m）

后端：立即缩短10分钟建筑/造兵时间；前端：立即缩短10分钟建筑/造兵时间；商城：立即缩短10分钟建筑/造兵时间

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:89](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:89>) · [data.js:224](</Users/chenjuan/Documents/游戏/frontend/js/data.js:224>) · [shop.js:48](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:48>)

```json
{
  "backend": {
    "key": "speedUp10m",
    "name": "10分加速符",
    "icon": "⚡",
    "cat": "CAT_UTIL",
    "desc": "立即缩短10分钟建筑/造兵时间",
    "speedUpSeconds": "10 * 60"
  },
  "frontend": {
    "name": "10分加速符",
    "icon": "⚡",
    "desc": "立即缩短10分钟建筑/造兵时间",
    "cat": "util",
    "seconds": 600
  },
  "shop": {
    "id": "speedUp10m",
    "cat": "util",
    "name": "10分加速符",
    "icon": "⚡",
    "desc": "立即缩短10分钟建筑/造兵时间",
    "price": 30,
    "stock": null,
    "tag": ""
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I051 · 1时加速符（speedUp1h）

后端：立即缩短1小时建筑/造兵时间；前端：立即缩短1小时建筑/造兵时间；商城：立即缩短1小时建筑/造兵时间

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:90](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:90>) · [data.js:225](</Users/chenjuan/Documents/游戏/frontend/js/data.js:225>) · [shop.js:49](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:49>)

```json
{
  "backend": {
    "key": "speedUp1h",
    "name": "1时加速符",
    "icon": "⚡",
    "cat": "CAT_UTIL",
    "desc": "立即缩短1小时建筑/造兵时间",
    "speedUpSeconds": "60 * 60"
  },
  "frontend": {
    "name": "1时加速符",
    "icon": "⚡",
    "desc": "立即缩短1小时建筑/造兵时间",
    "cat": "util",
    "seconds": 3600
  },
  "shop": {
    "id": "speedUp1h",
    "cat": "util",
    "name": "1时加速符",
    "icon": "⚡",
    "desc": "立即缩短1小时建筑/造兵时间",
    "price": 100,
    "stock": null,
    "tag": "热销"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I052 · 5时加速符（speedUp5h）

后端：立即缩短5小时建筑/造兵时间；前端：立即缩短5小时建筑/造兵时间；商城：立即缩短5小时建筑/造兵时间

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:91](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:91>) · [data.js:226](</Users/chenjuan/Documents/游戏/frontend/js/data.js:226>) · [shop.js:50](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:50>)

```json
{
  "backend": {
    "key": "speedUp5h",
    "name": "5时加速符",
    "icon": "⚡",
    "cat": "CAT_UTIL",
    "desc": "立即缩短5小时建筑/造兵时间",
    "speedUpSeconds": "5 * 60 * 60"
  },
  "frontend": {
    "name": "5时加速符",
    "icon": "⚡",
    "desc": "立即缩短5小时建筑/造兵时间",
    "cat": "util",
    "seconds": 18000
  },
  "shop": {
    "id": "speedUp5h",
    "cat": "util",
    "name": "5时加速符",
    "icon": "⚡",
    "desc": "立即缩短5小时建筑/造兵时间",
    "price": 400,
    "stock": null,
    "tag": ""
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I053 · 12时加速符（speedUp12h）

后端：立即缩短12小时建筑/造兵时间；前端：立即缩短12小时建筑/造兵时间；商城：立即缩短12小时建筑/造兵时间

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:92](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:92>) · [data.js:227](</Users/chenjuan/Documents/游戏/frontend/js/data.js:227>) · [shop.js:51](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:51>)

```json
{
  "backend": {
    "key": "speedUp12h",
    "name": "12时加速符",
    "icon": "⚡",
    "cat": "CAT_UTIL",
    "desc": "立即缩短12小时建筑/造兵时间",
    "speedUpSeconds": "12 * 60 * 60"
  },
  "frontend": {
    "name": "12时加速符",
    "icon": "⚡",
    "desc": "立即缩短12小时建筑/造兵时间",
    "cat": "util",
    "seconds": 43200
  },
  "shop": {
    "id": "speedUp12h",
    "cat": "util",
    "name": "12时加速符",
    "icon": "⚡",
    "desc": "立即缩短12小时建筑/造兵时间",
    "price": 800,
    "stock": null,
    "tag": ""
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I054 · 24时加速符（speedUp24h）

后端：立即缩短24小时建筑/造兵时间；前端：立即缩短24小时建筑/造兵时间；商城：立即缩短24小时建筑/造兵时间

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:93](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:93>) · [data.js:228](</Users/chenjuan/Documents/游戏/frontend/js/data.js:228>) · [shop.js:52](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:52>)

```json
{
  "backend": {
    "key": "speedUp24h",
    "name": "24时加速符",
    "icon": "⚡",
    "cat": "CAT_UTIL",
    "desc": "立即缩短24小时建筑/造兵时间",
    "speedUpSeconds": "24 * 60 * 60"
  },
  "frontend": {
    "name": "24时加速符",
    "icon": "⚡",
    "desc": "立即缩短24小时建筑/造兵时间",
    "cat": "util",
    "seconds": 86400
  },
  "shop": {
    "id": "speedUp24h",
    "cat": "util",
    "name": "24时加速符",
    "icon": "⚡",
    "desc": "立即缩短24小时建筑/造兵时间",
    "price": 1500,
    "stock": null,
    "tag": "推荐"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I055 · 36时加速符（speedUp36h）

后端：立即缩短36小时建筑/造兵时间；前端：立即缩短36小时建筑/造兵时间；商城：立即缩短36小时建筑/造兵时间

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:94](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:94>) · [data.js:229](</Users/chenjuan/Documents/游戏/frontend/js/data.js:229>) · [shop.js:53](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:53>)

```json
{
  "backend": {
    "key": "speedUp36h",
    "name": "36时加速符",
    "icon": "⚡",
    "cat": "CAT_UTIL",
    "desc": "立即缩短36小时建筑/造兵时间",
    "speedUpSeconds": "36 * 60 * 60"
  },
  "frontend": {
    "name": "36时加速符",
    "icon": "⚡",
    "desc": "立即缩短36小时建筑/造兵时间",
    "cat": "util",
    "seconds": 129600
  },
  "shop": {
    "id": "speedUp36h",
    "cat": "util",
    "name": "36时加速符",
    "icon": "⚡",
    "desc": "立即缩短36小时建筑/造兵时间",
    "price": 2000,
    "stock": null,
    "tag": ""
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I056 · 48时加速符（speedUp48h）

后端：立即缩短48小时建筑/造兵时间；前端：立即缩短48小时建筑/造兵时间；商城：立即缩短48小时建筑/造兵时间

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:95](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:95>) · [data.js:230](</Users/chenjuan/Documents/游戏/frontend/js/data.js:230>) · [shop.js:54](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:54>)

```json
{
  "backend": {
    "key": "speedUp48h",
    "name": "48时加速符",
    "icon": "⚡",
    "cat": "CAT_UTIL",
    "desc": "立即缩短48小时建筑/造兵时间",
    "speedUpSeconds": "48 * 60 * 60"
  },
  "frontend": {
    "name": "48时加速符",
    "icon": "⚡",
    "desc": "立即缩短48小时建筑/造兵时间",
    "cat": "util",
    "seconds": 172800
  },
  "shop": {
    "id": "speedUp48h",
    "cat": "util",
    "name": "48时加速符",
    "icon": "⚡",
    "desc": "立即缩短48小时建筑/造兵时间",
    "price": 2500,
    "stock": null,
    "tag": "超值"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I057 · 72时加速符（speedUp72h）

后端：立即缩短72小时建筑/造兵时间；前端：立即缩短72小时建筑/造兵时间；商城：立即缩短72小时建筑/造兵时间

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:96](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:96>) · [data.js:231](</Users/chenjuan/Documents/游戏/frontend/js/data.js:231>) · [shop.js:55](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:55>)

```json
{
  "backend": {
    "key": "speedUp72h",
    "name": "72时加速符",
    "icon": "⚡",
    "cat": "CAT_UTIL",
    "desc": "立即缩短72小时建筑/造兵时间",
    "speedUpSeconds": "72 * 60 * 60"
  },
  "frontend": {
    "name": "72时加速符",
    "icon": "⚡",
    "desc": "立即缩短72小时建筑/造兵时间",
    "cat": "util",
    "seconds": 259200
  },
  "shop": {
    "id": "speedUp72h",
    "cat": "util",
    "name": "72时加速符",
    "icon": "⚡",
    "desc": "立即缩短72小时建筑/造兵时间",
    "price": 3500,
    "stock": null,
    "tag": "限时"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I058 · 护盾（shield）

后端：使用后8小时免受玩家攻击；前端：使用后8小时免受玩家攻击；商城：使用后8小时免受玩家攻击

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:99](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:99>) · [data.js:232](</Users/chenjuan/Documents/游戏/frontend/js/data.js:232>) · [shop.js:56](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:56>)

```json
{
  "backend": {
    "key": "shield",
    "name": "护盾",
    "icon": "🛡️",
    "cat": "CAT_UTIL",
    "desc": "使用后8小时免受玩家攻击",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "护盾",
    "icon": "🛡️",
    "desc": "使用后8小时免受玩家攻击",
    "cat": "util"
  },
  "shop": {
    "id": "shield",
    "cat": "util",
    "name": "护盾",
    "icon": "🛡️",
    "desc": "使用后8小时免受玩家攻击",
    "price": 200,
    "stock": null,
    "tag": ""
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I059 · 行军令（marchOrd）

后端：行军速度+50%,持续1小时；前端：行军速度+50%,持续1小时；商城：行军速度+50%,持续1小时

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:100](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:100>) · [data.js:233](</Users/chenjuan/Documents/游戏/frontend/js/data.js:233>) · [shop.js:57](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:57>)

```json
{
  "backend": {
    "key": "marchOrd",
    "name": "行军令",
    "icon": "🚩",
    "cat": "CAT_UTIL",
    "desc": "行军速度+50%,持续1小时",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "行军令",
    "icon": "🚩",
    "desc": "行军速度+50%,持续1小时",
    "cat": "util"
  },
  "shop": {
    "id": "marchOrd",
    "cat": "util",
    "name": "行军令",
    "icon": "🚩",
    "desc": "行军速度+50%,持续1小时",
    "price": 100,
    "stock": null,
    "tag": ""
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I060 · 人口动员令（populationOrder）

后端：使用后立即增加500空闲人口,不超过人口上限；前端：使用后立即增加500空闲人口,不超过人口上限；商城：使用后立即增加500空闲人口,不超过人口上限

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

名称、说明与效果缺少独立来源记录；短通用词本身不能证明复制。

位置：[ItemDef.java:101](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ItemDef.java:101>) · [data.js:234](</Users/chenjuan/Documents/游戏/frontend/js/data.js:234>) · [shop.js:58](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:58>)

```json
{
  "backend": {
    "key": "populationOrder",
    "name": "人口动员令",
    "icon": "👥",
    "cat": "CAT_UTIL",
    "desc": "使用后立即增加500空闲人口,不超过人口上限",
    "speedUpSeconds": 0
  },
  "frontend": {
    "name": "人口动员令",
    "icon": "👥",
    "desc": "使用后立即增加500空闲人口,不超过人口上限",
    "cat": "util"
  },
  "shop": {
    "id": "populationOrder",
    "cat": "util",
    "name": "人口动员令",
    "icon": "👥",
    "desc": "使用后立即增加500空闲人口,不超过人口上限",
    "price": 100,
    "stock": null,
    "tag": "推荐"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I061 · 周年庆大礼（annivPack）

前端：周年庆礼包；商城：钻石×2000 + 道具×20 + 限定头衔

**状态：来源不明；评估：待评估；建议次序：优先评估文案。**

营销描述未附活动策划来源；“开7倍”“限定头衔”“周年庆”等表达及发放承诺应逐项确认。

位置：[data.js:235](</Users/chenjuan/Documents/游戏/frontend/js/data.js:235>) · [shop.js:64](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:64>)

```json
{
  "frontend": {
    "name": "周年庆大礼",
    "icon": "🎉",
    "desc": "周年庆礼包",
    "cat": "gift"
  },
  "shop": {
    "id": "annivPack",
    "cat": "gift",
    "name": "周年庆大礼",
    "icon": "🎉",
    "desc": "钻石×2000 + 道具×20 + 限定头衔",
    "price": 1999,
    "stock": 1,
    "tag": "限定"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I062 · 珍珠（gem_pearl）

前端：稀有天然珍珠，野地采集获得，用于晋升军衔；后端珠宝：稀有天然珍珠，野地采集获得，用于晋升军衔

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

珠宝用于军衔晋升的组合需重点核对；已查的2014年攻略材料及门槛与本项目不同，不能认定照搬该表。

位置：[data.js:238](</Users/chenjuan/Documents/游戏/frontend/js/data.js:238>) · [MilitaryRankDef.java:23](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:23>)

```json
{
  "frontend": {
    "name": "珍珠",
    "icon": "⚪",
    "desc": "稀有天然珍珠，野地采集获得，用于晋升军衔",
    "cat": "jewelry"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I063 · 珊瑚（gem_coral）

前端：红润天然珊瑚，野地采集获得，用于晋升军衔；后端珠宝：红润天然珊瑚，野地采集获得，用于晋升军衔

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

珠宝用于军衔晋升的组合需重点核对；已查的2014年攻略材料及门槛与本项目不同，不能认定照搬该表。

位置：[data.js:239](</Users/chenjuan/Documents/游戏/frontend/js/data.js:239>) · [MilitaryRankDef.java:24](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:24>)

```json
{
  "frontend": {
    "name": "珊瑚",
    "icon": "🪸",
    "desc": "红润天然珊瑚，野地采集获得，用于晋升军衔",
    "cat": "jewelry"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I064 · 琉璃（gem_glaze）

前端：晶莹剔透琉璃，野地采集获得，用于晋升军衔；后端珠宝：晶莹剔透琉璃，野地采集获得，用于晋升军衔

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

珠宝用于军衔晋升的组合需重点核对；已查的2014年攻略材料及门槛与本项目不同，不能认定照搬该表。

位置：[data.js:240](</Users/chenjuan/Documents/游戏/frontend/js/data.js:240>) · [MilitaryRankDef.java:25](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:25>)

```json
{
  "frontend": {
    "name": "琉璃",
    "icon": "🔮",
    "desc": "晶莹剔透琉璃，野地采集获得，用于晋升军衔",
    "cat": "jewelry"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I065 · 琥珀（gem_amber）

前端：温润千年琥珀，野地采集获得，用于晋升军衔；后端珠宝：温润千年琥珀，野地采集获得，用于晋升军衔

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

珠宝用于军衔晋升的组合需重点核对；已查的2014年攻略材料及门槛与本项目不同，不能认定照搬该表。

位置：[data.js:241](</Users/chenjuan/Documents/游戏/frontend/js/data.js:241>) · [MilitaryRankDef.java:26](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:26>)

```json
{
  "frontend": {
    "name": "琥珀",
    "icon": "🍯",
    "desc": "温润千年琥珀，野地采集获得，用于晋升军衔",
    "cat": "jewelry"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I066 · 玛瑙（gem_agate）

前端：珍贵斑斓玛瑙，野地采集获得，用于晋升军衔；后端珠宝：珍贵斑斓玛瑙，野地采集获得，用于晋升军衔

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

珠宝用于军衔晋升的组合需重点核对；已查的2014年攻略材料及门槛与本项目不同，不能认定照搬该表。

位置：[data.js:242](</Users/chenjuan/Documents/游戏/frontend/js/data.js:242>) · [MilitaryRankDef.java:27](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:27>)

```json
{
  "frontend": {
    "name": "玛瑙",
    "icon": "🟤",
    "desc": "珍贵斑斓玛瑙，野地采集获得，用于晋升军衔",
    "cat": "jewelry"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I067 · 水晶（gem_crystal）

前端：璀璨高纯水晶，野地采集获得，用于晋升军衔；后端珠宝：璀璨高纯水晶，野地采集获得，用于晋升军衔

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

珠宝用于军衔晋升的组合需重点核对；已查的2014年攻略材料及门槛与本项目不同，不能认定照搬该表。

位置：[data.js:243](</Users/chenjuan/Documents/游戏/frontend/js/data.js:243>) · [MilitaryRankDef.java:28](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:28>)

```json
{
  "frontend": {
    "name": "水晶",
    "icon": "💎",
    "desc": "璀璨高纯水晶，野地采集获得，用于晋升军衔",
    "cat": "jewelry"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I068 · 翡翠（gem_jadeite）

前端：翠绿极品翡翠，野地采集获得，用于晋升军衔；后端珠宝：翠绿极品翡翠，野地采集获得，用于晋升军衔

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

珠宝用于军衔晋升的组合需重点核对；已查的2014年攻略材料及门槛与本项目不同，不能认定照搬该表。

位置：[data.js:244](</Users/chenjuan/Documents/游戏/frontend/js/data.js:244>) · [MilitaryRankDef.java:29](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:29>)

```json
{
  "frontend": {
    "name": "翡翠",
    "icon": "🟢",
    "desc": "翠绿极品翡翠，野地采集获得，用于晋升军衔",
    "cat": "jewelry"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I069 · 玉石（gem_jade）

前端：温润无瑕美玉，野地采集获得，用于晋升军衔；后端珠宝：温润无瑕美玉，野地采集获得，用于晋升军衔

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

珠宝用于军衔晋升的组合需重点核对；已查的2014年攻略材料及门槛与本项目不同，不能认定照搬该表。

位置：[data.js:245](</Users/chenjuan/Documents/游戏/frontend/js/data.js:245>) · [MilitaryRankDef.java:30](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:30>)

```json
{
  "frontend": {
    "name": "玉石",
    "icon": "🪨",
    "desc": "温润无瑕美玉，野地采集获得，用于晋升军衔",
    "cat": "jewelry"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I070 · 夜明珠（gem_nightpearl）

前端：绝世璀璨夜明珠，高级野地采集获得，用于晋升将官军衔；后端珠宝：绝世璀璨夜明珠，高级野地采集获得，用于晋升将官军衔

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

珠宝用于军衔晋升的组合需重点核对；已查的2014年攻略材料及门槛与本项目不同，不能认定照搬该表。

位置：[data.js:246](</Users/chenjuan/Documents/游戏/frontend/js/data.js:246>) · [MilitaryRankDef.java:31](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:31>)

```json
{
  "frontend": {
    "name": "夜明珠",
    "icon": "🌟",
    "desc": "绝世璀璨夜明珠，高级野地采集获得，用于晋升将官军衔",
    "cat": "jewelry"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I071 · 新手礼包（newbiePack）

商城：开7倍:粮20000/钢20000/油10000/稀5000/金3000

**状态：来源不明；评估：待评估；建议次序：优先评估文案。**

营销描述未附活动策划来源；“开7倍”“限定头衔”“周年庆”等表达及发放承诺应逐项确认。

位置：[shop.js:61](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:61>)

```json
{
  "shop": {
    "id": "newbiePack",
    "cat": "gift",
    "name": "新手礼包",
    "icon": "🎁",
    "desc": "开7倍:粮20000/钢20000/油10000/稀5000/金3000",
    "price": 99,
    "stock": 1,
    "tag": "限时"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I072 · 钻石月卡（monthCard）

商城：立即得300钻,30天内每日登录送100钻

**状态：来源不明；评估：待评估；建议次序：优先评估文案。**

营销描述未附活动策划来源；“开7倍”“限定头衔”“周年庆”等表达及发放承诺应逐项确认。

位置：[shop.js:62](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:62>)

```json
{
  "shop": {
    "id": "monthCard",
    "cat": "gift",
    "name": "钻石月卡",
    "icon": "💳",
    "desc": "立即得300钻,30天内每日登录送100钻",
    "price": 1500,
    "stock": 1,
    "tag": "推荐"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### I073 · 战备月卡（warChest）

商城：立即得500钻+15个常用道具组合

**状态：来源不明；评估：待评估；建议次序：优先评估文案。**

营销描述未附活动策划来源；“开7倍”“限定头衔”“周年庆”等表达及发放承诺应逐项确认。

位置：[shop.js:63](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:63>)

```json
{
  "shop": {
    "id": "warChest",
    "cat": "gift",
    "name": "战备月卡",
    "icon": "🎖️",
    "desc": "立即得500钻+15个常用道具组合",
    "price": 888,
    "stock": 1,
    "tag": "超值"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

## 技能

### S001 · 猛攻（frenzy）

攻击力额外+10%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

前后端定义及技能倍率可定位；未发现技能组合、倍率的设计推导或外部出处。通用技能名不等于侵权。

位置：[data.js:192](</Users/chenjuan/Documents/游戏/frontend/js/data.js:192>) · [OfficerSkillDef.java:16](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerSkillDef.java:16>) · [BattleService.java:45](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BattleService.java:45>)

```json
{
  "name": "猛攻",
  "desc": "攻击力额外+10%/级",
  "max": 5,
  "cat": "atk"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### S002 · 铁壁（bulwark）

防御力额外+10%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

前后端定义及技能倍率可定位；未发现技能组合、倍率的设计推导或外部出处。通用技能名不等于侵权。

位置：[data.js:193](</Users/chenjuan/Documents/游戏/frontend/js/data.js:193>) · [OfficerSkillDef.java:17](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerSkillDef.java:17>) · [BattleService.java:45](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BattleService.java:45>)

```json
{
  "name": "铁壁",
  "desc": "防御力额外+10%/级",
  "max": 5,
  "cat": "def"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### S003 · 闪电战（blitz）

行军速度+15%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

前后端定义及技能倍率可定位；未发现技能组合、倍率的设计推导或外部出处。通用技能名不等于侵权。

位置：[data.js:194](</Users/chenjuan/Documents/游戏/frontend/js/data.js:194>) · [OfficerSkillDef.java:18](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerSkillDef.java:18>) · [BattleService.java:45](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BattleService.java:45>)

```json
{
  "name": "闪电战",
  "desc": "行军速度+15%/级",
  "max": 5,
  "cat": "spd"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### S004 · 压制（suppress）

降低敌方攻击力8%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

前后端定义及技能倍率可定位；未发现技能组合、倍率的设计推导或外部出处。通用技能名不等于侵权。

位置：[data.js:195](</Users/chenjuan/Documents/游戏/frontend/js/data.js:195>) · [OfficerSkillDef.java:19](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerSkillDef.java:19>) · [BattleService.java:45](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BattleService.java:45>)

```json
{
  "name": "压制",
  "desc": "降低敌方攻击力8%/级",
  "max": 5,
  "cat": "debuff"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### S005 · 破甲（pierce）

无视敌方防御12%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

前后端定义及技能倍率可定位；未发现技能组合、倍率的设计推导或外部出处。通用技能名不等于侵权。

位置：[data.js:196](</Users/chenjuan/Documents/游戏/frontend/js/data.js:196>) · [OfficerSkillDef.java:20](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerSkillDef.java:20>) · [BattleService.java:45](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BattleService.java:45>)

```json
{
  "name": "破甲",
  "desc": "无视敌方防御12%/级",
  "max": 5,
  "cat": "pierce"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### S006 · 补给（supply）

粮食消耗-20%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

前后端定义及技能倍率可定位；未发现技能组合、倍率的设计推导或外部出处。通用技能名不等于侵权。

位置：[data.js:197](</Users/chenjuan/Documents/游戏/frontend/js/data.js:197>) · [OfficerSkillDef.java:21](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerSkillDef.java:21>) · [BattleService.java:45](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BattleService.java:45>)

```json
{
  "name": "补给",
  "desc": "粮食消耗-20%/级",
  "max": 5,
  "cat": "logi"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### S007 · 急救（medic）

战后伤兵额外回收+3%/级，最高15%

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

前后端定义及技能倍率可定位；未发现技能组合、倍率的设计推导或外部出处。通用技能名不等于侵权。

位置：[data.js:198](</Users/chenjuan/Documents/游戏/frontend/js/data.js:198>) · [OfficerSkillDef.java:22](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerSkillDef.java:22>) · [BattleService.java:45](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BattleService.java:45>)

```json
{
  "name": "急救",
  "desc": "战后伤兵额外回收+3%/级，最高15%",
  "max": 5,
  "cat": "medic"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### S008 · 连击（combo）

8%/级概率额外攻击一次

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

前后端定义及技能倍率可定位；未发现技能组合、倍率的设计推导或外部出处。通用技能名不等于侵权。

位置：[data.js:199](</Users/chenjuan/Documents/游戏/frontend/js/data.js:199>) · [OfficerSkillDef.java:23](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerSkillDef.java:23>) · [BattleService.java:45](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BattleService.java:45>)

```json
{
  "name": "连击",
  "desc": "8%/级概率额外攻击一次",
  "max": 5,
  "cat": "combo"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

## 数值

### N001 · 粮食（resources.food）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[ResourceDef.java:16](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ResourceDef.java:16>) · [data.js:17](</Users/chenjuan/Documents/游戏/frontend/js/data.js:17>)

```json
{
  "后端字段": {
    "key": "food",
    "name": "粮食",
    "icon": "粮",
    "baseCap": 2000,
    "capGrowth": 1.0
  },
  "后端原表达式": "new ResourceDef(\"food\", \"粮食\", \"粮\", 2000, 1.0)",
  "前端字段": {
    "name": "粮食",
    "icon": "img/res-food.svg",
    "baseCap": 2000,
    "capGrowth": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N002 · 钢铁（resources.steel）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[ResourceDef.java:17](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ResourceDef.java:17>) · [data.js:18](</Users/chenjuan/Documents/游戏/frontend/js/data.js:18>)

```json
{
  "后端字段": {
    "key": "steel",
    "name": "钢铁",
    "icon": "钢",
    "baseCap": 2000,
    "capGrowth": 1.0
  },
  "后端原表达式": "new ResourceDef(\"steel\", \"钢铁\", \"钢\", 2000, 1.0)",
  "前端字段": {
    "name": "钢铁",
    "icon": "img/res-steel.svg",
    "baseCap": 2000,
    "capGrowth": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N003 · 石油（resources.oil）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[ResourceDef.java:18](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ResourceDef.java:18>) · [data.js:19](</Users/chenjuan/Documents/游戏/frontend/js/data.js:19>)

```json
{
  "后端字段": {
    "key": "oil",
    "name": "石油",
    "icon": "油",
    "baseCap": 1500,
    "capGrowth": 0.9
  },
  "后端原表达式": "new ResourceDef(\"oil\", \"石油\", \"油\", 1500, 0.9)",
  "前端字段": {
    "name": "石油",
    "icon": "img/res-oil.svg",
    "baseCap": 1500,
    "capGrowth": 0.9
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N004 · 稀矿（resources.rare）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[ResourceDef.java:19](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ResourceDef.java:19>) · [data.js:20](</Users/chenjuan/Documents/游戏/frontend/js/data.js:20>)

```json
{
  "后端字段": {
    "key": "rare",
    "name": "稀矿",
    "icon": "稀",
    "baseCap": 800,
    "capGrowth": 0.7
  },
  "后端原表达式": "new ResourceDef(\"rare\", \"稀矿\", \"稀\", 800, 0.7)",
  "前端字段": {
    "name": "稀矿",
    "icon": "img/res-rare.svg",
    "baseCap": 800,
    "capGrowth": 0.7
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N005 · 黄金（resources.gold）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[ResourceDef.java:20](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/ResourceDef.java:20>) · [data.js:21](</Users/chenjuan/Documents/游戏/frontend/js/data.js:21>)

```json
{
  "后端字段": {
    "key": "gold",
    "name": "黄金",
    "icon": "金",
    "baseCap": 0,
    "capGrowth": 0
  },
  "后端原表达式": "new ResourceDef(\"gold\", \"黄金\", \"金\", 0, 0)",
  "前端字段": {
    "name": "黄金",
    "icon": "img/gold.svg",
    "baseCap": 0,
    "capGrowth": 0
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N006 · 市政厅（buildings.command）

主城,决定其他建筑等级上限

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:36](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:36>) · [data.js:36](</Users/chenjuan/Documents/游戏/frontend/js/data.js:36>)

```json
{
  "后端字段": {
    "key": "command",
    "name": "市政厅",
    "desc": "主城,决定其他建筑等级上限",
    "baseCost": "Map.of(\"steel\", 400, \"food\", 200)",
    "growth": 1.6,
    "cat": "core",
    "slots": 1
  },
  "后端原表达式": "new BuildingDef(\"command\", \"市政厅\", \"主城,决定其他建筑等级上限\", Map.of(\"steel\", 400, \"food\", 200), 1.6, \"core\", 1)",
  "前端字段": {
    "name": "市政厅",
    "desc": "主城,决定其他建筑等级上限",
    "baseCost": {
      "steel": 400,
      "food": 200
    },
    "growth": 1.6,
    "cat": "core",
    "slots": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N007 · 民居（buildings.house）

提供人口上限,每级+1200人口

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:38](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:38>) · [data.js:37](</Users/chenjuan/Documents/游戏/frontend/js/data.js:37>)

```json
{
  "后端字段": {
    "key": "house",
    "name": "民居",
    "desc": "提供人口上限,每级+1200人口",
    "baseCost": "Map.of(\"steel\", 120, \"food\", 60)",
    "growth": 1.5,
    "cat": "core",
    "slots": "GameConstants.GROUP_SLOTS_ARMY_MAX",
    "popPer": 1200,
    "produces": null,
    "baseProduce": null,
    "capPer": null,
    "protectPer": null,
    "defBonus": null,
    "airCap": null,
    "resBonus": null
  },
  "后端原表达式": "new BuildingDef(\"house\", \"民居\", \"提供人口上限,每级+1200人口\", Map.of(\"steel\", 120, \"food\", 60), 1.5, \"core\", GameConstants.GROUP_SLOTS_ARMY_MAX, 1200, null, null, null, null, null, null, null)",
  "前端字段": {
    "name": "民居",
    "desc": "提供人口上限,每级+1200人口",
    "baseCost": {
      "steel": 120,
      "food": 60
    },
    "growth": 1.5,
    "cat": "core",
    "popPer": 1200,
    "slots": 32
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N008 · 军工厂（buildings.factory）

生产步兵/卡车/装甲车与战机

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:41](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:41>) · [data.js:38](</Users/chenjuan/Documents/游戏/frontend/js/data.js:38>)

```json
{
  "后端字段": {
    "key": "factory",
    "name": "军工厂",
    "desc": "生产步兵/卡车/装甲车与战机",
    "baseCost": "Map.of(\"steel\", 240, \"oil\", 100)",
    "growth": 1.6,
    "cat": "army",
    "slots": "GameConstants.GROUP_SLOTS_ARMY_MAX"
  },
  "后端原表达式": "new BuildingDef(\"factory\", \"军工厂\", \"生产步兵/卡车/装甲车与战机\", Map.of(\"steel\", 240, \"oil\", 100), 1.6, \"army\", GameConstants.GROUP_SLOTS_ARMY_MAX)",
  "前端字段": {
    "name": "军工厂",
    "desc": "生产步兵、装甲车辆与战机",
    "baseCost": {
      "steel": 240,
      "oil": 100
    },
    "growth": 1.6,
    "cat": "army",
    "slots": 32
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N009 · 轻工厂（buildings.lightfactory）

生产轻型坦克

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:43](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:43>) · [data.js:39](</Users/chenjuan/Documents/游戏/frontend/js/data.js:39>)

```json
{
  "后端字段": {
    "key": "lightfactory",
    "name": "轻工厂",
    "desc": "生产轻型坦克",
    "baseCost": "Map.of(\"steel\", 260, \"oil\", 110, \"rare\", 10)",
    "growth": 1.6,
    "cat": "army",
    "slots": 1
  },
  "后端原表达式": "new BuildingDef(\"lightfactory\", \"轻工厂\", \"生产轻型坦克\", Map.of(\"steel\", 260, \"oil\", 110, \"rare\", 10), 1.6, \"army\", 1)",
  "前端字段": {
    "name": "轻工厂",
    "desc": "生产轻型坦克",
    "baseCost": {
      "steel": 260,
      "oil": 110,
      "rare": 10
    },
    "growth": 1.6,
    "cat": "army",
    "slots": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N010 · 重工厂（buildings.heavyfactory）

生产重型坦克/突击炮/火箭

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:45](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:45>) · [data.js:40](</Users/chenjuan/Documents/游戏/frontend/js/data.js:40>)

```json
{
  "后端字段": {
    "key": "heavyfactory",
    "name": "重工厂",
    "desc": "生产重型坦克/突击炮/火箭",
    "baseCost": "Map.of(\"steel\", 320, \"oil\", 140, \"rare\", 30)",
    "growth": 1.6,
    "cat": "army",
    "slots": 1
  },
  "后端原表达式": "new BuildingDef(\"heavyfactory\", \"重工厂\", \"生产重型坦克/突击炮/火箭\", Map.of(\"steel\", 320, \"oil\", 140, \"rare\", 30), 1.6, \"army\", 1)",
  "前端字段": {
    "name": "重工厂",
    "desc": "生产重型坦克/突击炮/火箭",
    "baseCost": {
      "steel": 320,
      "oil": 140,
      "rare": 30
    },
    "growth": 1.6,
    "cat": "army",
    "slots": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N011 · 机场（buildings.airport）

生产空军

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:47](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:47>) · [data.js:41](</Users/chenjuan/Documents/游戏/frontend/js/data.js:41>)

```json
{
  "后端字段": {
    "key": "airport",
    "name": "机场",
    "desc": "生产空军",
    "baseCost": "Map.of(\"steel\", 280, \"oil\", 120, \"rare\", 30)",
    "growth": 1.6,
    "cat": "army",
    "slots": 1
  },
  "后端原表达式": "new BuildingDef(\"airport\", \"机场\", \"生产空军\", Map.of(\"steel\", 280, \"oil\", 120, \"rare\", 30), 1.6, \"army\", 1)",
  "前端字段": {
    "name": "机场",
    "desc": "生产空军",
    "baseCost": {
      "steel": 280,
      "oil": 120,
      "rare": 30
    },
    "growth": 1.6,
    "cat": "army",
    "slots": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N012 · 港口（buildings.port）

生产海军

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:49](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:49>) · [data.js:41](</Users/chenjuan/Documents/游戏/frontend/js/data.js:41>)

```json
{
  "后端字段": {
    "key": "port",
    "name": "港口",
    "desc": "生产海军",
    "baseCost": "Map.of(\"steel\", 360, \"oil\", 160, \"rare\", 50)",
    "growth": 1.7,
    "cat": "army",
    "slots": 1
  },
  "后端原表达式": "new BuildingDef(\"port\", \"港口\", \"生产海军\", Map.of(\"steel\", 360, \"oil\", 160, \"rare\", 50), 1.7, \"army\", 1)",
  "前端字段": {
    "name": "港口",
    "desc": "生产海军",
    "baseCost": {
      "steel": 360,
      "oil": 160,
      "rare": 50
    },
    "growth": 1.7,
    "cat": "army",
    "slots": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N013 · 军校（buildings.academy）

招募军官

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:51](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:51>) · [data.js:43](</Users/chenjuan/Documents/游戏/frontend/js/data.js:43>)

```json
{
  "后端字段": {
    "key": "academy",
    "name": "军校",
    "desc": "招募军官",
    "baseCost": "Map.of(\"steel\", 200, \"food\", 120, \"gold\", 200)",
    "growth": 1.6,
    "cat": "core",
    "slots": 1
  },
  "后端原表达式": "new BuildingDef(\"academy\", \"军校\", \"招募军官\", Map.of(\"steel\", 200, \"food\", 120, \"gold\", 200), 1.6, \"core\", 1)",
  "前端字段": {
    "name": "军校",
    "desc": "招募军官",
    "baseCost": {
      "steel": 200,
      "food": 120,
      "gold": 200
    },
    "growth": 1.6,
    "cat": "core",
    "slots": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N014 · 参谋部（buildings.staff）

军官槽位与野地上限,带兵上限 +10%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:53](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:53>) · [data.js:44](</Users/chenjuan/Documents/游戏/frontend/js/data.js:44>)

```json
{
  "后端字段": {
    "key": "staff",
    "name": "参谋部",
    "desc": "军官槽位与野地上限,带兵上限 +10%/级",
    "baseCost": "Map.of(\"steel\", 220, \"food\", 100)",
    "growth": 1.6,
    "cat": "core",
    "slots": 1
  },
  "后端原表达式": "new BuildingDef(\"staff\", \"参谋部\", \"军官槽位与野地上限,带兵上限 +10%/级\", Map.of(\"steel\", 220, \"food\", 100), 1.6, \"core\", 1)",
  "前端字段": {
    "name": "参谋部",
    "desc": "军官槽位与野地上限,带兵上限 +10%/级",
    "baseCost": {
      "steel": 220,
      "food": 100
    },
    "growth": 1.6,
    "cat": "core",
    "slots": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N015 · 农田（buildings.farm）

每小时产出粮食

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:55](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:55>) · [data.js:45](</Users/chenjuan/Documents/游戏/frontend/js/data.js:45>)

```json
{
  "后端字段": {
    "key": "farm",
    "name": "农田",
    "desc": "每小时产出粮食",
    "baseCost": "Map.of(\"steel\", 80)",
    "growth": 1.5,
    "cat": "res",
    "slots": "GameConstants.GROUP_SLOTS_RES_MAX",
    "popPer": null,
    "produces": "food",
    "baseProduce": 40,
    "capPer": null,
    "protectPer": null,
    "defBonus": null,
    "airCap": null,
    "resBonus": null
  },
  "后端原表达式": "new BuildingDef(\"farm\", \"农田\", \"每小时产出粮食\", Map.of(\"steel\", 80), 1.5, \"res\", GameConstants.GROUP_SLOTS_RES_MAX, null, \"food\", 40, null, null, null, null, null)",
  "前端字段": {
    "name": "农田",
    "desc": "每小时产出粮食",
    "baseCost": {
      "steel": 80
    },
    "growth": 1.5,
    "cat": "res",
    "produces": "food",
    "baseProduce": 40,
    "slots": 32
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N016 · 炼钢厂（buildings.refinery）

每小时产出钢铁

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:58](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:58>) · [data.js:46](</Users/chenjuan/Documents/游戏/frontend/js/data.js:46>)

```json
{
  "后端字段": {
    "key": "refinery",
    "name": "炼钢厂",
    "desc": "每小时产出钢铁",
    "baseCost": "Map.of(\"steel\", 80)",
    "growth": 1.5,
    "cat": "res",
    "slots": "GameConstants.GROUP_SLOTS_RES_MAX",
    "popPer": null,
    "produces": "steel",
    "baseProduce": 40,
    "capPer": null,
    "protectPer": null,
    "defBonus": null,
    "airCap": null,
    "resBonus": null
  },
  "后端原表达式": "new BuildingDef(\"refinery\", \"炼钢厂\", \"每小时产出钢铁\", Map.of(\"steel\", 80), 1.5, \"res\", GameConstants.GROUP_SLOTS_RES_MAX, null, \"steel\", 40, null, null, null, null, null)",
  "前端字段": {
    "name": "炼钢厂",
    "desc": "每小时产出钢铁",
    "baseCost": {
      "steel": 80
    },
    "growth": 1.5,
    "cat": "res",
    "produces": "steel",
    "baseProduce": 40,
    "slots": 32
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N017 · 石油基地（buildings.oilfield）

每小时产出石油

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:61>) · [data.js:47](</Users/chenjuan/Documents/游戏/frontend/js/data.js:47>)

```json
{
  "后端字段": {
    "key": "oilfield",
    "name": "石油基地",
    "desc": "每小时产出石油",
    "baseCost": "Map.of(\"steel\", 80)",
    "growth": 1.5,
    "cat": "res",
    "slots": "GameConstants.GROUP_SLOTS_RES_MAX",
    "popPer": null,
    "produces": "oil",
    "baseProduce": 25,
    "capPer": null,
    "protectPer": null,
    "defBonus": null,
    "airCap": null,
    "resBonus": null
  },
  "后端原表达式": "new BuildingDef(\"oilfield\", \"石油基地\", \"每小时产出石油\", Map.of(\"steel\", 80), 1.5, \"res\", GameConstants.GROUP_SLOTS_RES_MAX, null, \"oil\", 25, null, null, null, null, null)",
  "前端字段": {
    "name": "石油基地",
    "desc": "每小时产出石油",
    "baseCost": {
      "steel": 80
    },
    "growth": 1.5,
    "cat": "res",
    "produces": "oil",
    "baseProduce": 25,
    "slots": 32
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N018 · 稀矿厂（buildings.raremine）

每小时产出稀矿

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:64](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:64>) · [data.js:48](</Users/chenjuan/Documents/游戏/frontend/js/data.js:48>)

```json
{
  "后端字段": {
    "key": "raremine",
    "name": "稀矿厂",
    "desc": "每小时产出稀矿",
    "baseCost": "Map.of(\"steel\", 120, \"oil\", 40)",
    "growth": 1.6,
    "cat": "res",
    "slots": "GameConstants.GROUP_SLOTS_RES_MAX",
    "popPer": null,
    "produces": "rare",
    "baseProduce": 12,
    "capPer": null,
    "protectPer": null,
    "defBonus": null,
    "airCap": null,
    "resBonus": null
  },
  "后端原表达式": "new BuildingDef(\"raremine\", \"稀矿厂\", \"每小时产出稀矿\", Map.of(\"steel\", 120, \"oil\", 40), 1.6, \"res\", GameConstants.GROUP_SLOTS_RES_MAX, null, \"rare\", 12, null, null, null, null, null)",
  "前端字段": {
    "name": "稀矿厂",
    "desc": "每小时产出稀矿",
    "baseCost": {
      "steel": 120,
      "oil": 40
    },
    "growth": 1.6,
    "cat": "res",
    "produces": "rare",
    "baseProduce": 12,
    "slots": 32
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N019 · 仓库（buildings.depot）

提升资源上限,被掠夺时保护资源

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:67](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:67>) · [data.js:49](</Users/chenjuan/Documents/游戏/frontend/js/data.js:49>)

```json
{
  "后端字段": {
    "key": "depot",
    "name": "仓库",
    "desc": "提升资源上限,被掠夺时保护资源",
    "baseCost": "Map.of(\"steel\", 100)",
    "growth": 1.5,
    "cat": "res",
    "slots": "GameConstants.GROUP_SLOTS_ARMY_MAX",
    "popPer": null,
    "produces": null,
    "baseProduce": null,
    "capPer": 1500,
    "protectPer": 1000,
    "defBonus": null,
    "airCap": null,
    "resBonus": null
  },
  "后端原表达式": "new BuildingDef(\"depot\", \"仓库\", \"提升资源上限,被掠夺时保护资源\", Map.of(\"steel\", 100), 1.5, \"res\", GameConstants.GROUP_SLOTS_ARMY_MAX, null, null, null, 1500, 1000, null, null, null)",
  "前端字段": {
    "name": "仓库",
    "desc": "提升资源上限,被掠夺时保护资源",
    "baseCost": {
      "steel": 100
    },
    "growth": 1.5,
    "cat": "res",
    "capPer": 1500,
    "protectPer": 1000,
    "slots": 32
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N020 · 科研中心（buildings.lab）

解锁与加速科技研究

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:70](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:70>) · [data.js:50](</Users/chenjuan/Documents/游戏/frontend/js/data.js:50>)

```json
{
  "后端字段": {
    "key": "lab",
    "name": "科研中心",
    "desc": "解锁与加速科技研究",
    "baseCost": "Map.of(\"steel\", 200, \"food\", 100, \"rare\", 20)",
    "growth": 1.6,
    "cat": "core",
    "slots": 1
  },
  "后端原表达式": "new BuildingDef(\"lab\", \"科研中心\", \"解锁与加速科技研究\", Map.of(\"steel\", 200, \"food\", 100, \"rare\", 20), 1.6, \"core\", 1)",
  "前端字段": {
    "name": "科研中心",
    "desc": "解锁与加速科技研究",
    "baseCost": {
      "steel": 200,
      "food": 100,
      "rare": 20
    },
    "growth": 1.6,
    "cat": "core",
    "slots": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N021 · 雷达站（buildings.radar）

侦察野地与敌方兵力

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:72](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:72>) · [data.js:51](</Users/chenjuan/Documents/游戏/frontend/js/data.js:51>)

```json
{
  "后端字段": {
    "key": "radar",
    "name": "雷达站",
    "desc": "侦察野地与敌方兵力",
    "baseCost": "Map.of(\"steel\", 180, \"oil\", 60, \"rare\", 20)",
    "growth": 1.6,
    "cat": "core",
    "slots": 1
  },
  "后端原表达式": "new BuildingDef(\"radar\", \"雷达站\", \"侦察野地与敌方兵力\", Map.of(\"steel\", 180, \"oil\", 60, \"rare\", 20), 1.6, \"core\", 1)",
  "前端字段": {
    "name": "雷达站",
    "desc": "侦察野地与敌方兵力",
    "baseCost": {
      "steel": 180,
      "oil": 60,
      "rare": 20
    },
    "growth": 1.6,
    "cat": "core",
    "slots": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N022 · 围墙（buildings.wall）

城防,提升守城部队防御

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:74](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:74>) · [data.js:52](</Users/chenjuan/Documents/游戏/frontend/js/data.js:52>)

```json
{
  "后端字段": {
    "key": "wall",
    "name": "围墙",
    "desc": "城防,提升守城部队防御",
    "baseCost": "Map.of(\"steel\", 200, \"food\", 80)",
    "growth": 1.5,
    "cat": "def",
    "slots": 1,
    "popPer": null,
    "produces": null,
    "baseProduce": null,
    "capPer": null,
    "protectPer": null,
    "defBonus": 5,
    "airCap": null,
    "resBonus": null
  },
  "后端原表达式": "new BuildingDef(\"wall\", \"围墙\", \"城防,提升守城部队防御\", Map.of(\"steel\", 200, \"food\", 80), 1.5, \"def\", 1, null, null, null, null, null, 5, null, null)",
  "前端字段": {
    "name": "围墙",
    "desc": "城防,提升守城部队防御",
    "baseCost": {
      "steel": 200,
      "food": 80
    },
    "growth": 1.5,
    "cat": "def",
    "defBonus": 5,
    "slots": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N023 · 停机坪（buildings.apron）

空军调度,提升空军出击上限

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:77](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:77>) · [data.js:53](</Users/chenjuan/Documents/游戏/frontend/js/data.js:53>)

```json
{
  "后端字段": {
    "key": "apron",
    "name": "停机坪",
    "desc": "空军调度,提升空军出击上限",
    "baseCost": "Map.of(\"steel\", 220, \"oil\", 80, \"rare\", 20)",
    "growth": 1.6,
    "cat": "def",
    "slots": 1,
    "popPer": null,
    "produces": null,
    "baseProduce": null,
    "capPer": null,
    "protectPer": null,
    "defBonus": null,
    "airCap": 20,
    "resBonus": null
  },
  "后端原表达式": "new BuildingDef(\"apron\", \"停机坪\", \"空军调度,提升空军出击上限\", Map.of(\"steel\", 220, \"oil\", 80, \"rare\", 20), 1.6, \"def\", 1, null, null, null, null, null, null, 20, null)",
  "前端字段": {
    "name": "停机坪",
    "desc": "空军调度,提升空军出击上限",
    "baseCost": {
      "steel": 220,
      "oil": 80,
      "rare": 20
    },
    "growth": 1.6,
    "cat": "def",
    "airCap": 20,
    "slots": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N024 · 运输站（buildings.transit）

资源调度,全资源产出 +3%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:80](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:80>) · [data.js:54](</Users/chenjuan/Documents/游戏/frontend/js/data.js:54>)

```json
{
  "后端字段": {
    "key": "transit",
    "name": "运输站",
    "desc": "资源调度,全资源产出 +3%/级",
    "baseCost": "Map.of(\"steel\", 160, \"food\", 80)",
    "growth": 1.6,
    "cat": "res",
    "slots": 1,
    "popPer": null,
    "produces": null,
    "baseProduce": null,
    "capPer": null,
    "protectPer": null,
    "defBonus": null,
    "airCap": null,
    "resBonus": 3
  },
  "后端原表达式": "new BuildingDef(\"transit\", \"运输站\", \"资源调度,全资源产出 +3%/级\", Map.of(\"steel\", 160, \"food\", 80), 1.6, \"res\", 1, null, null, null, null, null, null, null, 3)",
  "前端字段": {
    "name": "运输站",
    "desc": "资源调度,全资源产出 +3%/级",
    "baseCost": {
      "steel": 160,
      "food": 80
    },
    "growth": 1.6,
    "cat": "res",
    "resBonus": 3,
    "slots": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N025 · 联络中心（buildings.liaison）

外交,军官刷新更优质

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:83](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:83>) · [data.js:55](</Users/chenjuan/Documents/游戏/frontend/js/data.js:55>)

```json
{
  "后端字段": {
    "key": "liaison",
    "name": "联络中心",
    "desc": "外交,军官刷新更优质",
    "baseCost": "Map.of(\"steel\", 200, \"food\", 120, \"gold\", 200)",
    "growth": 1.6,
    "cat": "core",
    "slots": 1
  },
  "后端原表达式": "new BuildingDef(\"liaison\", \"联络中心\", \"外交,军官刷新更优质\", Map.of(\"steel\", 200, \"food\", 120, \"gold\", 200), 1.6, \"core\", 1)",
  "前端字段": {
    "name": "联络中心",
    "desc": "外交,军官刷新更优质",
    "baseCost": {
      "steel": 200,
      "food": 120,
      "gold": 200
    },
    "growth": 1.6,
    "cat": "core",
    "slots": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N026 · 交易所（buildings.exchange）

资源互换,按比例转换资源

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[BuildingDef.java:85](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/BuildingDef.java:85>) · [data.js:56](</Users/chenjuan/Documents/游戏/frontend/js/data.js:56>)

```json
{
  "后端字段": {
    "key": "exchange",
    "name": "交易所",
    "desc": "资源互换,按比例转换资源",
    "baseCost": "Map.of(\"steel\", 180, \"food\", 100, \"gold\", 100)",
    "growth": 1.5,
    "cat": "res",
    "slots": 1
  },
  "后端原表达式": "new BuildingDef(\"exchange\", \"交易所\", \"资源互换,按比例转换资源\", Map.of(\"steel\", 180, \"food\", 100, \"gold\", 100), 1.5, \"res\", 1)",
  "前端字段": {
    "name": "交易所",
    "desc": "资源互换,按比例转换资源",
    "baseCost": {
      "steel": 180,
      "food": 100,
      "gold": 100
    },
    "growth": 1.5,
    "cat": "res",
    "slots": 1
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N027 · 步兵（units.infantry）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:45](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:45>) · [data.js:67](</Users/chenjuan/Documents/游戏/frontend/js/data.js:67>)

```json
{
  "后端字段": {
    "key": "infantry",
    "name": "步兵",
    "cat": "inf",
    "atk": 6,
    "def": 4,
    "hp": 30,
    "spd": 3,
    "range": 100,
    "food": 1,
    "pop": 1,
    "build": "factory",
    "cost": "Map.of(\"steel\", 20, \"oil\", 0, \"rare\", 0)",
    "strongVs": null,
    "branch": "land"
  },
  "后端原表达式": "new UnitDef(\"infantry\", \"步兵\", \"inf\", 6, 4, 30, 3, 100, 1, 1, \"factory\", Map.of(\"steel\", 20, \"oil\", 0, \"rare\", 0), null, \"land\")",
  "前端字段": {
    "name": "步兵",
    "cat": "inf",
    "atk": 6,
    "def": 4,
    "hp": 30,
    "spd": 3,
    "range": 100,
    "food": 1,
    "pop": 1,
    "build": "factory",
    "cost": {
      "steel": 20,
      "oil": 0,
      "rare": 0
    },
    "strongVs": null,
    "branch": "land"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N028 · 摩托兵（units.motor）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:48](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:48>) · [data.js:68](</Users/chenjuan/Documents/游戏/frontend/js/data.js:68>)

```json
{
  "后端字段": {
    "key": "motor",
    "name": "摩托兵",
    "cat": "inf",
    "atk": 10,
    "def": 4,
    "hp": 30,
    "spd": 7,
    "range": 100,
    "food": 2,
    "pop": 1,
    "build": "factory",
    "cost": "Map.of(\"steel\", 40, \"oil\", 10, \"rare\", 0)",
    "strongVs": "infantry",
    "branch": "land"
  },
  "后端原表达式": "new UnitDef(\"motor\", \"摩托兵\", \"inf\", 10, 4, 30, 7, 100, 2, 1, \"factory\", Map.of(\"steel\", 40, \"oil\", 10, \"rare\", 0), \"infantry\", \"land\")",
  "前端字段": {
    "name": "摩托兵",
    "cat": "inf",
    "atk": 10,
    "def": 4,
    "hp": 30,
    "spd": 7,
    "range": 100,
    "food": 2,
    "pop": 1,
    "build": "factory",
    "cost": {
      "steel": 40,
      "oil": 10,
      "rare": 0
    },
    "strongVs": "infantry",
    "branch": "land"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N029 · 卡车（units.truck）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:51](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:51>) · [data.js:69](</Users/chenjuan/Documents/游戏/frontend/js/data.js:69>)

```json
{
  "后端字段": {
    "key": "truck",
    "name": "卡车",
    "cat": "inf",
    "atk": 2,
    "def": 6,
    "hp": 50,
    "spd": 8,
    "range": 0,
    "food": 2,
    "pop": 1,
    "build": "factory",
    "cost": "Map.of(\"steel\", 60, \"oil\", 20, \"rare\", 0)",
    "strongVs": null,
    "branch": "land",
    "logistic": true,
    "load": 50,
    "autoAdvance": false
  },
  "后端原表达式": "new UnitDef(\"truck\", \"卡车\", \"inf\", 2, 6, 50, 8, 0, 2, 1, \"factory\", Map.of(\"steel\", 60, \"oil\", 20, \"rare\", 0), null, \"land\", true, 50, false)",
  "前端字段": {
    "name": "卡车",
    "cat": "inf",
    "atk": 2,
    "def": 6,
    "hp": 50,
    "spd": 8,
    "range": 0,
    "food": 2,
    "pop": 1,
    "build": "factory",
    "cost": {
      "steel": 60,
      "oil": 20,
      "rare": 0
    },
    "strongVs": null,
    "branch": "land",
    "logistic": true,
    "load": 50,
    "autoAdvance": false
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N030 · 装甲车（units.armored）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:55](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:55>) · [data.js:70](</Users/chenjuan/Documents/游戏/frontend/js/data.js:70>)

```json
{
  "后端字段": {
    "key": "armored",
    "name": "装甲车",
    "cat": "arm",
    "atk": 18,
    "def": 12,
    "hp": 80,
    "spd": 7,
    "range": 120,
    "food": 4,
    "pop": 2,
    "build": "factory",
    "cost": "Map.of(\"steel\", 120, \"oil\", 40, \"rare\", 10)",
    "strongVs": "fighter",
    "branch": "land"
  },
  "后端原表达式": "new UnitDef(\"armored\", \"装甲车\", \"arm\", 18, 12, 80, 7, 120, 4, 2, \"factory\", Map.of(\"steel\", 120, \"oil\", 40, \"rare\", 10), \"fighter\", \"land\")",
  "前端字段": {
    "name": "装甲车",
    "cat": "arm",
    "atk": 18,
    "def": 12,
    "hp": 80,
    "spd": 7,
    "range": 120,
    "food": 4,
    "pop": 2,
    "build": "factory",
    "cost": {
      "steel": 120,
      "oil": 40,
      "rare": 10
    },
    "strongVs": "fighter",
    "branch": "land"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N031 · 轻型坦克（units.ltank）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:58](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:58>) · [data.js:71](</Users/chenjuan/Documents/游戏/frontend/js/data.js:71>)

```json
{
  "后端字段": {
    "key": "ltank",
    "name": "轻型坦克",
    "cat": "arm",
    "atk": 28,
    "def": 22,
    "hp": 120,
    "spd": 6,
    "range": 130,
    "food": 5,
    "pop": 2,
    "build": "lightfactory",
    "cost": "Map.of(\"steel\", 200, \"oil\", 60, \"rare\", 20)",
    "strongVs": "armored",
    "branch": "land"
  },
  "后端原表达式": "new UnitDef(\"ltank\", \"轻型坦克\", \"arm\", 28, 22, 120, 6, 130, 5, 2, \"lightfactory\", Map.of(\"steel\", 200, \"oil\", 60, \"rare\", 20), \"armored\", \"land\")",
  "前端字段": {
    "name": "轻型坦克",
    "cat": "arm",
    "atk": 28,
    "def": 22,
    "hp": 120,
    "spd": 6,
    "range": 130,
    "food": 5,
    "pop": 2,
    "build": "lightfactory",
    "cost": {
      "steel": 200,
      "oil": 60,
      "rare": 20
    },
    "strongVs": "armored",
    "branch": "land"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N032 · 重型坦克（units.htank）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:61](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:61>) · [data.js:72](</Users/chenjuan/Documents/游戏/frontend/js/data.js:72>)

```json
{
  "后端字段": {
    "key": "htank",
    "name": "重型坦克",
    "cat": "arm",
    "atk": 50,
    "def": 40,
    "hp": 220,
    "spd": 4,
    "range": 140,
    "food": 8,
    "pop": 4,
    "build": "heavyfactory",
    "cost": "Map.of(\"steel\", 400, \"oil\", 120, \"rare\", 50)",
    "strongVs": "ltank",
    "branch": "land"
  },
  "后端原表达式": "new UnitDef(\"htank\", \"重型坦克\", \"arm\", 50, 40, 220, 4, 140, 8, 4, \"heavyfactory\", Map.of(\"steel\", 400, \"oil\", 120, \"rare\", 50), \"ltank\", \"land\")",
  "前端字段": {
    "name": "重型坦克",
    "cat": "arm",
    "atk": 50,
    "def": 40,
    "hp": 220,
    "spd": 4,
    "range": 140,
    "food": 8,
    "pop": 4,
    "build": "heavyfactory",
    "cost": {
      "steel": 400,
      "oil": 120,
      "rare": 50
    },
    "strongVs": "ltank",
    "branch": "land"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N033 · 突击炮（units.assault）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:64](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:64>) · [data.js:73](</Users/chenjuan/Documents/游戏/frontend/js/data.js:73>)

```json
{
  "后端字段": {
    "key": "assault",
    "name": "突击炮",
    "cat": "arm",
    "atk": 60,
    "def": 18,
    "hp": 120,
    "spd": 4,
    "range": 300,
    "food": 7,
    "pop": 3,
    "build": "heavyfactory",
    "cost": "Map.of(\"steel\", 360, \"oil\", 100, \"rare\", 60)",
    "strongVs": "htank",
    "branch": "land"
  },
  "后端原表达式": "new UnitDef(\"assault\", \"突击炮\", \"arm\", 60, 18, 120, 4, 300, 7, 3, \"heavyfactory\", Map.of(\"steel\", 360, \"oil\", 100, \"rare\", 60), \"htank\", \"land\")",
  "前端字段": {
    "name": "突击炮",
    "cat": "arm",
    "atk": 60,
    "def": 18,
    "hp": 120,
    "spd": 4,
    "range": 300,
    "food": 7,
    "pop": 3,
    "build": "heavyfactory",
    "cost": {
      "steel": 360,
      "oil": 100,
      "rare": 60
    },
    "strongVs": "htank",
    "branch": "land"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N034 · 火箭（units.rocket）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:67](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:67>) · [data.js:74](</Users/chenjuan/Documents/游戏/frontend/js/data.js:74>)

```json
{
  "后端字段": {
    "key": "rocket",
    "name": "火箭",
    "cat": "arm",
    "atk": 90,
    "def": 14,
    "hp": 100,
    "spd": 4,
    "range": 350,
    "food": 9,
    "pop": 4,
    "build": "heavyfactory",
    "cost": "Map.of(\"steel\", 500, \"oil\", 160, \"rare\", 100)",
    "strongVs": "htank",
    "branch": "land"
  },
  "后端原表达式": "new UnitDef(\"rocket\", \"火箭\", \"arm\", 90, 14, 100, 4, 350, 9, 4, \"heavyfactory\", Map.of(\"steel\", 500, \"oil\", 160, \"rare\", 100), \"htank\", \"land\")",
  "前端字段": {
    "name": "火箭",
    "cat": "arm",
    "atk": 90,
    "def": 14,
    "hp": 100,
    "spd": 4,
    "range": 350,
    "food": 9,
    "pop": 4,
    "build": "heavyfactory",
    "cost": {
      "steel": 500,
      "oil": 160,
      "rare": 100
    },
    "strongVs": "htank",
    "branch": "land"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N035 · 侦察机（units.scout）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:70](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:70>) · [data.js:75](</Users/chenjuan/Documents/游戏/frontend/js/data.js:75>)

```json
{
  "后端字段": {
    "key": "scout",
    "name": "侦察机",
    "cat": "air",
    "atk": 4,
    "def": 6,
    "hp": 30,
    "spd": 14,
    "range": 200,
    "food": 3,
    "pop": 1,
    "build": "factory",
    "cost": "Map.of(\"steel\", 80, \"oil\", 40, \"rare\", 10)",
    "strongVs": null,
    "branch": "air",
    "logistic": null,
    "load": null,
    "autoAdvance": false
  },
  "后端原表达式": "new UnitDef(\"scout\", \"侦察机\", \"air\", 4, 6, 30, 14, 200, 3, 1, \"factory\", Map.of(\"steel\", 80, \"oil\", 40, \"rare\", 10), null, \"air\", null, null, false)",
  "前端字段": {
    "name": "侦察机",
    "cat": "air",
    "atk": 4,
    "def": 6,
    "hp": 30,
    "spd": 14,
    "range": 200,
    "food": 3,
    "pop": 1,
    "build": "factory",
    "cost": {
      "steel": 80,
      "oil": 40,
      "rare": 10
    },
    "strongVs": null,
    "branch": "air",
    "autoAdvance": false
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N036 · 特种兵（units.special）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:74](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:74>) · [data.js:76](</Users/chenjuan/Documents/游戏/frontend/js/data.js:76>)

```json
{
  "后端字段": {
    "key": "special",
    "name": "特种兵",
    "cat": "air",
    "atk": 24,
    "def": 14,
    "hp": 60,
    "spd": 13,
    "range": 180,
    "food": 4,
    "pop": 2,
    "build": "factory",
    "cost": "Map.of(\"steel\", 160, \"oil\", 60, \"rare\", 30)",
    "strongVs": null,
    "branch": "air"
  },
  "后端原表达式": "new UnitDef(\"special\", \"特种兵\", \"air\", 24, 14, 60, 13, 180, 4, 2, \"factory\", Map.of(\"steel\", 160, \"oil\", 60, \"rare\", 30), null, \"air\")",
  "前端字段": {
    "name": "特种兵",
    "cat": "air",
    "atk": 24,
    "def": 14,
    "hp": 60,
    "spd": 13,
    "range": 180,
    "food": 4,
    "pop": 2,
    "build": "factory",
    "cost": {
      "steel": 160,
      "oil": 60,
      "rare": 30
    },
    "strongVs": null,
    "branch": "air"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N037 · 战斗机（units.fighter）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:77](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:77>) · [data.js:77](</Users/chenjuan/Documents/游戏/frontend/js/data.js:77>)

```json
{
  "后端字段": {
    "key": "fighter",
    "name": "战斗机",
    "cat": "air",
    "atk": 35,
    "def": 22,
    "hp": 90,
    "spd": 12,
    "range": 260,
    "food": 5,
    "pop": 2,
    "build": "factory",
    "cost": "Map.of(\"steel\", 200, \"oil\", 80, \"rare\", 30)",
    "strongVs": "bomber",
    "branch": "air"
  },
  "后端原表达式": "new UnitDef(\"fighter\", \"战斗机\", \"air\", 35, 22, 90, 12, 260, 5, 2, \"factory\", Map.of(\"steel\", 200, \"oil\", 80, \"rare\", 30), \"bomber\", \"air\")",
  "前端字段": {
    "name": "战斗机",
    "cat": "air",
    "atk": 35,
    "def": 22,
    "hp": 90,
    "spd": 12,
    "range": 260,
    "food": 5,
    "pop": 2,
    "build": "factory",
    "cost": {
      "steel": 200,
      "oil": 80,
      "rare": 30
    },
    "strongVs": "bomber",
    "branch": "air"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N038 · 轰炸机（units.bomber）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:80](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:80>) · [data.js:78](</Users/chenjuan/Documents/游戏/frontend/js/data.js:78>)

```json
{
  "后端字段": {
    "key": "bomber",
    "name": "轰炸机",
    "cat": "air",
    "atk": 70,
    "def": 16,
    "hp": 110,
    "spd": 9,
    "range": 280,
    "food": 7,
    "pop": 3,
    "build": "factory",
    "cost": "Map.of(\"steel\", 320, \"oil\", 140, \"rare\", 60)",
    "strongVs": "htank",
    "branch": "air"
  },
  "后端原表达式": "new UnitDef(\"bomber\", \"轰炸机\", \"air\", 70, 16, 110, 9, 280, 7, 3, \"factory\", Map.of(\"steel\", 320, \"oil\", 140, \"rare\", 60), \"htank\", \"air\")",
  "前端字段": {
    "name": "轰炸机",
    "cat": "air",
    "atk": 70,
    "def": 16,
    "hp": 110,
    "spd": 9,
    "range": 280,
    "food": 7,
    "pop": 3,
    "build": "factory",
    "cost": {
      "steel": 320,
      "oil": 140,
      "rare": 60
    },
    "strongVs": "htank",
    "branch": "air"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N039 · 运输机（units.transport）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:83](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:83>) · [data.js:79](</Users/chenjuan/Documents/游戏/frontend/js/data.js:79>)

```json
{
  "后端字段": {
    "key": "transport",
    "name": "运输机",
    "cat": "air",
    "atk": 2,
    "def": 12,
    "hp": 120,
    "spd": 8,
    "range": 0,
    "food": 5,
    "pop": 2,
    "build": "factory",
    "cost": "Map.of(\"steel\", 240, \"oil\", 100, \"rare\", 30)",
    "strongVs": null,
    "branch": "air",
    "logistic": true,
    "load": 80,
    "autoAdvance": false
  },
  "后端原表达式": "new UnitDef(\"transport\", \"运输机\", \"air\", 2, 12, 120, 8, 0, 5, 2, \"factory\", Map.of(\"steel\", 240, \"oil\", 100, \"rare\", 30), null, \"air\", true, 80, false)",
  "前端字段": {
    "name": "运输机",
    "cat": "air",
    "atk": 2,
    "def": 12,
    "hp": 120,
    "spd": 8,
    "range": 0,
    "food": 5,
    "pop": 2,
    "build": "factory",
    "cost": {
      "steel": 240,
      "oil": 100,
      "rare": 30
    },
    "strongVs": null,
    "branch": "air",
    "logistic": true,
    "load": 80
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N040 · 驱逐舰（units.destroyer）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:87](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:87>) · [data.js:80](</Users/chenjuan/Documents/游戏/frontend/js/data.js:80>)

```json
{
  "后端字段": {
    "key": "destroyer",
    "name": "驱逐舰",
    "cat": "nav",
    "atk": 40,
    "def": 28,
    "hp": 160,
    "spd": 6,
    "range": 250,
    "food": 7,
    "pop": 3,
    "build": "port",
    "cost": "Map.of(\"steel\", 300, \"oil\", 120, \"rare\", 60)",
    "strongVs": "sub",
    "branch": "sea"
  },
  "后端原表达式": "new UnitDef(\"destroyer\", \"驱逐舰\", \"nav\", 40, 28, 160, 6, 250, 7, 3, \"port\", Map.of(\"steel\", 300, \"oil\", 120, \"rare\", 60), \"sub\", \"sea\")",
  "前端字段": {
    "name": "驱逐舰",
    "cat": "nav",
    "atk": 40,
    "def": 28,
    "hp": 160,
    "spd": 6,
    "range": 250,
    "food": 7,
    "pop": 3,
    "build": "port",
    "cost": {
      "steel": 300,
      "oil": 120,
      "rare": 60
    },
    "strongVs": "sub",
    "branch": "sea"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N041 · 潜艇（units.sub）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:90](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:90>) · [data.js:81](</Users/chenjuan/Documents/游戏/frontend/js/data.js:81>)

```json
{
  "后端字段": {
    "key": "sub",
    "name": "潜艇",
    "cat": "nav",
    "atk": 65,
    "def": 18,
    "hp": 110,
    "spd": 5,
    "range": 230,
    "food": 6,
    "pop": 3,
    "build": "port",
    "cost": "Map.of(\"steel\", 360, \"oil\", 100, \"rare\", 80)",
    "strongVs": "battleship",
    "branch": "sea"
  },
  "后端原表达式": "new UnitDef(\"sub\", \"潜艇\", \"nav\", 65, 18, 110, 5, 230, 6, 3, \"port\", Map.of(\"steel\", 360, \"oil\", 100, \"rare\", 80), \"battleship\", \"sea\")",
  "前端字段": {
    "name": "潜艇",
    "cat": "nav",
    "atk": 65,
    "def": 18,
    "hp": 110,
    "spd": 5,
    "range": 230,
    "food": 6,
    "pop": 3,
    "build": "port",
    "cost": {
      "steel": 360,
      "oil": 100,
      "rare": 80
    },
    "strongVs": "battleship",
    "branch": "sea"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N042 · 战列舰（units.battleship）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:93](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:93>) · [data.js:82](</Users/chenjuan/Documents/游戏/frontend/js/data.js:82>)

```json
{
  "后端字段": {
    "key": "battleship",
    "name": "战列舰",
    "cat": "nav",
    "atk": 100,
    "def": 60,
    "hp": 360,
    "spd": 4,
    "range": 320,
    "food": 12,
    "pop": 6,
    "build": "port",
    "cost": "Map.of(\"steel\", 700, \"oil\", 240, \"rare\", 160)",
    "strongVs": "destroyer",
    "branch": "sea"
  },
  "后端原表达式": "new UnitDef(\"battleship\", \"战列舰\", \"nav\", 100, 60, 360, 4, 320, 12, 6, \"port\", Map.of(\"steel\", 700, \"oil\", 240, \"rare\", 160), \"destroyer\", \"sea\")",
  "前端字段": {
    "name": "战列舰",
    "cat": "nav",
    "atk": 100,
    "def": 60,
    "hp": 360,
    "spd": 4,
    "range": 320,
    "food": 12,
    "pop": 6,
    "build": "port",
    "cost": {
      "steel": 700,
      "oil": 240,
      "rare": 160
    },
    "strongVs": "destroyer",
    "branch": "sea"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N043 · 航母（units.carrier）

基础属性、消耗、生产门槛与相克配置

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[UnitDef.java:96](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/UnitDef.java:96>) · [data.js:83](</Users/chenjuan/Documents/游戏/frontend/js/data.js:83>)

```json
{
  "后端字段": {
    "key": "carrier",
    "name": "航母",
    "cat": "nav",
    "atk": 130,
    "def": 40,
    "hp": 280,
    "spd": 4,
    "range": 400,
    "food": 15,
    "pop": 8,
    "build": "port",
    "cost": "Map.of(\"steel\", 900, \"oil\", 300, \"rare\", 240)",
    "strongVs": null,
    "branch": "sea"
  },
  "后端原表达式": "new UnitDef(\"carrier\", \"航母\", \"nav\", 130, 40, 280, 4, 400, 15, 8, \"port\", Map.of(\"steel\", 900, \"oil\", 300, \"rare\", 240), null, \"sea\")",
  "前端字段": {
    "name": "航母",
    "cat": "nav",
    "atk": 130,
    "def": 40,
    "hp": 280,
    "spd": 4,
    "range": 400,
    "food": 15,
    "pop": 8,
    "build": "port",
    "cost": {
      "steel": 900,
      "oil": 300,
      "rare": 240
    },
    "strongVs": null,
    "branch": "sea"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N044 · 碉堡（forts.bunker）

坚固掩体,反步兵,近程高血防

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[FortDef.java:23](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/FortDef.java:23>) · [data.js:60](</Users/chenjuan/Documents/游戏/frontend/js/data.js:60>)

```json
{
  "后端字段": {
    "key": "bunker",
    "name": "碉堡",
    "desc": "坚固掩体,反步兵,近程高血防",
    "atk": 8,
    "def": 14,
    "hp": 260,
    "range": 60,
    "spd": 0,
    "cost": "Map.of(\"steel\", 30, \"oil\", 0, \"rare\", 0)",
    "strongVs": "infantry",
    "cat": "fort",
    "autoAdvance": false
  },
  "后端原表达式": "new FortDef(\"bunker\", \"碉堡\", \"坚固掩体,反步兵,近程高血防\", 8, 14, 260, 60, 0, Map.of(\"steel\", 30, \"oil\", 0, \"rare\", 0), \"infantry\", \"fort\", false)",
  "前端字段": {
    "name": "碉堡",
    "desc": "坚固掩体,反步兵,近程高血防",
    "atk": 8,
    "def": 14,
    "hp": 260,
    "range": 60,
    "spd": 0,
    "cost": {
      "steel": 30,
      "oil": 0,
      "rare": 0
    },
    "strongVs": "infantry",
    "cat": "fort",
    "autoAdvance": false
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N045 · 榴弹炮（forts.howitzer）

远程压制,反步兵与建筑

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[FortDef.java:27](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/FortDef.java:27>) · [data.js:61](</Users/chenjuan/Documents/游戏/frontend/js/data.js:61>)

```json
{
  "后端字段": {
    "key": "howitzer",
    "name": "榴弹炮",
    "desc": "远程压制,反步兵与建筑",
    "atk": 50,
    "def": 6,
    "hp": 80,
    "range": 300,
    "spd": 0,
    "cost": "Map.of(\"steel\", 60, \"oil\", 10, \"rare\", 5)",
    "strongVs": "infantry",
    "cat": "fort",
    "autoAdvance": false
  },
  "后端原表达式": "new FortDef(\"howitzer\", \"榴弹炮\", \"远程压制,反步兵与建筑\", 50, 6, 80, 300, 0, Map.of(\"steel\", 60, \"oil\", 10, \"rare\", 5), \"infantry\", \"fort\", false)",
  "前端字段": {
    "name": "榴弹炮",
    "desc": "远程压制,反步兵与建筑",
    "atk": 50,
    "def": 6,
    "hp": 80,
    "range": 300,
    "spd": 0,
    "cost": {
      "steel": 60,
      "oil": 10,
      "rare": 5
    },
    "strongVs": "infantry",
    "cat": "fort",
    "autoAdvance": false
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N046 · 反坦克炮（forts.antitank）

穿甲火力,反装甲

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[FortDef.java:31](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/FortDef.java:31>) · [data.js:62](</Users/chenjuan/Documents/游戏/frontend/js/data.js:62>)

```json
{
  "后端字段": {
    "key": "antitank",
    "name": "反坦克炮",
    "desc": "穿甲火力,反装甲",
    "atk": 45,
    "def": 6,
    "hp": 70,
    "range": 250,
    "spd": 0,
    "cost": "Map.of(\"steel\", 70, \"oil\", 10, \"rare\", 10)",
    "strongVs": "ltank",
    "cat": "fort",
    "autoAdvance": false
  },
  "后端原表达式": "new FortDef(\"antitank\", \"反坦克炮\", \"穿甲火力,反装甲\", 45, 6, 70, 250, 0, Map.of(\"steel\", 70, \"oil\", 10, \"rare\", 10), \"ltank\", \"fort\", false)",
  "前端字段": {
    "name": "反坦克炮",
    "desc": "穿甲火力,反装甲",
    "atk": 45,
    "def": 6,
    "hp": 70,
    "range": 250,
    "spd": 0,
    "cost": {
      "steel": 70,
      "oil": 10,
      "rare": 10
    },
    "strongVs": "ltank",
    "cat": "fort",
    "autoAdvance": false
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N047 · 防空炮（forts.flak）

对空火力,反空军

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[FortDef.java:35](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/FortDef.java:35>) · [data.js:63](</Users/chenjuan/Documents/游戏/frontend/js/data.js:63>)

```json
{
  "后端字段": {
    "key": "flak",
    "name": "防空炮",
    "desc": "对空火力,反空军",
    "atk": 35,
    "def": 5,
    "hp": 60,
    "range": 280,
    "spd": 0,
    "cost": "Map.of(\"steel\", 55, \"oil\", 15, \"rare\", 15)",
    "strongVs": "fighter",
    "cat": "fort",
    "autoAdvance": false
  },
  "后端原表达式": "new FortDef(\"flak\", \"防空炮\", \"对空火力,反空军\", 35, 5, 60, 280, 0, Map.of(\"steel\", 55, \"oil\", 15, \"rare\", 15), \"fighter\", \"fort\", false)",
  "前端字段": {
    "name": "防空炮",
    "desc": "对空火力,反空军",
    "atk": 35,
    "def": 5,
    "hp": 60,
    "range": 280,
    "spd": 0,
    "cost": {
      "steel": 55,
      "oil": 15,
      "rare": 15
    },
    "strongVs": "fighter",
    "cat": "fort",
    "autoAdvance": false
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N048 · 攻击科技（techs.attack_tech）

全军攻击 +5%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:25](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:25>) · [data.js:87](</Users/chenjuan/Documents/游戏/frontend/js/data.js:87>)

```json
{
  "后端字段": {
    "key": "attack_tech",
    "name": "攻击科技",
    "branch": "军事",
    "desc": "全军攻击 +5%/级",
    "maxLevel": 10,
    "labReq": 1,
    "cost": "Map.of(\"steel\", 240, \"food\", 120)",
    "costGrowth": 1.7,
    "effect": "atk_all"
  },
  "后端原表达式": "new TechDef(\"attack_tech\", \"攻击科技\", \"军事\", \"全军攻击 +5%/级\", 10, 1, Map.of(\"steel\", 240, \"food\", 120), 1.7, \"atk_all\")",
  "前端字段": {
    "name": "攻击科技",
    "branch": "军事",
    "desc": "全军攻击 +5%/级",
    "max": 10,
    "labReq": 1,
    "baseCost": {
      "steel": 240,
      "food": 120
    },
    "growth": 1.7,
    "affect": "atk_all"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N049 · 防御科技（techs.defense_tech）

全军防御 +5%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:27](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:27>) · [data.js:88](</Users/chenjuan/Documents/游戏/frontend/js/data.js:88>)

```json
{
  "后端字段": {
    "key": "defense_tech",
    "name": "防御科技",
    "branch": "军事",
    "desc": "全军防御 +5%/级",
    "maxLevel": 10,
    "labReq": 2,
    "cost": "Map.of(\"steel\", 240, \"food\", 120)",
    "costGrowth": 1.7,
    "effect": "def_all"
  },
  "后端原表达式": "new TechDef(\"defense_tech\", \"防御科技\", \"军事\", \"全军防御 +5%/级\", 10, 2, Map.of(\"steel\", 240, \"food\", 120), 1.7, \"def_all\")",
  "前端字段": {
    "name": "防御科技",
    "branch": "军事",
    "desc": "全军防御 +5%/级",
    "max": 10,
    "labReq": 1,
    "baseCost": {
      "steel": 240,
      "food": 120
    },
    "growth": 1.7,
    "affect": "def_all"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N050 · 武器射程（techs.weapon_range）

全军武器射程 +5%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:29](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:29>) · [data.js:89](</Users/chenjuan/Documents/游戏/frontend/js/data.js:89>)

```json
{
  "后端字段": {
    "key": "weapon_range",
    "name": "武器射程",
    "branch": "军事",
    "desc": "全军武器射程 +5%/级",
    "maxLevel": 10,
    "labReq": 2,
    "cost": "Map.of(\"steel\", 280, \"food\", 140, \"rare\", 20)",
    "costGrowth": 1.8,
    "effect": "range_all"
  },
  "后端原表达式": "new TechDef(\"weapon_range\", \"武器射程\", \"军事\", \"全军武器射程 +5%/级\", 10, 2, Map.of(\"steel\", 280, \"food\", 140, \"rare\", 20), 1.8, \"range_all\")",
  "前端字段": {
    "name": "武器射程",
    "branch": "军事",
    "desc": "全军武器射程 +5%/级",
    "max": 10,
    "labReq": 2,
    "baseCost": {
      "steel": 280,
      "food": 140,
      "rare": 20
    },
    "growth": 1.8,
    "affect": "range_all"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N051 · 军队生命（techs.cmd_hp）

军队生命 +5%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:30](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:30>) · [data.js:90](</Users/chenjuan/Documents/游戏/frontend/js/data.js:90>)

```json
{
  "后端字段": {
    "key": "cmd_hp",
    "name": "军队生命",
    "branch": "军事",
    "desc": "军队生命 +5%/级",
    "maxLevel": 10,
    "labReq": 3,
    "cost": "Map.of(\"steel\", 300, \"food\", 160, \"rare\", 30)",
    "costGrowth": 1.8,
    "effect": "hp_all"
  },
  "后端原表达式": "new TechDef(\"cmd_hp\", \"军队生命\", \"军事\", \"军队生命 +5%/级\", 10, 3, Map.of(\"steel\", 300, \"food\", 160, \"rare\", 30), 1.8, \"hp_all\")",
  "前端字段": {
    "name": "军队生命",
    "branch": "军事",
    "desc": "军队生命 +5%/级",
    "max": 10,
    "labReq": 3,
    "baseCost": {
      "steel": 300,
      "food": 160,
      "rare": 30
    },
    "growth": 1.8,
    "affect": "hp_all"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N052 · 步兵负重（techs.inf_load）

步兵负重 +20%/级(掠夺)

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:33](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:33>) · [data.js:91](</Users/chenjuan/Documents/游戏/frontend/js/data.js:91>)

```json
{
  "后端字段": {
    "key": "inf_load",
    "name": "步兵负重",
    "branch": "步兵",
    "desc": "步兵负重 +20%/级(掠夺)",
    "maxLevel": 5,
    "labReq": 2,
    "cost": "Map.of(\"steel\", 200, \"food\", 100)",
    "costGrowth": 1.6,
    "effect": "load"
  },
  "后端原表达式": "new TechDef(\"inf_load\", \"步兵负重\", \"步兵\", \"步兵负重 +20%/级(掠夺)\", 5, 2, Map.of(\"steel\", 200, \"food\", 100), 1.6, \"load\")",
  "前端字段": {
    "name": "步兵负重",
    "branch": "后勤",
    "desc": "步兵负重 +20%/级(掠夺)",
    "max": 5,
    "labReq": 2,
    "baseCost": {
      "steel": 200,
      "food": 100
    },
    "growth": 1.6,
    "affect": "load"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N053 · 燃烧引擎（techs.arm_engine）

装甲系移动 +5%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:36](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:36>) · [data.js:92](</Users/chenjuan/Documents/游戏/frontend/js/data.js:92>)

```json
{
  "后端字段": {
    "key": "arm_engine",
    "name": "燃烧引擎",
    "branch": "装甲",
    "desc": "装甲系移动 +5%/级",
    "maxLevel": 10,
    "labReq": 3,
    "cost": "Map.of(\"steel\", 320, \"oil\", 120, \"rare\", 40)",
    "costGrowth": 1.8,
    "effect": "spd_arm"
  },
  "后端原表达式": "new TechDef(\"arm_engine\", \"燃烧引擎\", \"装甲\", \"装甲系移动 +5%/级\", 10, 3, Map.of(\"steel\", 320, \"oil\", 120, \"rare\", 40), 1.8, \"spd_arm\")",
  "前端字段": {
    "name": "燃烧引擎",
    "branch": "机动",
    "desc": "装甲系移动 +5%/级",
    "max": 10,
    "labReq": 3,
    "baseCost": {
      "steel": 320,
      "oil": 120,
      "rare": 40
    },
    "growth": 1.8,
    "affect": "spd_arm"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N054 · 喷气推进（techs.air_engine）

空军移动 +5%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:39](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:39>) · [data.js:93](</Users/chenjuan/Documents/游戏/frontend/js/data.js:93>)

```json
{
  "后端字段": {
    "key": "air_engine",
    "name": "喷气推进",
    "branch": "航空",
    "desc": "空军移动 +5%/级",
    "maxLevel": 10,
    "labReq": 4,
    "cost": "Map.of(\"steel\", 360, \"oil\", 160, \"rare\", 70)",
    "costGrowth": 1.9,
    "effect": "spd_air"
  },
  "后端原表达式": "new TechDef(\"air_engine\", \"喷气推进\", \"航空\", \"空军移动 +5%/级\", 10, 4, Map.of(\"steel\", 360, \"oil\", 160, \"rare\", 70), 1.9, \"spd_air\")",
  "前端字段": {
    "name": "喷气推进",
    "branch": "机动",
    "desc": "空军移动 +5%/级",
    "max": 10,
    "labReq": 4,
    "baseCost": {
      "steel": 360,
      "oil": 160,
      "rare": 70
    },
    "growth": 1.9,
    "affect": "spd_air"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N055 · 舰船动力（techs.nav_engine）

海军移动 +5%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:42](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:42>) · [data.js:94](</Users/chenjuan/Documents/游戏/frontend/js/data.js:94>)

```json
{
  "后端字段": {
    "key": "nav_engine",
    "name": "舰船动力",
    "branch": "航海",
    "desc": "海军移动 +5%/级",
    "maxLevel": 10,
    "labReq": 5,
    "cost": "Map.of(\"steel\", 400, \"oil\", 200, \"rare\", 100)",
    "costGrowth": 2.0,
    "effect": "spd_nav"
  },
  "后端原表达式": "new TechDef(\"nav_engine\", \"舰船动力\", \"航海\", \"海军移动 +5%/级\", 10, 5, Map.of(\"steel\", 400, \"oil\", 200, \"rare\", 100), 2.0, \"spd_nav\")",
  "前端字段": {
    "name": "舰船动力",
    "branch": "机动",
    "desc": "海军移动 +5%/级",
    "max": 10,
    "labReq": 5,
    "baseCost": {
      "steel": 400,
      "oil": 200,
      "rare": 100
    },
    "growth": 2,
    "affect": "spd_nav"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N056 · 资源采集（techs.log_production）

资源产出 +5%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:45](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:45>) · [data.js:95](</Users/chenjuan/Documents/游戏/frontend/js/data.js:95>)

```json
{
  "后端字段": {
    "key": "log_production",
    "name": "资源采集",
    "branch": "后勤",
    "desc": "资源产出 +5%/级",
    "maxLevel": 10,
    "labReq": 1,
    "cost": "Map.of(\"steel\", 320, \"food\", 160)",
    "costGrowth": 1.8,
    "effect": "res"
  },
  "后端原表达式": "new TechDef(\"log_production\", \"资源采集\", \"后勤\", \"资源产出 +5%/级\", 10, 1, Map.of(\"steel\", 320, \"food\", 160), 1.8, \"res\")",
  "前端字段": {
    "name": "资源采集",
    "branch": "后勤",
    "desc": "资源产出 +5%/级",
    "max": 10,
    "labReq": 1,
    "baseCost": {
      "steel": 320,
      "food": 160
    },
    "growth": 1.8,
    "affect": "res"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N057 · 仓储技术（techs.log_warehouse）

资源上限 +10%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:47](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:47>) · [data.js:96](</Users/chenjuan/Documents/游戏/frontend/js/data.js:96>)

```json
{
  "后端字段": {
    "key": "log_warehouse",
    "name": "仓储技术",
    "branch": "后勤",
    "desc": "资源上限 +10%/级",
    "maxLevel": 5,
    "labReq": 2,
    "cost": "Map.of(\"steel\", 280, \"food\", 140)",
    "costGrowth": 1.7,
    "effect": "cap"
  },
  "后端原表达式": "new TechDef(\"log_warehouse\", \"仓储技术\", \"后勤\", \"资源上限 +10%/级\", 5, 2, Map.of(\"steel\", 280, \"food\", 140), 1.7, \"cap\")",
  "前端字段": {
    "name": "仓储技术",
    "branch": "后勤",
    "desc": "资源上限 +10%/级",
    "max": 5,
    "labReq": 2,
    "baseCost": {
      "steel": 280,
      "food": 140
    },
    "growth": 1.7,
    "affect": "cap"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N058 · 军需补给（techs.log_food）

养兵耗粮 -5%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:49](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:49>) · [data.js:97](</Users/chenjuan/Documents/游戏/frontend/js/data.js:97>)

```json
{
  "后端字段": {
    "key": "log_food",
    "name": "军需补给",
    "branch": "后勤",
    "desc": "养兵耗粮 -5%/级",
    "maxLevel": 10,
    "labReq": 3,
    "cost": "Map.of(\"steel\", 360, \"food\", 200)",
    "costGrowth": 1.8,
    "effect": "food_save"
  },
  "后端原表达式": "new TechDef(\"log_food\", \"军需补给\", \"后勤\", \"养兵耗粮 -5%/级\", 10, 3, Map.of(\"steel\", 360, \"food\", 200), 1.8, \"food_save\")",
  "前端字段": {
    "name": "军需补给",
    "branch": "后勤",
    "desc": "养兵耗粮 -5%/级",
    "max": 10,
    "labReq": 3,
    "baseCost": {
      "steel": 360,
      "food": 200
    },
    "growth": 1.8,
    "affect": "food_save"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N059 · 训练加速（techs.log_train）

征召批量 +10%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:51](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:51>) · [data.js:98](</Users/chenjuan/Documents/游戏/frontend/js/data.js:98>)

```json
{
  "后端字段": {
    "key": "log_train",
    "name": "训练加速",
    "branch": "后勤",
    "desc": "征召批量 +10%/级",
    "maxLevel": 10,
    "labReq": 2,
    "cost": "Map.of(\"steel\", 300, \"food\", 180, \"gold\", 100)",
    "costGrowth": 1.8,
    "effect": "train"
  },
  "后端原表达式": "new TechDef(\"log_train\", \"训练加速\", \"后勤\", \"征召批量 +10%/级\", 10, 2, Map.of(\"steel\", 300, \"food\", 180, \"gold\", 100), 1.8, \"train\")",
  "前端字段": {
    "name": "训练加速",
    "branch": "后勤",
    "desc": "征召批量 +10%/级",
    "max": 10,
    "labReq": 2,
    "baseCost": {
      "steel": 300,
      "food": 180,
      "gold": 100
    },
    "growth": 1.8,
    "affect": "train"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N060 · 建筑加速（techs.log_build）

建筑升级资源 -5%/级

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:53](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:53>) · [data.js:99](</Users/chenjuan/Documents/游戏/frontend/js/data.js:99>)

```json
{
  "后端字段": {
    "key": "log_build",
    "name": "建筑加速",
    "branch": "后勤",
    "desc": "建筑升级资源 -5%/级",
    "maxLevel": 10,
    "labReq": 2,
    "cost": "Map.of(\"steel\", 340, \"food\", 160, \"gold\", 120)",
    "costGrowth": 1.8,
    "effect": "build"
  },
  "后端原表达式": "new TechDef(\"log_build\", \"建筑加速\", \"后勤\", \"建筑升级资源 -5%/级\", 10, 2, Map.of(\"steel\", 340, \"food\", 160, \"gold\", 120), 1.8, \"build\")",
  "前端字段": {
    "name": "建筑加速",
    "branch": "后勤",
    "desc": "建筑升级资源 -5%/级",
    "max": 10,
    "labReq": 2,
    "baseCost": {
      "steel": 340,
      "food": 160,
      "gold": 120
    },
    "growth": 1.8,
    "affect": "build"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N061 · 医疗技术（techs.log_medical）

伤兵可回收 +5%/级，最高50%

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:55](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:55>) · [data.js:100](</Users/chenjuan/Documents/游戏/frontend/js/data.js:100>)

```json
{
  "后端字段": {
    "key": "log_medical",
    "name": "医疗技术",
    "branch": "后勤",
    "desc": "伤兵可回收 +5%/级，最高50%",
    "maxLevel": 10,
    "labReq": 3,
    "cost": "Map.of(\"steel\", 320, \"food\", 220, \"gold\", 150)",
    "costGrowth": 1.8,
    "effect": "medical"
  },
  "后端原表达式": "new TechDef(\"log_medical\", \"医疗技术\", \"后勤\", \"伤兵可回收 +5%/级，最高50%\", 10, 3, Map.of(\"steel\", 320, \"food\", 220, \"gold\", 150), 1.8, \"medical\")",
  "前端字段": {
    "name": "医疗技术",
    "branch": "后勤",
    "desc": "伤兵可回收 +5%/级，最高50%",
    "max": 10,
    "labReq": 3,
    "baseCost": {
      "steel": 320,
      "food": 220,
      "gold": 150
    },
    "growth": 1.8,
    "affect": "medical"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N062 · 侦察技术（techs.recon_level）

侦察情报深度 +1 阶/级，逐级探明城防、守军、建筑、科技与将领

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份不同或该条后来加入；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:58](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:58>) · [data.js:101](</Users/chenjuan/Documents/游戏/frontend/js/data.js:101>)

```json
{
  "后端字段": {
    "key": "recon_level",
    "name": "侦察技术",
    "branch": "侦察",
    "desc": "侦察情报深度 +1 阶/级，逐级探明城防、守军、建筑、科技与将领",
    "maxLevel": 5,
    "labReq": 1,
    "cost": "Map.of(\"steel\", 180, \"oil\", 60)",
    "costGrowth": 1.6,
    "effect": "recon"
  },
  "后端原表达式": "new TechDef(\"recon_level\", \"侦察技术\", \"侦察\", \"侦察情报深度 +1 阶/级，逐级探明城防、守军、建筑、科技与将领\", 5, 1, Map.of(\"steel\", 180, \"oil\", 60), 1.6, \"recon\")",
  "前端字段": {
    "name": "侦察技术",
    "branch": "侦察",
    "desc": "侦察情报深度 +1 阶/级，逐级探明城防、守军、建筑、科技与将领",
    "max": 5,
    "labReq": 1,
    "baseCost": {
      "steel": 180,
      "oil": 60
    },
    "growth": 1.6,
    "affect": "recon"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N063 · 雷达预警（techs.recon_radar）

提前发现敌方 +1 回合

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前前端数据与最早可见备份一致；此比较仅说明项目内沿革。未发现最初数值来源、平衡模型或授权。

位置：[TechDef.java:60](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/TechDef.java:60>) · [data.js:102](</Users/chenjuan/Documents/游戏/frontend/js/data.js:102>)

```json
{
  "后端字段": {
    "key": "recon_radar",
    "name": "雷达预警",
    "branch": "侦察",
    "desc": "提前发现敌方 +1 回合",
    "maxLevel": 3,
    "labReq": 2,
    "cost": "Map.of(\"steel\", 240, \"oil\", 100, \"rare\", 20)",
    "costGrowth": 1.7,
    "effect": "radar"
  },
  "后端原表达式": "new TechDef(\"recon_radar\", \"雷达预警\", \"侦察\", \"提前发现敌方 +1 回合\", 3, 2, Map.of(\"steel\", 240, \"oil\", 100, \"rare\", 20), 1.7, \"radar\")",
  "前端字段": {
    "name": "雷达预警",
    "branch": "侦察",
    "desc": "提前发现敌方 +1 回合",
    "max": 3,
    "labReq": 2,
    "baseCost": {
      "steel": 240,
      "oil": 100,
      "rare": 20
    },
    "growth": 1.7,
    "affect": "radar"
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N064 · 列兵晋升表（rank.1）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:37](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:37>)

```json
{
  "tier": 1,
  "name": "列兵",
  "prestige": 0,
  "baseCap": 1000,
  "reqGems": "Map.of()"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N065 · 上等兵晋升表（rank.2）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:38](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:38>)

```json
{
  "tier": 2,
  "name": "上等兵",
  "prestige": 200,
  "baseCap": 1500,
  "reqGems": "Map.of(\"gem_pearl\", 3)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N066 · 下士晋升表（rank.3）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:39](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:39>)

```json
{
  "tier": 3,
  "name": "下士",
  "prestige": 500,
  "baseCap": 2000,
  "reqGems": "Map.of(\"gem_pearl\", 5, \"gem_coral\", 2)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N067 · 中士晋升表（rank.4）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:40](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:40>)

```json
{
  "tier": 4,
  "name": "中士",
  "prestige": 1000,
  "baseCap": 2600,
  "reqGems": "Map.of(\"gem_pearl\", 8, \"gem_coral\", 4, \"gem_glaze\", 2)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N068 · 上士晋升表（rank.5）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:41](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:41>)

```json
{
  "tier": 5,
  "name": "上士",
  "prestige": 2000,
  "baseCap": 3300,
  "reqGems": "Map.of(\"gem_coral\", 6, \"gem_glaze\", 4, \"gem_amber\", 2)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N069 · 军士长晋升表（rank.6）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:42](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:42>)

```json
{
  "tier": 6,
  "name": "军士长",
  "prestige": 3500,
  "baseCap": 4100,
  "reqGems": "Map.of(\"gem_glaze\", 8, \"gem_amber\", 5, \"gem_agate\", 2)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N070 · 准尉晋升表（rank.7）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:43](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:43>)

```json
{
  "tier": 7,
  "name": "准尉",
  "prestige": 5500,
  "baseCap": 5000,
  "reqGems": "Map.of(\"gem_amber\", 8, \"gem_agate\", 5, \"gem_crystal\", 2)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N071 · 少尉晋升表（rank.8）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:44](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:44>)

```json
{
  "tier": 8,
  "name": "少尉",
  "prestige": 8000,
  "baseCap": 6000,
  "reqGems": "Map.of(\"gem_agate\", 8, \"gem_crystal\", 5, \"gem_jadeite\", 2)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N072 · 中尉晋升表（rank.9）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:45](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:45>)

```json
{
  "tier": 9,
  "name": "中尉",
  "prestige": 15000,
  "baseCap": 7200,
  "reqGems": "Map.of(\"gem_crystal\", 8, \"gem_jadeite\", 5, \"gem_jade\", 2)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N073 · 上尉晋升表（rank.10）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:46](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:46>)

```json
{
  "tier": 10,
  "name": "上尉",
  "prestige": 25000,
  "baseCap": 8600,
  "reqGems": "Map.of(\"gem_jadeite\", 8, \"gem_jade\", 5, \"gem_nightpearl\", 1)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N074 · 少校晋升表（rank.11）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:47](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:47>)

```json
{
  "tier": 11,
  "name": "少校",
  "prestige": 45000,
  "baseCap": 10200,
  "reqGems": "Map.of(\"gem_jade\", 8, \"gem_nightpearl\", 2, \"gem_pearl\", 15)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N075 · 中校晋升表（rank.12）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:48](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:48>)

```json
{
  "tier": 12,
  "name": "中校",
  "prestige": 80000,
  "baseCap": 12000,
  "reqGems": "Map.of(\"gem_nightpearl\", 4, \"gem_coral\", 15, \"gem_glaze\", 12)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N076 · 上校晋升表（rank.13）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:49](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:49>)

```json
{
  "tier": 13,
  "name": "上校",
  "prestige": 150000,
  "baseCap": 14000,
  "reqGems": "Map.of(\"gem_amber\", 15, \"gem_agate\", 12, \"gem_crystal\", 10)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N077 · 大校晋升表（rank.14）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:50](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:50>)

```json
{
  "tier": 14,
  "name": "大校",
  "prestige": 300000,
  "baseCap": 16200,
  "reqGems": "Map.of(\"gem_crystal\", 15, \"gem_jadeite\", 12, \"gem_jade\", 10)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N078 · 少将晋升表（rank.15）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:51](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:51>)

```json
{
  "tier": 15,
  "name": "少将",
  "prestige": 600000,
  "baseCap": 17500,
  "reqGems": "Map.of(\"gem_jadeite\", 18, \"gem_jade\", 15, \"gem_nightpearl\", 6)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N079 · 中将晋升表（rank.16）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:52](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:52>)

```json
{
  "tier": 16,
  "name": "中将",
  "prestige": 1200000,
  "baseCap": 18800,
  "reqGems": "Map.of(\"gem_jade\", 20, \"gem_nightpearl\", 10, \"gem_crystal\", 15, \"gem_pearl\", 20)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N080 · 上将晋升表（rank.17）

声望门槛、基础带兵量与珠宝数量

**状态：来源不明；评估：待评估；建议次序：优先评估组合。**

当前17阶表可定位；和已核实的2014年攻略不同。未找到本表独立设计依据。

位置：[MilitaryRankDef.java:53](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:53>)

```json
{
  "tier": 17,
  "name": "上将",
  "prestige": 2500000,
  "baseCap": 20000,
  "reqGems": "Map.of(\"gem_nightpearl\", 15, \"gem_jade\", 25, \"gem_jadeite\", 25, \"gem_agate\", 20)"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N081 · 世界配置 size（world.size）

200

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[data.js:173](</Users/chenjuan/Documents/游戏/frontend/js/data.js:173>) · [WorldConfig.java:13](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/WorldConfig.java:13>)

```json
200
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N082 · 世界配置 viewRadius（world.viewRadius）

3

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[data.js:173](</Users/chenjuan/Documents/游戏/frontend/js/data.js:173>) · [WorldConfig.java:14](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/WorldConfig.java:14>)

```json
3
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N083 · 世界配置 marchSecPerGrid（world.marchSecPerGrid）

9

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[data.js:173](</Users/chenjuan/Documents/游戏/frontend/js/data.js:173>) · [WorldConfig.java:15](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/WorldConfig.java:15>)

```json
9
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N084 · 流寇 Lv.1（bandit.1）

守军编成与资源/经验奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[WorldConfig.java:23](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/WorldConfig.java:23>)

```json
{
  "lv": 1,
  "army": {
    "infantry": 20
  },
  "reward": {
    "food": 80,
    "steel": 120,
    "oil": 60,
    "rare": 10,
    "gold": 15,
    "exp": 15
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N085 · 流寇 Lv.2（bandit.2）

守军编成与资源/经验奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[WorldConfig.java:26](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/WorldConfig.java:26>)

```json
{
  "lv": 2,
  "army": {
    "infantry": 30,
    "motor": 8
  },
  "reward": {
    "food": 120,
    "steel": 180,
    "oil": 90,
    "rare": 15,
    "gold": 20,
    "exp": 25
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N086 · 流寇 Lv.3（bandit.3）

守军编成与资源/经验奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[WorldConfig.java:29](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/WorldConfig.java:29>)

```json
{
  "lv": 3,
  "army": {
    "motor": 15,
    "armored": 6
  },
  "reward": {
    "food": 180,
    "steel": 260,
    "oil": 140,
    "rare": 25,
    "gold": 30,
    "exp": 40
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N087 · 流寇 Lv.4（bandit.4）

守军编成与资源/经验奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[WorldConfig.java:32](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/WorldConfig.java:32>)

```json
{
  "lv": 4,
  "army": {
    "ltank": 10,
    "armored": 8
  },
  "reward": {
    "food": 240,
    "steel": 360,
    "oil": 200,
    "rare": 40,
    "gold": 45,
    "exp": 60
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N088 · 流寇 Lv.5（bandit.5）

守军编成与资源/经验奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[WorldConfig.java:35](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/WorldConfig.java:35>)

```json
{
  "lv": 5,
  "army": {
    "htank": 6,
    "assault": 4,
    "fighter": 4
  },
  "reward": {
    "food": 320,
    "steel": 480,
    "oil": 280,
    "rare": 60,
    "gold": 70,
    "exp": 90
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N089 · 流寇 Lv.6（bandit.6）

守军编成与资源/经验奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[WorldConfig.java:38](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/WorldConfig.java:38>)

```json
{
  "lv": 6,
  "army": {
    "htank": 10,
    "rocket": 6,
    "bomber": 4
  },
  "reward": {
    "food": 440,
    "steel": 660,
    "oil": 400,
    "rare": 90,
    "gold": 100,
    "exp": 130
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N090 · 流寇 Lv.7（bandit.7）

守军编成与资源/经验奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[WorldConfig.java:41](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/WorldConfig.java:41>)

```json
{
  "lv": 7,
  "army": {
    "htank": 16,
    "rocket": 10,
    "fighter": 10,
    "sub": 4
  },
  "reward": {
    "food": 600,
    "steel": 900,
    "oil": 560,
    "rare": 130,
    "gold": 150,
    "exp": 180
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N091 · 流寇 Lv.8（bandit.8）

守军编成与资源/经验奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[WorldConfig.java:44](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/WorldConfig.java:44>)

```json
{
  "lv": 8,
  "army": {
    "battleship": 4,
    "carrier": 1,
    "fighter": 20
  },
  "reward": {
    "food": 800,
    "steel": 1200,
    "oil": 760,
    "rare": 180,
    "gold": 220,
    "exp": 250
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N092 · 试玩补给（recharge.p6）

适合首次体验充值

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前为模拟充值；档位数值和月卡/礼包承诺未附策划来源。

位置：[recharge.js:14](</Users/chenjuan/Documents/游戏/frontend/js/recharge.js:14>) · [ShopController.java:165](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/controller/ShopController.java:165>)

```json
{
  "id": "p6",
  "cat": "diamond",
  "name": "试玩补给",
  "icon": "💎",
  "rmb": 6,
  "diamond": 60,
  "desc": "适合首次体验充值"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N093 · 少将补给（recharge.p30）

额外赠送30钻石

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前为模拟充值；档位数值和月卡/礼包承诺未附策划来源。

位置：[recharge.js:15](</Users/chenjuan/Documents/游戏/frontend/js/recharge.js:15>) · [ShopController.java:165](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/controller/ShopController.java:165>)

```json
{
  "id": "p30",
  "cat": "diamond",
  "name": "少将补给",
  "icon": "💠",
  "rmb": 30,
  "diamond": 330,
  "desc": "额外赠送30钻石",
  "bonus": "赠30"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N094 · 中将补给（recharge.p98）

额外赠送100钻石

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前为模拟充值；档位数值和月卡/礼包承诺未附策划来源。

位置：[recharge.js:16](</Users/chenjuan/Documents/游戏/frontend/js/recharge.js:16>) · [ShopController.java:165](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/controller/ShopController.java:165>)

```json
{
  "id": "p98",
  "cat": "diamond",
  "name": "中将补给",
  "icon": "💠",
  "rmb": 98,
  "diamond": 1080,
  "desc": "额外赠送100钻石",
  "bonus": "赠100"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N095 · 上将补给（recharge.p198）

额外赠送250钻石

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前为模拟充值；档位数值和月卡/礼包承诺未附策划来源。

位置：[recharge.js:17](</Users/chenjuan/Documents/游戏/frontend/js/recharge.js:17>) · [ShopController.java:165](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/controller/ShopController.java:165>)

```json
{
  "id": "p198",
  "cat": "diamond",
  "name": "上将补给",
  "icon": "💎",
  "rmb": 198,
  "diamond": 2230,
  "desc": "额外赠送250钻石",
  "bonus": "赠250"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N096 · 大将补给（recharge.p328）

额外赠送500钻石

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前为模拟充值；档位数值和月卡/礼包承诺未附策划来源。

位置：[recharge.js:18](</Users/chenjuan/Documents/游戏/frontend/js/recharge.js:18>) · [ShopController.java:165](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/controller/ShopController.java:165>)

```json
{
  "id": "p328",
  "cat": "diamond",
  "name": "大将补给",
  "icon": "💎",
  "rmb": 328,
  "diamond": 3780,
  "desc": "额外赠送500钻石",
  "bonus": "赠500"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N097 · 统帅补给（recharge.p648）

额外赠送1200钻石

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前为模拟充值；档位数值和月卡/礼包承诺未附策划来源。

位置：[recharge.js:19](</Users/chenjuan/Documents/游戏/frontend/js/recharge.js:19>) · [ShopController.java:165](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/controller/ShopController.java:165>)

```json
{
  "id": "p648",
  "cat": "diamond",
  "name": "统帅补给",
  "icon": "💎",
  "rmb": 648,
  "diamond": 7680,
  "desc": "额外赠送1200钻石",
  "bonus": "赠1200"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N098 · 元帅礼包（recharge.p1280）

钻石、资源与稀有道具组合礼包

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前为模拟充值；档位数值和月卡/礼包承诺未附策划来源。

位置：[recharge.js:20](</Users/chenjuan/Documents/游戏/frontend/js/recharge.js:20>) · [ShopController.java:165](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/controller/ShopController.java:165>)

```json
{
  "id": "p1280",
  "cat": "marshal",
  "name": "元帅礼包",
  "icon": "🎖️",
  "rmb": 1280,
  "diamond": 15800,
  "desc": "钻石、资源与稀有道具组合礼包",
  "bonus": "豪华"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N099 · 钻石月卡（recharge.mk30）

立即获得300钻石，持续领取月卡福利

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前为模拟充值；档位数值和月卡/礼包承诺未附策划来源。

位置：[recharge.js:21](</Users/chenjuan/Documents/游戏/frontend/js/recharge.js:21>) · [ShopController.java:165](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/controller/ShopController.java:165>)

```json
{
  "id": "mk30",
  "cat": "monthly",
  "name": "钻石月卡",
  "icon": "💳",
  "rmb": 30,
  "diamond": 300,
  "desc": "立即获得300钻石，持续领取月卡福利"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N100 · 战备月卡（recharge.wk98）

立即获得980钻石，享受战备补给特权

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前为模拟充值；档位数值和月卡/礼包承诺未附策划来源。

位置：[recharge.js:22](</Users/chenjuan/Documents/游戏/frontend/js/recharge.js:22>) · [ShopController.java:165](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/controller/ShopController.java:165>)

```json
{
  "id": "wk98",
  "cat": "monthly",
  "name": "战备月卡",
  "icon": "🎖️",
  "rmb": 98,
  "diamond": 980,
  "desc": "立即获得980钻石，享受战备补给特权"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N101 · 建筑施工时间（rule.build-time）

实际施工含 30 × 2.4^当前等级；基础资源成本按 growth 的等级次幂计算。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[BuildService.java:188](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BuildService.java:188>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N102 · 建筑取消/拆除返还（rule.build-refund）

取消返还系数0.5，拆除返还系数0.3。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[BuildService.java:65](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BuildService.java:65>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N103 · 城市建筑槽位增长（rule.build-slots）

资源/军事分组槽位、基础数与上限在本地实现中定义。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[BuildService.java:840](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BuildService.java:840>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N104 · 战斗伤害、回合与初始距离（rule.battle-core）

伤害公式 atk² × count × cm × closeMul / (def × 10)；最多30回合；NPC/玩家初始距离2200/3000。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[BattleService.java:14](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BattleService.java:14>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N105 · 伤害分配、连击与整数击杀（rule.battle-allocation）

伤害分配、连击倍率和小数击杀概率；整组表达需结合独立平衡设计核对。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[BattleService.java:480](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BattleService.java:480>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N106 · 科技/军官/围墙战斗倍率（rule.battle-tech）

攻击/防御/生命与移动科技倍率，军官军事属性与围墙加成。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[BattleService.java:646](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BattleService.java:646>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N107 · 征兵队列、批量与生产速度（rule.recruit-capacity）

批量按建筑等级×10及科技计算，车道生产速度含等级×0.05。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[ArmyService.java:90](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/ArmyService.java:90>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N108 · 带兵上限公式（rule.army-cap）

floor((军衔基础 + 市政厅等级×1000) × (1+参谋部等级×0.10) × (1+指挥官等级×0.025))。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[ArmyService.java:470](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/ArmyService.java:470>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N109 · 解散部队返还（rule.army-refund）

钢铁成本×数量×0.3。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[ArmyService.java:256](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/ArmyService.java:256>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N110 · 科技费用与升级方式（rule.tech-cost）

科技费用按 costGrowth^当前等级计算；当前 upgrade 实现直接增加等级，未设置研究倒计时。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[TechService.java:184](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/TechService.java:184>) · [TechService.java:86](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/TechService.java:86>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N111 · 人口增长/流失、民心民怨和税收（rule.population-tax）

人口增长系数0.03，超额人口流失系数0.10；黄金时产=平民×税率×(1+市长学识/100)×2。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[TickService.java:170](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/TickService.java:170>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N112 · 资源仓储和建筑产能规则（rule.resource-cap）

实际资源上限另按对应资源建筑等级×200000计算；不能直接把 ResourceDef.baseCap 当作当前容量规则。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[TickService.java:455](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/TickService.java:455>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N113 · 军官培养与招募常量（rule.officer-limits）

属性上限255、等级上限100、军校刷新200黄金/1小时、候选7、每星招募80、解雇返还20、赏赐冷却30分钟、技能最多3。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[OfficerService.java:47](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/OfficerService.java:47>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N114 · 军官星级/属性/忠诚/薪资抽取（rule.officer-roll）

星级阈值0.5/0.78/0.92/0.985，联络等级偏移0.04；属性基础30+星级×12+随机0至9。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[OfficerService.java:950](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/OfficerService.java:950>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N115 · 装备层级与套装数值（rule.equipment-sets）

列兵/校官/元帅等级要求1/40/100；主属性5/15/30，副属性1/3/5；套装3/15/30，元帅另有全属性+5。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[OfficerEquipmentDef.java:22](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java:22>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N116 · 珠宝掉落分段与概率（rule.gem-drops）

按野地等级分段产生珠宝，概率及数量见原始实现摘录。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[MilitaryRankDef.java:103](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:103>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N117 · 军衔对应城市上限（rule.city-cap）

按军衔阶数决定主城/分城容量。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[MilitaryRankDef.java:65](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/MilitaryRankDef.java:65>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N118 · 备战/交战时间（rule.war-time）

6小时备战、24小时交战。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[WorldService.java:52](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/WorldService.java:52>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N119 · 采集耗时（rule.gather-time）

至少60秒；按采集量/(10×采集速度倍率)计算。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[MarchService.java:221](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/MarchService.java:221>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N120 · 资源道具实际发放（rule.resource-items）

黄金箱1000至5000随机；资源箱各500，其他礼包各按实现发放。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[DepotService.java:145](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/DepotService.java:145>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N121 · 护盾与行军令实际持续时间（rule.utility-items）

护盾8小时；行军令1小时、速度+50%；人口令增加500但不超过容量。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[DepotService.java:211](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/DepotService.java:211>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N122 · 伤兵保存与回收率（rule.medical）

保存7天；回收率=医疗科技等级×5+急救等级×3（百分数）。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[WoundedService.java:16](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/WoundedService.java:16>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N123 · 新玩家初始资源、部队与道具（rule.new-player）

初始赠送、基础城市与库存数值属于独立平衡设计需确认的部分。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[GameStateService.java:610](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/GameStateService.java:610>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N124 · 商城服务器价格表（rule.shop-price）

价格与充值档位在服务器另有定义；部分仅在后端销售的装备须一起审核。

**状态：来源不明；评估：待评估；建议次序：核对设计依据。**

代码提供当前计算方式，未提供最初取值的独立来源、平衡测试曲线或设计推导。注释中的“对应/复制JS”是项目内迁移线索，并非复制第三方的证据。

位置：[ShopController.java:165](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/controller/ShopController.java:165>)

原始实现摘录已收录在 inventory.json；按本条位置可核对完整公式。

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### N125 · 钻石前端定义（resources.diamond）

货币图标与容量占位字段

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

代码定义可定位；未发现外部原稿、创作过程或授权记录。

位置：[data.js:22](</Users/chenjuan/Documents/游戏/frontend/js/data.js:22>)

```json
{
  "name": "钻石",
  "icon": "💎",
  "baseCap": 0,
  "capGrowth": 0
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

## 附加文案

### C001 · 诺曼底战役（zone.normandy）

滩头登陆,撕开大西洋壁垒。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

历史地名/战役事实与具体措辞需区分。该战区仍随前端交付；进入关卡仅提示由后端处理。

位置：[data.js:123](</Users/chenjuan/Documents/游戏/frontend/js/data.js:123>)

```json
{
  "解锁": null
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C002 · 奥马哈滩头（no_1）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:128](</Users/chenjuan/Documents/游戏/frontend/js/data.js:128>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "no_1",
  "name": "奥马哈滩头",
  "enemy": {
    "infantry": 40
  },
  "reward": {
    "food": 200,
    "steel": 300,
    "oil": 150,
    "rare": 30,
    "gold": 50,
    "exp": 30
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C003 · 滨海小镇（no_2）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:129](</Users/chenjuan/Documents/游戏/frontend/js/data.js:129>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "no_2",
  "name": "滨海小镇",
  "enemy": {
    "infantry": 60,
    "motor": 15
  },
  "reward": {
    "food": 240,
    "steel": 360,
    "oil": 180,
    "rare": 40,
    "gold": 60,
    "exp": 50
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C004 · 桥头堡（no_3）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:130](</Users/chenjuan/Documents/游戏/frontend/js/data.js:130>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "no_3",
  "name": "桥头堡",
  "enemy": {
    "motor": 20,
    "armored": 10,
    "ltank": 6
  },
  "reward": {
    "food": 300,
    "steel": 460,
    "oil": 240,
    "rare": 60,
    "gold": 80,
    "exp": 80
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C005 · 敌军反扑（no_4）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:131](</Users/chenjuan/Documents/游戏/frontend/js/data.js:131>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "no_4",
  "name": "敌军反扑",
  "enemy": {
    "ltank": 14,
    "assault": 6,
    "fighter": 8
  },
  "reward": {
    "food": 380,
    "steel": 580,
    "oil": 320,
    "rare": 90,
    "gold": 110,
    "exp": 120
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C006 · 司令部突袭（no_5）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:132](</Users/chenjuan/Documents/游戏/frontend/js/data.js:132>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "no_5",
  "name": "司令部突袭",
  "enemy": {
    "htank": 8,
    "assault": 10,
    "bomber": 6,
    "destroyer": 4
  },
  "reward": {
    "food": 500,
    "steel": 800,
    "oil": 460,
    "rare": 140,
    "gold": 160,
    "exp": 180
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C007 · 北非战场（zone.africa）

黄沙漫天,坦克洪流对决。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

历史地名/战役事实与具体措辞需区分。该战区仍随前端交付；进入关卡仅提示由后端处理。

位置：[data.js:135](</Users/chenjuan/Documents/游戏/frontend/js/data.js:135>)

```json
{
  "解锁": {
    "zone": "normandy",
    "stage": 3
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C008 · 沙漠哨所（af_1）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:140](</Users/chenjuan/Documents/游戏/frontend/js/data.js:140>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "af_1",
  "name": "沙漠哨所",
  "enemy": {
    "motor": 30,
    "ltank": 10
  },
  "reward": {
    "food": 360,
    "steel": 560,
    "oil": 320,
    "rare": 80,
    "gold": 90,
    "exp": 100
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C009 · 补给线截击（af_2）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:141](</Users/chenjuan/Documents/游戏/frontend/js/data.js:141>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "af_2",
  "name": "补给线截击",
  "enemy": {
    "ltank": 16,
    "assault": 8
  },
  "reward": {
    "food": 440,
    "steel": 680,
    "oil": 400,
    "rare": 100,
    "gold": 120,
    "exp": 140
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C010 · 装甲对决（af_3）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:142](</Users/chenjuan/Documents/游戏/frontend/js/data.js:142>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "af_3",
  "name": "装甲对决",
  "enemy": {
    "htank": 10,
    "assault": 10,
    "fighter": 10
  },
  "reward": {
    "food": 540,
    "steel": 820,
    "oil": 500,
    "rare": 130,
    "gold": 160,
    "exp": 180
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C011 · 海岸炮台（af_4）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:143](</Users/chenjuan/Documents/游戏/frontend/js/data.js:143>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "af_4",
  "name": "海岸炮台",
  "enemy": {
    "assault": 12,
    "battleship": 3,
    "destroyer": 6
  },
  "reward": {
    "food": 660,
    "steel": 1000,
    "oil": 620,
    "rare": 170,
    "gold": 210,
    "exp": 230
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C012 · 隆美尔之影（af_5）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:144](</Users/chenjuan/Documents/游戏/frontend/js/data.js:144>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "af_5",
  "name": "隆美尔之影",
  "enemy": {
    "htank": 16,
    "rocket": 8,
    "bomber": 10,
    "sub": 4
  },
  "reward": {
    "food": 880,
    "steel": 1320,
    "oil": 820,
    "rare": 230,
    "gold": 300,
    "exp": 320
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C013 · 东线战场（zone.eastern）

凛冬将至,钢铁洪流碰撞。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

历史地名/战役事实与具体措辞需区分。该战区仍随前端交付；进入关卡仅提示由后端处理。

位置：[data.js:147](</Users/chenjuan/Documents/游戏/frontend/js/data.js:147>)

```json
{
  "解锁": {
    "zone": "africa",
    "stage": 3
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C014 · 边境遭遇（es_1）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:152](</Users/chenjuan/Documents/游戏/frontend/js/data.js:152>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "es_1",
  "name": "边境遭遇",
  "enemy": {
    "infantry": 100,
    "ltank": 20
  },
  "reward": {
    "food": 600,
    "steel": 900,
    "oil": 560,
    "rare": 140,
    "gold": 160,
    "exp": 180
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C015 · 钢铁洪流（es_2）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:153](</Users/chenjuan/Documents/游戏/frontend/js/data.js:153>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "es_2",
  "name": "钢铁洪流",
  "enemy": {
    "htank": 20,
    "assault": 12
  },
  "reward": {
    "food": 720,
    "steel": 1080,
    "oil": 680,
    "rare": 180,
    "gold": 200,
    "exp": 230
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C016 · 库尔斯克（es_3）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:154](</Users/chenjuan/Documents/游戏/frontend/js/data.js:154>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "es_3",
  "name": "库尔斯克",
  "enemy": {
    "htank": 28,
    "rocket": 10,
    "fighter": 16,
    "bomber": 8
  },
  "reward": {
    "food": 880,
    "steel": 1320,
    "oil": 840,
    "rare": 230,
    "gold": 260,
    "exp": 300
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C017 · 城市巷战（es_4）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:155](</Users/chenjuan/Documents/游戏/frontend/js/data.js:155>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "es_4",
  "name": "城市巷战",
  "enemy": {
    "infantry": 200,
    "motor": 60,
    "assault": 16,
    "special": 12
  },
  "reward": {
    "food": 1040,
    "steel": 1560,
    "oil": 1000,
    "rare": 290,
    "gold": 330,
    "exp": 380
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C018 · 柏林外围（es_5）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:156](</Users/chenjuan/Documents/游戏/frontend/js/data.js:156>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "es_5",
  "name": "柏林外围",
  "enemy": {
    "htank": 36,
    "rocket": 16,
    "fighter": 20,
    "bomber": 12,
    "battleship": 4,
    "special": 20
  },
  "reward": {
    "food": 1400,
    "steel": 2100,
    "oil": 1340,
    "rare": 400,
    "gold": 480,
    "exp": 520
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C019 · 太平洋战场（zone.pacific）

海天之间,谁主沉浮。

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

历史地名/战役事实与具体措辞需区分。该战区仍随前端交付；进入关卡仅提示由后端处理。

位置：[data.js:159](</Users/chenjuan/Documents/游戏/frontend/js/data.js:159>)

```json
{
  "解锁": {
    "zone": "eastern",
    "stage": 3
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C020 · 环礁侦察（pa_1）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:164](</Users/chenjuan/Documents/游戏/frontend/js/data.js:164>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "pa_1",
  "name": "环礁侦察",
  "enemy": {
    "scout": 10,
    "special": 8,
    "fighter": 16,
    "sub": 4
  },
  "reward": {
    "food": 800,
    "steel": 1200,
    "oil": 800,
    "rare": 200,
    "gold": 220,
    "exp": 220
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C021 · 航母编队（pa_2）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:165](</Users/chenjuan/Documents/游戏/frontend/js/data.js:165>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "pa_2",
  "name": "航母编队",
  "enemy": {
    "fighter": 24,
    "bomber": 12,
    "carrier": 1,
    "destroyer": 6,
    "special": 12
  },
  "reward": {
    "food": 1000,
    "steel": 1500,
    "oil": 1000,
    "rare": 260,
    "gold": 300,
    "exp": 300
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C022 · 夜袭港口（pa_3）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:166](</Users/chenjuan/Documents/游戏/frontend/js/data.js:166>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "pa_3",
  "name": "夜袭港口",
  "enemy": {
    "sub": 12,
    "battleship": 4,
    "destroyer": 8,
    "special": 16
  },
  "reward": {
    "food": 1200,
    "steel": 1800,
    "oil": 1200,
    "rare": 320,
    "gold": 380,
    "exp": 380
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C023 · 决战中途（pa_4）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:167](</Users/chenjuan/Documents/游戏/frontend/js/data.js:167>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "pa_4",
  "name": "决战中途",
  "enemy": {
    "carrier": 3,
    "fighter": 40,
    "bomber": 20,
    "battleship": 6,
    "special": 24
  },
  "reward": {
    "food": 1500,
    "steel": 2250,
    "oil": 1500,
    "rare": 420,
    "gold": 500,
    "exp": 480
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C024 · 东京湾（pa_5）

关卡名称、敌军编成和通关奖励

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

当前残留战役数据；未发现关卡命名及编排的创作依据。历史名词本身不证明复制。

位置：[data.js:168](</Users/chenjuan/Documents/游戏/frontend/js/data.js:168>) · [map.js:30](</Users/chenjuan/Documents/游戏/frontend/js/map.js:30>)

```json
{
  "id": "pa_5",
  "name": "东京湾",
  "enemy": {
    "carrier": 6,
    "battleship": 10,
    "fighter": 60,
    "bomber": 30,
    "special": 40
  },
  "reward": {
    "food": 2200,
    "steel": 3300,
    "oil": 2200,
    "rare": 640,
    "gold": 800,
    "exp": 700
  }
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C025 · 隆美尔传记（bio.1）

绰号"沙漠之狐"。二战期间率领非洲军团在北非战场屡创英军，以机动战术闻名于世。后因卷入刺杀希特勒事件被迫服毒自尽。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。隆美尔在列表中重复出现。

位置：[HistoricalOfficers.java:24](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:24>) · [data.js:249](</Users/chenjuan/Documents/游戏/frontend/js/data.js:249>)

```json
{
  "name": "隆美尔",
  "fullName": "埃尔温·隆美尔",
  "birth": 1891,
  "death": 1944,
  "nation": "德国",
  "rank": "陆军元帅"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C026 · 朱可夫传记（bio.2）

二战苏军最高统帅部副统帅。指挥莫斯科保卫战、斯大林格勒战役、库尔斯克会战和柏林战役，被誉为"胜利元帅"，是击败纳粹德国的关键人物。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。

位置：[HistoricalOfficers.java:26](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:26>) · [data.js:250](</Users/chenjuan/Documents/游戏/frontend/js/data.js:250>)

```json
{
  "name": "朱可夫",
  "fullName": "格奥尔基·朱可夫",
  "birth": 1896,
  "death": 1974,
  "nation": "苏联",
  "rank": "苏联元帅"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C027 · 巴顿传记（bio.3）

美国第三集团军司令。以勇猛果敢的装甲战术著称，率部横扫法国、德国，是盟军推进最快的将领。战后因车祸殉职。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。

位置：[HistoricalOfficers.java:28](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:28>) · [data.js:251](</Users/chenjuan/Documents/游戏/frontend/js/data.js:251>)

```json
{
  "name": "巴顿",
  "fullName": "乔治·巴顿",
  "birth": 1885,
  "death": 1945,
  "nation": "美国",
  "rank": "四星上将"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C028 · 曼施坦因传记（bio.4）

二战德军最杰出的战略家之一。策划了入侵法国的"黄色方案"（曼施坦因计划），在东线指挥克里米亚战役和哈尔科夫反击战。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。

位置：[HistoricalOfficers.java:30](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:30>) · [data.js:252](</Users/chenjuan/Documents/游戏/frontend/js/data.js:252>)

```json
{
  "name": "曼施坦因",
  "fullName": "埃里希·冯·曼施坦因",
  "birth": 1887,
  "death": 1973,
  "nation": "德国",
  "rank": "陆军元帅"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C029 · 古德里安传记（bio.5）

"闪击战之父"，德国装甲兵创始人。著有《注意！坦克》，奠定了现代装甲战理论。率部在波兰和法国战役中大放异彩。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。

位置：[HistoricalOfficers.java:32](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:32>) · [data.js:253](</Users/chenjuan/Documents/游戏/frontend/js/data.js:253>)

```json
{
  "name": "古德里安",
  "fullName": "海因茨·古德里安",
  "birth": 1888,
  "death": 1954,
  "nation": "德国",
  "rank": "上将"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C030 · 麦克阿瑟传记（bio.6）

太平洋战区盟军最高司令。主导"蛙跳战术"逐岛反攻，战后主持日本重建。朝鲜战争中指挥仁川登陆，名垂青史。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。

位置：[HistoricalOfficers.java:34](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:34>) · [data.js:254](</Users/chenjuan/Documents/游戏/frontend/js/data.js:254>)

```json
{
  "name": "麦克阿瑟",
  "fullName": "道格拉斯·麦克阿瑟",
  "birth": 1880,
  "death": 1964,
  "nation": "美国",
  "rank": "五星上将"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C031 · 蒙哥马利传记（bio.7）

英国第八集团军司令。在阿拉曼战役中击败隆美尔的非洲军团，扭转北非战局。后参与诺曼底登陆和欧洲西北部战役。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。

位置：[HistoricalOfficers.java:36](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:36>) · [data.js:255](</Users/chenjuan/Documents/游戏/frontend/js/data.js:255>)

```json
{
  "name": "蒙哥马利",
  "fullName": "伯纳德·蒙哥马利",
  "birth": 1887,
  "death": 1976,
  "nation": "英国",
  "rank": "陆军元帅"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C032 · 山本五十六传记（bio.8）

日本联合舰队司令长官。策划偷袭珍珠港，重创美国太平洋舰队。1943年座机被美军击落殒命，是日本海军的灵魂人物。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。

位置：[HistoricalOfficers.java:38](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:38>) · [data.js:256](</Users/chenjuan/Documents/游戏/frontend/js/data.js:256>)

```json
{
  "name": "山本五十六",
  "fullName": "山本五十六",
  "birth": 1884,
  "death": 1943,
  "nation": "日本",
  "rank": "海军大将"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C033 · 邓尼茨传记（bio.9）

德国海军潜艇部队创始人，首创"狼群战术"。希特勒自杀后曾短暂担任德国总统，主导投降。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。

位置：[HistoricalOfficers.java:40](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:40>) · [data.js:257](</Users/chenjuan/Documents/游戏/frontend/js/data.js:257>)

```json
{
  "name": "邓尼茨",
  "fullName": "卡尔·邓尼茨",
  "birth": 1891,
  "death": 1980,
  "nation": "德国",
  "rank": "海军元帅"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C034 · 艾森豪威尔传记（bio.10）

盟军远征军最高司令。统筹策划诺曼底登陆，协调英美联军在欧洲战场的全局战略。战后当选美国第34任总统。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。

位置：[HistoricalOfficers.java:42](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:42>) · [data.js:258](</Users/chenjuan/Documents/游戏/frontend/js/data.js:258>)

```json
{
  "name": "艾森豪威尔",
  "fullName": "德怀特·艾森豪威尔",
  "birth": 1890,
  "death": 1969,
  "nation": "美国",
  "rank": "五星上将"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C035 · 崔可夫传记（bio.11）

第62集团军司令。在斯大林格勒战役中死守城市，与德军逐屋争夺，被誉为"斯大林格勒的救星"。后率部攻入柏林。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。

位置：[HistoricalOfficers.java:44](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:44>) · [data.js:259](</Users/chenjuan/Documents/游戏/frontend/js/data.js:259>)

```json
{
  "name": "崔可夫",
  "fullName": "瓦西里·崔可夫",
  "birth": 1900,
  "death": 1982,
  "nation": "苏联",
  "rank": "苏联元帅"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C036 · 莫德尔传记（bio.12）

德军"防御之狮"。在东线多次组织成功防御，延迟苏军推进。1945年鲁尔包围战中兵败自尽，希特勒称之为"最忠诚的元帅"。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。

位置：[HistoricalOfficers.java:46](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:46>) · [data.js:260](</Users/chenjuan/Documents/游戏/frontend/js/data.js:260>)

```json
{
  "name": "莫德尔",
  "fullName": "瓦尔特·莫德尔",
  "birth": 1891,
  "death": 1945,
  "nation": "德国",
  "rank": "陆军元帅"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C037 · 尼米兹传记（bio.13）

美国太平洋舰队总司令。中途岛海战中以少胜多击沉四艘日军航母，扭转太平洋战局。潜艇出身的他被誉为"海上骑士"。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。

位置：[HistoricalOfficers.java:48](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:48>) · [data.js:261](</Users/chenjuan/Documents/游戏/frontend/js/data.js:261>)

```json
{
  "name": "尼米兹",
  "fullName": "切斯特·尼米兹",
  "birth": 1885,
  "death": 1966,
  "nation": "美国",
  "rank": "五星上将"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C038 · 华西列夫斯基传记（bio.14）

苏军总参谋长。参与策划莫斯科反攻、斯大林格勒合围和库尔斯克会战等重大战役，是苏军最高统帅部的核心智囊。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。

位置：[HistoricalOfficers.java:50](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:50>) · [data.js:262](</Users/chenjuan/Documents/游戏/frontend/js/data.js:262>)

```json
{
  "name": "华西列夫斯基",
  "fullName": "亚历山大·华西列夫斯基",
  "birth": 1895,
  "death": 1977,
  "nation": "苏联",
  "rank": "苏联元帅"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C039 · 隆美尔传记（bio.15）

绰号"沙漠之狐"。二战期间率领非洲军团在北非战场屡创英军，以机动战术闻名于世。后因卷入刺杀希特勒事件被迫服毒自尽。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

没有书目、URL、作者或改写记录；史实不等于该段措辞可自由复制。隆美尔在列表中重复出现。

位置：[HistoricalOfficers.java:52](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/HistoricalOfficers.java:52>) · [data.js:263](</Users/chenjuan/Documents/游戏/frontend/js/data.js:263>)

```json
{
  "name": "隆美尔",
  "fullName": "埃尔温·隆美尔",
  "birth": 1891,
  "death": 1944,
  "nation": "德国",
  "rank": "陆军元帅"
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C040 · 隆美尔（officerNames.1）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:105](</Users/chenjuan/Documents/游戏/frontend/js/data.js:105>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C041 · 朱可夫（officerNames.2）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:105](</Users/chenjuan/Documents/游戏/frontend/js/data.js:105>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C042 · 巴顿（officerNames.3）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:105](</Users/chenjuan/Documents/游戏/frontend/js/data.js:105>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C043 · 蒙哥马利（officerNames.4）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:105](</Users/chenjuan/Documents/游戏/frontend/js/data.js:105>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C044 · 古德里安（officerNames.5）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:105](</Users/chenjuan/Documents/游戏/frontend/js/data.js:105>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C045 · 曼施坦因（officerNames.6）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:105](</Users/chenjuan/Documents/游戏/frontend/js/data.js:105>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C046 · 麦克阿瑟（officerNames.7）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:105](</Users/chenjuan/Documents/游戏/frontend/js/data.js:105>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C047 · 尼米兹（officerNames.8）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:105](</Users/chenjuan/Documents/游戏/frontend/js/data.js:105>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C048 · 山本五十六（officerNames.9）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:106](</Users/chenjuan/Documents/游戏/frontend/js/data.js:106>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C049 · 邓尼茨（officerNames.10）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:106](</Users/chenjuan/Documents/游戏/frontend/js/data.js:106>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C050 · 崔可夫（officerNames.11）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:106](</Users/chenjuan/Documents/游戏/frontend/js/data.js:106>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C051 · 艾森豪威尔（officerNames.12）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:106](</Users/chenjuan/Documents/游戏/frontend/js/data.js:106>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C052 · 布雷德利（officerNames.13）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:106](</Users/chenjuan/Documents/游戏/frontend/js/data.js:106>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C053 · 莫德尔（officerNames.14）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:106](</Users/chenjuan/Documents/游戏/frontend/js/data.js:106>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C054 · 龙德施泰特（officerNames.15）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:106](</Users/chenjuan/Documents/游戏/frontend/js/data.js:106>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C055 · 华西列夫斯基（officerNames.16）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:106](</Users/chenjuan/Documents/游戏/frontend/js/data.js:106>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C056 · 海因里希（officerNames.17）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:107](</Users/chenjuan/Documents/游戏/frontend/js/data.js:107>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C057 · 克卢格（officerNames.18）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:107](</Users/chenjuan/Documents/游戏/frontend/js/data.js:107>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C058 · 霍特（officerNames.19）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:107](</Users/chenjuan/Documents/游戏/frontend/js/data.js:107>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C059 · 切尔尼亚霍夫斯基（officerNames.20）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:107](</Users/chenjuan/Documents/游戏/frontend/js/data.js:107>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C060 · 梁思成（officerNames.21）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:107](</Users/chenjuan/Documents/游戏/frontend/js/data.js:107>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C061 · 施瓦茨科普夫（officerNames.22）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:107](</Users/chenjuan/Documents/游戏/frontend/js/data.js:107>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C062 · 李宗仁（officerNames.23）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:107](</Users/chenjuan/Documents/游戏/frontend/js/data.js:107>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C063 · 孙立人（officerNames.24）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:107](</Users/chenjuan/Documents/游戏/frontend/js/data.js:107>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C064 · 列兵（officerTitles.1）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:109](</Users/chenjuan/Documents/游戏/frontend/js/data.js:109>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C065 · 上士（officerTitles.2）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:109](</Users/chenjuan/Documents/游戏/frontend/js/data.js:109>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C066 · 少尉（officerTitles.3）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:109](</Users/chenjuan/Documents/游戏/frontend/js/data.js:109>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C067 · 中尉（officerTitles.4）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:109](</Users/chenjuan/Documents/游戏/frontend/js/data.js:109>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C068 · 上尉（officerTitles.5）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:109](</Users/chenjuan/Documents/游戏/frontend/js/data.js:109>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C069 · 少校（officerTitles.6）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:109](</Users/chenjuan/Documents/游戏/frontend/js/data.js:109>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C070 · 中校（officerTitles.7）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:109](</Users/chenjuan/Documents/游戏/frontend/js/data.js:109>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C071 · 上校（officerTitles.8）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:109](</Users/chenjuan/Documents/游戏/frontend/js/data.js:109>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C072 · 准将（officerTitles.9）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:109](</Users/chenjuan/Documents/游戏/frontend/js/data.js:109>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C073 · 少将（officerTitles.10）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:109](</Users/chenjuan/Documents/游戏/frontend/js/data.js:109>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C074 · 中将（officerTitles.11）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:109](</Users/chenjuan/Documents/游戏/frontend/js/data.js:109>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C075 · 上将（officerTitles.12）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:109](</Users/chenjuan/Documents/游戏/frontend/js/data.js:109>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C076 · 流寇营地（banditNames.1）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:177](</Users/chenjuan/Documents/游戏/frontend/js/data.js:177>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C077 · 残兵游勇（banditNames.2）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:177](</Users/chenjuan/Documents/游戏/frontend/js/data.js:177>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C078 · 马匪哨所（banditNames.3）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:177](</Users/chenjuan/Documents/游戏/frontend/js/data.js:177>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C079 · 叛军据点（banditNames.4）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:177](</Users/chenjuan/Documents/游戏/frontend/js/data.js:177>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C080 · 山贼窝点（banditNames.5）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:177](</Users/chenjuan/Documents/游戏/frontend/js/data.js:177>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C081 · 溃兵残部（banditNames.6）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:177](</Users/chenjuan/Documents/游戏/frontend/js/data.js:177>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C082 · 武装走私队（banditNames.7）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:177](</Users/chenjuan/Documents/游戏/frontend/js/data.js:177>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C083 · 雇佣兵营（banditNames.8）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:177](</Users/chenjuan/Documents/游戏/frontend/js/data.js:177>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C084 · 汉堡（npcCityNames.1）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C085 · 华沙（npcCityNames.2）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C086 · 维也纳（npcCityNames.3）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C087 · 布鲁塞尔（npcCityNames.4）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C088 · 阿姆斯特丹（npcCityNames.5）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C089 · 斯德哥尔摩（npcCityNames.6）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C090 · 奥斯陆（npcCityNames.7）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C091 · 哥本哈根（npcCityNames.8）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C092 · 布拉格（npcCityNames.9）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C093 · 布达佩斯（npcCityNames.10）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C094 · 贝尔格莱德（npcCityNames.11）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C095 · 索菲亚（npcCityNames.12）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C096 · 布加勒斯特（npcCityNames.13）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C097 · 赫尔辛基（npcCityNames.14）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C098 · 都柏林（npcCityNames.15）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C099 · 里斯本（npcCityNames.16）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:188](</Users/chenjuan/Documents/游戏/frontend/js/data.js:188>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C100 · 钢铁洪流（playerCityNames.1）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:153](</Users/chenjuan/Documents/游戏/frontend/js/data.js:153>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C101 · 虎式之巢（playerCityNames.2）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:189](</Users/chenjuan/Documents/游戏/frontend/js/data.js:189>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C102 · 苍穹之眼（playerCityNames.3）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:189](</Users/chenjuan/Documents/游戏/frontend/js/data.js:189>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C103 · 深海利剑（playerCityNames.4）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:189](</Users/chenjuan/Documents/游戏/frontend/js/data.js:189>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C104 · 雷霆要塞（playerCityNames.5）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:189](</Users/chenjuan/Documents/游戏/frontend/js/data.js:189>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C105 · 孤狼营地（playerCityNames.6）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:189](</Users/chenjuan/Documents/游戏/frontend/js/data.js:189>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C106 · 铁血堡垒（playerCityNames.7）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:189](</Users/chenjuan/Documents/游戏/frontend/js/data.js:189>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C107 · 风暴前线（playerCityNames.8）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:189](</Users/chenjuan/Documents/游戏/frontend/js/data.js:189>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C108 · 暗夜哨站（playerCityNames.9）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:189](</Users/chenjuan/Documents/游戏/frontend/js/data.js:189>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C109 · 烈焰军团（playerCityNames.10）

姓名、军衔或地点候选池

**状态：通用名词/史实，编排来源待核；评估：待评估；建议次序：低优先核对。**

单独的历史姓名、地名或军衔不等于复制作品；名单选择、虚构名称及关联表达缺少来源说明。

位置：[data.js:189](</Users/chenjuan/Documents/游戏/frontend/js/data.js:189>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C110 · 通用军官简介1（generic-bio.1）

出身于军人世家，自幼熟读兵法，立志报效国家。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

通用军官长句模板；注释只追溯到项目内JS，未附外部原文或原创记录。

位置：[OfficerService.java:68](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/OfficerService.java:68>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C111 · 通用军官简介2（generic-bio.2）

行伍出身，从基层一步步摸爬滚打，积累了丰富的实战经验。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

通用军官长句模板；注释只追溯到项目内JS，未附外部原文或原创记录。

位置：[OfficerService.java:69](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/OfficerService.java:69>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C112 · 通用军官简介3（generic-bio.3）

毕业于军事学院，擅长战术分析与兵力调度，是不可多得的将才。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

通用军官长句模板；注释只追溯到项目内JS，未附外部原文或原创记录。

位置：[OfficerService.java:70](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/OfficerService.java:70>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C113 · 通用军官简介4（generic-bio.4）

身经百战的老将，曾在多次战役中力挽狂澜，威名远扬。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

通用军官长句模板；注释只追溯到项目内JS，未附外部原文或原创记录。

位置：[OfficerService.java:71](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/OfficerService.java:71>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### C114 · 通用军官简介5（generic-bio.5）

年轻有为的军事天才，以大胆果断的指挥风格著称。

**状态：来源不明；评估：待评估；建议次序：优先评估长文案。**

通用军官长句模板；注释只追溯到项目内JS，未附外部原文或原创记录。

位置：[OfficerService.java:72](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/OfficerService.java:72>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

## 头像

### V001 · 陆军上将（img/avatars/commander-1.svg）

预设头像

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[commander-1.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/avatars/commander-1.svg:1>) · [main-view.js:874](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:874>)

```json
{
  "sha256": "9a6f45e1a67bb5f1e2219fecc00cb6875ddbb73ec1ce0d0ba678b40b2e4375d7",
  "引用数": 1,
  "注释摘要": [
    " Background ",
    " Body / Uniform ",
    " Shirt & Tie ",
    " Collar & Lapels ",
    " Gold Epaulets / Stars "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### V002 · 装甲指挥官（img/avatars/commander-2.svg）

预设头像

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[commander-2.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/avatars/commander-2.svg:1>) · [main-view.js:875](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:875>)

```json
{
  "sha256": "5a4c112d592b4f6ad71e8c3545abce2bdf3c9ef04cddb43afe870fed0c62f2d9",
  "引用数": 1,
  "注释摘要": [
    " Background ",
    " Jacket ",
    " Tanker Skull / Cross Badge ",
    " Head & Ears ",
    " Eyes "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### V003 · 王牌飞行员（img/avatars/commander-3.svg）

预设头像

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[commander-3.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/avatars/commander-3.svg:1>) · [main-view.js:876](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:876>)

```json
{
  "sha256": "bac0b6f555e0597587a88c3de1ebd0bf940a2c32b56ec09966dc50680bdd64ac",
  "引用数": 1,
  "注释摘要": [
    " Background ",
    " Brown Leather Flight Jacket ",
    " White Aviator Silk Scarf ",
    " Head & Chin ",
    " Leather Flight Helmet & Ear Flaps "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### V004 · 海军提督（img/avatars/commander-4.svg）

预设头像

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[commander-4.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/avatars/commander-4.svg:1>) · [main-view.js:877](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:877>)

```json
{
  "sha256": "5726a990c00239faea72cb0f1e865dcb7a50223f7cd06043a81768298b35f6ce",
  "引用数": 1,
  "注释摘要": [
    " Background ",
    " Navy Double-breasted Uniform ",
    " Gold Double-breasted Buttons ",
    " Head ",
    " Eyes "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### V005 · 战术参谋长（img/avatars/commander-5.svg）

预设头像

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[commander-5.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/avatars/commander-5.svg:1>) · [main-view.js:878](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:878>)

```json
{
  "sha256": "250494e856b9a54f112e17b79f817295126eff1a5b153d222b9887bd5cb6e93a",
  "引用数": 1,
  "注释摘要": [
    " Background ",
    " Hair (Back) ",
    " Uniform ",
    " Tie & Shirt ",
    " Head & Neck "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### V006 · 特战先锋（img/avatars/commander-6.svg）

预设头像

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[commander-6.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/avatars/commander-6.svg:1>) · [main-view.js:879](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:879>)

```json
{
  "sha256": "7c3533d3f0df4b1cbea231db6e8fbae5a42a182671d8f361b4f70b3f9fa1bfbf",
  "引用数": 1,
  "注释摘要": [
    " Background ",
    " Tactical Rig & Camo Vest ",
    " Webbing straps ",
    " Head & Chin ",
    " Camouflage Warpaint on Cheeks "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### V007 · 最高元帅（img/avatars/commander-7.svg）

预设头像

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[commander-7.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/avatars/commander-7.svg:1>) · [main-view.js:880](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:880>)

```json
{
  "sha256": "90d82c38a991c18c3e1faed072ba10723b73686f079b7223b562e628e5237cf4",
  "引用数": 1,
  "注释摘要": [
    " Background ",
    " Grand Marshal Uniform ",
    " Gold Marshal Sash ",
    " Red Collar Tabs with Gold Stars ",
    " Head & Chin "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### V008 · 萌系指挥官（img/avatars/commander-8.svg）

预设头像

**状态：来源待核；评估：待评估；建议次序：优先核对创作来源。**

历史描述已按用户要求删减；创作来源与权利归属仍待评估。

位置：[commander-8.svg:4](</Users/chenjuan/Documents/游戏/frontend/img/avatars/commander-8.svg:4>) · [main-view.js:873](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:873>) · [main-view.js:892](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:892>) · [core.js:490](</Users/chenjuan/Documents/游戏/frontend/js/core.js:490>) · [core.js:497](</Users/chenjuan/Documents/游戏/frontend/js/core.js:497>) · [player-profile.js:69](</Users/chenjuan/Documents/游戏/frontend/js/player-profile.js:69>)

```json
{
  "sha256": "d901d9536dbc2607389acb92c986cdfa4fcf888eecb800f23b8cb3e60557f432",
  "引用数": 7,
  "注释摘要": [
    " Clean white/soft background ",
    " Doodle Frame Corners ",
    " Doodle Desk ",
    " Tea / Coffee Cup with Steam ",
    " Cute Stickman Head "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

## 图标

### A001 · 军校（img/buildings/academy.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[academy.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/academy.svg:1>) · [build.js:1273](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1273>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "efcd4d883b1afec4fde2f95e9a24ea1b64d39c2dfc412a3096057b2a9be5a671",
  "引用数": 2,
  "注释摘要": [
    " Academy Classical Facade Background ",
    " Steps ",
    " Pillars (4) ",
    " Military Academy Officer Crest (Center Foreground Shield) ",
    " Shield "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A002 · 机场（img/buildings/airport.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[airport.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/airport.svg:1>) · [build.js:1271](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1271>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "9044815f70dacad302f6598a5356d519d04412495077c0dc498a3bb4c988707c",
  "引用数": 2,
  "注释摘要": [
    " Ground Runway / Tarmac Surface ",
    " Runway Centerline Dashes ",
    " Airfield Control Tower (Left) ",
    " Tower Base ",
    " Windows on tower column "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A003 · 停机坪（img/buildings/apron.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[apron.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/apron.svg:1>) · [build.js:1278](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1278>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "4067f8251415c92e570518d124bacb08d038f4dd100bfc7fcb422544beb3d1b5",
  "引用数": 2,
  "注释摘要": [
    " Perspective Tarmac Apron Octagon / Pad ",
    " Tarmac Expansion Joints / Seams ",
    " Outer Yellow Border Ring / Box ",
    " Bold Landing \"H\" Mark with Tactical Stencil ",
    " Fighter Aircraft Silhouette overlay (top right heading) "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A004 · 市政厅（img/buildings/command.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[command.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/command.svg:1>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "6cefbd6116fe784d16794849d80ef5f04d5e6d4f7a33bbb6a7061f66a35ffefc",
  "引用数": 2,
  "注释摘要": [
    " Flagpole & Red Commander Pennant ",
    " Pediment / Classical Triangular Roof ",
    " Gold Star Crest in Pediment ",
    " Cornice Beam ",
    " Main Building Body & Pillars "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A005 · 仓库（img/buildings/depot.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[depot.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/depot.svg:1>) · [build.js:1280](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1280>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "c73b8319960d361c07e26d6303704db02766ea00db35073b6d3e4975ff3f3f82",
  "引用数": 2,
  "注释摘要": [
    " Warehouse Main Structure ",
    " Corrugated roof lines ",
    " Concrete Base Foundation ",
    " Metal Rolling Shutter Cargo Door (Left) ",
    " Stacked Wooden Munitions Crate 1 (Bottom Right) "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A006 · 交易所（img/buildings/exchange.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[exchange.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/exchange.svg:1>) · [build.js:1282](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1282>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "cf73fa771771eff9dd5e7ca557fab25d731b3ecf9058795148512ce96330fe0e",
  "引用数": 2,
  "注释摘要": [
    " Exchange Pillar / Pedestal Base ",
    " Central Brass Balance Mast ",
    " Tilted Brass Beam ",
    " Left Scale Pan (Higher - Ammo / Munitions) ",
    " Ammo projectile / ingot in left pan "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A007 · 军工厂（img/buildings/factory.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[factory.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/factory.svg:1>) · [build.js:1268](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1268>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "2d17c3aee87cde49bbb7b61dd3fdd5ee864576273274ea67ec5c75f07a76c430",
  "引用数": 2,
  "注释摘要": [
    " Industrial Smoke Plumes (Thicker & Visually Clearer) ",
    " Twin Heavy Brick Smokestacks ",
    " Sawtooth Factory Roof & Main Hall ",
    " Sawtooth points reaching up to y=7 and y=11 ",
    " Sawtooth Slanted Skylights (Sky reflection) "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A008 · 重工厂（img/buildings/heavyfactory.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[heavyfactory.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/heavyfactory.svg:1>) · [build.js:1270](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1270>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "59045f4287ff019e6455e1a0bc107586adce1d12618f46f813f8a5919f96cc12",
  "引用数": 2,
  "注释摘要": [
    " Heavy Heavy Industrial Structure ",
    " Blast Furnace Chimney (Right) ",
    " Molten Fire Glow at Chimney Top ",
    " Foundry Main Structure ",
    " Reinforced Girders / Armor Truss on Roof "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A009 · 民居（img/buildings/house.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[house.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/house.svg:1>) · [build.js:1267](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1267>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "a682c4cb69fcd2a22af35b80b5f4ea64196f5b5c077b057425462ae44498efa8",
  "引用数": 2,
  "注释摘要": [
    " Heavy Concrete Blast Berm Foundation ",
    " Large Military Quonset Barracks Main Arched Body ",
    " Barracks Corrugated Seams ",
    " Front Semi-Circular End Facade ",
    " Troop Entrance Doorway with Steel Frame "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A010 · 科研中心（img/buildings/lab.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[lab.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/lab.svg:1>) · [build.js:1275](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1275>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "d8a7990791d5d1a1b0956b2c773b845281111babe7b4082abd99c17ed6ebc648",
  "引用数": 2,
  "注释摘要": [
    " Fortified Concrete Lab Base ",
    " Base status LEDs ",
    " Science Flask / Atomic Reactor Silhouette ",
    " Flask neck ",
    " Flask body "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A011 · 联络中心（img/buildings/liaison.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[liaison.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/liaison.svg:1>) · [build.js:1279](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1279>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "7066fc93f48000bdbf11b4231532ccc8341a627565bd7d4a9ce4a1d1a6b4caea",
  "引用数": 2,
  "注释摘要": [
    " Fortified Communications Bunker ",
    " Comm window slit with green phosphorescent light ",
    " High-Gain Lattice Antenna Mast (Center) ",
    " Antenna Crossbars & Tip ",
    " Radio Frequency Broadcast Waves / Lightning Signals "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A012 · 轻工厂（img/buildings/lightfactory.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[lightfactory.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/lightfactory.svg:1>) · [build.js:1269](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1269>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "e4778700fe7a12c8c924f0ae059466f7acb46fe5739fe7e732953fa1378f0505",
  "引用数": 2,
  "注释摘要": [
    " Industrial Shed Roof ",
    " Light Tank Silhouette emerging from workshop ",
    " Crane Rail Hook ",
    " Light Tank Front / Silhouette inside hangar ",
    " Tank Turret & Gun Barrel "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A013 · 港口（img/buildings/port.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[port.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/port.svg:1>) · [build.js:1272](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1272>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "39b01ec1d656855724bba54a4f3a4be9631264008502921d987c59d10f82bc0e",
  "引用数": 2,
  "注释摘要": [
    " Ocean Water Base ",
    " Gentle Wave Lines ",
    " Concrete Pier / Quay Structure (Left to Center) ",
    " Quay bollards ",
    " Dockside Heavy Crane (Left) "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A014 · 雷达站（img/buildings/radar.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[radar.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/radar.svg:1>) · [build.js:1276](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1276>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "9d6482f17a26d31d18ca3374dedcbca0708a5c7250184ac0e155beb02daf9e92",
  "引用数": 2,
  "注释摘要": [
    " Fortified Concrete Bunker Base ",
    " Steel Lattice Mast / Pedestal ",
    " Turret Mount Platform ",
    " Parabolic Radar Dish (Angled up and to the right) ",
    " Feed Horn Boom & Subreflector "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A015 · 参谋部（img/buildings/staff.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[staff.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/staff.svg:1>) · [build.js:1274](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1274>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "2b2676347000099eff281eba8b87d9f5a9945524038c2c864d49ee0570e8bcf3",
  "引用数": 2,
  "注释摘要": [
    " Crossed Golden Field Marshal Batons (Background) ",
    " Tactical Command Shield ",
    " Laurel Wreath Trim Inside Shield ",
    " 4 Stars of High General Command ",
    " Top Center Big Star "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A016 · 运输站（img/buildings/transit.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[transit.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/transit.svg:1>) · [build.js:1281](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1281>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "873a6f13e4ed5915af317509ee5857bf33a8493d6f2f805a1f4ccbfee648c661",
  "引用数": 2,
  "注释摘要": [
    " Ground / Road with Center Dash ",
    " Transport Bay Canopy / Shelter (Top/Back) ",
    " Military Cargo Truck Silhouette (Side profile facing right) ",
    " Cargo Bed / Canvas Cover ",
    " Canvas rib seams "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A017 · 围墙（img/buildings/wall.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：未提交文件，无入库记录。

位置：[wall.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/buildings/wall.svg:1>) · [build.js:1277](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1277>) · [build.js:1266](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1266>)

```json
{
  "sha256": "510c42b3e5025aade48f96f207c32992c1ccba450a6df44202f98d36e627d54d",
  "引用数": 2,
  "注释摘要": [
    " Ground Line & Barbed Wire Base ",
    " Main Wall Rampart ",
    " Wall crenellations / battlements (center wall) ",
    " Left Flanking Bastion Tower ",
    " Left embrasure / arrow slit "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A018 · forest（img/forest.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：0e2e54b 2026-09-12。

位置：[forest.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/forest.svg:1>) · [data.js:113](</Users/chenjuan/Documents/游戏/frontend/js/data.js:113>)

```json
{
  "sha256": "a9e68f319bc771446c3ed6544739b524ee082870b6dc843f6664bce22525e71d",
  "引用数": 1,
  "注释摘要": [
    " Sky background ",
    " Ground ",
    " Grass tufts ",
    " Background tree (smaller) ",
    " Middle tree "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A019 · gold（img/gold.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：0e2e54b 2026-09-12。

位置：[gold.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/gold.svg:1>) · [data.js:21](</Users/chenjuan/Documents/游戏/frontend/js/data.js:21>)

```json
{
  "sha256": "62067b26ccb9795914c1d536ccb8639643868c85e31f626f340711548d3f80a1",
  "引用数": 1,
  "注释摘要": [
    " Drop shadow ",
    " Main ingot body (rounded rectangle, slightly tilted) ",
    " Top edge highlight ",
    " Engraved geometric patterns ",
    " Left swirl pattern "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A020 · grainfield（img/grainfield.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：0e2e54b 2026-09-12。

位置：[grainfield.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/grainfield.svg:1>) · [data.js:116](</Users/chenjuan/Documents/游戏/frontend/js/data.js:116>)

```json
{
  "sha256": "10f2f139df69fa68019f3c74c5b313faeb6fc3e0f4047dfbc1750014f26fdeeb",
  "引用数": 1,
  "注释摘要": [
    " Distant hills ",
    " Field rows (perspective) ",
    " Foreground soil ",
    " Wheat stalks - background ",
    " Wheat stalks - middle "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A021 · ironworks（img/ironworks.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：0e2e54b 2026-09-12。

位置：[ironworks.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/ironworks.svg:1>) · [data.js:117](</Users/chenjuan/Documents/游戏/frontend/js/data.js:117>)

```json
{
  "sha256": "1912db9e39d028dccdf1425f16d91370acc901bde675398110d719260df75d6a",
  "引用数": 1,
  "注释摘要": [
    " Distant mountains ",
    " Ground ",
    " Building base ",
    " Roof ",
    " Chimneys "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A022 · mine（img/mine.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：0e2e54b 2026-09-12。

位置：[mine.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/mine.svg:1>)

```json
{
  "sha256": "9bfe295fc9a82637fcb176196649e623fdaf1b4d188e4bd5e5122dc2e1d460c2",
  "引用数": 0,
  "注释摘要": [
    " Night sky background ",
    " Stars ",
    " Cave opening / mountain silhouette ",
    " Cave dark entrance ",
    " Cave rocks around entrance "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A023 · mount（img/mount.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：0e2e54b 2026-09-12。

位置：[mount.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/mount.svg:1>) · [data.js:114](</Users/chenjuan/Documents/游戏/frontend/js/data.js:114>)

```json
{
  "sha256": "eb13a1966fc80e1581b2f3e861be02c8b2a69f4143d1c29d9995f55c58fc7f83",
  "引用数": 1,
  "注释摘要": [
    " Sky ",
    " Back mountain (lightest) ",
    " Back mountain snow caps ",
    " Middle mountain ",
    " Middle snow caps "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A024 · oil（img/oil.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：0e2e54b 2026-09-12。

位置：[oil.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/oil.svg:1>) · [build.js:39](</Users/chenjuan/Documents/游戏/frontend/js/build.js:39>) · [data.js:26](</Users/chenjuan/Documents/游戏/frontend/js/data.js:26>)

```json
{
  "sha256": "9764a53268060a20522062fb4570e392173cc02e0f9829b016b6d5f9e79e5751",
  "引用数": 2,
  "注释摘要": [
    " Oil pool on ground ",
    " Oil pool highlight ",
    " Oil stream from spout ",
    " Tilted barrel (rotated -25deg, positioned upper-left) ",
    " Main barrel body (ellipse for perspective) "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A025 · population（img/population.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：0e2e54b 2026-09-12。

位置：[population.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/population.svg:1>)

```json
{
  "sha256": "b9e06e5398f2a8210f353c0ba46286dec89380d8ae306f1afff2d15b0b26658d",
  "引用数": 0,
  "注释摘要": [
    " 草帽 ",
    " 农民面孔 ",
    " 劳动服与锄头 "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A026 · rare（img/rare.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：0e2e54b 2026-09-12。

位置：[rare.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/rare.svg:1>)

```json
{
  "sha256": "e7bc0f6d360315d2e5914859b017f08e3f6dfb9256ac2670efe193a3afdc67a5",
  "引用数": 0,
  "注释摘要": [
    " Outer stone base ",
    " Stone cracks ",
    " Inner crystal cavity ",
    " Main central crystal ",
    " Left side crystal "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A027 · rarefactory（img/rarefactory.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：0e2e54b 2026-09-12。

位置：[rarefactory.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/rarefactory.svg:1>) · [data.js:119](</Users/chenjuan/Documents/游戏/frontend/js/data.js:119>)

```json
{
  "sha256": "12845e09a463762acaacddd7638a1eb04cc9373ef85048153992bafb45fe7a08",
  "引用数": 1,
  "注释摘要": [
    " Background glow ",
    " Stars ",
    " Main building ",
    " Roof (industrial slanted) ",
    " Chimney with purple glow "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A028 · res-food（img/res-food.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[res-food.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/res-food.svg:1>) · [build.js:1260](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1260>) · [data.js:17](</Users/chenjuan/Documents/游戏/frontend/js/data.js:17>)

```json
{
  "sha256": "f290586e8e8855b00fa7ee84b12ff6539ffb10aecb372083ea4d294a047bc4fa",
  "引用数": 2,
  "注释摘要": [
    " Central & side stems ",
    " Center wheat grains ",
    " Top apex grain ",
    " Row 1 ",
    " Row 2 "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A029 · res-oil（img/res-oil.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[res-oil.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/res-oil.svg:1>) · [build.js:1262](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1262>) · [data.js:19](</Users/chenjuan/Documents/游戏/frontend/js/data.js:19>)

```json
{
  "sha256": "b5d579fa0a3bb06de271ef7f62bdb682cb2f8104cdf761faeda42134e8284426",
  "引用数": 2,
  "注释摘要": [
    " Barrel body gradient: metallic dark olive / industrial steel ",
    " Barrel Main Body (Cylinder) ",
    " Top Rim & Lid ",
    " Cap / Bung hole on lid ",
    " Horizontal Rolling Hoops / Ribs (Characteristic 55-gal drum ridges) "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A030 · res-pop（img/res-pop.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[res-pop.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/res-pop.svg:1>) · [main-view.js:721](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:721>)

```json
{
  "sha256": "bb7cbbaee58b4ee0e5eccc1d02e5bddcf4b332f350b33364aa08ee8b3f9ca0d2",
  "引用数": 1,
  "注释摘要": [
    " Primary Figure Gradient (Worker / Citizen) ",
    " Secondary Figure Gradient (Farmer / Companion) ",
    " Skin Tone ",
    " Cap Tone ",
    " Secondary Figure (Behind, Right) "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A031 · res-rare（img/res-rare.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[res-rare.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/res-rare.svg:1>) · [build.js:1263](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1263>) · [data.js:20](</Users/chenjuan/Documents/游戏/frontend/js/data.js:20>)

```json
{
  "sha256": "ea8636f2228f64865f11143db1da600ff120286add3faede1d6829d9a8fd2bda",
  "引用数": 2,
  "注释摘要": [
    " Rock Matrix Gradient ",
    " Purple Rare Crystal Main ",
    " Cyan Secondary Crystal ",
    " Base Ore Rock Matrix ",
    " Rock cracks & texture "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A032 · res-steel（img/res-steel.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[res-steel.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/res-steel.svg:1>) · [build.js:1261](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1261>) · [data.js:18](</Users/chenjuan/Documents/游戏/frontend/js/data.js:18>)

```json
{
  "sha256": "b693bc39e69e30567ed4ac18424435dcbfdc050e4c46ad534b221fec7647a15f",
  "引用数": 2,
  "注释摘要": [
    " Steel Top Face Gradient ",
    " Steel Front Face Gradient ",
    " Steel Side Face Gradient ",
    " Cyan Steel Specular Shine ",
    " Bottom Ingot "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A033 · settings（img/settings.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：0e2e54b 2026-09-12。

位置：[settings.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/settings.svg:1>)

```json
{
  "sha256": "ea377bed0adbe2495efdb46458cd87f84c45b89f6d45fdbe35b7ab9b5f2d6d4a",
  "引用数": 0,
  "注释摘要": []
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A034 · shop（img/shop.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[shop.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/shop.svg:1>) · [core.js:509](</Users/chenjuan/Documents/游戏/frontend/js/core.js:509>)

```json
{
  "sha256": "ae3ba1ed9b91fd7d4c0a9ee95822689031a524342b34477d3b98c2d99eeeafa3",
  "引用数": 1,
  "注释摘要": [
    " Lid gradient: Warm lustrous military gold ",
    " Crate body: Deep armored bronze-steel ",
    " Metal reinforcement bands: Bright polished brass/gold ",
    " Star insignia: Radiant white-gold ",
    " Soft ambient shadow "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A035 · swamp（img/swamp.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：0e2e54b 2026-09-12。

位置：[swamp.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/swamp.svg:1>) · [data.js:115](</Users/chenjuan/Documents/游戏/frontend/js/data.js:115>)

```json
{
  "sha256": "2340d2a506f3cdf03eed601ea35b522bbf4d89bec1d56a9c2d9252803c1f11e6",
  "引用数": 1,
  "注释摘要": [
    " Distant trees/mist ",
    " Water surface ",
    " Water ripples ",
    " Lily pads ",
    " Lily pad notches "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A036 · 装甲车（img/units/armored.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[armored.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/armored.svg:1>) · [main-view.js:13](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:13>)

```json
{
  "sha256": "78da858f204a687c4ccef93d1c62f437627c17dda3d734f90c4bd1b1bff152eb",
  "引用数": 1,
  "注释摘要": [
    " Machine Gun Barrel on Turret ",
    " Small Machine Gun Turret ",
    " Sloped Armored Hull ",
    " Armored Vision Slit / Visor ",
    " 4 Large Off-Road Wheels "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A037 · 突击炮（img/units/assault.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[assault.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/assault.svg:1>) · [main-view.js:16](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:16>)

```json
{
  "sha256": "2d5095c31c137b8e39e77ceac19b12f9ca1d77742d7e9279c9fa8eb1f0337b71",
  "引用数": 1,
  "注释摘要": [
    " Gun Barrel with Muzzle Brake (Low Slung, Powerful) ",
    " Pig-head Mantlet (Saukopf) ",
    " Low-Profile Casemate Superstructure (No Turret) ",
    " Tracks ",
    " Wheels "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A038 · 战列舰（img/units/battleship.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[battleship.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/battleship.svg:1>) · [main-view.js:25](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:25>)

```json
{
  "sha256": "150cbbcb520dc55f1654296a36932f3c385034ba0b625b434b2df833013c30c9",
  "引用数": 1,
  "注释摘要": [
    " Waterline ",
    " Massive Battleship Armored Hull ",
    " Heavy Armored Citadel / Pagoda Superstructure ",
    " Forward Triple Gun Turrets ",
    " Aft Gun Turret "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A039 · 轰炸机（img/units/bomber.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[bomber.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/bomber.svg:1>) · [main-view.js:21](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:21>)

```json
{
  "sha256": "33bd511718ef47d8340ca65b5b03f768222d7af52f9a838212031d466422b10c",
  "引用数": 1,
  "注释摘要": [
    " Large Wingspan ",
    " 4 Engine Nacelles on Wings ",
    " Heavy Fuselage ",
    " Glass Greenhouse Nose & Cockpit ",
    " Tail Fin "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A040 · 航母（img/units/carrier.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[carrier.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/carrier.svg:1>) · [main-view.js:26](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:26>)

```json
{
  "sha256": "44aa994a85bd37671dac005e4919a700993b3418f2b7ca60d4c1b7fdca5226ec",
  "引用数": 1,
  "注释摘要": [
    " Waterline ",
    " Hull beneath flight deck ",
    " Flat Flight Deck (Long Horizontal Runway) ",
    " Runway Centerline Dashes ",
    " Island Superstructure & Radar Mast (Starboard / Right) "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A041 · 驱逐舰（img/units/destroyer.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[destroyer.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/destroyer.svg:1>) · [main-view.js:23](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:23>)

```json
{
  "sha256": "6213062e5602087168f76711ec54127e0815d81391f5c43375da491a38a3c115",
  "引用数": 1,
  "注释摘要": [
    " Ocean Waterline ",
    " Destroyer Warship Hull ",
    " Bridge Superstructure & Mast ",
    " Smokestack Funnel ",
    " Forward Gun Turret & Barrel "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A042 · 战斗机（img/units/fighter.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[fighter.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/fighter.svg:1>) · [main-view.js:20](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:20>)

```json
{
  "sha256": "78a502c4516b9c0a7a9a0206d0c718a4a1f294f909479a5ef121d9918324e885",
  "引用数": 1,
  "注释摘要": [
    " Wings (Top-down / oblique fighter planform) ",
    " Wing Roundel ",
    " Sleek Fuselage ",
    " Bubble Canopy ",
    " Red Nose Propeller Spinner "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A043 · 重型坦克（img/units/htank.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[htank.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/htank.svg:1>) · [main-view.js:15](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:15>)

```json
{
  "sha256": "edfc8f7b88656a41d7c6a472ed0d188602e6d0a42e057111d6b7053e50add094",
  "引用数": 1,
  "注释摘要": [
    " Heavy Tank Armor (Panzer Grey / Camo Dark) ",
    " Track Steel ",
    " Long Heavy Gun Barrel (Pointing Left) ",
    " Muzzle Brake ",
    " Barrel Tube "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A044 · 步兵（img/units/infantry.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[infantry.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/infantry.svg:1>) · [main-view.js:10](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:10>)

```json
{
  "sha256": "bfaa19ea938aeeceafbef0ad0f0e3b46596c4529a0ad496a7aeafa97e3558c47",
  "引用数": 1,
  "注释摘要": [
    " Soldier Body & Combat Tunic ",
    " Crossed Webbing Harness & Ammo Pouches ",
    " Face / Jawline ",
    " M1 Steel Combat Helmet (Classic Bowl Shape with Brim) ",
    " Helmet Chinstrap "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A045 · 轻型坦克（img/units/ltank.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[ltank.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/ltank.svg:1>) · [main-view.js:14](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:14>)

```json
{
  "sha256": "5632843cb598490e318d3fb541333d0faf6806ded1aec9fd4be2c5b28ffbfd95",
  "引用数": 1,
  "注释摘要": [
    " Light Tank Camo (Sandy Khaki / Olive Green) ",
    " Gun Barrel (Agile, High-Velocity 37mm-50mm) ",
    " Mantlet ",
    " Turret (Compact, Angular) ",
    " Hatch "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A046 · 摩托兵（img/units/motor.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[motor.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/motor.svg:1>) · [main-view.js:11](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:11>)

```json
{
  "sha256": "0df7e69164d24d981bc7cb69bbfcd90b114470bd57c63094ce6f4beac4efa7eb",
  "引用数": 1,
  "注释摘要": [
    " Sidecar Hull (Tub) ",
    " Motorcycle Main Frame & Fuel Tank ",
    " Handlebars & Front Fork ",
    " Front Headlight ",
    " Wheels (Spoked Tires) "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A047 · 火箭（img/units/rocket.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[rocket.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/rocket.svg:1>) · [main-view.js:17](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:17>)

```json
{
  "sha256": "cdc8213ceac3b7648735d78fc4f176095421a1c7d2d190db91059e51110377a2",
  "引用数": 1,
  "注释摘要": [
    " Truck Chassis Green ",
    " Steel Launch Rails ",
    " Rocket Projectile Body (Military Olive / Dark Steel with Red Warhead) ",
    " Launch Rails Rack (Angled 35 degrees pointing upper-left) ",
    " Rail Guide Beams (Parallel I-Beams) "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A048 · 侦察机（img/units/scout.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[scout.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/scout.svg:1>) · [main-view.js:18](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:18>)

```json
{
  "sha256": "e41c13f610cfa587f7ef64c388a70d74b6bec99453a8abb3baf6c312213642f8",
  "引用数": 1,
  "注释摘要": [
    " Plane Fuselage Metal ",
    " Glass Canopy Cyan ",
    " High-Mounted Straight Wing (Banked angle) ",
    " Far Wing ",
    " Near Wing (Prominent) "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A049 · 特种兵（img/units/special.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[special.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/special.svg:1>) · [main-view.js:19](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:19>)

```json
{
  "sha256": "d0d94065b77926d9f353dd0a99273b55fd1988be52ae9e76c4f6e393be9fff9f",
  "引用数": 1,
  "注释摘要": [
    " Special Forces Maroon / Dark Crimson Beret ",
    " Tactical Camo Uniform ",
    " Skin Tone ",
    " Gun Steel ",
    " Body / Torso with Tactical Vest "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A050 · 潜艇（img/units/sub.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[sub.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/sub.svg:1>) · [main-view.js:24](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:24>)

```json
{
  "sha256": "23741982d458b3f8b90c71fe529a8db4f2148c3429b57462985447ec15d602e3",
  "引用数": 1,
  "注释摘要": [
    " Water Waves ",
    " Submarine Hull ",
    " Conning Tower / Sail ",
    " Periscope Mast ",
    " Deck Gun "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A051 · 运输机（img/units/transport.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[transport.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/transport.svg:1>) · [main-view.js:22](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:22>)

```json
{
  "sha256": "6ee184d2067aa8d03d16c9bded160828ba842a4e2854e0a26fe54c75a21fb8f3",
  "引用数": 1,
  "注释摘要": [
    " Wings ",
    " 2 Twin Engines ",
    " Fuselage ",
    " Cockpit ",
    " Parachute/Cargo Door Marking "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A052 · 卡车（img/units/truck.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：bd3485d 2026-09-13 [UPDATE]: 进一步优化战斗细节 优化遗漏主题界面。

位置：[truck.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/units/truck.svg:1>) · [main-view.js:12](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:12>)

```json
{
  "sha256": "00e2de778ba3cc2cfc6595eee8f4631fbc8d562da1cca5484fe82eb11dc43b82",
  "引用数": 1,
  "注释摘要": [
    " Army Green Cab & Chassis ",
    " Canvas Canopy Cover (Cargo Bed) ",
    " Tire Rubber ",
    " Cargo Canvas Bed (Arched Canopy) ",
    " Canopy Stiffening Ribs "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### A053 · wild-oil（img/wild-oil.svg）

本地SVG图标

**状态：来源不明；评估：待评估；建议次序：补作者与授权。**

SVG含本地矢量路径；未见作者/来源/许可证元数据。可编辑SVG和绘制注释不能证明独立原创。 最早可见入库记录：0e2e54b 2026-09-12。

位置：[wild-oil.svg:1](</Users/chenjuan/Documents/游戏/frontend/img/wild-oil.svg:1>) · [data.js:118](</Users/chenjuan/Documents/游戏/frontend/js/data.js:118>)

```json
{
  "sha256": "9f124b8f41bf8cffefc165b320af6930d49b87c24a89caea9492486444a4974e",
  "引用数": 1,
  "注释摘要": [
    " Sunset sky ",
    " Distant ground ",
    " Oil pool (left) ",
    " Oil derrick (center) ",
    " Base "
  ]
}
```

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

## 页面结构

### P001 · 军队（army）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[army.js:451](</Users/chenjuan/Documents/游戏/frontend/js/army.js:451>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P002 · 旧战斗入口（battle）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[battle.js:794](</Users/chenjuan/Documents/游戏/frontend/js/battle.js:794>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P003 · 旧战报入口（report）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[battle.js:795](</Users/chenjuan/Documents/游戏/frontend/js/battle.js:795>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P004 · 战报列表（reports）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[battle.js:796](</Users/chenjuan/Documents/游戏/frontend/js/battle.js:796>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P005 · 战报详情（reportDetail）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[battle.js:797](</Users/chenjuan/Documents/游戏/frontend/js/battle.js:797>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P006 · 资源建设（buildRes）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[build.js:1788](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1788>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P007 · 军事建设（buildArmy）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[build.js:1789](</Users/chenjuan/Documents/游戏/frontend/js/build.js:1789>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P008 · 仓库（depot）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[depot.js:478](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:478>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P009 · 道具使用（depotUse）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[depot.js:479](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:479>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P010 · 军官改名（depotRename）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[depot.js:480](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:480>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P011 · 城防（fort）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[fort.js:108](</Users/chenjuan/Documents/游戏/frontend/js/fort.js:108>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P012 · 军团（guild）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[guild.js:219](</Users/chenjuan/Documents/游戏/frontend/js/guild.js:219>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P013 · 邮件（mail）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[mail.js:410](</Users/chenjuan/Documents/游戏/frontend/js/mail.js:410>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P014 · 主线任务/晋衔（mainQuest）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[main-quest.js:910](</Users/chenjuan/Documents/游戏/frontend/js/main-quest.js:910>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P015 · 登录/注册（login）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[main-view.js:171](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:171>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P016 · 首页（home）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[main-view.js:639](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:639>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P017 · 设置/关于（settings）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[main-view.js:785](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:785>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P018 · 旧战役地图（map）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[map.js:152](</Users/chenjuan/Documents/游戏/frontend/js/map.js:152>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P019 · 旧野地页（wild）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[map.js:153](</Users/chenjuan/Documents/游戏/frontend/js/map.js:153>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P020 · 军校（academy）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[officer.js:910](</Users/chenjuan/Documents/游戏/frontend/js/officer.js:910>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P021 · 军官列表（officer）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[officer.js:911](</Users/chenjuan/Documents/游戏/frontend/js/officer.js:911>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P022 · 军官详情（officerDetail）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[officer.js:912](</Users/chenjuan/Documents/游戏/frontend/js/officer.js:912>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P023 · 模拟充值（recharge）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[recharge.js:105](</Users/chenjuan/Documents/游戏/frontend/js/recharge.js:105>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P024 · 商城（shop）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[shop.js:168](</Users/chenjuan/Documents/游戏/frontend/js/shop.js:168>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P025 · 科技（tech）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[tech.js:83](</Users/chenjuan/Documents/游戏/frontend/js/tech.js:83>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P026 · 共享世界地图（world）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

世界地图正在迭代，另有 MAP_UPGRADE_PLAN.md 本地方案；不能据此证明全部页面结构独立设计。列表布局仍沿用既有主题。

位置：[world.js:1363](</Users/chenjuan/Documents/游戏/frontend/js/world.js:1363>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P027 · 出征（dispatch）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[world.js:1364](</Users/chenjuan/Documents/游戏/frontend/js/world.js:1364>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P028 · 军情（alerts）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[world.js:1365](</Users/chenjuan/Documents/游戏/frontend/js/world.js:1365>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P029 · 伤兵营（wounded）

审查分区顺序、导航、文字操作、卡片结构、交互路径和整体视觉组合

**状态：来源不明；评估：待评估；建议次序：逐页对照原图。**

页面布局实现可定位；未发现该页完整设计稿、原型或第三方对照原页。共用蓝白主题应一并评估。

位置：[wounded.js:129](</Users/chenjuan/Documents/游戏/frontend/js/wounded.js:129>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P030 · 共用晴空蓝白主题（shared-blue-white）

蓝白顶栏、多列导航、紧凑文字列表、方括号/竖线操作、键盘数字导航

**状态：来源待核；评估：待评估；建议次序：优先评估结构。**

历史描述已按用户要求删减；创作来源与权利归属仍待评估。

位置：[themes.css:32](</Users/chenjuan/Documents/游戏/frontend/css/themes.css:32>) · [themes.css:530](</Users/chenjuan/Documents/游戏/frontend/css/themes.css:530>) · [index.html:25](</Users/chenjuan/Documents/游戏/frontend/index.html:25>) · [main-view.js:1](</Users/chenjuan/Documents/游戏/frontend/js/main-view.js:1>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P031 · 主题预览页（preview）

四种主题、手机模拟器、场景页签、配色与设计说明

**状态：来源待核；评估：待评估；建议次序：优先评估结构。**

历史描述已按用户要求删减；创作来源与权利归属仍待评估。

位置：[theme-preview.html:583](</Users/chenjuan/Documents/游戏/frontend/theme-preview.html:583>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P032 · 指挥官资料抽屉（profile）

独立弹层/组件的排列、文案与交互结构

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

可见本地实现；未附设计稿与来源。

位置：[player-profile.js:9](</Users/chenjuan/Documents/游戏/frontend/js/player-profile.js:9>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P033 · 头像选择与自定义外链（avatar-picker）

独立弹层/组件的排列、文案与交互结构

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

可见本地实现；未附设计稿与来源。外链头像由玩家指定，实际图片不在静态仓库审查范围。

位置：[player-profile.js:68](</Users/chenjuan/Documents/游戏/frontend/js/player-profile.js:68>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P034 · 主题选择弹窗（theme-picker）

独立弹层/组件的排列、文案与交互结构

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

可见本地实现；未附设计稿与来源。

位置：[theme.js:101](</Users/chenjuan/Documents/游戏/frontend/js/theme.js:101>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P035 · 新手欢迎/引导浮层（tutorial）

独立弹层/组件的排列、文案与交互结构

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

可见本地实现；未附设计稿与来源。

位置：[main-quest.js:83](</Users/chenjuan/Documents/游戏/frontend/js/main-quest.js:83>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### P036 · 主城/分城切换组件（city-switch）

独立弹层/组件的排列、文案与交互结构

**状态：来源不明；评估：待评估；建议次序：补来源记录。**

可见本地实现；未附设计稿与来源。

位置：[cities.js:2](</Users/chenjuan/Documents/游戏/frontend/js/cities.js:2>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

## 外部资源

### L001 · Unicode / 系统Emoji图标（unicode-icons）

道具、技能界面、导航、提示中直接使用字符图标

**状态：技术来源可识别；评估：待评估；建议次序：确认无外部字形打包。**

源码直接包含Emoji字符，未见捆绑Apple/Twemoji等字形图片包。字符与具体字体绘制不同，不据此认定复制旧游戏图标。

位置：[data.js:25](</Users/chenjuan/Documents/游戏/frontend/js/data.js:25>) · [depot.js:17](</Users/chenjuan/Documents/游戏/frontend/js/depot.js:17>) · [task.js:49](</Users/chenjuan/Documents/游戏/frontend/js/task.js:49>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### L002 · 系统字体回退栈（fonts）

Courier New、Lucida Console、系统中文和无衬线字体

**状态：技术来源可识别；评估：待评估；建议次序：低优先核对。**

快照中未见字体文件或 @font-face 加载；使用设备已有字体，不等于取得字体文件再分发权。

位置：[style.css:7](</Users/chenjuan/Documents/游戏/frontend/css/style.css:7>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### L003 · PixiJS Legacy 7.4.3（pixi）

本地打包的地图渲染库

**状态：有第三方许可线索；评估：待评估；建议次序：核对许可文件。**

文件头明确标示版本及MIT许可链接；MAP_UPGRADE_PLAN.md记录使用PixiJS。需核对完整许可和随包告知，不能把MIT库当作来源不明的游戏美术。

位置：[pixi-legacy-7.4.3.min.js:1](</Users/chenjuan/Documents/游戏/frontend/vendor/pixi-legacy-7.4.3.min.js:1>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### L004 · 预览页远程样式脚本（preview-cdn）

https://www.gstatic.com/antigravity/web/dev/tailwindcss.min.js

**状态：外部来源已定位，许可待核；评估：待评估；建议次序：补版本与许可。**

URL可见；仓库未见该文件的版本锁定及许可归档。此URL不能证明页面或图片的创作者。

位置：[theme-preview.html:7](</Users/chenjuan/Documents/游戏/frontend/theme-preview.html:7>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### L005 · 玩家自定义头像外链（avatar-url）

玩家可输入 https 图片地址，显示于头像及资料页

**状态：动态内容未覆盖；评估：待评估；建议次序：另审实际外链内容。**

静态审查无法确定实际玩家URL所指图片的作者及许可；本轮未读取数据库或批量访问玩家图片。

位置：[player-profile.js:201](</Users/chenjuan/Documents/游戏/frontend/js/player-profile.js:201>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）

### L006 · 旧品牌内部存档键（legacy-save-key）

ww2_wistone_v1

**状态：历史关联标识；评估：待评估；建议次序：评估后决定是否迁移。**

第一阶段未改此内部标识；没有证据表明它正在作为可见游戏名称展示。它是项目来历线索，不是单项作品复制证据。

位置：[data.js:9](</Users/chenjuan/Documents/游戏/frontend/js/data.js:9>) · [GameConstants.java:14](</Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/constants/GameConstants.java:14>)

评估选项：保留并补证 / 重写、重绘或重设计 / 停用 / 待确认（尚未选择）
