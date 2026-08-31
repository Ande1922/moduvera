package io.github.ande1922.moduvera.example.notes.application;

import io.github.ande1922.moduvera.example.notes.domain.Note;

public interface NoteCreatedPublisher {

    void publish(Note note);
}
