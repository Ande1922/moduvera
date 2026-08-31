package io.github.ande1922.moduvera.example.notes.domain;

import io.github.ande1922.moduvera.error.CodedException;
import io.github.ande1922.moduvera.error.ErrorCode;

public final class NoteNotFoundException extends CodedException {

    private static final ErrorCode CODE = new ErrorCode("notes.not-found");

    public NoteNotFoundException() {
        super(CODE, "Note was not found");
    }
}
