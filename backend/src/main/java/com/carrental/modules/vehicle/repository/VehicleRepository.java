package com.carrental.modules.vehicle.repository;

import com.carrental.modules.vehicle.entity.Vehicle;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface VehicleRepository extends JpaRepository<Vehicle, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Vehicle e where e.id = :id")
    java.util.Optional<Vehicle> lock(@Param("id") String id);
}
