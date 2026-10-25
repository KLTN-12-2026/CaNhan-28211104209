package com.carrental.modules.operation.repository;

import com.carrental.modules.operation.entity.IncidentCase;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface IncidentCaseRepository extends JpaRepository<IncidentCase, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from IncidentCase e where e.id = :id")
    java.util.Optional<IncidentCase> lock(@Param("id") String id);
}
