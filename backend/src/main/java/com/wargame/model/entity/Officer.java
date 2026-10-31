package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@lombok.EqualsAndHashCode(callSuper = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "officers")
public class Officer extends CityOwnedEntity {

    /** 薪资宽限的虚拟入职起点；仅冻结顺延，不能修改真实招募时间。 */
    @Column(name = "salary_grace_from")
    private Long salaryGraceFrom;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "name_renamed_at", nullable = false)
    private long nameRenamedAt = 0L;

    @Column(name = "star")
    private Integer star;

    @Column(name = "level")
    private Integer level;

    @Column(name = "military")
    private Integer military;

    @Column(name = "defense")
    private Integer defense;

    @Column(name = "logistics")
    private Integer logistics;

    @Column(name = "knowledge")
    private Integer knowledge;

    @Column(name = "loyalty")
    private Integer loyalty;

    @Column(name = "salary")
    private Integer salary;

    @Column(name = "exp")
    private Long exp;

    @Column(name = "role", length = 50)
    private String role;

    @Column(name = "recruit_at")
    private Long recruitAt;

    @Column(name = "reward_at")
    private Long rewardAt;

    @Column(name = "skills", columnDefinition = "text")
    private String skills;

    /** 已学技能固定使用原满级效果；读取旧存档和写入新技能使用同一口径。 */
    public String getSkills() {
        return com.wargame.model.constants.OfficerSkillDef.fullLevelSkills(skills);
    }

    public void setSkills(String value) {
        skills = com.wargame.model.constants.OfficerSkillDef.fullLevelSkills(value);
    }

    @Column(name = "bio", columnDefinition = "text")
    private String bio;

    @Column(name = "attr_points", nullable = false)
    private Integer attrPoints = 0;
    // 只追踪实际自主加点；招募资质、升星等永久属性不作为可退点数。
    @Column(name = "assigned_military", nullable = false)
    private int assignedMilitary;
    @Column(name = "assigned_defense", nullable = false)
    private int assignedDefense;
    @Column(name = "assigned_logistics", nullable = false)
    private int assignedLogistics;
    @Column(name = "assigned_knowledge", nullable = false)
    private int assignedKnowledge;
    @Column(name = "legacy_attr_allocation", nullable = false)
    private boolean legacyAttrAllocation;

    public int assignedPoints() {
        return assignedMilitary + assignedDefense + assignedLogistics + assignedKnowledge;
    }

}
