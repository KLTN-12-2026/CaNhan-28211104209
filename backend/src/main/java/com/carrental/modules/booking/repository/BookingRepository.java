package com.carrental.modules.booking.repository;

import com.carrental.modules.booking.entity.Booking;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface BookingRepository extends JpaRepository<Booking, String> {
    @Query("select e from Booking e where e.vehicleId in :vehicleIds and (e.status in ('CONFIRMED','IN_PROGRESS') or (e.status = 'PENDING' and e.expiresAt > :now) or (e.policyVersion='calendar-2026-10-17' and e.status in ('RETURNED','COMPLETED') and e.endAt > :now)) order by e.vehicleId, e.startAt")
    java.util.List<Booking> searchSchedule(@Param("vehicleIds") java.util.List<String> vehicleIds, @Param("now") java.time.Instant now);
    java.util.List<Booking> findByVehicleIdOrderByStartAtAsc(String vehicleId);
    java.util.List<Booking> findByUserIdOrderByCreatedAtDescIdAsc(String userId);
    java.util.List<Booking> findAllByOrderByCreatedAtDescIdAsc();

    @Query("select e.vehicleId from Booking e where e.id = :id")
    java.util.Optional<String> findVehicleIdById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Booking e where e.vehicleId = :vehicleId order by e.startAt asc")
    java.util.List<Booking> lockScheduleByVehicleId(@Param("vehicleId") String vehicleId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Booking e where e.id = :id")
    java.util.Optional<Booking> lock(@Param("id") String id);
}
