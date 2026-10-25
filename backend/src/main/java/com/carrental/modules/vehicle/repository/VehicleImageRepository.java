package com.carrental.modules.vehicle.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class VehicleImageRepository {
    private final JdbcTemplate jdbc;

    public VehicleImageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Link(String id, String vehicleId, String url, int sortOrder, String attribution) {}

    public List<Link> findByVehicleIds(List<String> vehicleIds) {
        if (vehicleIds.isEmpty()) return List.of();
        String placeholders = vehicleIds.stream().map(i -> "?").reduce((a, b) -> a + "," + b).orElse("");
        String sql = "select id, vehicle_id, url, sort_order, attribution from vehicle_images where vehicle_id in (" + placeholders + ") order by vehicle_id, sort_order, id";
        return jdbc.query(sql, (rs, i) -> new Link(
                rs.getString("id"),
                rs.getString("vehicle_id"),
                rs.getString("url"),
                rs.getInt("sort_order"),
                rs.getString("attribution")
        ), vehicleIds.toArray());
    }

    public List<Link> findByVehicleId(String vehicleId) {
        return jdbc.query("select id, vehicle_id, url, sort_order, attribution from vehicle_images where vehicle_id = ? order by sort_order, id",
                (rs, i) -> new Link(
                        rs.getString("id"),
                        rs.getString("vehicle_id"),
                        rs.getString("url"),
                        rs.getInt("sort_order"),
                        rs.getString("attribution")
                ), vehicleId);
    }

    public Optional<Link> findById(String imageId) {
        return jdbc.query("select id, vehicle_id, url, sort_order, attribution from vehicle_images where id = ?",
                (rs, i) -> new Link(
                        rs.getString("id"),
                        rs.getString("vehicle_id"),
                        rs.getString("url"),
                        rs.getInt("sort_order"),
                        rs.getString("attribution")
                ), imageId).stream().findFirst();
    }

    public Optional<String> findVehicleIdByImageId(String imageId) {
        return jdbc.query("select vehicle_id from vehicle_images where id = ?",
                (rs, i) -> rs.getString("vehicle_id"), imageId).stream().findFirst();
    }

    public int getMaxSortOrder(String vehicleId) {
        Integer max = jdbc.queryForObject("select max(sort_order) from vehicle_images where vehicle_id = ?", Integer.class, vehicleId);
        return max != null ? max : -1;
    }

    public void deleteByVehicleId(String vehicleId) {
        jdbc.update("delete from vehicle_images where vehicle_id = ?", vehicleId);
    }

    public void deleteById(String imageId) {
        jdbc.update("delete from vehicle_images where id = ?", imageId);
    }

    public void insertLink(String vehicleId, String imageId, String url, int sortOrder, String attribution) {
        String sql = "insert into vehicle_images (id, vehicle_id, url, sort_order, attribution) values (?, ?, ?, ?, ?)";
        jdbc.update(sql, imageId, vehicleId, url, sortOrder, attribution);
    }

    public void updateLink(String imageId, String url, String attribution, int sortOrder) {
        String sql = "update vehicle_images set url = ?, attribution = ?, sort_order = ? where id = ?";
        jdbc.update(sql, url, attribution, sortOrder, imageId);
    }

    public void updateSortOrderOnly(String imageId, int sortOrder) {
        jdbc.update("update vehicle_images set sort_order = ? where id = ?", sortOrder, imageId);
    }

    public void updateSortOrders(String vehicleId, List<String> imageIds) {
        if (imageIds.isEmpty()) return;
        String placeholders = imageIds.stream().map(i -> "?").reduce((a, b) -> a + "," + b).orElse("");
        String sql = "select id, sort_order from vehicle_images where vehicle_id = ? and id in (" + placeholders + ")";
        List<Link> current = jdbc.query(sql, (rs, i) -> new Link(
                rs.getString("id"),
                null,
                null,
                rs.getInt("sort_order"),
                null
        ), new Object[]{vehicleId}.length == 1 ? new Object[]{vehicleId} : java.util.stream.Stream.concat(java.util.stream.Stream.of(vehicleId), imageIds.stream()).toArray());

        Map<String, Integer> newSortOrders = new java.util.HashMap<>();
        for (int i = 0; i < imageIds.size(); i++) {
            newSortOrders.put(imageIds.get(i), i);
        }

        for (Link link : current) {
            Integer newOrder = newSortOrders.get(link.id());
            if (newOrder != null && link.sortOrder() != newOrder) {
                jdbc.update("update vehicle_images set sort_order = ? where id = ?", newOrder, link.id());
            }
        }
    }

    public boolean existsByVehicleIdAndId(String vehicleId, String imageId) {
        Long count = jdbc.queryForObject("select count(*) from vehicle_images where vehicle_id = ? and id = ?", Long.class, vehicleId, imageId);
        return count != null && count > 0;
    }

    public Set<String> findExistingImageIds(List<String> imageIds) {
        if (imageIds.isEmpty()) return Set.of();
        String placeholders = imageIds.stream().map(i -> "?").reduce((a, b) -> a + "," + b).orElse("");
        String sql = "select id from vehicle_images where id in (" + placeholders + ")";
        return jdbc.queryForList(sql, String.class, imageIds.toArray()).stream().collect(java.util.stream.Collectors.toSet());
    }
}