package com.carrental.modules.vehicle.service;

import com.carrental.exception.DomainException;
import com.carrental.modules.vehicle.repository.VehicleCatalogRepository;
import com.carrental.security.Actor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleCatalogService {
    private final VehicleCatalogRepository repository;
    public VehicleCatalogService(VehicleCatalogRepository repository) { this.repository = repository; }
    public record Item(String id, String name, String revision) {}

    public record NamedItem(String id, String name) {}
    public record PublicCatalog(List<NamedItem> brands, List<NamedItem> categories, List<NamedItem> amenities) {}
    private List<NamedItem> names(String kind) {
        return repository.list(kind).stream().map(row -> new NamedItem(row.id(), row.name())).toList();
    }
    public PublicCatalog publicCatalog() {
        return new PublicCatalog(names("vehicle-brands"), names("vehicle-categories"), names("amenities"));
    }
    private Item view(String kind, String id, String name) {
        try {
            byte[] bytes = (kind + "\0" + id + "\0" + name).getBytes(StandardCharsets.UTF_8);
            return new Item(id, name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String name(String name) {
        if (name == null || name.strip().isEmpty() || name.strip().length() > 100)
            throw DomainException.bad("Tên phải có từ 1 đến 100 ký tự.");
        return name.strip();
    }
    public List<Item> list(Actor actor, String kind) {
        actor.require("ADMIN");
        return repository.list(kind).stream().map(row -> view(kind, row.id(), row.name())).toList();
    }
    @Transactional
    public Item create(Actor actor, String kind, String input) {
        actor.require("ADMIN");
        String name = name(input), id = UUID.randomUUID().toString();
        try { repository.insert(kind, id, name); }
        catch (DuplicateKeyException e) { throw DomainException.conflict("Tên danh mục đã tồn tại."); }
        return view(kind, id, name);
    }
    @Transactional
    public Item update(Actor actor, String kind, String id, String input, String expectedRevision) {
        actor.require("ADMIN");
        String name = name(input);
        if (expectedRevision == null || !expectedRevision.matches("[a-f0-9]{64}"))
            throw DomainException.bad("expectedRevision không hợp lệ.");
        VehicleCatalogRepository.Row row = repository.lock(kind, id).orElseThrow(DomainException::missing);
        if (!view(kind, row.id(), row.name()).revision().equals(expectedRevision))
            throw DomainException.conflict("Danh mục đã thay đổi. Vui lòng tải lại.");
        try { repository.update(kind, id, name); }
        catch (DuplicateKeyException e) { throw DomainException.conflict("Tên danh mục đã tồn tại."); }
        return view(kind, id, name);
    }
    @Transactional
    public void delete(Actor actor, String kind, String id, String expectedRevision) {
        actor.require("ADMIN");
        if (expectedRevision == null || !expectedRevision.matches("[a-f0-9]{64}"))
            throw DomainException.bad("Cần phiên bản danh mục hợp lệ để xóa.");
        var row = repository.lock(kind, id).orElseThrow(DomainException::missing);
        if (!view(kind, row.id(), row.name()).revision().equals(expectedRevision))
            throw DomainException.conflict("Danh mục đã thay đổi. Vui lòng tải lại trước khi xóa.");
        long usage = repository.usage(kind, id);
        if (usage > 0) throw DomainException.conflict("Danh mục đang được " + usage + " xe sử dụng nên không thể xóa. Hãy chỉnh lại liên kết của xe trước.");
        try { repository.delete(kind, id); }
        catch (DataIntegrityViolationException failure) {
            throw DomainException.conflict("Danh mục đang được dữ liệu khác sử dụng nên không thể xóa. Vui lòng tải lại.");
        }
    }
}
