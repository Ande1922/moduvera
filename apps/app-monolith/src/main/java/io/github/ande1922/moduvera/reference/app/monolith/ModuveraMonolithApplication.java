package io.github.ande1922.moduvera.reference.app.monolith;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(BusinessCoreConfiguration.class)
public class ModuveraMonolithApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModuveraMonolithApplication.class, args);
    }
}
