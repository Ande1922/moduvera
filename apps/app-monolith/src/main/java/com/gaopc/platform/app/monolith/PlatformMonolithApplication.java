package com.gaopc.platform.app.monolith;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(BusinessCoreConfiguration.class)
public class PlatformMonolithApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformMonolithApplication.class, args);
    }
}
