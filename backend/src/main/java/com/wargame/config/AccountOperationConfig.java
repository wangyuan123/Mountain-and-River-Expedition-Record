package com.wargame.config;

import com.wargame.model.UserPrincipal;
import com.wargame.security.AccountException;
import com.wargame.service.AccountService;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.Set;

/** 请求取得玩家行锁后再检查会话，注销与已经通过 HTTP 鉴权的游戏操作互斥。 */
@Configuration
public class AccountOperationConfig {
    @Bean
    @org.springframework.context.annotation.Role(org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE)
    public static DefaultPointcutAdvisor accountOperationAdvisor(ObjectProvider<AccountService> accounts,
                                                          ObjectProvider<com.wargame.service.compliance.AntiAddictionService> protection,
                                                          ObjectProvider<PlatformTransactionManager> transactions) {
        var pointcut = new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> type) {
                if (!type.getPackageName().equals("com.wargame.controller")) return false;
                return type != com.wargame.controller.AuthController.class || Set.of("dismissTutorial", "disable", "deletionPreview").contains(method.getName());
            }
        };
        MethodInterceptor advice = invocation -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal user)) return invocation.proceed();
            return new TransactionTemplate(transactions.getObject()).execute(status -> {
                var player = accounts.getObject().lockPlayer(user.getPlayerId());
                if (!player.accountActive() || player.getAuthVersion() != user.getAuthVersion())
                    throw new AccountException("ACCOUNT_UNAVAILABLE", "登录状态已失效，请重新登录");
                boolean gameOperation = invocation.getMethod().getDeclaringClass() != com.wargame.controller.ComplianceController.class
                        && (invocation.getMethod().getDeclaringClass() != com.wargame.controller.AuthController.class
                        || invocation.getMethod().getName().equals("dismissTutorial"));
                var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
                var request = attributes instanceof org.springframework.web.context.request.ServletRequestAttributes web ? web.getRequest() : null;
                String secret = request == null ? null : request.getHeader("X-Play-Session");
                String path = request == null ? "" : request.getRequestURI();
                if (gameOperation) {
                    // 锁后重新判断绝对截止时间，排队等待不获得额外游戏时间。
                    protection.getObject().requireOperationAccess(player, secret, path);
                }
                try {
                    Object result = invocation.proceed();
                    // 长事务跨过截止时间时不提交游戏指令，也不向请求方返回游戏载荷。
                    if (gameOperation) protection.getObject().requireAccess(player, secret, path);
                    return result;
                }
                catch (RuntimeException | Error e) { throw e; }
                catch (Throwable e) { throw new IllegalStateException(e); }
            });
        };
        var advisor = new DefaultPointcutAdvisor(pointcut, advice);
        advisor.setOrder(-100);
        return advisor;
    }
}
