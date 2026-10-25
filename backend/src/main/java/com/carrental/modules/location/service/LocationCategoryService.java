package com.carrental.modules.location.service;

import com.carrental.exception.DomainException;
import com.carrental.modules.location.entity.LocationCategory;
import com.carrental.modules.location.repository.LocationCategoryRepository;
import com.carrental.security.Actor;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocationCategoryService {
    private final LocationCategoryRepository categories;

    public LocationCategoryService(LocationCategoryRepository categories) {
        this.categories = categories;
    }

    public record View(String id, String name, String revision) {}

    @Transactional(readOnly = true)
    public List<View> list(Actor actor) {
        actor.require("ADMIN");
        return categories.findAllByOrderByNameAsc().stream().map(this::view).toList();
    }

    @Transactional
    public View create(Actor actor, String submittedName) {
        actor.require("ADMIN");
        LocationCategory category = new LocationCategory();
        category.name = normalize(submittedName);
        return view(categories.saveAndFlush(category));
    }

    @Transactional
    public View update(Actor actor, String id, String submittedName, String expectedRevision) {
        actor.require("ADMIN");
        LocationCategory category = categories.lockById(id).orElseThrow(DomainException::missing);
        View current = view(category);
        if (!current.revision().equals(expectedRevision)) {
            throw DomainException.conflict("Danh mục đã thay đổi. Vui lòng tải lại trước khi lưu.");
        }
        category.name = normalize(submittedName);
        return view(categories.saveAndFlush(category));
    }

    private View view(LocationCategory category) {
        return new View(category.id, category.name, CatalogRevision.of(category.id, category.name));
    }

    private String normalize(String submittedName) {
        String name = submittedName.strip();
        if (name.isEmpty()) {
            throw DomainException.bad("Tên danh mục không được để trống.");
        }
        if (name.length() > 100) {
            throw DomainException.bad("Tên danh mục không được vượt quá 100 ký tự.");
        }
        return name;
    }
}