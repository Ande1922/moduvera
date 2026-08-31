package io.github.ande1922.moduvera.example.notes.infrastructure.messaging;

import io.github.ande1922.moduvera.example.notes.application.NoteCreatedPublisher;
import io.github.ande1922.moduvera.example.notes.domain.Note;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.publication.DurablePublication;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class OutboxNoteCreatedPublisher implements NoteCreatedPublisher {

    private final DurablePublication outbox;
    private final ObjectMapper json;
    private final Clock clock;

    public OutboxNoteCreatedPublisher(DurablePublication outbox, ObjectMapper json, Clock clock) {
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void publish(Note note) {
        var context = ExecutionContextHolder.require();
        var descriptor = new MessageDescriptor(
                new MessageId(UUID.randomUUID().toString()),
                MessageKind.EVENT,
                new MessageType("io.github.ande1922.moduvera.example.notes.created.v1"),
                URI.create("urn:service:notes-demo"),
                new Destination("notes.events"),
                clock.instant(),
                context.tenantId(),
                new Actor(ActorType.SERVICE, "notes-demo"),
                context.correlationId(),
                null,
                context.initiator(),
                context.tenantId().value() + ":" + note.id());
        outbox.append(SerializedMessage.json(descriptor, payload(note)));
    }

    private String payload(Note note) {
        try {
            return json.writeValueAsString(
                    Map.of("noteId", Long.toString(note.id()), "content", note.content()));
        } catch (JacksonException failure) {
            throw new IllegalStateException("note event serialization failed", failure);
        }
    }
}
