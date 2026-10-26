package com.carrental.modules.ai.itinerary.repository;

import com.carrental.modules.ai.itinerary.entity.ItineraryItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItineraryItemRepository extends JpaRepository<ItineraryItem, String> {
    List<ItineraryItem> findByDayIdOrderBySortOrderAsc(String dayId);
}
