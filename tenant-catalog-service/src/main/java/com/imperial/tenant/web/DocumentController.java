package com.imperial.tenant.web;

import com.imperial.common.tenant.TenantContext;
import com.imperial.common.tenant.TenantScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** Local development adapter. Replace its file write with an R2 putObject call;
 * the database deliberately stores metadata and a storage key, never file bytes. */
@RestController
@RequestMapping("/documents")
public class DocumentController {
    @PersistenceContext private EntityManager entityManager;
    @Value("${document-storage.local-path:uploads}") private String storageRoot;

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('TENANT','PENDING')")
    @TenantScoped
    @Transactional
    public DocumentView upload(@RequestParam String type, @RequestParam MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "file is required");
        UUID tenantId = TenantContext.get(), id = UUID.randomUUID();
        String safeName = Path.of(file.getOriginalFilename() == null ? "document" : file.getOriginalFilename()).getFileName().toString();
        String key = tenantId + "/" + id + "-" + safeName;
        Path target = Path.of(storageRoot).resolve(key);
        Files.createDirectories(target.getParent());
        file.transferTo(target);
        entityManager.createNativeQuery("INSERT INTO tenant_documents (id, tenant_id, document_type, storage_key, original_filename, content_type, uploaded_at) VALUES (:id,:tenant,:type,:key,:name,:content,:at)")
                .setParameter("id", id).setParameter("tenant", tenantId).setParameter("type", type).setParameter("key", key)
                .setParameter("name", safeName).setParameter("content", file.getContentType()).setParameter("at", java.sql.Timestamp.from(Instant.now())).executeUpdate();
        return new DocumentView(id, type, safeName, "PENDING");
    }
    public record DocumentView(UUID id, String type, String filename, String status) { }
}
