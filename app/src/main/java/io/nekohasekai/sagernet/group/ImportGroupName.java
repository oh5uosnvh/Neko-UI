package io.nekohasekai.sagernet.group;

/** Display filenames are data, never commands. Drop only the last extension. */
public final class ImportGroupName {
    private ImportGroupName() {}
    public static String fromFileName(String fileName) {
        if (fileName == null) return "";
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name;
    }
}
