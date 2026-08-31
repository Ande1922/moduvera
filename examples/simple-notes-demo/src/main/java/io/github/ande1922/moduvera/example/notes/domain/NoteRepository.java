package io.github.ande1922.moduvera.example.notes.domain;

import java.util.Optional;

public interface NoteRepository {

    void save(Note note);

    Optional<Note> findById(long id);
}
