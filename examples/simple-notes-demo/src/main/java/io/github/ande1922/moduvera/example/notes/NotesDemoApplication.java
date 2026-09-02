package io.github.ande1922.moduvera.example.notes;

import io.github.ande1922.moduvera.example.notes.migration.NotesMigrationConfiguration;
import io.github.ande1922.moduvera.messaging.kafka.migration.ModuveraMessagingMigrationConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@MapperScan("io.github.ande1922.moduvera.example.notes.infrastructure.persistence")
@Import({NotesMigrationConfiguration.class, ModuveraMessagingMigrationConfiguration.class})
public class NotesDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotesDemoApplication.class, args);
    }
}
