package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id")
    private Long playerId;

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "content", nullable = false, length = 80)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
