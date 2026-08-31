package io.github.ande1922.moduvera.example.notes.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("demo_note")
final class NoteRow {

    @TableId
    private Long id;

    private String tenantId;
    private String content;
    private OffsetDateTime createdAt;

    NoteRow() {}

    NoteRow(long id, String tenantId, String content, OffsetDateTime createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.content = content;
        this.createdAt = createdAt;
    }

    Long getId() {
        return id;
    }

    String getTenantId() {
        return tenantId;
    }

    String getContent() {
        return content;
    }

    OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
