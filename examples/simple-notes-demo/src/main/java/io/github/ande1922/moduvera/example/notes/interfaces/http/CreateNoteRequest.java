package io.github.ande1922.moduvera.example.notes.interfaces.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateNoteRequest(
        @NotBlank @Size(max = 500) String content) {}
