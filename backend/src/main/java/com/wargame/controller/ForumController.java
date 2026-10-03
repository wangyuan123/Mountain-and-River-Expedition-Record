package com.wargame.controller;

import com.wargame.model.dto.ForumDtos.*;
import com.wargame.model.entity.ForumBoard;
import com.wargame.service.AuthService;
import com.wargame.service.ForumService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/forum")
public class ForumController {

    private final ForumService forumService;
    private final AuthService authService;

    public ForumController(ForumService forumService, AuthService authService) {
        this.forumService = forumService;
        this.authService = authService;
    }

    @GetMapping("/boards")
    public ResponseEntity<List<ForumBoard>> getBoards() {
        return ResponseEntity.ok(forumService.getBoards());
    }

    @GetMapping("/topics")
    public ResponseEntity<Page<TopicSummaryDto>> getTopics(
            @RequestParam(required = false) Long boardId,
            @RequestParam(required = false) Boolean isEssence,
            @RequestParam(required = false) String topicType,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size
    ) {
        return ResponseEntity.ok(forumService.getTopics(boardId, isEssence, topicType, query, page, size));
    }

    @GetMapping("/hot")
    public ResponseEntity<List<TopicSummaryDto>> getHotTopics() {
        return ResponseEntity.ok(forumService.getHotTopics());
    }

    @GetMapping("/topics/{id}")
    public ResponseEntity<TopicDetailDto> getTopicDetail(@PathVariable Long id) {
        return ResponseEntity.ok(forumService.getTopicDetail(id));
    }

    @PostMapping("/topics")
    public ResponseEntity<TopicSummaryDto> createTopic(@RequestBody CreateTopicRequest req) {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(forumService.createTopic(playerId, req));
    }

    @GetMapping("/topics/{id}/replies")
    public ResponseEntity<Page<ReplyDto>> getReplies(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(forumService.getReplies(id, page, size));
    }

    @PostMapping("/topics/{id}/replies")
    public ResponseEntity<ReplyDto> createReply(
            @PathVariable Long id,
            @RequestBody CreateReplyRequest req
    ) {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(forumService.createReply(playerId, id, req));
    }

    @PostMapping("/like")
    public ResponseEntity<Map<String, Object>> toggleLike(
            @RequestParam String targetType,
            @RequestParam Long targetId
    ) {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(forumService.toggleLike(playerId, targetType, targetId));
    }

    @PostMapping("/topics/{id}/accept-bounty")
    public ResponseEntity<Map<String, Object>> acceptBounty(
            @PathVariable Long id,
            @RequestParam Long replyId
    ) {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(forumService.acceptBounty(playerId, id, replyId));
    }

    @PostMapping("/topics/{id}/tip")
    public ResponseEntity<Map<String, Object>> tipTopic(
            @PathVariable Long id,
            @RequestBody TipRequest req
    ) {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(forumService.tipTopic(playerId, id, req));
    }

    @PostMapping("/topics/{id}/moderate")
    public ResponseEntity<Map<String, Object>> moderateTopic(
            @PathVariable Long id,
            @RequestBody AdminTopicActionRequest req
    ) {
        Long playerId = authService.getCurrentPlayer().getId();
        boolean isAdmin = SecurityContextHolder.getContext().getAuthentication() != null &&
                SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return ResponseEntity.ok(forumService.moderateTopic(playerId, id, req, isAdmin));
    }

    @GetMapping("/profile")
    public ResponseEntity<ForumProfileDto> getProfile() {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(forumService.getProfile(playerId));
    }

    @GetMapping("/my-topics")
    public ResponseEntity<Page<TopicSummaryDto>> getMyTopics(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size
    ) {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(forumService.getMyTopics(playerId, page, size));
    }
}
