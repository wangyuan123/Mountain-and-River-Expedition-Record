# 军官四维属性与战斗对冲机制设计记录

- **记录日期**：2026-09-20
- **版本标识**：`officer-system-rebalance-20260920`
- **当前规则补充（2026-09-26）**：本记录保留四维属性设计背景。军官军事、防御属性直接加成己方攻防时，仍在固定第 3、6、9…回合生效；五项周期技能从首次交战回合 E 起，在 E、E+3、E+6…回合生效。战斗使用的综合属性包含已穿戴装备和已激活套装。师夷长技的敌方攻击参考值计入综合军事属性，不受固定属性回合限制，也不复制全军冲锋等技能。具体公式、叠加顺序和实现差异以 [军官技能池设计记录](officer-skills-design-record.md) 为准。
- **出兵容量规则补充**：少校基础上限为 300,000，此后每阶增加 50,000，上将为 600,000；迎战上限为基础的 1.5 倍加各类加成。军官后勤属性不直接增加带兵上限，围墙与三军统帅的计算口径见 [军衔出兵上限与出城迎战配置记录](troop-capacity-design-record.md)。
- **关联代码与资源**：
  - 后端实体与服务：[`Officer.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/model/entity/Officer.java)、[`OfficerEquipment.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/model/entity/OfficerEquipment.java)、[`OfficerEquipmentDef.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/model/constants/OfficerEquipmentDef.java)、[`OfficerService.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/OfficerService.java)、[`EquipmentService.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/EquipmentService.java)、[`BattleService.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/BattleService.java)、[`MarchService.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/MarchService.java)
  - 数据库迁移：[`V38__officer_defense_attribute.sql`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/resources/db/migration/V38__officer_defense_attribute.sql)
  - 前端逻辑：[`officer.js`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/officer.js)、[`save.js`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/save.js)
  - 技能体系设计：[`officer-skills-design-record.md`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/output/officer-skills-design-record.md)
  - 验证测试：[`OfficerLevelUpTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/OfficerLevelUpTest.java)、[`AcademyRecruitmentTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/AcademyRecruitmentTest.java)、[`BattleServiceTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/BattleServiceTest.java)

---

## 一、 设计核心理念与体系重构

本次重构旨在解决原军官体系中“重攻轻守、属性失衡、缺乏技能回合博弈”的问题，确立了完整的四维属性对等体系：

1. **确立四维独立属性体系**：
   - **军事（Military）**：进攻核心。在固定第 3、6、9…回合提供全军百分比攻击力加成。
   - **防御（Defense）**：守御核心。与军事属性形成镜像对抗，在固定第 3、6、9…回合提升全军基础防御力百分比。
   - **后勤（Logistics）**：统御与战备。降低行军机动耗时；当前不直接增加带兵上限，容量由军衔基础、满级围墙和已任命指挥官的三军统帅技能计算。
   - **学识（Knowledge）**：政略与战策。担任市长时提升黄金税收与科研效率，出征时影响部分战法判定与战斗经验获取。

2. **严谨的极限数值边界（219 单项上限）**：
   - 统一设定单项属性系统硬上限为 **219**（`ATTR_MAX = 219`）。
   - 彻底废除旧版升级自动多点随机膨胀机制，采用**每升 1 级固定获得 1 点自由属性点**（1级至100级满级共获得 **99 点**）。
   - 5 星极品军官初始专精主属性上限为 **120**，满级加满单项主属性：$120 + 99 = 219$，恰好契合系统天花板；优秀 5 星主属性初始下限为 **111**，满级可达 $111 + 99 = 210$。实现满级 5 星军官主属性稳定落在 **210 ～ 219** 黄金区间。

3. **属性回合攻防对冲闭环（第 3、6、9…回合）**：
   - 指挥官军事、防御属性在第 $3n$ 回合（$n \ge 1$）生效；五项周期技能另从首次交战回合起算。
   - 攻方指挥官军事属性转化为“全军攻击力加成”，守方指挥官防御属性转化为“全军防御力加成”。
   - 守方的强化防御值直接带入非线性有效减伤公式，有效遏制纯攻极端秒杀，实现深度的排兵布阵与将领搭配策略。

---

## 二、 军校刷新与五星军官招募机制

### 1. 军校各等级五星军官刷新概率

军校等级直接决定刷新出五星（5★）稀有军官的几率，每提升 1 级增加 0.3%，满级 10 级达到 3.0%：

| 军校等级 | 五星（5★）概率 | 四星（4★）概率 | 三星（3★）概率 | 二星（2★）概率 | 一星（1★）概率 | 每次刷新黄金消耗 |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **Lv.1** | **0.3%** | 2.7% | 15.0% | 40.0% | 42.0% | 1,000 |
| **Lv.2** | **0.6%** | 3.4% | 16.0% | 40.0% | 40.0% | 1,200 |
| **Lv.3** | **0.9%** | 4.1% | 17.0% | 40.0% | 38.0% | 1,500 |
| **Lv.4** | **1.2%** | 4.8% | 18.0% | 39.0% | 37.0% | 1,800 |
| **Lv.5** | **1.5%** | 5.5% | 19.0% | 39.0% | 35.0% | 2,200 |
| **Lv.6** | **1.8%** | 6.2% | 20.0% | 38.0% | 34.0% | 2,600 |
| **Lv.7** | **2.1%** | 6.9% | 21.0% | 38.0% | 32.0% | 3,100 |
| **Lv.8** | **2.4%** | 7.6% | 22.0% | 37.0% | 31.0% | 3,600 |
| **Lv.9** | **2.7%** | 8.3% | 23.0% | 37.0% | 29.0% | 4,200 |
| **Lv.10** | **3.0%** | 9.0% | 24.0% | 36.0% | 28.0% | 5,000 |

### 2. 五星军官专精生成算法

当军校命中 5★ 军官时，执行四维专精抽取算法：
1. **专精主属性抽取**：在 `[军事, 防御, 后勤, 学识]` 四个维度中等概率随机抽取 1 项为主属性。
   - 主属性初始值：$Attr_{\text{main}} \in [111, 120]$ 的均匀随机整数。
   - 极品判定：初始主属性达到 120 即为极品胚子。
2. **副属性抽取**：其余 3 项作为副属性，各自独立在 $[50, 100]$ 区间内均匀随机生成：
   - $Attr_{\text{sub\_1}}, Attr_{\text{sub\_2}}, Attr_{\text{sub\_3}} \in [50, 100]$
3. **初始等级与可用属性点**：
   - 军校招募出来的军官默认为 1 级，可用未分配属性点为 0。

---

## 三、 军官成长与洗点数值模型

### 1. 升级自由点数机制
- **常规升级**：每提升 1 级固定获得 **1 点**自由属性点，可由玩家自主加在四维任意单项上。
- **满级经验书直升**：使用满级经验书直达 100 级时，全额补偿获得 $(100 - \text{当前等级}) \times 1$ 点属性点。
- **单项上限截断**：
  - 任意单项加点执行严格边界校验：$\text{新属性值} \le 219$。
  - 若已达到 219，则禁止继续加点，并向客户端返回明确提示：“已达到上限 (219)”。

### 2. 满级五星军官属性区间推导

$$\begin{aligned}
\text{满级极品主属性} &= 120 \ (\text{初始极品}) + 99 \ (\text{升级99点}) = \mathbf{219} \\
\text{满级达标主属性} &= 111 \ (\text{初始保底}) + 99 \ (\text{升级99点}) = \mathbf{210}
\end{aligned}$$

- **结论**：所有 5 星军官若在培养过程中专精单项加点，满级核心主属性严格保证在 **[210, 219]** 之间。
- **多星级初始与洗点对照表**：

| 星级 | 专精主属性基准 | 副属性区间 | 100级满级极限主属性 | 洗点重置规则 |
| :---: | :---: | :---: | :---: | :---: |
| **5★** | **111 ～ 120** | **50 ～ 100** | **210 ～ 219** | 自动识别最高项设为 120，其余三项设为 75，返还 $(\text{等级}-1)$ 点 |
| **4★** | 80 ～ 95 | 40 ～ 75 | 179 ～ 194 | 重置为主属性 85、副属性 60，返还 $(\text{等级}-1)$ 点 |
| **3★** | 60 ～ 75 | 30 ～ 60 | 159 ～ 174 | 重置为各属性 50，返还 $(\text{等级}-1)$ 点 |
| **2★** | 45 ～ 55 | 20 ～ 45 | 144 ～ 154 | 重置为各属性 40，返还 $(\text{等级}-1)$ 点 |
| **1★** | 30 ～ 40 | 10 ～ 35 | 129 ～ 139 | 重置为各属性 30，返还 $(\text{等级}-1)$ 点 |

---

## 四、 军官属性回合战斗对冲机制（第 3、6、9…回合）

### 1. 触发时机与环境上下文
在战斗结算引擎 [`BattleService.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/BattleService.java) 中：
- 战斗每轮推进一个回合（$Round = 1, 2, 3 \dots 30$）。
- 当 $Round \pmod 3 == 0$ 时，指挥官军事、防御属性生效。五项周期技能以首次实际交战回合 $E$ 为起点，在 $E$、$E+3$、$E+6$…回合进入生效窗口。例如第 4 回合首次交战，技能在第 4、7、10、13…回合生效；两种周期可重合，也可错开。

### 2. 攻守双向数值计算公式

在固定属性回合中，攻守双方指挥官属性全面激活；下列公式仅说明属性加成，不代表技能也在该回合触发。这里的军事、防御均为 `EquipmentService.attributes()` 汇总后的综合属性，即军官自身属性加已穿戴装备、已激活套装的对应加成；不是仅取军官自身裸值。

师夷长技另按 `同名兵种对应领域基础攻击 × (1 + 0.10 × 敌方攻击科技等级) × (1 + 敌方综合军事 / 100)` 计算学习基准，且在它自己的技能回合中始终读取综合军事；借甲御敌的防御参考值则只计基础防御和敌方防御科技，不含敌方综合防御属性。

1. **攻方攻击倍率（$AtkMul$）**：
   $$AtkMul = 1.0 + \frac{\text{攻方指挥官军事属性}}{100}$$
   *(例：攻方军官军事 150，属性回合全军攻击提升 +150%，即 $AtkMul = 2.5$)*

2. **守方基础防御倍率（$DefMul$）**：
   $$DefMul = 1.0 + \frac{\text{守方指挥官防御属性}}{100}$$
   *(例：守方军官防御 120，属性回合全军基础防御力提升 +120%，即 $DefMul = 2.2$)*

3. **有效防御力计算（$EffDef$）**：
   $$EffDef = BaseDef \times (\text{科技加成}) \times DefMul$$

4. **伤害计算与对冲衰减公式**：
   $$Damage = \frac{Atk \times AtkMul \times \text{攻击份额} \times \text{克制倍率} \times 100}{100 + 5 \times EffDef}$$

### 3. 攻防博弈对冲案例分析

以重型坦克（Base HP=385, Base DEF=63.5）对抗为例，在属性回合遭受等效 100,000 面板爆发伤害时：
- **无防守军官（$DefMul = 1.0$）**：
  $$EffDef = 63.5 \implies \text{减伤分母} = 100 + 5 \times 63.5 = 417.5 \implies \text{伤害承受} = \frac{100,000 \times 100}{417.5} \approx \mathbf{23,952}$$
- **配备高防御将领（防御 180，属性回合 $DefMul = 2.8$）**：
  $$EffDef = 63.5 \times 2.8 = 177.8 \implies \text{减伤分母} = 100 + 5 \times 177.8 = 989 \implies \text{伤害承受} = \frac{100,000 \times 100}{989} \approx \mathbf{10,111}$$
- **对冲效果**：高防御军官直接将攻方的毁灭性技能爆发削减了 **57.8%**，实现了真正意义上的战术对冲。

### 4. 战报日志表现
属性与技能回合可能不同。当前战报分别展示本回合生效的属性和技能，例如第 4 回合首次交战时：
```text
-- 第4回合 --
我方将领加成：全军冲锋 +50%攻击
敌方将领加成：坚守阵地 +50%防御
```

---

## 五、 装备系统与套装共鸣扩展

军官装备分为军事、防御、后勤、学识四个分支，每个分支有列兵、校官、元帅三阶，槽位为武器、徽章、外套。以下按当前 `OfficerEquipmentDef` 与 `EquipmentService.attributes()` 更新，替换早期设计中的名称与数值。每件装备除主属性外，也为其余三项属性提供副属性加成。

### 1. 防御分支装备明细表

| 品阶 | 部位 | 装备名称 | 基础属性加成 |
| :---: | :---: | :---: | :---: |
| **列兵阶 (Recruit)** | 武器 | 列兵护身盾 | 防御 +5，其余三项各 +1 |
| **列兵阶 (Recruit)** | 徽章 | 列兵坚守勋章 | 防御 +5，其余三项各 +1 |
| **列兵阶 (Recruit)** | 外套 | 列兵防弹背心 | 防御 +5，其余三项各 +1 |
| **校官阶 (Officer)** | 武器 | 校官防暴盾 | 防御 +15，其余三项各 +3 |
| **校官阶 (Officer)** | 徽章 | 校官铁壁勋章 | 防御 +15，其余三项各 +3 |
| **校官阶 (Officer)** | 外套 | 校官重装防弹甲 | 防御 +15，其余三项各 +3 |
| **元帅阶 (Marshal)** | 武器 | 元帅重装盾 | 防御 +30，其余三项各 +5 |
| **元帅阶 (Marshal)** | 徽章 | 元帅不屈之星 | 防御 +30，其余三项各 +5 |
| **元帅阶 (Marshal)** | 外套 | 元帅钛金铠 | 防御 +30，其余三项各 +5 |

### 2. 四维套装共鸣属性（同一阶、同一分支的 3 件装备激活）

| 套装品阶 | 套装件数 | 激活共鸣效果（按装备分支专精） |
| :---: | :---: | :--- |
| **列兵套装 (Recruit Set)** | 3 件 | 对应专精属性额外 +3 |
| **校官套装 (Officer Set)** | 3 件 | 对应专精属性额外 +15 |
| **元帅套装 (Marshal Set)** | 3 件 | 对应专精属性额外 +30，并额外全属性 +5（专精属性合计 +35） |

套装效果在单件装备属性之上叠加。例如元帅军事三件套为军事提供 `3 × 30 + 30 + 5 = 125`，其他三项属性各提供 `3 × 5 + 5 = 20`。这些军事加成会进入师夷长技读取的敌方综合军事属性；背包中未穿戴的装备不计入。前文 219 的成长上限指军官自身属性，不限制叠加装备、套装后的综合属性。

---

## 六、 数据库架构与接口交互

### 1. Flyway 数据库增量升级 (`V38__officer_defense_attribute.sql`)
```sql
-- 1. 军官主体表添加 defense 字段，设置默认基础值 50
ALTER TABLE officers ADD COLUMN IF NOT EXISTS defense INT NOT NULL DEFAULT 50;

-- 2. 军官装备表添加 defense_bonus 字段
ALTER TABLE officer_equipment ADD COLUMN IF NOT EXISTS defense_bonus INT NOT NULL DEFAULT 0;

-- 3. 历史存量 5 星将领平滑平摊或回填基准属性
UPDATE officers SET defense = 75 WHERE star = 5 AND defense = 50;
```

### 2. API 数据流与状态透传
- `GET /api/game/officers` 与 `GET /api/game/state`：
  - 返回数据包增加 `baseDefense` 与最终合并装备及官职后的 `defense`。
- `POST /api/game/officer/{id}/assign`：
  - 参数 `attr` 支持 `"military"`、`"defense"`、`"logistics"`、`"knowledge"`。
- `GET /api/game/world/view` / `GET /api/game/march/target`：
  - 敌我目标统帅预览中完整包含 `defense` 属性，供战前侦查研判。

---

## 七、 质量工程与测试覆盖

系统变更已通过单元测试与前端集成测试套件的全量验证：

1. **等级与加点测试** ([`OfficerLevelUpTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/OfficerLevelUpTest.java))：
   - `testAssignDefenseAttribute`：验证点数正常加至防御，防御值如期上升。
   - `testAttrMaxBoundary219`：验证单项加点至 219 时严格截止，溢出点数操作被异常拦截。
   - `testFiveStarWashReset`：验证 5 星军官洗点后主属性为 120、其余 3 项均为 75，且返还全部未分配点数。
2. **军校抽取机制测试** ([`AcademyRecruitmentTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/AcademyRecruitmentTest.java))：
   - 验证五星将领生成时四维中恰有 1 项在 $[111, 120]$，其余 3 项在 $[50, 100]$。
3. **战斗属性回合对冲测试** ([`BattleServiceTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/BattleServiceTest.java))：
   - `testCommanderDefenseAttributeMitigation`：验证第 3 回合守方防御加成正确参与减伤运算，并产出对应的日志标记。
4. **前端测试套件**：
   - 运行 `node --test frontend/tests/*.test.cjs`，167 项用例全绿通过。
