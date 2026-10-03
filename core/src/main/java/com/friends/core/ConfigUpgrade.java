package com.friends.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Keeps a server's config.yml current across upgrades: appends, with their comments, the top-level sections that the
 * bundled config.yml has and theirs lacks. Nothing they wrote is rewritten or reordered (their comments stay), and
 * missing keys inside an existing section are left to the code's defaults.
 */
public final class ConfigUpgrade {
    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("^([A-Za-z0-9_-]+):");

    private ConfigUpgrade() {}

    /** Appends what {@code file} lacks from {@code bundled}; returns the added section names (empty: unchanged). */
    public static List<String> addMissingSections(Path file, String bundled) throws IOException {
        String current = Files.readString(file, StandardCharsets.UTF_8);
        Set<String> present = current.lines().map(TOP_LEVEL_KEY::matcher).filter(Matcher::find)
                .map(m -> m.group(1)).collect(Collectors.toSet());
        StringBuilder added = new StringBuilder();
        List<String> names = new ArrayList<>();
        sections(bundled).forEach((key, block) -> {
            if (present.contains(key)) return;
            names.add(key);
            added.append('\n').append(block);
        });
        if (names.isEmpty()) return names;
        String text = (current.isEmpty() || current.endsWith("\n") ? "" : "\n") + added;
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        return names;
    }

    /** Each top-level key's lines, with the column-0 comments just above it. */
    static Map<String, String> sections(String yaml) {
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        StringBuilder pending = new StringBuilder(); // comments and blank lines: they belong to the next key
        StringBuilder current = null;
        for (String line : yaml.lines().toList()) {
            Matcher key = TOP_LEVEL_KEY.matcher(line);
            if (key.find()) {
                current = new StringBuilder(strip(pending)).append(line).append('\n');
                pending.setLength(0);
                sections.put(key.group(1), current);
            } else if (line.isBlank() || line.startsWith("#")) {
                pending.append(line).append('\n');
            } else if (current != null) { // indented: still the current section
                current.append(pending).append(line).append('\n');
                pending.setLength(0);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        sections.forEach((k, v) -> out.put(k, v.toString()));
        return out;
    }

    /** Drops the blank lines before a section's comment (they separated it from the previous one). */
    private static String strip(CharSequence comment) {
        return comment.toString().replaceFirst("^(\\s*\\n)+", "");
    }
}
