package com.carrental.modules.vehicle.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class VehicleAmenityRepository {
    private final JdbcTemplate jdbc;

    public VehicleAmenityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Link(String vehicleId, String amenityId) {}

    public List<Link> findByVehicleIds(List<String> vehicleIds) {
        if (vehicleIds.isEmpty()) return List.of();
        String placeholders = vehicleIds.stream().map(i -> "?").reduce((a, b) -> a + "," + b).orElse("");
        String sql = "select vehicle_id, amenity_id from vehicle_amenities where vehicle_id in (" + placeholders + ") order by vehicle_id, amenity_id";
        return jdbc.query(sql, (rs, i) -> new Link(rs.getString("vehicle_id"), rs.getString("amenity_id")), vehicleIds.toArray());
    }

    public List<Link> findByVehicleId(String vehicleId) {
        return jdbc.query("select vehicle_id, amenity_id from vehicle_amenities where vehicle_id = ? order by amenity_id",
                (rs, i) -> new Link(rs.getString("vehicle_id"), rs.getString("amenity_id")), vehicleId);
    }

    public Optional<String> findAmenityName(String amenityId) {
        return jdbc.query("select name from amenities where id = ?",
                (rs, i) -> rs.getString("name"), amenityId).stream().findFirst();
    }

    public List<String> findAllAmenityIds() {
        return jdbc.queryForList("select id from amenities order by id", String.class);
    }

    public Set<String> findExistingAmenityIds(List<String> amenityIds) {
        if (amenityIds.isEmpty()) return Set.of();
        String placeholders = amenityIds.stream().map(i -> "?").reduce((a, b) -> a + "," + b).orElse("");
        String sql = "select id from amenities where id in (" + placeholders + ")";
        return jdbc.queryForList(sql, String.class, amenityIds.toArray()).stream().collect(java.util.stream.Collectors.toSet());
    }

    public Map<String, String> findAmenityNames(List<String> amenityIds) {
        if (amenityIds.isEmpty()) return Map.of();
        String placeholders = amenityIds.stream().map(i -> "?").reduce((a, b) -> a + "," + b).orElse("");
        String sql = "select id, name from amenities where id in (" + placeholders + ")";
        return jdbc.query(sql, (rs, i) -> Map.entry(rs.getString("id"), rs.getString("name")), amenityIds.toArray())
                .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public void deleteByVehicleId(String vehicleId) {
        jdbc.update("delete from vehicle_amenities where vehicle_id = ?", vehicleId);
    }

    public void insertLinks(String vehicleId, List<String> amenityIds) {
        if (amenityIds.isEmpty()) return;
        String sql = "insert into vehicle_amenities (vehicle_id, amenity_id) values (?, ?)";
        jdbc.batchUpdate(sql, amenityIds, amenityIds.size(), (ps, amenityId) -> {
            ps.setString(1, vehicleId);
            ps.setString(2, amenityId);
        });
    }

    public boolean existsByVehicleIdAndAmenityId(String vehicleId, String amenityId) {
        Long count = jdbc.queryForObject("select count(*) from vehicle_amenities where vehicle_id = ? and amenity_id = ?", Long.class, vehicleId, amenityId);
        return count != null && count > 0;
    }
}