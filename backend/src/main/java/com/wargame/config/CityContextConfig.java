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
                request.setAttribute("game.cityScope", scope.enter(playerId, slot));
                return true;
            }
            @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
                if (request.getAttribute("game.cityScope") instanceof CityScope.Scope selected) selected.close();
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/auth/**");
    }
}
