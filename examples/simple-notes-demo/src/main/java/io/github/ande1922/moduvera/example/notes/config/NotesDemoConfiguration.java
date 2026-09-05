package io.github.ande1922.moduvera.example.notes.config;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.example.notes.domain.NoteNotFoundException;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.identifier.IdentifierGenerator;
import io.github.ande1922.moduvera.identifier.SnowflakeIdentifierGenerator;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.messaging.kafka.ReliableInboundEndpoint;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
import io.github.ande1922.moduvera.security.web.ExecutionContextHandlerSelection;
import io.github.ande1922.moduvera.web.ProblemStatusContributor;
import java.sql.Timestamp;
import java.net.URI;
import java.time.Clock;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class NotesDemoConfiguration {

    @Bean
    IdentifierGenerator identifierGenerator() {
        return new SnowflakeIdentifierGenerator(1);
    }

    @Bean
    UseCaseAuthorizer useCaseAuthorizer() {
        return new UseCaseAuthorizer();
    }

    @Bean
    ExecutionContextHandlerSelection notesHttpExecutionHandlers() {
        return ExecutionContextHandlerSelection.builder()
                .managePackage("io.github.ande1922.moduvera.example.notes.interfaces.http")
                .excludePackage("org.springframework.boot.actuate")
                .excludePackage("org.springframework.boot.autoconfigure.web.servlet.error")
                .build();
    }

    @Bean
    ProblemStatusContributor noteNotFoundProblemStatusContributor() {
        return exception -> exception instanceof NoteNotFoundException
                ? Optional.of(HttpStatus.NOT_FOUND)
                : Optional.empty();
    }

    @Bean
    ReliableInboundEndpoint noteCreated(
            ReliableMessageConsumerFactory consumers,
            JdbcTemplate jdbc,
            ObjectMapper json,
            Clock clock) {
        return consumers.forConsumer(
                "notes-audit",
                new InboundMessageContract(
                        MessageKind.EVENT,
                        new MessageType("io.github.ande1922.moduvera.example.notes.created.v1"),
                        URI.create("urn:service:notes-demo"),
                        new Destination("notes.events"),
                        new Actor(ActorType.SERVICE, "notes-demo")),
                serialized -> {
                    try {
                        long noteId = Long.parseLong(
                                json.readTree(serialized.payload()).required("noteId").asText());
                        var context = ExecutionContextHolder.require();
                        jdbc.update(
                                """
                                INSERT INTO demo_note_receipt(message_id, tenant_id, note_id, received_at)
                                VALUES (?, ?, ?, ?)
                                """,
                                serialized.descriptor().id().value(),
                                context.tenantId().value(),
                                noteId,
                                Timestamp.from(clock.instant()));
                    } catch (JacksonException | NumberFormatException invalidPayload) {
                        throw new NonRetryableMessageException("invalid note event payload", invalidPayload);
                    }
                });
    }
}
