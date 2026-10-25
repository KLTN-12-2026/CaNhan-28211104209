package com.carrental.modules.ai.itinerary.repository;

import com.carrental.modules.ai.itinerary.entity.ItineraryDay;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItineraryDayRepository extends JpaRepository<ItineraryDay, String> {
    List<ItineraryDay> findByItineraryIdOrderByTripDateAsc(String itineraryId);
}
