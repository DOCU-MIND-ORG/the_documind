package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.folder.CreateFolderRequest;
import com.accenture.intern.docmind.dto.folder.FolderResponse;
import com.accenture.intern.docmind.dto.folder.ReorderRequest;
import com.accenture.intern.docmind.dto.folder.UpdateFolderRequest;
import com.accenture.intern.docmind.service.FolderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/folders")
@RequiredArgsConstructor
public class FolderController {

    private final FolderService folderService;

    @PostMapping
    public ResponseEntity<FolderResponse> createFolder(
            @RequestBody CreateFolderRequest request,
            Principal principal
    ) {
        return ResponseEntity.ok(folderService.createFolder(principal.getName(), request));
    }

    @GetMapping
    public ResponseEntity<List<FolderResponse>> getAllFolders(Principal principal) {
        return ResponseEntity.ok(folderService.getAllFolders(principal.getName()));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<FolderResponse> updateFolder(
            @PathVariable Long id,
            @RequestBody UpdateFolderRequest request,
            Principal principal
    ) {
        return ResponseEntity.ok(folderService.updateFolder(principal.getName(), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFolder(
            @PathVariable Long id,
            Principal principal
    ) {
        folderService.deleteFolder(principal.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/reorder")
    public ResponseEntity<Void> reorderFolders(
            @RequestBody List<ReorderRequest> requests,
            Principal principal
    ) {
        folderService.reorderFolders(principal.getName(), requests);
        return ResponseEntity.ok().build();
    }
}
