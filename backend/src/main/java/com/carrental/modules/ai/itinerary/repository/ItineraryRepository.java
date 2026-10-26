package com.carrental.modules.ai.itinerary.repository;

import com.carrental.modules.ai.itinerary.entity.Itinerary;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItineraryRepository extends JpaRepository<Itinerary, String> {
    List<Itinerary> findByUserIdOrderByCreatedAtDesc(String userId);
}
