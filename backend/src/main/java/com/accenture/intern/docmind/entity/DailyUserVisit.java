package com.accenture.intern.docmind.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "daily_user_visit")
@IdClass(DailyUserVisitId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DailyUserVisit {
    
    @Id
    private LocalDate date;
    
    @Id
    private Long userId;
}
