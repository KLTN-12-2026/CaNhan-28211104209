package com.carrental.modules.user.controller;

import com.carrental.modules.user.service.LicenseService;
import com.carrental.security.Actor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/me/license/photos")
public class LicensePhotoController {
    private final LicenseService licenses;
    public LicensePhotoController(LicenseService licenses) { this.licenses = licenses; }
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object upload(@AuthenticationPrincipal Actor a, @RequestParam long version,
                         @RequestPart MultipartFile front, @RequestPart MultipartFile back) {
        return licenses.upload(a, version, front, back);
    }
    @GetMapping("/{side}")
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal Actor a, @PathVariable String side) {
        return privateImage(licenses.ownPhoto(a, side));
    }
    public static ResponseEntity<byte[]> privateImage(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
            .header("Cache-Control", "no-store, private").header("Pragma", "no-cache")
            .header("X-Content-Type-Options", "nosniff").header("Content-Disposition", "inline; filename=license.png")
            .body(bytes);
    }
}
