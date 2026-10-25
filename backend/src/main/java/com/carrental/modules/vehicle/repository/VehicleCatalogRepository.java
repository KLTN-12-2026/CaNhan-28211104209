package com.carrental.modules.vehicle.repository;

import com.carrental.exception.DomainException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VehicleCatalogRepository {
    public record Row(String id, String name) {}
    private final JdbcTemplate jdbc;
    public VehicleCatalogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private String table(String kind) {
        return switch (kind) {
            case "vehicle-brands" -> "vehicle_brands";
            case "vehicle-categories" -> "vehicle_categories";
            case "amenities" -> "amenities";
            default -> throw DomainException.bad("Danh mục không hợp lệ.");
        };
    }
    public List<Row> list(String kind) {
        return jdbc.query("select id,name from " + table(kind) + " order by name,id",
            (rs, index) -> new Row(rs.getString("id"), rs.getString("name")));
    }
    public Optional<Row> lock(String kind, String id) {
        return jdbc.query("select id,name from " + table(kind) + " where id=? for update",
            (rs, index) -> new Row(rs.getString("id"), rs.getString("name")), id).stream().findFirst();
    }
    public void insert(String kind, String id, String name) {
        jdbc.update("insert into " + table(kind) + "(id,name) values (?,?)", id, name);
    }
    public void update(String kind, String id, String name) {
        jdbc.update("update " + table(kind) + " set name=? where id=?", name, id);
    }
    public long usage(String kind, String id) {
        String query = switch (kind) {
            case "vehicle-brands" -> "select count(*) from vehicles where brand_id=?";
            case "vehicle-categories" -> "select count(*) from vehicles where category_id=?";
            case "amenities" -> "select count(*) from vehicle_amenities where amenity_id=?";
            default -> throw DomainException.bad("Danh mục không hợp lệ.");
        };
        return jdbc.queryForObject(query, Long.class, id);
    }
    public void delete(String kind, String id) {
        jdbc.update("delete from " + table(kind) + " where id=?", id);
    }
}
