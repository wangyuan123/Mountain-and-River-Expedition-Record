package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "forum_tips")
public class ForumTip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "topic_id", nullable = false)
    private Long topicId;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "sender_name", nullable = false, length = 50)
    private String senderName;

    @Column(name = "amount_gold", nullable = false)
    private Integer amountGold;

    @Column(name = "message", length = 255)
    private String message = "";

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
