package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.dto.folder.CreateFolderRequest;
import com.accenture.intern.docmind.dto.folder.FolderResponse;
import com.accenture.intern.docmind.dto.folder.ReorderRequest;
import com.accenture.intern.docmind.dto.folder.UpdateFolderRequest;
import com.accenture.intern.docmind.dto.session.SessionResponse;
import com.accenture.intern.docmind.entity.Folder;
import com.accenture.intern.docmind.entity.Session;
import com.accenture.intern.docmind.entity.User;
import com.accenture.intern.docmind.repository.FolderRepository;
import com.accenture.intern.docmind.repository.SessionRepository;
import com.accenture.intern.docmind.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class FolderService {

    private final FolderRepository folderRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    public FolderResponse createFolder(String userEmail, CreateFolderRequest request) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) throw new RuntimeException("User Not Found");

        Folder folder = Folder.builder()
                .user(user)
                .name(request.getName())
                .icon(request.getIcon())
                .colorHex(request.getColorHex())
                .build();

        Folder saved = folderRepository.save(folder);

        if (request.getSessionIds() != null && !request.getSessionIds().isEmpty()) {
            List<Session> sessions = sessionRepository.findAllById(request.getSessionIds());
            for (Session s : sessions) {
                if (s.getUser().getId().equals(user.getId())) {
                    s.getFolders().add(saved);
                    sessionRepository.save(s);
                    saved.getSessions().add(s);
                }
            }
        }

        return mapToResponse(saved);
    }

    public List<FolderResponse> getAllFolders(String userEmail) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) throw new RuntimeException("User Not Found");

        return folderRepository.findByUserOrderByDisplayOrderAscCreatedAtDesc(user)
                .stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    public FolderResponse updateFolder(String userEmail, Long folderId, UpdateFolderRequest request) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) throw new RuntimeException("User Not Found");

        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));

        if (!folder.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Access Denied");
        }

        if (request.getName() != null) folder.setName(request.getName());
        if (request.getIcon() != null) folder.setIcon(request.getIcon());
        if (request.getColorHex() != null) folder.setColorHex(request.getColorHex());
        if (request.getPinned() != null) folder.setPinned(request.getPinned());

        Folder saved = folderRepository.save(folder);
        return mapToResponse(saved);
    }

    public void deleteFolder(String userEmail, Long folderId) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) throw new RuntimeException("User Not Found");

        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));

        if (!folder.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Access Denied");
        }

        Set<Session> sessions = folder.getSessions();
        for (Session s : sessions) {
            s.getFolders().remove(folder);
            sessionRepository.save(s);
        }

        folderRepository.delete(folder);
    }

    public void reorderFolders(String userEmail, List<ReorderRequest> requests) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) throw new RuntimeException("User Not Found");

        Map<Long, Integer> orderMap = requests.stream()
                .collect(Collectors.toMap(ReorderRequest::getId, ReorderRequest::getOrder));

        List<Folder> folders = folderRepository.findAllById(orderMap.keySet());
        for (Folder folder : folders) {
            if (folder.getUser().getId().equals(user.getId())) {
                folder.setDisplayOrder(orderMap.get(folder.getId()));
                folderRepository.save(folder);
            }
        }
    }

    private FolderResponse mapToResponse(Folder folder) {
        List<SessionResponse> sessionResponses = new ArrayList<>();
        if (folder.getSessions() != null) {
            for (Session s : folder.getSessions()) {
                sessionResponses.add(SessionResponse.builder()
                        .sessionId(s.getSessionId())
                        .title(s.getTitle())
                        .archived(s.getArchived())
                        .createdAt(s.getCreatedAt())
                        .updatedAt(s.getUpdatedAt())
                        .folderIds(s.getFolders() != null ? s.getFolders().stream().map(com.accenture.intern.docmind.entity.Folder::getId).collect(Collectors.toList()) : java.util.Collections.emptyList())
                        .displayOrder(s.getDisplayOrder() != null ? s.getDisplayOrder() : 0)
                        .build());
            }
        }

        return FolderResponse.builder()
                .id(folder.getId())
                .name(folder.getName())
                .icon(folder.getIcon())
                .colorHex(folder.getColorHex())
                .displayOrder(folder.getDisplayOrder() != null ? folder.getDisplayOrder() : 0)
                .pinned(folder.getPinned() != null ? folder.getPinned() : false)
                .createdAt(folder.getCreatedAt())
                .updatedAt(folder.getUpdatedAt())
                .sessions(sessionResponses)
                .build();
    }
}
