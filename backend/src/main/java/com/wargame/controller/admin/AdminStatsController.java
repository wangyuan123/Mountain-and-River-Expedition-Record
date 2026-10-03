package com.wargame.controller.admin;

import com.wargame.repository.AdminOperationLogRepository;
import com.wargame.repository.NpcCityRepository;
import com.wargame.repository.PlayerCityRepository;
import com.wargame.repository.PlayerRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/stats")
public class AdminStatsController {

    private final PlayerRepository playerRepository;
    private final NpcCityRepository npcCityRepository;
    private final PlayerCityRepository playerCityRepository;
    private final AdminOperationLogRepository logRepository;

    public AdminStatsController(PlayerRepository playerRepository,
                                NpcCityRepository npcCityRepository,
                                PlayerCityRepository playerCityRepository,
                                AdminOperationLogRepository logRepository) {
        this.playerRepository = playerRepository;
        this.npcCityRepository = npcCityRepository;
        this.playerCityRepository = playerCityRepository;
        this.logRepository = logRepository;
    }

    @GetMapping
    public Map<String, Object> getStats() {
        long totalPlayers = playerRepository.count();
        long totalNpc = npcCityRepository.count();
        long totalCities = playerCityRepository.count();
        var recentLogs = logRepository.findAll(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();

        return Map.of(
                "totalPlayers", totalPlayers,
                "totalNpc", totalNpc,
                "totalCities", totalCities,
                "recentLogs", recentLogs
        );
    }
}
