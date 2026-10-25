package com.carrental.modules.location.repository;

import com.carrental.modules.location.entity.Location;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LocationRepository extends JpaRepository<Location, String> {
    List<Location> findAllByOrderByNameAsc();

    @Query("select location from Location location join fetch location.category order by location.name asc")
    List<Location> findAllWithCategoryOrderByNameAsc();

    @Query("select location from Location location join fetch location.category where location.id = :id")
    Optional<Location> findWithCategoryById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select location from Location location join fetch location.category where location.id = :id")
    Optional<Location> lockWithCategoryById(@Param("id") String id);
}