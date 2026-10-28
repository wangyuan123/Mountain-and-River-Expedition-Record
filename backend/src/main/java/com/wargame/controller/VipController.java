package com.wargame.controller;

import com.wargame.model.dto.VipDtos;
import com.wargame.model.entity.Player;
import com.wargame.service.AuthService;
import com.wargame.service.VipService;
import com.wargame.service.GameStateService;
import com.wargame.service.AvatarStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/game/vip")
@RequiredArgsConstructor
public class VipController {

    private final AuthService authService;
    private final VipService vipService;
    private final GameStateService gameStateService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getVipStatus() {
        Player player = authService.getCurrentPlayer();
        return ResponseEntity.ok(vipService.getVipStatus(player.getId()));
    }

    @PostMapping("/daily-salary/claim")
    public ResponseEntity<Map<String, Object>> claimDailySalary(@RequestBody(required = false) VipDtos.DailySalaryClaimRequest request) {
        Player player = authService.getCurrentPlayer();
        Integer targetCitySlot = request != null ? request.targetCitySlot() : null;
        return rewardResponse(player.getId(), vipService.claimDailySalary(player.getId(), targetCitySlot));
    }

    @PostMapping("/level-pack/claim")
    public ResponseEntity<Map<String, Object>> claimLevelPack(@RequestBody VipDtos.LevelPackClaimRequest request) {
        Player player = authService.getCurrentPlayer();
        if (request == null || request.targetLevel() == null) {
            throw new IllegalArgumentException("请指定目标等级");
        }
        return rewardResponse(player.getId(), vipService.claimLevelPack(player.getId(), request.targetLevel(), request.targetCitySlot()));
    }

    @PostMapping("/level-pack/claim-all")
    public ResponseEntity<Map<String, Object>> claimAllLevelPacks(@RequestBody(required = false) VipDtos.LevelPackClaimAllRequest request) {
        Player player = authService.getCurrentPlayer();
        Integer targetCitySlot = request != null ? request.targetCitySlot() : null;
        return rewardResponse(player.getId(), vipService.claimAllLevelPacks(player.getId(), targetCitySlot));
    }

    @PostMapping("/milestone/claim")
    public ResponseEntity<Map<String, Object>> claimMilestone(@RequestBody VipDtos.MilestoneClaimRequest request) {
        Player player = authService.getCurrentPlayer();
        if (request == null || request.milestoneCents() == null) {
            throw new IllegalArgumentException("请指定里程碑节点");
        }
        return rewardResponse(player.getId(), vipService.claimMilestoneReward(player.getId(), request.milestoneCents(), request.targetCitySlot()));
    }

    /** 下发当前浏览城市的资源和账号道具快照，向分城送奖时也不切换玩家视角。 */
    private ResponseEntity<Map<String, Object>> rewardResponse(Long playerId, Map<String, Object> result) {
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/appease-all")
    public ResponseEntity<Map<String, Object>> allCitiesAppease(@RequestBody(required = false) VipDtos.AllCitiesAppeaseRequest request) {
        Player player = authService.getCurrentPlayer();
        String type = (request != null && request.appeaseType() != null) ? request.appeaseType() : "free_amnesty";
        return ResponseEntity.ok(vipService.allCitiesAppease(player.getId(), type));
    }

    @PostMapping("/universal-skill/convert")
    public ResponseEntity<Map<String, Object>> convertUniversalSkill(@RequestBody VipDtos.UniversalSkillConvertRequest request) {
        Player player = authService.getCurrentPlayer();
        if (request == null || request.targetSkillId() == null) {
            throw new IllegalArgumentException("请选择目标技能");
        }
        return ResponseEntity.ok(vipService.convertUniversalSkillBook(player.getId(), request.targetSkillId()));
    }

    @PostMapping("/badge/toggle")
    public ResponseEntity<Map<String, Object>> toggleBadge(@RequestBody VipDtos.VipBadgeToggleRequest request) {
        Player player = authService.getCurrentPlayer();
        boolean visible = request != null && Boolean.TRUE.equals(request.visible());
        return ResponseEntity.ok(vipService.toggleVipBadge(player.getId(), visible));
    }

    @PostMapping("/avatar/frame")
    public ResponseEntity<Map<String, Object>> setAvatarFrame(@RequestBody VipDtos.AvatarFrameRequest request) {
        Player player = authService.getCurrentPlayer();
        String frameId = request != null ? request.frameId() : "default";
        return ResponseEntity.ok(vipService.setAvatarFrame(player.getId(), frameId));
    }

    @PostMapping("/avatar/url")
    public ResponseEntity<Map<String, Object>> setCustomAvatarUrl(@RequestBody VipDtos.CustomAvatarUrlRequest request) {
        Player player = authService.getCurrentPlayer();
        String url = request != null ? request.url() : null;
        return ResponseEntity.ok(vipService.setCustomAvatarUrl(player.getId(), url));
    }

    @PostMapping("/avatar/upload")
    public ResponseEntity<Map<String, Object>> uploadAvatar(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestBody(required = false) VipDtos.CustomAvatarUploadRequest body) throws IOException {
        Player player = authService.getCurrentPlayer();
        int vipLevel = player.getVipLevel() != null ? player.getVipLevel() : 0;
        if (vipLevel < 3) {
            throw new IllegalArgumentException("自定义头像功能需要达到 VIP 3 (战略支柱长官)");
        }

        byte[] imageBytes = null;
        String extension = "png";

        if (file != null && !file.isEmpty()) {
            if (file.getSize() > AvatarStorageService.MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("头像文件大小不能超过 1MB");
            }
            String contentType = file.getContentType();
            if (contentType != null && contentType.contains("webp")) extension = "webp";
            else if (contentType != null && contentType.contains("jpeg")) extension = "jpg";
            imageBytes = file.getBytes();
        } else if (body != null && body.dataUrl() != null && !body.dataUrl().isBlank()) {
            String dataUrl = body.dataUrl().trim();
            if (!dataUrl.startsWith("data:image/")) {
                throw new IllegalArgumentException("无效的图片数据格式");
            }
            int commaIdx = dataUrl.indexOf(',');
            if (commaIdx == -1) {
                throw new IllegalArgumentException("无效的 Base64 图片数据");
            }
            String mime = dataUrl.substring(5, commaIdx);
            if (mime.contains("webp")) extension = "webp";
            else if (mime.contains("jpeg")) extension = "jpg";
            String base64 = dataUrl.substring(commaIdx + 1);
            if (base64.length() > 4 * ((AvatarStorageService.MAX_IMAGE_BYTES + 2) / 3)) {
                throw new IllegalArgumentException("头像大小不能超过 1MB");
            }
            imageBytes = Base64.getDecoder().decode(base64);
            if (imageBytes.length > AvatarStorageService.MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("头像大小不能超过 1MB");
            }
        } else {
            throw new IllegalArgumentException("未提供有效的头像文件或数据");
        }

        validateImageMagic(imageBytes);

        return ResponseEntity.ok(vipService.uploadCustomAvatar(player.getId(), imageBytes, extension));
    }

    private void validateImageMagic(byte[] data) {
        if (data == null || data.length < 4) {
            throw new IllegalArgumentException("文件过小或无效");
        }
        boolean isPng = (data[0] == (byte) 0x89 && data[1] == (byte) 0x50 && data[2] == (byte) 0x4E && data[3] == (byte) 0x47);
        boolean isJpeg = (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8 && data[2] == (byte) 0xFF);
        boolean isWebp = data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';

        if (!isPng && !isJpeg && !isWebp) {
            throw new IllegalArgumentException("仅支持 PNG、JPEG 或 WebP 格式的图片文件");
        }
    }
}
