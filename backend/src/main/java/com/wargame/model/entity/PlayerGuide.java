package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "player_guide")
@IdClass(PlayerGuide.PK.class)
public class PlayerGuide {

    @Id
    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Id
    @Column(name = "step_id", nullable = false, length = 64)
    private String stepId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "completed_at")
    private Long completedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PK implements Serializable {
        private Long playerId;
        private String stepId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(playerId, pk.playerId) && Objects.equals(stepId, pk.stepId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, stepId);
        }
    }
}
