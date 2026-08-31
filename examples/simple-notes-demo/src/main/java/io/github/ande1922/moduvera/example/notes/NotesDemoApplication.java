package io.github.ande1922.moduvera.example.notes;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("io.github.ande1922.moduvera.example.notes.infrastructure.persistence")
public class NotesDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotesDemoApplication.class, args);
    }
}
