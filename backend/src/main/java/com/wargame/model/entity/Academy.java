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
@Table(name = "academy")
public class Academy extends CityOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "officers", columnDefinition = "text")
    private String officers;

    /** 军校历史候选仍可招募，技能强度按现行满级规则展示并保存。 */
    public String getOfficers() {
        if (officers == null || officers.isBlank()) return officers;
        var candidates = com.wargame.util.JsonUtil.parseList(officers);
        for (var candidate : candidates) {
            Object skills = candidate.get("skills");
            if (skills != null) candidate.put("skills", com.wargame.util.JsonUtil.parseList(
                    com.wargame.model.constants.OfficerSkillDef.fullLevelSkills(com.wargame.util.JsonUtil.toJson(skills))));
        }
        return com.wargame.util.JsonUtil.toJson(candidates);
    }
}
