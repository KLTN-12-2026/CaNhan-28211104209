package com.carrental.modules.vehicle.repository;

import com.carrental.modules.vehicle.entity.Amenity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AmenityRepository extends JpaRepository<Amenity, String> {
    List<Amenity> findAllByOrderByNameAscIdAsc();
}