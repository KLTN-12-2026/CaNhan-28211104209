package com.carrental;

import com.carrental.modules.user.service.LicenseService;
import com.carrental.security.Actor;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/** Explicit synthetic pixels, no real identity documents. Uses production upload/review paths. */
public final class LicensePhotoFixture {
    public static MockMultipartFile image(String part) {
        try {
            var out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), "png", out);
            return new MockMultipartFile(part, "synthetic-test.png", "image/png", out.toByteArray());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static void approved(LicenseService licenses, Actor user) {
        var current = licenses.mine(user);
        var uploaded = licenses.upload(user, current.version(), image("front"), image("back"));
        licenses.review(user.id(), new Actor("demo-staff-dn", "STAFF", "Fixture"), uploaded.version(), true, "Ảnh giả lập cho kiểm thử nghiệp vụ, không phải giấy tờ thật.");
    }
}
