package io.github.ande1922.moduvera.example.notes.interfaces.http;

import io.github.ande1922.moduvera.example.notes.domain.Note;
import java.time.Instant;

public record NoteView(String id, String content, Instant createdAt) {

    static NoteView from(Note note) {
        return new NoteView(Long.toString(note.id()), note.content(), note.createdAt());
    }
}
