# 城防容量与设计验证（2026-10-12）

## 已实现

- 碉堡、反坦克炮、榴弹炮、防空炮共享容量，每级城防 +3,000，10 级合计 30,000。
- 服务端建造限制、前端城防页面与建筑详情统一使用新容量。
- 建造超额或超大数量被拒绝，拒绝时不扣资源；各城市容量独立。
- 删除城防满级出兵 +100,000；攻击容量只取军衔基础和三军统帅技能，迎战额外增加半份军衔基础。

## 定向回归

后端：11 项通过，0 失败、0 错误。

```sh
MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8' mvn -o -f backend/pom.xml '-Dtest=FortCapacityTest,MayorSkillServiceTest#commanderLeadershipSkillIncreasesArmyCap,SortieBattleTest' test
```

日志：`backend-regression.log`。

前端：88 项通过，0 失败。

```sh
node --test frontend/tests/fort-capacity.test.cjs frontend/tests/officer-skills.test.cjs frontend/tests/build.test.cjs frontend/tests/map.test.cjs frontend/tests/player-profile.test.cjs frontend/tests/constants.test.cjs frontend/tests/battle-defaults.test.cjs frontend/tests/campaign-dispatch.test.cjs
```

日志：`frontend-regression.log`。

## 射程设计计算

2026-10-13已将反坦克炮、榴弹炮基础射程统一为1,850，与防空炮一致。目录中的原JSON保留2026-10-12生成时的旧参数，当前火箭车征服战开火与接触回合见 [更新后的战术距离设计](../../docs/BATTLE_COMMAND_AND_DISTANCE_DESIGN.md#5-2026-10-13-工事射程调整后的火箭车征服战)。下面脚本会读取执行时的源码并覆盖原JSON，本次文档更新未重新生成历史结果。

```sh
python3 output/city-fortification-design-20261012/range_distance_analysis.py
```

结果：`range-distance-analysis.json`，120 组单兵种对工事的理论射程进入场景。最终容量为每级 3,000，射程提案为每级 +1%、满级 +10%。忽略伤亡与指令变化，不能当作完整战斗模拟。

当前引擎基础距离：`D0 = max_pairs(3 × (stepA + stepB) + max(techRangeA, techRangeB)) + 100`。用户已确认新的守城提案：仅有实际参战工事的守城战采用 `D = D0 + 500`，城防建筑额外射程不参与 D0，其他战斗沿用 D0。脚本已按此提案生成结果，战斗引擎尚未接入。设计说明见 `docs/CITY_FORTIFICATION_DESIGN_20261012.md`。

## 状态与限制

删除城防防御倍率、工事城防射程加成、守城距离 +500 和超容量历史库存部署规则仍是提案。当前导入入口可写入超容量库存，现有参战流程未裁剪该库存，普通建造入口已执行新容量限制。

没有修改战斗距离与 24 回合上限，没有重新标定 PVP，也没有部署、重启或修改真实数据库。当前战斗规则版本为 `balance-v13-contact-movement-20261012`。后端源代码需重启后生效，前端需刷新。
