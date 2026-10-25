package com.carrental.modules.branch.repository;

import com.carrental.modules.branch.entity.BranchRoute;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface BranchRouteRepository extends JpaRepository<BranchRoute, String> {
    java.util.Optional<BranchRoute> findByPickupBranchIdAndReturnBranchId(String pickup, String destination);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from BranchRoute e where e.id = :id")
    java.util.Optional<BranchRoute> lock(@Param("id") String id);
}
