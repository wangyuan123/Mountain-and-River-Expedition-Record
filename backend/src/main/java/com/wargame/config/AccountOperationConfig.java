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
                try { return invocation.proceed(); }
                catch (RuntimeException | Error e) { throw e; }
                catch (Throwable e) { throw new IllegalStateException(e); }
            });
        };
        var advisor = new DefaultPointcutAdvisor(pointcut, advice);
        advisor.setOrder(-100);
        return advisor;
    }
}
