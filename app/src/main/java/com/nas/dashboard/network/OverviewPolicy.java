package com.nas.dashboard.network;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Display policy uses the NAS user's Asia/Shanghai day, independent of device locale. */
public final class OverviewPolicy {
    public static final String GREEN = "#75F0D2";
    public static final String YELLOW = "#F3C879";
    public static final String UNKNOWN = "#9BB4C7";
    private static final TimeZone ZONE = TimeZone.getTimeZone("Asia/Shanghai");
    private OverviewPolicy() { }

    public static Long parseTimestamp(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim().replaceFirst("(\\.\\d{3})\\d+", "$1")
            .replaceFirst("Z$", "+0000").replaceFirst("([+-]\\d{2}):(\\d{2})$", "$1$2");
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd HH:mm:ss.SSSZ", "yyyy-MM-dd HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"};
        for (String pattern : patterns) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(ZONE);
            format.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            Date date = format.parse(normalized, position);
            if (date != null && position.getIndex() == normalized.length()) return date.getTime();
        }
        return null;
    }

    public static boolean isTodayUnread(String updatedAt, boolean isRead, String videoId, long now) {
        if (isRead || videoId == null || videoId.isEmpty()) return false;
        Long timestamp = parseTimestamp(updatedAt);
        if (timestamp == null) return false;
        Calendar current = Calendar.getInstance(ZONE), article = Calendar.getInstance(ZONE);
        current.setTimeInMillis(now); article.setTimeInMillis(timestamp);
        return current.get(Calendar.YEAR) == article.get(Calendar.YEAR)
            && current.get(Calendar.DAY_OF_YEAR) == article.get(Calendar.DAY_OF_YEAR);
    }

    public static Double daysRemaining(String expiresAt, long now) {
        Long expires = parseTimestamp(expiresAt);
        return expires == null ? null : (expires - now) / 86400000.0;
    }

    public static String certificateColor(Double days) {
        return days == null || days.isNaN() || days.isInfinite() ? UNKNOWN : days > 15 ? GREEN : YELLOW;
    }
}
