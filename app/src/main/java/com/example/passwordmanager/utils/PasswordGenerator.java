package com.example.passwordmanager.utils;

import java.security.SecureRandom;

public class PasswordGenerator {
    private static final String LOWERCASE = "abcdefghijklmnopqrstuvwxyz";

    private static final String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final String NUMBERS = "0123456789";

    private static final String SYMBOLS = "!@#$%^&*()-_=+[]{}";

    private static final SecureRandom random = new SecureRandom();

    private PasswordGenerator() {

    }

    public static String generatePassword(int length) {
        return generatePassword(length, true, true, true);
    }

    public static String generatePassword(int length, boolean uppercase, boolean numbers, boolean symbols) {
        if (length < 8) {
            length = 8;
        }

        StringBuilder pool = new StringBuilder(LOWERCASE);
        if (uppercase) {
            pool.append(UPPERCASE);
        }

        if (numbers) {
            pool.append(NUMBERS);
        }

        if (symbols) {
            pool.append(SYMBOLS);
        }

        String allChars = pool.toString();

        StringBuilder password = new StringBuilder(length);

        // Đảm bảo có ít nhất 1 ký tự của mỗi loại
        password.append(
                LOWERCASE.charAt(random.nextInt(LOWERCASE.length()))
        );

        if (uppercase) {
            password.append(
                    UPPERCASE.charAt(random.nextInt(UPPERCASE.length()))
            );
        }
        if (numbers) {
            password.append(
                    NUMBERS.charAt(random.nextInt(NUMBERS.length()))
            );
        }

        if (symbols) {
            password.append(
                    SYMBOLS.charAt(random.nextInt(SYMBOLS.length()))
            );
        }

        // Các ký tự còn lại
        for (int i = password.length(); i < length; i++) {
            password.append(allChars.charAt(
                    random.nextInt(allChars.length())
            ));
        }

        // Trộn vị trí ký tự
        for (int i = password.length() -1; i>0; i--) {
            int j = random.nextInt(i+1);
            char temp = password.charAt(i);

            password.setCharAt(i, password.charAt(j));
            password.setCharAt(j,temp);
        }

        return password.toString();
    }
}
