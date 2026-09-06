package io.github.ande1922.moduvera.example.notes.config;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.example.notes.application.NoteCreatedEvent;
import io.github.ande1922.moduvera.example.notes.application.NoteCreatedHandler;
import io.github.ande1922.moduvera.example.notes.application.NoteReceiptStore;
import io.github.ande1922.moduvera.example.notes.domain.NoteNotFoundException;
import io.github.ande1922.moduvera.example.notes.infrastructure.persistence.JdbcNoteReceiptStore;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.identifier.IdentifierGenerator;
import io.github.ande1922.moduvera.identifier.SnowflakeIdentifierGenerator;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
import io.github.ande1922.moduvera.security.web.ExecutionContextHandlerSelection;
import io.github.ande1922.moduvera.web.ProblemStatusContributor;
import java.net.URI;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
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
    NoteReceiptStore noteReceiptStore(JdbcTemplate jdbc) {
        return new JdbcNoteReceiptStore(jdbc);
    }

    @Bean
    NoteCreatedHandler noteCreatedHandler(
            InboxRepository inboxRepository,
            TransactionBoundary transactions,
            NoteReceiptStore receipts,
            Clock clock) {
        return new NoteCreatedHandler(
                new InboxTemplate("notes-audit", inboxRepository, transactions, clock),
                receipts,
                clock);
    }

    @Bean
    Consumer<Message<byte[]>> noteCreated(
            ReliableMessageConsumerFactory consumers,
            NoteCreatedHandler handler,
            ObjectMapper json) {
        return consumers.forContract(
                new InboundMessageContract(
                        MessageKind.EVENT,
                        new MessageType("io.github.ande1922.moduvera.example.notes.created.v1"),
                        URI.create("urn:service:notes-demo"),
                        new Destination("notes.events"),
                        new Actor(ActorType.SERVICE, "notes-demo")),
                serialized -> {
                    try {
                        var event = new NoteCreatedEvent(Long.parseLong(
                                json.readTree(serialized.payload()).required("noteId").asText()));
                        handler.handle(event, serialized.descriptor().id());
                    } catch (JacksonException | NumberFormatException invalidPayload) {
                        throw new NonRetryableMessageException("invalid note event payload", invalidPayload);
                    }
                });
    }
}
