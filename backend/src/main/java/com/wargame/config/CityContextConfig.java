package com.wargame.config;

import com.wargame.model.UserPrincipal;
import com.wargame.service.CityScope;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

/** Pin every authenticated request to its initiating city, even if another tab switches. */
@Configuration
public class CityContextConfig implements WebMvcConfigurer {
    private final CityScope scope;
    public CityContextConfig(CityScope scope) { this.scope = scope; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal user)) return true;
                Long playerId = user.getPlayerId();
                String header = request.getHeader("X-City-Id");
                int slot = scope.slot(playerId);
                if (header != null && !header.isBlank()) {
                    Long cityId;
                    try { cityId = Long.valueOf(header); } catch (NumberFormatException e) { throw new IllegalArgumentException("城市编号无效"); }
                    slot = scope.requireOwned(playerId, cityId, true).getCitySlot();
                }
                if (isCityWrite(request) && isFrozenCityWrite(request.getRequestURI())) {
                    try (var ignored = scope.enter(playerId, slot)) {
                        scope.assertWritable(playerId);
                    }
                }
                request.setAttribute("game.cityScope", scope.enter(playerId, slot));
                return true;
            }
            @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
                if (request.getAttribute("game.cityScope") instanceof CityScope.Scope selected) selected.close();
            }
        }).addPathPatterns("/api/game/**");
    }

    private static boolean isCityWrite(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    private static boolean isFrozenCityWrite(String uri) {
        if (uri == null) return false;
        if (uri.contains("/game/surrender") || uri.contains("/game/mail") || uri.contains("/game/chat")
                || uri.contains("/game/guild") || uri.contains("/game/private") || uri.contains("/game/shop")
                || uri.contains("/game/quest") || uri.contains("/game/rank") || uri.contains("/game/cities/switch")) return false;
        return uri.contains("/game/build") || uri.contains("/game/army") || uri.contains("/game/tech")
                || uri.contains("/game/officer") || uri.contains("/game/fort") || uri.contains("/game/depot")
                || uri.contains("/game/market") || uri.contains("/game/cities/teleport") || uri.contains("/game/wounded") || uri.contains("/game/unit-equipment")
                || uri.contains("/game/prisoner") || uri.endsWith("/game/world/dispatch")
                || uri.endsWith("/game/world/stop-gather") || uri.endsWith("/game/world/cancel-march")
                || uri.endsWith("/game/world/speedup-march") || uri.contains("/game/world/battle/command")
                || uri.contains("/game/world/support/dismiss") || uri.startsWith("/api/game/wild/");
    }
}
