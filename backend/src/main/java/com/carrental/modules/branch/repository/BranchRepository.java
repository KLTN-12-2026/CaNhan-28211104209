package com.carrental.modules.branch.repository;

import com.carrental.modules.branch.entity.Branch;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface BranchRepository extends JpaRepository<Branch, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Branch e where e.id = :id")
    java.util.Optional<Branch> lock(@Param("id") String id);
}
