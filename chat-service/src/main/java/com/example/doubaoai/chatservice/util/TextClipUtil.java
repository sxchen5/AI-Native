package com.example.doubaoai.chatservice.util;

/**
 * 将文本限制在最大字符数内，尽量在标点或空格处断开，末尾不追加省略号。
 */
public final class TextClipUtil {

    private TextClipUtil() {
    }

    public static String clipNatural(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        String t = s.strip().replaceAll("[…\\.]+$", "").strip();
        if (t.length() <= maxChars) {
            return t;
        }
        String slice = t.substring(0, maxChars);
        int minBreak = Math.max(0, maxChars - 10);
        for (int i = slice.length() - 1; i >= minBreak; i--) {
            char c = slice.charAt(i);
            if (c == '，' || c == '。' || c == '、' || c == '；' || c == '：' || c == '？' || c == '！'
                    || c == ',' || c == '.' || c == ';' || c == ':' || c == '?' || c == '!'
                    || c == ' ') {
                return slice.substring(0, i + 1).strip();
            }
        }
        return slice.strip();
    }

    public static String stripTrailingEllipsis(String s) {
        if (s == null) {
            return "";
        }
        return s.strip().replaceAll("[…\\.]+$", "").strip();
    }
}
