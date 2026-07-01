package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entity.DailyUserVisit;
import com.accenture.intern.docmind.entity.DailyUserVisitId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyUserVisitRepository extends JpaRepository<DailyUserVisit, DailyUserVisitId> {
}
