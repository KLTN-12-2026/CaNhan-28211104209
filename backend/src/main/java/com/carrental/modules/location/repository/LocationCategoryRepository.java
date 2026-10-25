package com.carrental.modules.location.repository;

import com.carrental.modules.location.entity.LocationCategory;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LocationCategoryRepository extends JpaRepository<LocationCategory, String> {
    List<LocationCategory> findAllByOrderByNameAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select category from LocationCategory category where category.id = :id")
    Optional<LocationCategory> lockById(@Param("id") String id);
}