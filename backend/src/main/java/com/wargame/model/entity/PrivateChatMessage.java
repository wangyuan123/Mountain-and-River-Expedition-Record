package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "private_chat_messages")
public class PrivateChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long senderId;
    @Column(nullable = false) private Long recipientId;
    @Column(nullable = false, length = 80) private String content;
    @Column(nullable = false) private Long createdAt;
    @Column(nullable = false) private boolean readByRecipient;
}
