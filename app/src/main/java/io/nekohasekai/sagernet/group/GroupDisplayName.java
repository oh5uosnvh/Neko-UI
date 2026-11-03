package io.nekohasekai.sagernet.group;

import java.util.Locale;

/** Strip only known import/subscription file suffixes; dots within a provider's name are data. */
public final class GroupDisplayName {
    private GroupDisplayName() {}
    public static String withoutFileSuffix(String value) {
        if (value == null) return "";
        String name = value.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{".yaml", ".yml", ".json", ".txt", ".conf"}) {
            if (lower.endsWith(suffix) && name.length() > suffix.length()) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }
}
