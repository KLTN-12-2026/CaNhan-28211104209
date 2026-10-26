package com.carrental.modules.vehicle.service;

import com.carrental.exception.DomainException;
import com.carrental.modules.vehicle.repository.VehicleAmenityRepository;
import com.carrental.modules.vehicle.repository.VehicleRepository;
import com.carrental.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.HexFormat;

@Service
public class VehicleAmenityService {
    private final VehicleRepository vehicleRepo;
    private final VehicleAmenityRepository linkRepo;

    public VehicleAmenityService(VehicleRepository vehicleRepo, VehicleAmenityRepository linkRepo) {
        this.vehicleRepo = vehicleRepo;
        this.linkRepo = linkRepo;
    }

    public record AmenityView(String id, String name) {}
    public record AmenityLinksDto(List<AmenityView> amenities, String revision) {}

    public AmenityLinksDto getAmenities(String vehicleId) {
        vehicleRepo.findById(vehicleId).orElseThrow(DomainException::missing);
        List<VehicleAmenityRepository.Link> links = linkRepo.findByVehicleId(vehicleId);
        List<String> amenityIds = links.stream().map(VehicleAmenityRepository.Link::amenityId).toList();
        Map<String, String> amenityNames = linkRepo.findAmenityNames(amenityIds);
        List<AmenityView> amenities = links.stream()
                .map(l -> new AmenityView(l.amenityId(), amenityNames.getOrDefault(l.amenityId(), "")))
                .toList();
        return new AmenityLinksDto(amenities, computeRevision(vehicleId, amenityIds));
    }

    @Transactional
    public AmenityLinksDto updateAmenities(Actor actor, String vehicleId, List<String> amenityIds, String expectedRevision) {
        actor.require("ADMIN");
        vehicleRepo.lock(vehicleId).orElseThrow(DomainException::missing);

        if (amenityIds == null) {
            throw DomainException.bad("amenityIds là bắt buộc.");
        }
        if (expectedRevision == null || !expectedRevision.matches("[a-f0-9]{64}")) {
            throw DomainException.bad("expectedRevision không hợp lệ.");
        }

        Set<String> seen = new HashSet<>();
        for (String id : amenityIds) {
            if (id == null || id.isBlank()) {
                throw DomainException.bad("amenityIds không được chứa phần tử null hoặc rỗng.");
            }
            if (!seen.add(id)) {
                throw DomainException.bad("amenityIds không được chứa ID trùng lặp.");
            }
        }

        // Validate all amenity IDs exist in bulk
        Set<String> existingAmenityIds = linkRepo.findExistingAmenityIds(amenityIds);
        for (String id : amenityIds) {
            if (!existingAmenityIds.contains(id)) {
                throw DomainException.bad("Tiện nghi không tồn tại: " + id);
            }
        }

        List<VehicleAmenityRepository.Link> currentLinks = linkRepo.findByVehicleId(vehicleId);
        List<String> currentAmenityIds = currentLinks.stream().map(VehicleAmenityRepository.Link::amenityId).toList();
        String currentRevision = computeRevision(vehicleId, currentAmenityIds);

        if (!currentRevision.equals(expectedRevision)) {
            throw DomainException.conflict("Liên kết tiện nghi đã thay đổi. Vui lòng tải lại.");
        }

        // No-op: same set of amenity IDs (order doesn't matter for revision)
        Set<String> currentSet = new HashSet<>(currentAmenityIds);
        Set<String> requestedSet = new HashSet<>(amenityIds);
        if (currentSet.equals(requestedSet)) {
            // Bulk fetch names for current links
            Map<String, String> amenityNames = linkRepo.findAmenityNames(currentAmenityIds);
            return new AmenityLinksDto(
                    currentLinks.stream().map(l -> new AmenityView(l.amenityId(), amenityNames.getOrDefault(l.amenityId(), ""))).toList(),
                    currentRevision);
        }

        linkRepo.deleteByVehicleId(vehicleId);
        if (!amenityIds.isEmpty()) {
            linkRepo.insertLinks(vehicleId, amenityIds);
        }

        List<VehicleAmenityRepository.Link> newLinks = linkRepo.findByVehicleId(vehicleId);
        String newRevision = computeRevision(vehicleId, amenityIds);

        // Bulk fetch names for new links
        Map<String, String> newAmenityNames = linkRepo.findAmenityNames(amenityIds);
        return new AmenityLinksDto(
                newLinks.stream().map(l -> new AmenityView(l.amenityId(), newAmenityNames.getOrDefault(l.amenityId(), ""))).toList(),
                newRevision);
    }

    public String computeRevision(String vehicleId, List<String> amenityIds) {
        try {
            List<String> sorted = new ArrayList<>(amenityIds);
            sorted.sort(String::compareTo);
            String canonical = vehicleId + "\0" + String.join("\0", sorted);
            byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, List<AmenityView>> getAmenitiesBulk(List<String> vehicleIds) {
        if (vehicleIds.isEmpty()) return Map.of();
        List<VehicleAmenityRepository.Link> links = linkRepo.findByVehicleIds(vehicleIds);

        // Collect all unique amenity IDs for bulk name lookup
        Set<String> allAmenityIds = new HashSet<>();
        Map<String, List<String>> vehicleToAmenities = new HashMap<>();
        for (VehicleAmenityRepository.Link link : links) {
            vehicleToAmenities.computeIfAbsent(link.vehicleId(), k -> new ArrayList<>()).add(link.amenityId());
            allAmenityIds.add(link.amenityId());
        }

        // Bulk fetch amenity names
        Map<String, String> amenityNames = linkRepo.findAmenityNames(new ArrayList<>(allAmenityIds));

        Map<String, List<AmenityView>> result = new HashMap<>();
        for (String vehicleId : vehicleIds) {
            List<String> amenityIds = vehicleToAmenities.getOrDefault(vehicleId, List.of());
            List<AmenityView> views = new ArrayList<>();
            for (String amenityId : amenityIds) {
                views.add(new AmenityView(amenityId, amenityNames.getOrDefault(amenityId, "")));
            }
            result.put(vehicleId, views);
        }
        return result;
    }
}