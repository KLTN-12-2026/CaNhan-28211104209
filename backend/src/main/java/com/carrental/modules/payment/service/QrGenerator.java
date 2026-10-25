package com.carrental.modules.payment.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component
public final class QrGenerator {
    private QrGenerator() {}

    public static byte[] generatePng(String content, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return output.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("Không tạo được mã QR", e);
        }
    }

    public static String buildDemoPayload(String requestCode, long amountVnd) {
        return "KDRIVE_DEMO|" + requestCode + "|" + amountVnd;
    }

    public static boolean isDemoPayload(String content) {
        return content != null && content.startsWith("KDRIVE_DEMO|");
    }

    public static String parseRequestCode(String payload) {
        if (!isDemoPayload(payload)) return null;
        String[] parts = payload.split("\\|", 3);
        return parts.length >= 2 ? parts[1] : null;
    }

    public static Long parseAmount(String payload) {
        if (!isDemoPayload(payload)) return null;
        String[] parts = payload.split("\\|", 3);
        if (parts.length < 3) return null;
        try {
            return Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}