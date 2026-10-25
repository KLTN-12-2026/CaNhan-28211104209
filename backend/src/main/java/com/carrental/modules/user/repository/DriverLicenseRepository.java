package com.carrental.modules.user.repository;

import com.carrental.modules.user.entity.DriverLicense;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface DriverLicenseRepository extends JpaRepository<DriverLicense, String> {
    java.util.Optional<DriverLicense> findByUserId(String userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from DriverLicense e where e.id = :id")
    java.util.Optional<DriverLicense> lock(@Param("id") String id);
}
