package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entity.Folder;
import com.accenture.intern.docmind.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FolderRepository extends JpaRepository<Folder, Long> {
    List<Folder> findByUserOrderByDisplayOrderAscCreatedAtDesc(User user);
}
