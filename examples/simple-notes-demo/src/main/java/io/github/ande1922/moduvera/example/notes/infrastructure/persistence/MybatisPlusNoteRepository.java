package io.github.ande1922.moduvera.example.notes.infrastructure.persistence;

import io.github.ande1922.moduvera.example.notes.domain.Note;
import io.github.ande1922.moduvera.example.notes.domain.NoteRepository;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisPlusNoteRepository implements NoteRepository {

    private final NoteMapper mapper;

    public MybatisPlusNoteRepository(NoteMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(Note note) {
        String tenantId = ExecutionContextHolder.require().tenantId().value();
        NoteRow row = new NoteRow(
                note.id(),
                tenantId,
                note.content(),
                note.createdAt().atOffset(ZoneOffset.UTC));
        if (mapper.insert(row) != 1) {
            throw new IllegalStateException("note insert did not affect exactly one row");
        }
    }

    @Override
    public Optional<Note> findById(long id) {
        ExecutionContextHolder.require();
        return Optional.ofNullable(mapper.selectById(id)).map(MybatisPlusNoteRepository::toDomain);
    }

    private static Note toDomain(NoteRow row) {
        return new Note(
                row.getId(), row.getContent(), row.getCreatedAt().toInstant());
    }
}
