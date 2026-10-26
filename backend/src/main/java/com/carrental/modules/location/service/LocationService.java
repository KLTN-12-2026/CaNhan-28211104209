package com.carrental.modules.location.service;

import com.carrental.exception.DomainException;
import com.carrental.modules.location.entity.Location;
import com.carrental.modules.location.entity.LocationCategory;
import com.carrental.modules.location.repository.LocationCategoryRepository;
import com.carrental.modules.location.repository.LocationRepository;
import com.carrental.security.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocationService {
    private static final Pattern STATUS = Pattern.compile("ACTIVE|INACTIVE");

    private final LocationRepository locations;
    private final LocationCategoryRepository categories;
    private final ObjectMapper json;
    private final Clock clock;

    public LocationService(LocationRepository locations, LocationCategoryRepository categories, ObjectMapper json, Clock clock) {
        this.locations = locations;
        this.categories = categories;
        this.json = json;
        this.clock = clock;
    }

    public record View(
        String id,
        String categoryId,
        String categoryName,
        String name,
        BigDecimal latitude,
        BigDecimal longitude,
        String status,
        String revision,
        VisitMetadata metadata
    ) {}

    public record PublicView(String id, String categoryId, String categoryName, String name,
        BigDecimal latitude, BigDecimal longitude, VisitMetadata metadata) {
        public PublicView(String id, String categoryId, String categoryName, String name, BigDecimal latitude, BigDecimal longitude) {
            this(id, categoryId, categoryName, name, latitude, longitude, null);
        }
    }

    @Transactional(readOnly = true)
    public List<PublicView> activeCatalog(Actor actor) {
        actor.require("USER");
        return locations.findAllWithCategoryOrderByNameAsc().stream()
            .filter(location -> "ACTIVE".equals(location.status))
            .map(location -> new PublicView(location.id, location.category.id, location.category.name,
                location.name, location.latitude, location.longitude, metadata(location))).toList();
    }

    @Transactional(readOnly = true)
    public List<View> list(Actor actor) {
        actor.require("ADMIN");
        return locations.findAllWithCategoryOrderByNameAsc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public View get(Actor actor, String id) {
        actor.require("ADMIN");
        return view(locations.findWithCategoryById(id).orElseThrow(DomainException::missing));
    }

    @Transactional
    public View create(Actor actor, Input input) {
        actor.require("ADMIN");
        Location location = new Location();
        location.category = category(input.categoryId());
        apply(location, input);
        return view(locations.saveAndFlush(location));
    }

    @Transactional
    public View update(Actor actor, String id, Input input, String expectedRevision) {
        actor.require("ADMIN");
        Location location = locations.lockWithCategoryById(id).orElseThrow(DomainException::missing);
        if (!view(location).revision().equals(expectedRevision)) {
            throw DomainException.conflict("Địa điểm đã thay đổi. Vui lòng tải lại trước khi lưu.");
        }
        location.category = category(input.categoryId());
        apply(location, input);
        return view(locations.saveAndFlush(location));
    }

    private LocationCategory category(String categoryId) {
        return categories.findById(categoryId).orElseThrow(() -> DomainException.bad("Loại địa điểm không tồn tại."));
    }

    private void apply(Location location, Input input) {
        location.name = normalizeName(input.name());
        location.latitude = coordinate(input.latitude(), "Vĩ độ", new BigDecimal("-90"), new BigDecimal("90"));
        location.longitude = coordinate(input.longitude(), "Kinh độ", new BigDecimal("-180"), new BigDecimal("180"));
        if (!STATUS.matcher(input.status()).matches()) {
            throw DomainException.bad("Trạng thái phải là ACTIVE hoặc INACTIVE.");
        }
        location.status = input.status();
        if (input.metadata() != null) {
            try { location.visitMetadata = json.writeValueAsString(input.metadata().validated(clock)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw DomainException.bad("Metadata địa điểm không hợp lệ."); }
        }
    }

    private String normalizeName(String submittedName) {
        String name = submittedName.strip();
        if (name.isEmpty()) {
            throw DomainException.bad("Tên địa điểm không được để trống.");
        }
        if (name.length() > 200) {
            throw DomainException.bad("Tên địa điểm không được vượt quá 200 ký tự.");
        }
        return name;
    }

    private BigDecimal coordinate(BigDecimal value, String label, BigDecimal minimum, BigDecimal maximum) {
        if (value.scale() > 7) {
            throw DomainException.bad(label + " không được có quá 7 chữ số thập phân.");
        }
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw DomainException.bad(label + " nằm ngoài phạm vi cho phép.");
        }
        return value.setScale(7, RoundingMode.UNNECESSARY);
    }

    private View view(Location location) {
        String revision = CatalogRevision.of(
            location.id,
            location.category.id,
            location.name,
            location.latitude.toPlainString(),
            location.longitude.toPlainString(),
            location.status,
            location.visitMetadata == null ? "" : location.visitMetadata
        );
        return new View(
            location.id,
            location.category.id,
            location.category.name,
            location.name,
            location.latitude,
            location.longitude,
            location.status,
            revision,
            metadata(location)
        );
    }

    public record Input(
        String categoryId,
        String name,
        BigDecimal latitude,
        BigDecimal longitude,
        String status,
        VisitMetadata metadata
    ) {
        public Input(String categoryId, String name, BigDecimal latitude, BigDecimal longitude, String status) {
            this(categoryId, name, latitude, longitude, status, null);
        }
    }

    private VisitMetadata metadata(Location location) {
        if (location.visitMetadata == null) return null;
        try { return json.readValue(location.visitMetadata, VisitMetadata.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Stored location metadata is invalid", error); }
    }
}
