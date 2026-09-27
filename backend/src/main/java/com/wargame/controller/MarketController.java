package com.wargame.controller;

import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.MarketOrder;
import com.wargame.service.AuthService;
import com.wargame.service.CityScope;
import com.wargame.service.GameStateService;
import com.wargame.service.MarketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/game/market")
public class MarketController {

    private final AuthService authService;
    private final MarketService marketService;
    private final GameStateService gameStateService;
    private final CityScope cityScope;

    public MarketController(AuthService authService,
                            MarketService marketService,
                            GameStateService gameStateService,
                            CityScope cityScope) {
        this.authService = authService;
        this.marketService = marketService;
        this.gameStateService = gameStateService;
        this.cityScope = cityScope;
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview(
            @RequestParam(required = false, defaultValue = "all") String resourceType,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        Long playerId = authService.getCurrentPlayer().getId();
        int citySlot = cityScope.slot(playerId);
        Map<String, Object> data = marketService.getMarketOverview(playerId, citySlot, resourceType, page, size);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/my-orders")
    public ResponseEntity<List<MarketOrder>> getMyOrders() {
        Long playerId = authService.getCurrentPlayer().getId();
        return ResponseEntity.ok(marketService.getMyOrders(playerId));
    }

    @PostMapping("/order/create")
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody GameDtos.MarketCreateOrderRequest req) {
        Long playerId = authService.getCurrentPlayer().getId();
        int citySlot = cityScope.slot(playerId);
        MarketOrder order = marketService.createOrder(
                playerId,
                citySlot,
                req.resourceType(),
                req.amount(),
                req.pricePerUnit()
        );
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("order", order);
        res.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(res);
    }

    @PostMapping("/order/buy")
    public ResponseEntity<Map<String, Object>> buyOrder(@RequestBody GameDtos.MarketBuyOrderRequest req) {
        Long playerId = authService.getCurrentPlayer().getId();
        int citySlot = cityScope.slot(playerId);
        MarketOrder order = marketService.buyOrder(playerId, citySlot, req.orderId());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("order", order);
        res.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(res);
    }

    @PostMapping("/order/cancel")
    public ResponseEntity<Map<String, Object>> cancelOrder(@RequestBody GameDtos.MarketCancelOrderRequest req) {
        Long playerId = authService.getCurrentPlayer().getId();
        int citySlot = cityScope.slot(playerId);
        MarketOrder order = marketService.cancelOrder(playerId, citySlot, req.orderId());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("order", order);
        res.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(res);
    }

    @PostMapping("/exchange")
    public ResponseEntity<Map<String, Object>> systemExchange(@RequestBody GameDtos.MarketExchangeRequest req) {
        Long playerId = authService.getCurrentPlayer().getId();
        int citySlot = cityScope.slot(playerId);
        Map<String, Object> exchangeData = marketService.systemExchange(
                playerId,
                citySlot,
                req.fromRes(),
                req.toRes(),
                req.amount()
        );
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("exchange", exchangeData);
        res.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(res);
    }
}
