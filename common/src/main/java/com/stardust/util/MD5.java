package com.stardust.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MD5 {

    public static byte[] md5Bytes(String message) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(message.getBytes());
            return md5.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] md5Bytes(byte[] data) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return md5.digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String md5(String message) {
        return bytesToHex(md5Bytes(message));
    }

    public static String md5(byte[] data) {
        return bytesToHex(md5Bytes(data));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(32);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xFF & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
