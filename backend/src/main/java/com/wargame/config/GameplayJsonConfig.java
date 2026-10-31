package com.wargame.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 数量、等级和标识必须是整数，禁止把脚本提交的小数静默截断后执行。 */
@Configuration
public class GameplayJsonConfig {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictGameplayIntegers() {
        return builder -> builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
