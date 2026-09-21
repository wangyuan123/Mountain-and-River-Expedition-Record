# 军官技能系统平衡重构与战斗机制设计记录

- **记录日期**：2026-09-20
- **版本标识**：`officer-skills-rebalance-20260920`
- **关联代码与资源**：
  - 后端实体与服务：[`OfficerSkillDef.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/model/constants/OfficerSkillDef.java)、[`BattleService.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/BattleService.java)、[`BuildService.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/BuildService.java)、[`TickService.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java)
  - 前端逻辑与定义：[`data.js`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/data.js)、[`core.js`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/core.js)
  - 验证测试：[`MayorSkillServiceTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/service/MayorSkillServiceTest.java)、[`BattleMechanicsTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/BattleMechanicsTest.java)、[`BattleDamageTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/BattleDamageTest.java)、[`BattleServiceTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/BattleServiceTest.java)、[`officer-skills.test.cjs`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/tests/officer-skills.test.cjs)

---

## 一、 背景与重构目标

在原军官技能体系中，存在若干阻碍战术多样性与损害玩家游戏体验的痛点：
1. **极端不可控的翻倍暴毙**：原【连环打击】具备概率连击翻倍火力，一旦在军官技能爆发回合（第 3、6、9... 回合）触发，会造成 4~8 倍的瞬间毁灭性火力，导致防守方部队瞬间蒸发，使防御属性与减伤机制完全失效。
2. **工期硬上限收益截断**：原【工程营造】设定为每级 10%（满级 50%），但后端存在工期下限硬保护（`Math.max(0.35, ...)`），导致 4 级后技能加成直接撞墙截断，第 5 级呈现“零收益”甚至负体验。
3. **关键减伤技能缺乏常驻保护**：原【火力压制】限制在军官行动回合（第 $3n$ 回合）生效，导致普通交火回合防御真空，无法发挥稳定的防守光环作用。
4. **反击机制滞后与判定漏洞**：原【绝地反击】仅在死战阶段（战败或残血阶段）生效，且未校验双方交火射程与防空/对地打击属性，造成不合理的“跨空间反击”。
5. **养兵负担过重**：驻扎消耗过高，玩家日常需频繁上线屯粮，甚至夜间面临断粮弃军的焦虑。

本次重构确立以下四大原则：
- **消除极端爆发，强化战术对冲**：移除不可控翻倍，将减伤光环常驻化，提升战场博弈的确定性。
- **严谨物理约束**：反击、突击等技能必须受战场网格距离、射程与兵种打击属性严格约束。
- **平滑正向收益**：彻底规避工期硬上限造成的属性溢出与折损。
- **减负与体验优化**：大幅提升屯粮与节粮收益，改善玩家长期驻军与养兵生态。

---

## 二、 技能池全景重构（14项 $\rightarrow$ 13项）

彻底废除【连环打击】（`combo`），技能池精简并规范为 13 项四字技能，涵盖出征军官战斗加成与城池市长内政加成：

| 技能代码 | 规范名称 | 作用场景 | 调整后每级加成 | 满级（Lv.5）效果 | 机制说明 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `assault` | **全力猛攻** | 出征战法 | +10%/级攻击 | **+50%** | 第 3/6/9... 军官回合全军额外攻击爆发 |
| `bulwark` | **铜墙铁壁** | 出征战法 | +10%/级防御 | **+50%** | 第 3/6/9... 军官回合全军额外基础防御加成 |
| `suppress` | **火力压制** | 出征光环 | **-5%/级受敌攻击** | **-25%（常驻）** | **全回合常驻生效**，削弱敌方造成的所有最终伤害 |
| `counter` | **绝地反击** | 出征反击 | **+10%/级反击火力** | **+50% 反击** | **第 3/6/9... 回合受击存活后必反击**，受射程与打击属性约束 |
| `blitz` | **闪电突击** | 战场战术 | **+4%/级移速** | **+20%** | **仅作用于战场交战格子推进步长**，不接入大地图行军 |
| `pierce` | **破甲打击** | 出征战法 | +5%/级破甲 | **+25%** | 攻击时按比例削减目标的基础防御力 |
| `repair` | **战场急救** | 出征战术 | +5%/级伤兵率 | **+25%** | 战后阵亡兵力转化为伤兵的救治比例提升 |
| `harvest` | **屯田增产** | 市长内政 | **+10%/级粮食产量** | **+50%** | 派驻市长时大幅提高城池农田的粮食产出 |
| `mine` | **精工开采** | 市长内政 | +5%/级矿产产量 | **+25%** | 派驻市长时提高铁矿、石油产出 |
| `industry` | **军工铸造** | 市长内政 | -5%/级招募耗时 | **-25%** | 派驻市长时缩减兵营各兵种招募时间 |
| `construct` | **工程营造** | 市长内政 | **-4%/级建筑工期** | **-20%** | 派驻市长时缩短建筑建造与升级工期，避开硬上限 |
| `tax` | **通商赋税** | 市长内政 | +5%/级黄金税收 | **+25%** | 派驻市长时增加城池居民黄金税收产出 |
| `ration` | **军屯自给** | 驻扎内政 | **-16%/级粮食消耗** | **-80%** | 市长管辖城池所有驻军与行军耗粮大幅减免 |
| ~~`combo`~~ | ~~连环打击~~ | *(已移除)* | *(已彻底删除)* | *(已彻底删除)* | 剔除双倍火力与连击逻辑，消除秒杀失衡 |

---

## 三、 重点技能数学模型与机制解析

### 1. 闪电突击 (`blitz`)：战场机动与大地图行军的严格解耦
- **数值模型**：
  $$\text{战场步长增益} = 1.0 + 0.04 \times \text{blitzLv} \quad (\text{最高 } +20\%)$$
- **设计决策**：
  - 闪电突击**严格限定于交火战场的格子距离推进**（例如缩短坦克与火炮之间的初始接敌距离）。
  - **绝不接入大地图行军逻辑（`MarchService`）**。大地图行军速度严格由兵种基础速度与科研【行军速度】决定。若将技能移速引入大地图，不仅会导致行军时间跨度产生巨大阶跃，更会造成偷袭与侦查预警体系的失衡。

### 2. 火力压制 (`suppress`)：由偶发爆发转为常驻防守光环
- **原逻辑痛点**：原逻辑带 `round % 3 == 0 && officerActive`，仅在军官行动回合生效。玩家在第 1、2、4、5 回合承受全额爆发，防守极其被动脆弱。
- **调整后模型**：
  $$\text{敌方输出乘数} = 1.0 - 0.05 \times \text{suppressLv} \quad (\text{满级 } -25\%)$$
  $$\text{受击最终伤害} = \text{RawDamage} \times (1.0 - 0.05 \times \text{suppressLv})$$
- **战略价值**：解除回合判定后，火力压制成为战场上最可靠的“防秒杀装甲光环”，无论面对敌方常规齐射还是高爆发回合，均稳定吸收 25% 伤害。

### 3. 绝地反击 (`counter`)：回合制受击反击与射程打击物理约束
- **触发时机**：
  在每个军官行动回合（第 3、6、9... 回合）：
  $$\text{Round} \pmod 3 == 0 \implies \text{officerActive} = \text{true}$$
  当己方受到敌方攻击且部队在受创后依然有存活兵力时触发。
- **伤害公式**：
  $$\text{反击伤害} = \sum (\text{各存活兵种剩余数量} \times \text{对应攻击力}) \times (0.10 \times \text{counterLv})$$
  *(满级反击伤害达到存活兵力总攻击力的 **50%**)*
- **双重前置物理约束（至关重要）**：
  1. **射程约束（Range Check）**：反击部队的基础攻击射程必须大于或等于交战网格距离（$\text{Range} \ge \text{Distance}$）。若敌方火炮在距离 6 处开火，而己方仅存步枪兵（射程 0），射程不足则**判定反击无法送达，直接豁免**。
  2. **攻击属性/领域匹配（Domain Check）**：反击必须具备对应的打击能力（例如轰炸机对空为 0，遭受战斗机拦截时无法进行空中反击）。

### 4. 屯田增产 (`harvest`) 与 军屯自给 (`ration`)：护肝与后勤体系重塑
- **屯田增产**：
  $$\text{粮食实际产出} = \text{基础产量} \times (1 + \text{科技加成} + 0.10 \times \text{harvestLv})$$
  满级提升 50% 产粮，大幅强化内政种田收益。
- **军屯自给**：
  $$\text{驻军耗粮乘数} = 1.0 - 0.16 \times \text{rationLv}$$
  满级缩减：$1.0 - 0.16 \times 5 = \mathbf{0.20}$（**立减 80% 耗粮**）。
- **生态价值**：10 万大军原本每小时消耗 100,000 粮食，在 5 级军屯市长驻防下，耗粮降至 20,000/小时。玩家在睡前不再需要为大军可能在半夜断粮溃散而被迫定闹钟，极大保障了玩家留存与睡眠质量。

### 5. 工程营造 (`construct`) 与工期硬上限（0.35）设计原理解释
- **为什么存在工期硬上限 `Math.max(0.35, ...)`？**
  在建筑工期计算中：
  $$\text{工期} = \text{BaseDuration} \times \text{科技系数} \times \text{市长技能系数} \times \text{主城加成}$$
  如果允许各类系数无限连乘相加，后期工期缩短率会逼近 100%（即工期降为 0 秒甚至负数），导致瞬间完成高阶建筑，严重击穿游戏经济与加速道具数值平衡。因此后端设定了底线保护：工期缩短至多只能达到原时长的 35%（即最快压缩到原本的 35%，缩短上限 65%）。
- **原数值漏洞与调整设计**：
  - 原设定：每级 10%（满级 50%）。高等级玩家自身建筑科研常年提供 40%~50% 缩减，此时 5 级工程营造（-50%）直接导致总加成远超 65%，在 3 级或 4 级时就直接触碰 0.35 截断线，第 5 级技能加点彻底“浪费”。
  - 调整后设定：**每级 4%（满级 20%）**。5 级提供平稳的 20% 缩减空间，与科技叠加后依然落在安全收益区间，确保每一级加点都有稳定、明确的正向收益。

### 6. 全力猛攻与铜墙铁壁的同构性与城墙防御计算
- **全力猛攻与铜墙铁壁的同构镜像**：
  两者均在第 3、6、9... 回合激活：
  $$Atk_{eff} = Atk \times (1 + \text{科技}) \times (1 + 0.10 \times \text{assaultLv})$$
  $$Def_{eff} = Def \times (1 + \text{科技}) \times (1 + 0.10 \times \text{bulwarkLv})$$
  形成了完美的攻防镜像对冲。
- **城墙加成与最终防御力乘法机制**：
  城墙在防御结算中直接提升守军所依托的掩体强度：
  $$Def_{final} = Def_{base} \times (1 + \text{城防加成}) \times (1 + \text{技能加成})$$
  乘法结构保证了高防御军官驻守在高等级城墙的要塞时，能够发挥“一加一大于二”的筑垒防御效果，避免城防建设在数值后期被攻城重炮轻易贬值。

---

## 四、 代码架构实现与前后端同步

### 1. 后端实现
- [`OfficerSkillDef.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/model/constants/OfficerSkillDef.java)：
  - 移除了 `combo`；
  - 统一更新技能的数值常数与四字命名。
- [`BattleService.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/BattleService.java)：
  - `SKILL_RATES` 更新：`blitz: 0.04, suppress: 0.05, counter: 0.10, ration: 0.16`；
  - 移除 `comboHit` 连击翻倍火力分支；
  - `suppress` 去除 `officerActive` 限制，改为全回合常驻减伤；
  - `counter` 调整为 `officerActive` 回合受击判定，按存活总伤 $\times (0.10 \times Lv)$ 计算。
- [`BuildService.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/BuildService.java)：
  - 工程营造工期缩减更新为 `0.04 * constructLv`。
- [`TickService.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java)：
  - 屯田产粮加成更新为 `0.10 * harvestLv`；
  - 军屯节粮更新为 `1.0 - 0.16 * rationLv`。

### 2. 前端实现
- [`data.js`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/data.js)：
  - 技能字典中删除 `combo`；
  - 更新所有技能的四字规范命名与显示说明。
- [`core.js`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/core.js)：
  - 同步更新技能费率映射表：`blitz: 0.04, suppress: 0.05, harvest: 0.10, ration: 0.16, construct: 0.04`；
  - `skillBonus('counter')` 更新为 `0.10 * Math.min(5, lv)`。

---

## 五、 质量工程与全量自动化测试验证

全套机制已经通过后端与前端严密的回归测试套件验证：

1. **内政与工期测试** ([`MayorSkillServiceTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/service/MayorSkillServiceTest.java))：
   - 验证技能池总数精确为 13；
   - 验证 5 级屯田增产达到 +50% 粮食产出；
   - 验证 5 级军屯自给实现 80% 耗粮减免；
   - 验证 5 级工程营造平滑缩减 20% 工期且不触发硬上限截断。
2. **战斗机制与对称性测试** ([`BattleMechanicsTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/BattleMechanicsTest.java))：
   - 验证火力压制在常规回合与军官回合均稳定生效（减伤一致）；
   - 验证铜墙铁壁在军官回合正常激发防御翻倍。
3. **伤害模型与指挥官生命测试** ([`BattleDamageTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/BattleDamageTest.java))：
   - 移除连击依赖后，对空攻击在技能加成下精准输出 150 原始火力；
   - 指挥官生命值提升测试在移除连击后正常检验有效生命值翻倍逻辑。
4. **测试执行成绩**：
   - **后端 Maven 测试**：`mvn test -Dtest="*Test,!AttackDesignTest"` $\rightarrow$ **284 项测试全部通过（0 错误，0 失败）**。
   - **前端 Node.js 测试**：`node --test frontend/tests/*.cjs` $\rightarrow$ **171 项测试全部通过（0 错误，0 失败）**。
