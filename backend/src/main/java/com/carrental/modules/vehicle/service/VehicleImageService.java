package com.carrental.modules.vehicle.service;

import com.carrental.exception.DomainException;
import com.carrental.modules.vehicle.repository.VehicleImageRepository;
import com.carrental.modules.vehicle.repository.VehicleRepository;
import com.carrental.security.Actor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Service
public class VehicleImageService {
    private final VehicleRepository vehicleRepo;
    private final VehicleImageRepository imageRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VehicleImageService(VehicleRepository vehicleRepo, VehicleImageRepository imageRepo) {
        this.vehicleRepo = vehicleRepo;
        this.imageRepo = imageRepo;
    }

    public record ImageView(String id, String url, int sortOrder, String attribution) {}
    public record ImagesDto(List<ImageView> images, String revision) {}

    public record CreateRequest(String url, String attribution, String expectedRevision) {}
    public record UpdateRequest(String url, String attribution, String expectedRevision) {}
    public record ReorderRequest(List<String> imageIds, String expectedRevision) {}

    private static final int MAX_URL_LENGTH = 500;
    private static final int MAX_ATTRIBUTION_LENGTH = 1000;
    private static final Pattern URL_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:.*");

    public ImagesDto getImages(String vehicleId) {
        vehicleRepo.findById(vehicleId).orElseThrow(DomainException::missing);
        List<VehicleImageRepository.Link> links = imageRepo.findByVehicleId(vehicleId);
        List<ImageView> images = links.stream()
                .map(l -> new ImageView(l.id(), l.url(), l.sortOrder(), l.attribution()))
                .toList();
        return new ImagesDto(images, computeRevision(vehicleId, links));
    }

    @Transactional
    public ImagesDto createImage(Actor actor, String vehicleId, CreateRequest request) {
        actor.require("ADMIN");
        vehicleRepo.lock(vehicleId).orElseThrow(DomainException::missing);

        validateRevision(request.expectedRevision());
        validateCreateRequest(request);

        List<VehicleImageRepository.Link> currentLinks = imageRepo.findByVehicleId(vehicleId);
        String currentRevision = computeRevision(vehicleId, currentLinks);

        if (!currentRevision.equals(request.expectedRevision())) {
            throw DomainException.conflict("Hình ảnh xe đã thay đổi. Vui lòng tải lại.");
        }

        int maxSortOrder = imageRepo.getMaxSortOrder(vehicleId);
        if (maxSortOrder == Integer.MAX_VALUE) {
            throw DomainException.bad("Số lượng hình ảnh đã vượt quá giới hạn.");
        }
        int newSortOrder = maxSortOrder + 1;
        String imageId = UUID.randomUUID().toString();
        imageRepo.insertLink(vehicleId, imageId, request.url().trim(), newSortOrder, normalizeAttribution(request.attribution()));

        List<VehicleImageRepository.Link> newLinks = imageRepo.findByVehicleId(vehicleId);
        String newRevision = computeRevision(vehicleId, newLinks);

        return new ImagesDto(
                newLinks.stream().map(l -> new ImageView(l.id(), l.url(), l.sortOrder(), l.attribution())).toList(),
                newRevision);
    }

    @Transactional
    public ImagesDto updateImage(Actor actor, String vehicleId, String imageId, UpdateRequest request) {
        actor.require("ADMIN");
        vehicleRepo.lock(vehicleId).orElseThrow(DomainException::missing);

        if (!imageRepo.existsByVehicleIdAndId(vehicleId, imageId)) {
            throw DomainException.missing();
        }

        validateRevision(request.expectedRevision());
        validateUpdateRequest(request);

        List<VehicleImageRepository.Link> currentLinks = imageRepo.findByVehicleId(vehicleId);
        String currentRevision = computeRevision(vehicleId, currentLinks);

        if (!currentRevision.equals(request.expectedRevision())) {
            throw DomainException.conflict("Hình ảnh xe đã thay đổi. Vui lòng tải lại.");
        }

        // Check if no-op (same metadata and order)
        VehicleImageRepository.Link currentLink = currentLinks.stream().filter(l -> l.id().equals(imageId)).findFirst().orElseThrow();
        String newUrl = request.url().trim();
        String newAttribution = normalizeAttribution(request.attribution());
        if (currentLink.url().equals(newUrl) && Objects.equals(currentLink.attribution(), newAttribution)) {
            // No-op: return current state without any DML
            return new ImagesDto(
                    currentLinks.stream().map(l -> new ImageView(l.id(), l.url(), l.sortOrder(), l.attribution())).toList(),
                    currentRevision);
        }

        imageRepo.updateLink(imageId, newUrl, newAttribution, currentLink.sortOrder());

        List<VehicleImageRepository.Link> newLinks = imageRepo.findByVehicleId(vehicleId);
        String newRevision = computeRevision(vehicleId, newLinks);

        return new ImagesDto(
                newLinks.stream().map(l -> new ImageView(l.id(), l.url(), l.sortOrder(), l.attribution())).toList(),
                newRevision);
    }

    @Transactional
    public ImagesDto reorderImages(Actor actor, String vehicleId, ReorderRequest request) {
        actor.require("ADMIN");
        vehicleRepo.lock(vehicleId).orElseThrow(DomainException::missing);

        validateRevision(request.expectedRevision());
        validateReorderRequest(request, vehicleId);

        List<VehicleImageRepository.Link> currentLinks = imageRepo.findByVehicleId(vehicleId);
        String currentRevision = computeRevision(vehicleId, currentLinks);

        if (!currentRevision.equals(request.expectedRevision())) {
            throw DomainException.conflict("Hình ảnh xe đã thay đổi. Vui lòng tải lại.");
        }

        // Check if no-op (same order)
        List<String> currentIds = currentLinks.stream().map(VehicleImageRepository.Link::id).toList();
        if (currentIds.equals(request.imageIds())) {
            return new ImagesDto(
                    currentLinks.stream().map(l -> new ImageView(l.id(), l.url(), l.sortOrder(), l.attribution())).toList(),
                    currentRevision);
        }

        // Use negative temporary sort orders to avoid UNIQUE constraint collision with existing non-negative sort_orders
        int tempBase = temporaryOrderBase(currentLinks);
        for (int i = 0; i < request.imageIds().size(); i++) {
            String imageId = request.imageIds().get(i);
            imageRepo.updateSortOrderOnly(imageId, tempBase + i);
        }

        // Now set final contiguous sort orders (0..n-1)
        for (int i = 0; i < request.imageIds().size(); i++) {
            String imageId = request.imageIds().get(i);
            imageRepo.updateSortOrderOnly(imageId, i);
        }

        List<VehicleImageRepository.Link> newLinks = imageRepo.findByVehicleId(vehicleId);
        String newRevision = computeRevision(vehicleId, newLinks);

        return new ImagesDto(
                newLinks.stream().map(l -> new ImageView(l.id(), l.url(), l.sortOrder(), l.attribution())).toList(),
                newRevision);
    }

    private void validateRevision(String revision) {
        if (revision == null || !revision.matches("[a-f0-9]{64}")) {
            throw DomainException.bad("expectedRevision không hợp lệ.");
        }
    }

    private void validateCreateRequest(CreateRequest request) {
        if (request.url() == null || request.url().isBlank()) {
            throw DomainException.bad("URL là bắt buộc.");
        }
        String url = request.url().trim();
        if (url.length() > MAX_URL_LENGTH) {
            throw DomainException.bad("URL quá dài (tối đa 500 ký tự).");
        }
        validateUrl(url);
    }

    private void validateUpdateRequest(UpdateRequest request) {
        if (request.url() == null || request.url().isBlank()) {
            throw DomainException.bad("URL là bắt buộc.");
        }
        String url = request.url().trim();
        if (url.length() > MAX_URL_LENGTH) {
            throw DomainException.bad("URL quá dài (tối đa 500 ký tự).");
        }
        validateUrl(url);
    }

    private void validateUrl(String url) {
        // Root-relative URL under /media/vehicles/
        if (url.startsWith("/media/vehicles/")) {
            validateRootRelativeUrl(url);
            return;
        }

        // Absolute HTTPS URL
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme)) {
                throw DomainException.bad("URL phải dùng HTTPS.");
            }
            if (uri.getUserInfo() != null) {
                throw DomainException.bad("URL không được chứa thông tin xác thực.");
            }
            if (uri.getFragment() != null) {
                throw DomainException.bad("URL không được chứa fragment.");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw DomainException.bad("URL phải có host hợp lệ.");
            }
            // Check for control characters, whitespace
            if (url.matches(".*[\\p{Cntrl}\\s].*")) {
                throw DomainException.bad("URL chứa ký tự không hợp lệ.");
            }
            // Check for path traversal
            for (String segment : uri.getPath().split("/", -1)) {
                String decodedDots = segment.replaceAll("(?i)%2e", ".");
                if (decodedDots.equals(".") || decodedDots.equals("..")) {
                    throw DomainException.bad("URL chứa traversal không hợp lệ.");
                }
            }
        } catch (URISyntaxException e) {
            throw DomainException.bad("URL không hợp lệ: " + e.getMessage());
        }
    }

    private void validateRootRelativeUrl(String url) {
        // Must start with /media/vehicles/
        if (!url.startsWith("/media/vehicles/")) {
            throw DomainException.bad("Root-relative URL phải nằm trong /media/vehicles/");
        }
        String path = url.substring("/media/vehicles/".length());
        if (path.isEmpty()) {
            throw DomainException.bad("Root-relative URL phải có filename không rỗng.");
        }
        // No query or fragment
        if (url.contains("?") || url.contains("#")) {
            throw DomainException.bad("Root-relative URL không được chứa query hoặc fragment.");
        }
        // Path segments: only alphanumeric, _, -, ., /
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw DomainException.bad("Root-relative URL có segment không hợp lệ.");
            }
            if (!segment.matches("^[a-zA-Z0-9_.-]+$")) {
                throw DomainException.bad("Root-relative URL chứa ký tự không hợp lệ.");
            }
            if (segment.contains("\\") || segment.contains("%")) {
                throw DomainException.bad("Root-relative URL chứa ký tự không hợp lệ.");
            }
        }
        // Check for control characters, whitespace
        if (url.matches(".*[\\p{Cntrl}\\s].*")) {
            throw DomainException.bad("URL chứa ký tự không hợp lệ.");
        }
    }

    private void validateReorderRequest(ReorderRequest request, String vehicleId) {
        if (request.imageIds() == null) {
            throw DomainException.bad("imageIds là bắt buộc.");
        }
        if (request.imageIds().isEmpty()) {
            // Empty array is valid only if vehicle has no images
            List<VehicleImageRepository.Link> current = imageRepo.findByVehicleId(vehicleId);
            if (!current.isEmpty()) {
                throw DomainException.bad("imageIds không được rỗng khi xe vẫn còn hình ảnh.");
            }
            return;
        }

        Set<String> seen = new HashSet<>();
        for (String id : request.imageIds()) {
            if (id == null || id.isBlank()) {
                throw DomainException.bad("imageIds không được chứa phần tử null hoặc rỗng.");
            }
            if (!seen.add(id)) {
                throw DomainException.bad("imageIds không được chứa ID trùng lặp.");
            }
        }

        // Validate all image IDs belong to this vehicle

        // Check for missing or extra images
        List<VehicleImageRepository.Link> current = imageRepo.findByVehicleId(vehicleId);
        Set<String> currentIds = current.stream().map(VehicleImageRepository.Link::id).collect(java.util.stream.Collectors.toSet());
        Set<String> requestedIds = new HashSet<>(request.imageIds());
        if (!currentIds.equals(requestedIds)) {
            throw DomainException.bad("imageIds phải là hoán vị chính xác của toàn bộ hình ảnh xe.");
        }

    }

    private int temporaryOrderBase(List<VehicleImageRepository.Link> links) {
        long candidate = Integer.MIN_VALUE;
        for (int occupied : links.stream().map(VehicleImageRepository.Link::sortOrder).sorted().toList()) {
            if ((long) occupied - candidate >= links.size()) break;
            if (occupied >= candidate) candidate = (long) occupied + 1;
        }
        if (candidate + links.size() - 1 > Integer.MAX_VALUE) {
            throw DomainException.bad("Không đủ vùng thứ tự tạm cho hình ảnh.");
        }
        return (int) candidate;
    }

    private record RevisionState(String vehicleId, List<ImageView> images) {}

    private String normalizeAttribution(String attribution) {
        if (attribution == null || attribution.trim().isEmpty()) {
            return null;
        }
        String normalized = attribution.trim();
        if (normalized.length() > MAX_ATTRIBUTION_LENGTH) {
            throw DomainException.bad("Attribution quá dài (tối đa 1000 ký tự).");
        }
        return normalized;
    }

    public String computeRevision(String vehicleId, List<VehicleImageRepository.Link> links) {
        try {
            // Canonical JSON using typed DTO and Jackson serializer
            var canonical = new RevisionState(vehicleId, links.stream()
                    .sorted(Comparator.comparingInt(VehicleImageRepository.Link::sortOrder)
                            .thenComparing(VehicleImageRepository.Link::id))
                    .map(link -> new ImageView(link.id(), link.url(), link.sortOrder(), link.attribution()))
                    .toList());
            String json = objectMapper.writeValueAsString(canonical);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, List<ImageView>> getImagesBulk(List<String> vehicleIds) {
        if (vehicleIds.isEmpty()) return Map.of();
        List<VehicleImageRepository.Link> links = imageRepo.findByVehicleIds(vehicleIds);

        Map<String, List<ImageView>> result = new HashMap<>();
        for (String vehicleId : vehicleIds) {
            result.put(vehicleId, new ArrayList<>());
        }
        for (VehicleImageRepository.Link link : links) {
            List<ImageView> views = result.computeIfAbsent(link.vehicleId(), k -> new ArrayList<>());
            views.add(new ImageView(link.id(), link.url(), link.sortOrder(), link.attribution()));
        }
        return result;
    }
}
