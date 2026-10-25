package com.carrental.security;

import com.carrental.exception.DomainException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

public final class PasswordPolicy {
    private PasswordPolicy() {}
    private static final Set<String> COMMON = Set.of("password1!", "password123!", "qwerty123!", "admin123!", "welcome1!", "12345678", "password");
    public static void validate(String password) {
        if (password == null || password.length() < 8 || password.length() > 72
            || password.getBytes(StandardCharsets.UTF_8).length > 72
            || !password.codePoints().anyMatch(Character::isUpperCase)
            || !password.codePoints().anyMatch(Character::isLowerCase)
            || !password.codePoints().anyMatch(Character::isDigit)
            || !password.codePoints().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c))) {
            throw DomainException.bad("Mật khẩu cần ít nhất 8 ký tự, có chữ hoa, chữ thường, số và ký tự đặc biệt; tối đa 72 byte UTF-8.");
        }
        if (COMMON.contains(password.toLowerCase(Locale.ROOT))) throw DomainException.bad("Mật khẩu quá phổ biến. Vui lòng chọn mật khẩu khác.");
    }
}
