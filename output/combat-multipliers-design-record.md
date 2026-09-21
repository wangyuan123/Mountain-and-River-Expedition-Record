# 战场克制机制与纯属性面板决胜规范

- **文档名称**：战场克制机制与纯属性面板决胜规范
- **归档日期**：2026-09-20
- **生效版本**：`balance-v8-pure-attributes-20260920`
- **关联代码**：
  - 后端规则：[`BattleRules.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/model/constants/BattleRules.java)
  - 战斗核心：[`BattleService.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/BattleService.java)
  - 属性定义：[`UnitDef.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/model/constants/UnitDef.java)、[`FortDef.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/model/constants/FortDef.java)
  - 仿真沙盘：[`BattleBalanceTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/BattleBalanceTest.java)

---

## 一、 架构理念与设计演进

### 1. 为什么走向“彻底取消克制倍率，纯属性面板决胜”？
在传统 SLG 设计中，过度依赖“硬编码乘法倍率”往往会导致底层属性与战术反馈脱节，使玩家产生“明明面板很高却莫名被几倍暴击蒸发”的黑盒感。

本项目在 `v8` 版本中彻底移除了所有的硬编码倍率，实现了完全透明的**纯属性面板对抗模型**：
- **交战倍率恒为 $1.00\times$**：不再对任何兵种施加隐性增伤或减伤乘数；
- **所见即所得**：兵种名片面板上的对地、对空、对海、对工事四维攻击力、防御力、生命值、射程与移速，就是决定战场胜负与战损的全部依据；
- **战术机制取代数值补丁**：依靠“射程先手优势”、“重坦前排掩护”、“特种兵越障渗透”等机制自然衍生战术深度。

### 2. 演进历程总览
1. **`v1 ~ v5`（早期原型）**：攻击平方与贴脸近战翻倍，粗放设置全局空地倍率；
2. **`v6`（四域拆分）**：拆分四维独立攻击面板，确立线性减伤守恒公式，保留 16 组细碎倍率；
3. **`v7`（核心精简）**：废弃负向衰减与轻装地面冗余倍率，仅保留海战与重装甲链；
4. **`v8`（当前纯属性版）**：**彻底置空倍率表**，所有兵种/工事对抗倍率一律恒定为 $1.00\times$。

---

## 二、 核心战斗伤害结算模型（纯属性模式）

### 1. 线性伤害公式
每次攻击动作造成的伤害遵循严格守恒的线性公式：

$$\text{Damage} = \text{TargetAttack} \times \text{CurrentCount} \times \text{AttackBuff} \times \text{ComboMul} \times \mathbf{1.00} \times \frac{100}{100 + 5 \times \text{EffectiveDef}}$$

- $\text{TargetAttack}$：根据目标所在领域（地/空/海/工事）读取对应面板基础攻击力；
- $\text{EffectiveDef}$：受击方基础防御力 $\times (1 + 0.05 \times \text{defense\_tech}) \times \text{城墙加成}$；
- **伤害倍率 $\mathbf{Multiplier} \equiv 1.00$**；
- $\text{Kills} = \lfloor \text{Damage} / \text{EffectiveHP} \rfloor$（不足一单位的余量按概率结算）。

### 2. 纯属性下的天然克制生态
1. **射程压制与移动消耗**：
   - 远程支援武器（如火箭车 2000 射程、战列舰 1600 射程、榴弹炮 3950 射程）在敌军冲入射程前可进行多轮致命齐射；
   - 近战短手单位在冲锋过程中必须承受大量路途战损，射程优势成为战场首要胜负手。
2. **装甲厚度与天然减免**：
   - 重型坦克（63.5 防御，减伤达 $76\%$）与战列舰（120 防御，减伤达 $86\%$）拥有极高的有效生存厚度（EHP），天然抵御机枪步枪等轻武器扫射，不再需要人为附加 $0.5\times$ 等防君子负倍率。
3. **专职四域火力分工**：
   - 制空战机（对空 64）对轰炸机（对空 12）具备断层式制空优势；
   - 潜艇专职反舰（对海 66），无法对空或拆工事；
   - 攻坚重器（特种兵攻坚 188、突击炮攻坚 167、火箭攻坚 179）针对高血防城防工事定标。
4. **战术阵型与掩护机制**：
   - **重坦掩护（Heavy Tank Cover）**：前排重坦存活时，强制吸收常规地面直射，掩护身后脆弱的火箭车与支援火炮；
   - **特种兵渗透（Special Forces Infiltration）**：特种兵具备极高机动速度，**无视重坦掩护拦截**，直插敌阵后排优先点杀火炮与后勤车队。

---

## 三、 倍率状态与演进对比速查表

| 作战对抗场景 | 历史旧版本设定 | v8 纯属性版本设定 | 胜负与克制主导逻辑 |
| :--- | :---: | :---: | :--- |
| **潜艇 $\to$ 战列舰 / 航母** | $3.00\times$ 鱼雷特攻 | **$1.00\times$** | 潜艇接近后以 66 对海攻击雷击；战列舰以 1600 射程与重装甲抗衡 |
| **驱逐舰 $\to$ 潜艇** | $2.50\times$ 深弹猎潜 | **$1.00\times$** | 驱逐舰凭借 400 射程 vs 潜艇 100 射程，超远先手深弹扫射脆皮潜艇 |
| **战列舰 $\to$ 驱逐舰** | $1.75\times$ 重炮压制 | **$1.00\times$** | 战列舰以 1600 射程与 9100 EHP 碾压驱逐舰 |
| **火箭车 $\to$ 重型坦克** | $2.50\times$ 撕裂重防 | **$1.00\times$** | 火箭车凭借 2000 射程超视距齐射，在重坦冲锋近身前消耗其前排耐久 |
| **重型坦克 $\to$ 轻型坦克** | $2.25\times$ 装甲碾压 | **$1.00\times$** | 重坦 63.5 防御与 385 生命，以正面对决厚重优势抵挡轻坦 |
| **轻型坦克 $\to$ 火炮/突击炮** | $2.00\times$ 机动突袭 | **$1.00\times$** | 轻坦高移速与装甲正面冲锋无防护火炮 |
| **特种兵 $\to$ 火炮/支援部队** | $2.00\times$ 渗透斩首 | **$1.00\times$** | **机制主导**：越过前排重坦掩护，直插后排优先集火低耐久火炮 |
| **城防反坦克炮 $\to$ 装甲车辆** | $2.00\times$ 穿甲猎杀 | **$1.00\times$** | 反坦克炮以 3850 远程超视距穿甲火力拦截冲锋坦克 |
| **步兵 / 摩托 $\to$ 装甲车辆** | $0.50\times$ 负向减伤 | **$1.00\times$** | 坦克 63.5 防御天然减免 76% 伤害，步兵 6 攻击自然无法破防 |
| **战斗机 $\to$ 轰炸机** | $1.15\times$ 拦截微加成 | **$1.00\times$** | 战机凭借 64 对空面板与 350 射程，对 12 对空轰炸机天然绝对压制 |
| **全体兵种与工事** | 各类零碎倍率 | **$1.00\times$** | **彻底消除所有隐性倍率，全面回归基础属性面板** |

---

## 四、 自动化仿真与沙盘平衡验证结论

### 1. 测试套件与回归覆盖
在后端的自动化测试套件中，针对纯属性模式完成了全量验证：
- **机制与伤害测试**（[`BattleMechanicsTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/BattleMechanicsTest.java)、[`BattleDamageTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/BattleDamageTest.java)）：
  - 验证了在无倍率状态下，伤害守恒、余伤不继承弱点、重坦掩护吸收直射、特种兵越障渗透点杀后排火炮、连击与技能加成等逻辑完全正确；
- **沙盘平衡测试**（[`BattleBalanceTest.java`](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/test/java/com/wargame/BattleBalanceTest.java)）：
  - 运行 26,880 场多维度沙盘实测；
  - 验证了在陆战领域，所有战斗单位在纯属性面板下均保有明确的等资源反制手段；
  - 历史对比结果已完整沉淀至 `backend/target/balance-summary.csv`。

### 2. 全量工程验证结果
- **战报呈现优化**：同步移除了战报日志中的 `[倍率×1.0]` 冗余格式化标签，仅在触发战术机制（如 `[前排承伤]`、`[指定集火]`、`[破甲XX%]`）时展示状态括号，使战报更纯净、贴合纯属性设计；
- **后端测试**：Maven 运行全套单元与集成测试，**286 项测试全部通过（0 失败，0 错误）**；
- **前端测试**：Node 运行全部前端与契约测试，**174 项测试全部通过（0 失败，0 错误）**。
