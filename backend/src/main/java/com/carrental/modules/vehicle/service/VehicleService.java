package com.carrental.modules.vehicle.service;
import com.carrental.modules.vehicle.entity.Vehicle;
import com.carrental.modules.vehicle.repository.VehicleRepository;
import com.carrental.modules.vehicle.repository.VehicleAmenityRepository;
import com.carrental.modules.vehicle.repository.VehicleImageRepository;
import com.carrental.modules.branch.service.BranchService;
import com.carrental.exception.DomainException;
import com.carrental.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.Locale;
import java.time.Clock;
import java.time.Instant;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
@Service public class VehicleService {
 private final VehicleRepository repo; private final BranchService branches; private final VehicleCatalogService catalogService; private final VehicleAmenityService amenityService; private final VehicleImageService imageService;
 private final Clock clock;
 private final VehicleAmenityRepository amenityLinks;
 private final VehicleImageRepository imageLinks;
 public VehicleService(VehicleRepository r, BranchService b, VehicleCatalogService catalogService, VehicleAmenityService amenityService, VehicleImageService imageService, Clock clock, VehicleAmenityRepository amenityLinks, VehicleImageRepository imageLinks) {
     repo = r; branches = b; this.catalogService = catalogService; this.amenityService = amenityService; this.imageService = imageService;
     this.clock = clock; this.amenityLinks = amenityLinks; this.imageLinks = imageLinks;
 }
 public record View(String id, String name, String plate, String brandId, String categoryId, String homeBranchId, String currentBranchId, int seats, String transmission, long dailyPrice, String status, String description, long version, List<VehicleAmenityService.AmenityView> amenities, List<VehicleImageService.ImageView> images, Instant createdAt) {}
 public View view(Vehicle v) {
     List<VehicleAmenityService.AmenityView> amenities = amenityService.getAmenities(v.id).amenities();
     List<VehicleImageService.ImageView> images = imageService.getImages(v.id).images();
     return new View(v.id, v.name, v.plate, v.brandId, v.categoryId, v.homeBranchId, v.currentBranchId, v.seats, v.transmission, v.dailyPrice, v.status, v.description, v.version, amenities, images, v.createdAt);
 }
 public List<View> list() {
     List<Vehicle> vehicles = repo.findAll(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
     List<String> vehicleIds = vehicles.stream().map(v -> v.id).toList();
     Map<String, List<VehicleAmenityService.AmenityView>> amenityMap = vehicleIds.isEmpty() ? Map.of() : amenityService.getAmenitiesBulk(vehicleIds);
     Map<String, List<VehicleImageService.ImageView>> imageMap = vehicleIds.isEmpty() ? Map.of() : imageService.getImagesBulk(vehicleIds);
     return vehicles.stream().map(v -> {
         List<VehicleAmenityService.AmenityView> amenities = amenityMap.getOrDefault(v.id, List.of());
         List<VehicleImageService.ImageView> images = imageMap.getOrDefault(v.id, List.of());
         return new View(v.id, v.name, v.plate, v.brandId, v.categoryId, v.homeBranchId, v.currentBranchId, v.seats, v.transmission, v.dailyPrice, v.status, v.description, v.version, amenities, images, v.createdAt);
     }).toList();
 }
 public Vehicle get(String id) { return repo.findById(id).orElseThrow(DomainException::missing); }
 public Vehicle lock(String id) { return repo.lock(id).orElseThrow(DomainException::missing); }
 public VehicleCatalogService.PublicCatalog catalog() { return catalogService.publicCatalog(); }
 @Transactional public View create(Actor a, View input) {
     a.require("ADMIN"); branches.active(input.homeBranchId());
     Vehicle v = new Vehicle(); apply(v, input); v.createdAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); v.homeBranchId = input.homeBranchId(); v.currentBranchId = input.homeBranchId(); return view(repo.saveAndFlush(v));
 }
 @Transactional public View edit(Actor a, String id, View input) {
     a.require("ADMIN"); Vehicle v = lock(id); if (v.version != input.version()) throw DomainException.conflict("Xe đã thay đổi.");
     apply(v, input); repo.flush(); return view(v);
 }
 @Transactional public void delete(Actor actor, String id, long version) {
     actor.require("ADMIN");
     if (version < 0) throw DomainException.bad("Phiên bản xe không hợp lệ.");
     Vehicle vehicle = lock(id);
     if (vehicle.version != version) throw DomainException.conflict("Xe đã thay đổi. Vui lòng tải lại trước khi xóa.");
     try {
         amenityLinks.deleteByVehicleId(id);
         imageLinks.deleteByVehicleId(id);
         repo.delete(vehicle);
         repo.flush();
     } catch (DataIntegrityViolationException exception) {
         // FK RESTRICT preserves booking history; the transaction also restores image/amenity links.
         throw DomainException.conflict("Xe đã có đơn thuê hoặc dữ liệu liên quan nên không thể xóa. Hãy chuyển sang Ngừng khai thác.");
     }
 }
 private void apply(Vehicle v, View i) {
     if (i.name() == null || i.name().isBlank() || i.name().length() > 120 || i.plate() == null || i.plate().isBlank() || i.plate().length() > 20 || i.dailyPrice() < 1 || i.dailyPrice() > 100000000 || i.seats() < 2 || i.seats() > 9 || i.transmission() == null || !Set.of("AUTO", "MANUAL").contains(i.transmission()) || i.status() == null || !Set.of("ACTIVE", "MAINTENANCE", "INACTIVE").contains(i.status())) throw DomainException.bad("Thông số xe không hợp lệ.");
     v.name = i.name().strip(); v.plate = i.plate().strip().toUpperCase(Locale.ROOT); v.brandId = i.brandId(); v.categoryId = i.categoryId(); v.seats = i.seats(); v.transmission = i.transmission(); v.dailyPrice = i.dailyPrice(); v.status = i.status(); v.description = i.description();
 }
 @Transactional public View operation(Actor actor, String id, String status, long version) {
     if (status == null || !Set.of("ACTIVE", "MAINTENANCE").contains(status)) throw DomainException.bad("Staff chỉ cập nhật trạng thái vận hành.");
     Vehicle v = lock(id); branches.requireStaff(actor, v.currentBranchId);
     if (v.version != version) throw DomainException.conflict("Xe đã thay đổi.");
     if ("INACTIVE".equals(v.status) || !Set.of("ACTIVE", "MAINTENANCE").contains(status)) throw DomainException.bad("Staff chỉ cập nhật trạng thái vận hành.");
     v.status = status; repo.flush(); return view(v);
 }
}
