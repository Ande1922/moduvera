package io.github.ande1922.moduvera.example.notes.interfaces.http;

import io.github.ande1922.moduvera.example.notes.application.NoteApplicationService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notes")
final class NoteController {

    private final NoteApplicationService notes;

    NoteController(NoteApplicationService notes) {
        this.notes = notes;
    }

    @PostMapping
    ResponseEntity<NoteView> create(@Valid @RequestBody CreateNoteRequest request) {
        NoteView created = NoteView.from(notes.create(request.content()));
        return ResponseEntity.created(URI.create("/api/v1/notes/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    NoteView get(@PathVariable long id) {
        return NoteView.from(notes.get(id));
    }
}
