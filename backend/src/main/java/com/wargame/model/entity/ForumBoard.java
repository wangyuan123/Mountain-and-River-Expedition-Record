package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "forum_boards")
public class ForumBoard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "board_key", unique = true, nullable = false, length = 50)
    private String boardKey;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "description", length = 255)
    private String description = "";

    @Column(name = "icon", length = 50)
    private String icon = "Document";

    @Column(name = "min_prestige", nullable = false)
    private Integer minPrestige = 0;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "topic_count", nullable = false)
    private Integer topicCount = 0;

    @Column(name = "post_count", nullable = false)
    private Integer postCount = 0;
}
