package com.carrental.modules.ai.itinerary.service;

import com.carrental.exception.DomainException;
import com.carrental.modules.ai.itinerary.dto.CreateItineraryRequest;
import com.carrental.modules.ai.itinerary.dto.ItineraryDetailDto;
import com.carrental.modules.ai.itinerary.dto.ItineraryPreviewResponse;
import com.carrental.modules.ai.itinerary.dto.ItinerarySummaryDto;
import com.carrental.modules.ai.itinerary.entity.Itinerary;
import com.carrental.modules.ai.itinerary.entity.ItineraryDay;
import com.carrental.modules.ai.itinerary.entity.ItineraryItem;
import com.carrental.modules.ai.itinerary.repository.ItineraryDayRepository;
import com.carrental.modules.ai.itinerary.repository.ItineraryItemRepository;
import com.carrental.modules.ai.itinerary.repository.ItineraryRepository;
import com.carrental.security.Actor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ItineraryStorageService {
    private final ItineraryRepository itineraryRepo;
    private final ItineraryDayRepository dayRepo;
    private final ItineraryItemRepository itemRepo;
    private final ItineraryPreviewService previewService;
    private final ObjectMapper json;
    private final Clock clock;

    public ItineraryStorageService(
            ItineraryRepository itineraryRepo,
            ItineraryDayRepository dayRepo,
            ItineraryItemRepository itemRepo,
            ItineraryPreviewService previewService,
            ObjectMapper json,
            Clock clock) {
        this.itineraryRepo = itineraryRepo;
        this.dayRepo = dayRepo;
        this.itemRepo = itemRepo;
        this.previewService = previewService;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public ItineraryDetailDto create(Actor actor, CreateItineraryRequest request) {
        actor.require("USER");
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw DomainException.bad("Tiêu đề hành trình không được để trống.");
        }
        String title = request.title().strip();
        if (title.length() > 200) {
            throw DomainException.bad("Tiêu đề hành trình không được vượt quá 200 ký tự.");
        }
        if (request.fingerprint() == null || request.fingerprint().isBlank()) {
            throw DomainException.bad("Chữ ký xác thực (fingerprint) không được để trống.");
        }
        if (request.orderedLocationIds() == null || request.orderedLocationIds().isEmpty()) {
            throw DomainException.bad("Danh sách địa điểm lưu không được để trống.");
        }

        // Tái thẩm định toàn vẹn trực tiếp từ server facts và OSRM chặng (F02: Không gọi lại Qwen)
        var verified = previewService.revalidateOrderedLocations(
            actor,
            request.tripDate(),
            request.startTime(),
            request.endTime(),
            request.startLocationId(),
            request.preferredCategoryId(),
            request.orderedLocationIds(),
            request.fingerprint()
        );

        Instant now = Instant.now(clock);

        Itinerary itinerary = new Itinerary();
        itinerary.userId = actor.id();
        itinerary.title = title;
        ItineraryPreviewResponse.CriteriaInfo savedCriteria = new ItineraryPreviewResponse.CriteriaInfo(
            verified.criteria().contractVersion(),
            verified.criteria().planVersion(),
            verified.criteria().tripDate(),
            verified.criteria().startTime(),
            verified.criteria().endTime(),
            verified.criteria().startLocationId(),
            verified.criteria().startLocationName(),
            verified.criteria().preferredCategoryId(),
            verified.fingerprint()
        );
        try {
            itinerary.criteria = json.writeValueAsString(savedCriteria);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không thể serialize tiêu chí hành trình", e);
        }
        itinerary.createdAt = now;
        itinerary = itineraryRepo.save(itinerary);

        ItineraryDay day = new ItineraryDay();
        day.itineraryId = itinerary.id;
        day.tripDate = request.tripDate();
        day = dayRepo.save(day);

        List<ItineraryPreviewResponse.ScheduleItem> items = verified.items();
        for (var scheduleItem : items) {
            ItineraryItem item = new ItineraryItem();
            item.dayId = day.id;
            item.locationId = scheduleItem.locationId();
            item.sortOrder = scheduleItem.sortOrder();
            try {
                item.snapshot = json.writeValueAsString(scheduleItem);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Không thể serialize snapshot điểm đến", e);
            }
            itemRepo.save(item);
        }

        return new ItineraryDetailDto(
            itinerary.id,
            itinerary.title,
            itinerary.createdAt,
            verified.criteria(),
            day.tripDate.toString(),
            items,
            verified.totals(),
            verified.fingerprint()
        );
    }

    @Transactional(readOnly = true)
    public List<ItinerarySummaryDto> list(Actor actor) {
        actor.require("USER");
        List<Itinerary> list = itineraryRepo.findByUserIdOrderByCreatedAtDesc(actor.id());
        List<ItinerarySummaryDto> result = new ArrayList<>();

        for (Itinerary it : list) {
            List<ItineraryDay> days = dayRepo.findByItineraryIdOrderByTripDateAsc(it.id);
            if (days.isEmpty()) continue;
            ItineraryDay day = days.get(0);
            List<ItineraryItem> items = itemRepo.findByDayIdOrderBySortOrderAsc(day.id);

            int totalDwell = 0;
            int totalTravel = 0;
            double totalDist = 0.0;

            for (ItineraryItem item : items) {
                try {
                    var node = json.readTree(item.snapshot);
                    totalDwell += node.path("dwellMinutes").asInt(0);
                    totalTravel += node.path("travelToNextMinutes").asInt(0);
                    totalDist += node.path("distanceToNextMeters").asDouble(0.0);
                } catch (Exception ignored) {}
            }

            result.add(new ItinerarySummaryDto(
                it.id,
                it.title,
                day.tripDate.toString(),
                items.size(),
                totalDwell,
                totalTravel,
                totalDist,
                it.createdAt
            ));
        }

        return result;
    }

    @Transactional(readOnly = true)
    public ItineraryDetailDto get(Actor actor, String id) {
        actor.require("USER");
        Itinerary itinerary = itineraryRepo.findById(id)
            .orElseThrow(DomainException::missing);

        if (!itinerary.userId.equals(actor.id())) {
            throw DomainException.missing();
        }

        List<ItineraryDay> days = dayRepo.findByItineraryIdOrderByTripDateAsc(itinerary.id);
        if (days.isEmpty()) {
            throw DomainException.missing();
        }
        ItineraryDay day = days.get(0);

        List<ItineraryItem> dbItems = itemRepo.findByDayIdOrderBySortOrderAsc(day.id);
        List<ItineraryPreviewResponse.ScheduleItem> items = new ArrayList<>();

        int totalDwell = 0;
        int totalWait = 0;
        int totalTravel = 0;
        double totalDist = 0.0;

        for (ItineraryItem item : dbItems) {
            try {
                ItineraryPreviewResponse.ScheduleItem scheduleItem = json.readValue(item.snapshot, ItineraryPreviewResponse.ScheduleItem.class);
                items.add(scheduleItem);
                totalDwell += (scheduleItem.dwellMinutes() != null ? scheduleItem.dwellMinutes() : 0);
                totalWait += (scheduleItem.waitMinutes() != null ? scheduleItem.waitMinutes() : 0);
                totalTravel += (scheduleItem.travelToNextMinutes() != null ? scheduleItem.travelToNextMinutes() : 0);
                totalDist += (scheduleItem.distanceToNextMeters() != null ? scheduleItem.distanceToNextMeters() : 0.0);
            } catch (Exception e) {
                throw new IllegalStateException("Snapshot dữ liệu hành trình không hợp lệ", e);
            }
        }

        ItineraryPreviewResponse.CriteriaInfo criteria;
        try {
            criteria = json.readValue(itinerary.criteria, ItineraryPreviewResponse.CriteriaInfo.class);
        } catch (Exception e) {
            throw new IllegalStateException("Criteria của hành trình không hợp lệ", e);
        }

        ItineraryPreviewResponse.Totals totals = new ItineraryPreviewResponse.Totals(
            totalDwell, totalWait, totalTravel, totalDist, items.size()
        );

        return new ItineraryDetailDto(
            itinerary.id,
            itinerary.title,
            itinerary.createdAt,
            criteria,
            day.tripDate.toString(),
            items,
            totals,
            criteria.fingerprint()
        );
    }
}
