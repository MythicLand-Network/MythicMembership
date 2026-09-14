package cn.mythicland.mythicmembership.bukkit;

import java.time.Duration;
import java.util.Locale;

final class MembershipDurationParser {
    private MembershipDurationParser() {
    }

    static Duration parse(String input) {
        if (input == null || input.length() < 2) throw new IllegalArgumentException("期限格式应为正数加 s/m/h/d");
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        long amount;
        try {
            amount = Long.parseLong(normalized.substring(0, normalized.length() - 1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("期限格式应为正数加 s/m/h/d");
        }
        if (amount <= 0L) throw new IllegalArgumentException("期限必须为正数");
        return switch (normalized.charAt(normalized.length() - 1)) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("期限单位仅支持 s/m/h/d");
        };
    }
}
