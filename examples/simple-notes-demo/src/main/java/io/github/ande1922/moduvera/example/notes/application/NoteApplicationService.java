package io.github.ande1922.moduvera.example.notes.application;

import io.github.ande1922.moduvera.example.notes.domain.Note;
import io.github.ande1922.moduvera.example.notes.domain.NoteNotFoundException;
import io.github.ande1922.moduvera.example.notes.domain.NoteRepository;
import io.github.ande1922.moduvera.authorization.PermissionCode;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.identifier.IdentifierGenerator;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class NoteApplicationService {

    private static final PermissionCode READ = new PermissionCode("notes:read");
    private static final PermissionCode WRITE = new PermissionCode("notes:write");

    private final NoteRepository notes;
    private final IdentifierGenerator identifiers;
    private final UseCaseAuthorizer authorizer;
    private final TransactionBoundary transactions;
    private final NoteCreatedPublisher createdPublisher;
    private final Clock clock;

    @Autowired
    public NoteApplicationService(
            NoteRepository notes,
            IdentifierGenerator identifiers,
            UseCaseAuthorizer authorizer,
            TransactionBoundary transactions,
            NoteCreatedPublisher createdPublisher) {
        this(notes, identifiers, authorizer, transactions, createdPublisher, Clock.systemUTC());
    }

    NoteApplicationService(
            NoteRepository notes,
            IdentifierGenerator identifiers,
            UseCaseAuthorizer authorizer,
            TransactionBoundary transactions,
            NoteCreatedPublisher createdPublisher,
            Clock clock) {
        this.notes = notes;
        this.identifiers = identifiers;
        this.authorizer = authorizer;
        this.transactions = transactions;
        this.createdPublisher = createdPublisher;
        this.clock = clock;
    }

    public Note create(String content) {
        authorizer.require(WRITE);
        return transactions.inTransaction(() -> {
            Note note = new Note(identifiers.nextId(), content, clock.instant());
            notes.save(note);
            createdPublisher.publish(note);
            return note;
        });
    }

    public Note get(long id) {
        authorizer.require(READ);
        return notes.findById(id).orElseThrow(NoteNotFoundException::new);
    }
}
