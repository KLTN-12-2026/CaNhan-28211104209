package com.carrental.modules.user.service;

import com.carrental.exception.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;

/** Private files: never registered as a static resource or returned as a path. */
@Component
public class LicensePhotoStore {
    private final Path root;
    public LicensePhotoStore(@Value("${app.license-photo-directory}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }
    public byte[] normalize(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 5 * 1024 * 1024)
            throw DomainException.bad("Mỗi mặt GPLX cần ảnh JPEG/PNG, tối đa 5 MB.");
        try (var stream = ImageIO.createImageInputStream(file.getInputStream())) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw DomainException.bad("Tệp không phải ảnh JPEG/PNG hợp lệ.");
            var reader = readers.next();
            try {
                if (!java.util.Set.of("JPEG", "JPG", "PNG").contains(reader.getFormatName().toUpperCase(java.util.Locale.ROOT)))
                    throw DomainException.bad("Chỉ nhận ảnh JPEG/PNG.");
                reader.setInput(stream, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || (long) w * h > 20_000_000)
                    throw DomainException.bad("Ảnh vượt giới hạn 20 triệu điểm ảnh.");
                BufferedImage image = reader.read(0);
                var output = new ByteArrayOutputStream();
                // Re-encode pixels: discard EXIF, arbitrary metadata and trailing payload.
                ImageIO.write(image, "png", output);
                if (output.size() > 10 * 1024 * 1024) throw DomainException.bad("Ảnh sau xử lý quá lớn. Vui lòng giảm kích thước.");
                return output.toByteArray();
            } finally { reader.dispose(); }
        } catch (IOException e) { throw DomainException.bad("Không đọc được ảnh GPLX."); }
    }
    public String write(byte[] bytes) {
        String key = UUID.randomUUID() + ".png";
        try { Files.createDirectories(root); Files.write(path(key), bytes, StandardOpenOption.CREATE_NEW); return key; }
        catch (IOException e) { throw new IllegalStateException("Không lưu được ảnh GPLX."); }
    }
    public byte[] read(String key) {
        if (key == null) throw DomainException.missing();
        try { return Files.readAllBytes(path(key)); }
        catch (IOException e) { throw DomainException.missing(); }
    }
    public void remove(String key) {
        if (key == null) return;
        try { Files.deleteIfExists(path(key)); }
        catch (IOException e) { throw new IllegalStateException("Không xóa được ảnh GPLX đã thay thế."); }
    }
    private Path path(String key) {
        if (!key.matches("[a-f0-9-]{36}\\.png")) throw DomainException.missing();
        return root.resolve(key);
    }
}
