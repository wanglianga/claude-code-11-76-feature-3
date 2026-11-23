package com.labroute.config;

import com.labroute.service.DemoDataService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BootstrapConfig {

    @Bean
    public ApplicationRunner seedRunner(DemoDataService demo) {
        return args -> demo.seedIfEmpty();
    }
}
