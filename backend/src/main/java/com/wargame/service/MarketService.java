package com.wargame.service;

import com.wargame.model.entity.Building;
import com.wargame.model.entity.MarketOrder;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.Resources;
import com.wargame.repository.BuildingRepository;
import com.wargame.repository.MarketOrderRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.ResourcesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class MarketService {

    private final MarketOrderRepository marketOrderRepository;
    private final ResourcesRepository resourcesRepository;
    private final BuildingRepository buildingRepository;
    private final PlayerRepository playerRepository;

    @Autowired
    private CityScope cityScope;

    public MarketService(MarketOrderRepository marketOrderRepository,
                         ResourcesRepository resourcesRepository,
                         BuildingRepository buildingRepository,
                         PlayerRepository playerRepository) {
        this.marketOrderRepository = marketOrderRepository;
        this.resourcesRepository = resourcesRepository;
        this.buildingRepository = buildingRepository;
        this.playerRepository = playerRepository;
    }

    public int getExchangeLevel(Long playerId, int citySlot) {
        List<Building> list = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, citySlot, "exchange");
        return list.isEmpty() ? 0 : Objects.requireNonNullElse(list.get(0).getLevel(), 0);
    }

    public int getMarketOrderSlotCap(Long playerId, int citySlot) {
        int exLv = getExchangeLevel(playerId, citySlot);
        List<Building> depots = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, citySlot, "depot");
        long depotCount = depots.stream().filter(b -> b.getLevel() != null && b.getLevel() > 0).count();
        // 基础2个槽位 + 交易所每2级+1 + 仓库有效槽位
        return 2 + (exLv / 2) + (int) depotCount;
    }

    public double getExchangeRate(int exchangeLevel) {
        if (exchangeLevel <= 0) return 0.50;
        return Math.min(0.90, 0.50 + exchangeLevel * 0.04);
    }

    public double getTaxRate(int exchangeLevel) {
        if (exchangeLevel <= 0) return 0.10;
        return Math.max(0.05, 0.10 - (exchangeLevel - 1) * 0.005);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMarketOverview(Long playerId, int citySlot, String resourceType, int page, int size) {
        int pageIndex = Math.max(0, page);
        int pageSize = Math.min(50, Math.max(1, size));
        PageRequest pageRequest = PageRequest.of(pageIndex, pageSize);

        Page<MarketOrder> ordersPage;
        if (resourceType != null && !resourceType.isBlank() && !"all".equalsIgnoreCase(resourceType)) {
            ordersPage = marketOrderRepository.findByStatusAndResourceTypeOrderByPricePerUnitAscCreatedAtDesc("ACTIVE", resourceType, pageRequest);
        } else {
            ordersPage = marketOrderRepository.findByStatusOrderByPricePerUnitAscCreatedAtDesc("ACTIVE", pageRequest);
        }

        int exLv = getExchangeLevel(playerId, citySlot);
        int slotCap = getMarketOrderSlotCap(playerId, citySlot);
        int myActiveCount = marketOrderRepository.countBySellerIdAndStatus(playerId, "ACTIVE");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exchangeLevel", exLv);
        result.put("exchangeRate", getExchangeRate(exLv));
        result.put("taxRate", getTaxRate(exLv));
        result.put("slotCap", slotCap);
        result.put("usedSlots", myActiveCount);
        result.put("orders", ordersPage.getContent());
        result.put("totalPages", ordersPage.getTotalPages());
        result.put("totalElements", ordersPage.getTotalElements());
        result.put("currentPage", pageIndex);
        return result;
    }

    @Transactional(readOnly = true)
    public List<MarketOrder> getMyOrders(Long playerId) {
        return marketOrderRepository.findBySellerIdOrderByCreatedAtDesc(playerId);
    }

    @Transactional
    public MarketOrder createOrder(Long playerId, int citySlot, String resourceType, int amount, int pricePerUnit) {
        int exLv = getExchangeLevel(playerId, citySlot);
        if (exLv <= 0) {
            throw new IllegalArgumentException("当前城池未建成交易所，无法上架");
        }
        if (!Set.of("food", "steel", "oil", "rare").contains(resourceType)) {
            throw new IllegalArgumentException("不支持的交易资源类型");
        }
        if (amount <= 0 || amount > 100_000_000) {
            throw new IllegalArgumentException("上架数量不合理");
        }
        if (pricePerUnit <= 0 || pricePerUnit > 1_000_000) {
            throw new IllegalArgumentException("单价不合理");
        }

        int slotCap = getMarketOrderSlotCap(playerId, citySlot);
        int activeCount = marketOrderRepository.countBySellerIdAndStatus(playerId, "ACTIVE");
        if (activeCount >= slotCap) {
            throw new IllegalArgumentException("上架槽位已满 (" + activeCount + "/" + slotCap + ")");
        }

        Resources res = resourcesRepository.findCityForTreatment(playerId, citySlot)
                .orElseThrow(() -> new IllegalArgumentException("资源信息不存在"));

        int current = getResourceAmount(res, resourceType);
        if (current < amount) {
            throw new IllegalArgumentException("当前城池资源不足以支持上架");
        }

        // 扣减资源
        deductResource(res, resourceType, amount);
        resourcesRepository.save(res);

        Player seller = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        MarketOrder order = new MarketOrder();
        order.setSellerId(playerId);
        order.setSellerName(seller.getUsername());
        order.setSellerCitySlot(citySlot);
        order.setResourceType(resourceType);
        order.setAmount(amount);
        order.setPricePerUnit(pricePerUnit);
        long totalPrice = (long) amount * pricePerUnit;
        order.setTotalPrice(totalPrice);
        order.setTaxRate(getTaxRate(exLv));
        order.setStatus("ACTIVE");
        order.setCreatedAt(LocalDateTime.now());

        return marketOrderRepository.save(order);
    }

    @Transactional
    public MarketOrder buyOrder(Long buyerId, int buyerCitySlot, Long orderId) {
        int exLv = getExchangeLevel(buyerId, buyerCitySlot);
        if (exLv <= 0) {
            throw new IllegalArgumentException("未建成交易所，无法进行市场交易");
        }

        MarketOrder order = marketOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalArgumentException("挂单不存在"));

        if (!"ACTIVE".equals(order.getStatus())) {
            throw new IllegalArgumentException("该挂单已被购买或已下架");
        }
        if (Objects.equals(order.getSellerId(), buyerId)) {
            throw new IllegalArgumentException("不能购买自己上架的商品");
        }

        Resources buyerRes = resourcesRepository.findCityForTreatment(buyerId, buyerCitySlot)
                .orElseThrow(() -> new IllegalArgumentException("买方资源不存在"));

        long costGold = order.getTotalPrice();
        int buyerGold = Objects.requireNonNullElse(buyerRes.getGold(), 0);
        if (buyerGold < costGold) {
            throw new IllegalArgumentException("黄金不足，无法购买该挂单 (需 " + costGold + " 黄金)");
        }

        Player buyer = playerRepository.findById(buyerId)
                .orElseThrow(() -> new IllegalArgumentException("买方不存在"));

        // 1. 买家扣除黄金，增加资源
        buyerRes.setGold((int) (buyerGold - costGold));
        addResource(buyerRes, order.getResourceType(), order.getAmount());
        resourcesRepository.save(buyerRes);

        // 2. 卖家结算黄金（扣除税费）
        double tax = Objects.requireNonNullElse(order.getTaxRate(), 0.10);
        long earnGold = Math.max(0, Math.round(costGold * (1.0 - tax)));

        Resources sellerRes = resourcesRepository.findCityForTreatment(order.getSellerId(), order.getSellerCitySlot())
                .orElse(null);
        if (sellerRes != null) {
            sellerRes.setGold(Objects.requireNonNullElse(sellerRes.getGold(), 0) + (int) earnGold);
            resourcesRepository.save(sellerRes);
        }

        // 3. 标记订单为已售出
        order.setStatus("SOLD");
        order.setBuyerId(buyerId);
        order.setBuyerName(buyer.getUsername());
        order.setCompletedAt(LocalDateTime.now());

        return marketOrderRepository.save(order);
    }

    @Transactional
    public MarketOrder cancelOrder(Long playerId, int citySlot, Long orderId) {
        MarketOrder order = marketOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalArgumentException("挂单不存在"));

        if (!Objects.equals(order.getSellerId(), playerId)) {
            throw new IllegalArgumentException("无权下架此挂单");
        }
        if (!"ACTIVE".equals(order.getStatus())) {
            throw new IllegalArgumentException("该挂单已完成或已下架");
        }

        // 返还资源给卖家（优先返还到当前请求城池，或原上架城池）
        int targetSlot = order.getSellerCitySlot();
        Resources res = resourcesRepository.findCityForTreatment(playerId, targetSlot)
                .orElseGet(() -> resourcesRepository.findCityForTreatment(playerId, citySlot)
                        .orElseThrow(() -> new IllegalArgumentException("资源记录不存在")));

        addResource(res, order.getResourceType(), order.getAmount());
        resourcesRepository.save(res);

        order.setStatus("CANCELLED");
        order.setCompletedAt(LocalDateTime.now());

        return marketOrderRepository.save(order);
    }

    @Transactional
    public Map<String, Object> systemExchange(Long playerId, int citySlot, String fromRes, String toRes, int amount) {
        int exLv = getExchangeLevel(playerId, citySlot);
        if (exLv <= 0) {
            throw new IllegalArgumentException("当前城池未建成交易所，无法调配资源");
        }
        if (!Set.of("food", "steel", "oil", "rare").contains(fromRes) ||
            !Set.of("food", "steel", "oil", "rare").contains(toRes)) {
            throw new IllegalArgumentException("不支持调配的资源类型");
        }
        if (fromRes.equals(toRes)) {
            throw new IllegalArgumentException("兑换源与目标资源不能相同");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("兑换数量无效");
        }

        Resources res = resourcesRepository.findCityForTreatment(playerId, citySlot)
                .orElseThrow(() -> new IllegalArgumentException("资源信息不存在"));

        int current = getResourceAmount(res, fromRes);
        if (current < amount) {
            throw new IllegalArgumentException("源资源不足");
        }

        double rate = getExchangeRate(exLv);
        int gain = (int) Math.floor(amount * rate);
        if (gain <= 0) {
            throw new IllegalArgumentException("兑换获得数量不足 1");
        }

        deductResource(res, fromRes, amount);
        addResource(res, toRes, gain);
        resourcesRepository.save(res);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fromRes", fromRes);
        result.put("toRes", toRes);
        result.put("cost", amount);
        result.put("gain", gain);
        result.put("rate", rate);
        return result;
    }

    private int getResourceAmount(Resources res, String type) {
        return switch (type) {
            case "food" -> Objects.requireNonNullElse(res.getFood(), 0);
            case "steel" -> Objects.requireNonNullElse(res.getSteel(), 0);
            case "oil" -> Objects.requireNonNullElse(res.getOil(), 0);
            case "rare" -> Objects.requireNonNullElse(res.getRare(), 0);
            default -> 0;
        };
    }

    private void deductResource(Resources res, String type, int amount) {
        switch (type) {
            case "food" -> res.setFood(Math.max(0, Objects.requireNonNullElse(res.getFood(), 0) - amount));
            case "steel" -> res.setSteel(Math.max(0, Objects.requireNonNullElse(res.getSteel(), 0) - amount));
            case "oil" -> res.setOil(Math.max(0, Objects.requireNonNullElse(res.getOil(), 0) - amount));
            case "rare" -> res.setRare(Math.max(0, Objects.requireNonNullElse(res.getRare(), 0) - amount));
        }
    }

    private void addResource(Resources res, String type, int amount) {
        switch (type) {
            case "food" -> res.setFood(Objects.requireNonNullElse(res.getFood(), 0) + amount);
            case "steel" -> res.setSteel(Objects.requireNonNullElse(res.getSteel(), 0) + amount);
            case "oil" -> res.setOil(Objects.requireNonNullElse(res.getOil(), 0) + amount);
            case "rare" -> res.setRare(Objects.requireNonNullElse(res.getRare(), 0) + amount);
        }
    }
}
