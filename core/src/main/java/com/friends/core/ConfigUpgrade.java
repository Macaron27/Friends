package com.friends.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps a server's config.yml current across upgrades: adds, with their comments, what the bundled config.yml has and
 * theirs lacks. Whole top-level sections go at the end of the file; keys one level down (e.g.
 * {@code private-messages.rate-limit}) go at the end of their section, indented like the user's. Nothing they wrote
 * is rewritten or reordered (their comments and line endings stay); keys missing deeper down are left to the code's
 * defaults.
 */
public final class ConfigUpgrade {
    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("^([A-Za-z0-9_-]+):");
    private static final Pattern CHILD_KEY = Pattern.compile("^( +)([A-Za-z0-9_-]+):");

    private ConfigUpgrade() {}

    /** Their section: where it ends (offset after its last indented line), its keys' indent, which keys it has. */
    record Block(int end, boolean scalar, int indent, Set<String> keys) {}

    /** Adds what {@code file} lacks from {@code bundled}; returns what was added, e.g. "presence" (empty: unchanged). */
    public static List<String> addMissingSections(Path file, String bundled) throws IOException {
        String current = Files.readString(file, StandardCharsets.UTF_8);
        String nl = current.contains("\r\n") ? "\r\n" : "\n";
        Map<String, Block> theirs = blocks(current);
        List<String> names = new ArrayList<>();
        TreeMap<Integer, StringBuilder> inserts = new TreeMap<>(); // offset in current -> text to insert there
        StringBuilder appended = new StringBuilder();
        sections(bundled).forEach((key, section) -> {
            Block mine = theirs.get(key);
            if (mine == null) {
                names.add(key);
                appended.append('\n').append(section);
                return;
            }
            if (mine.scalar()) return; // "key: value" or "key: {...}": nothing can go under it
            children(section).forEach((child, text) -> {
                if (mine.keys().contains(child)) return;
                names.add(key + "." + child);
                inserts.computeIfAbsent(mine.end(), _ -> new StringBuilder()).append(reindent(text, mine.indent()));
            });
        });
        if (names.isEmpty()) return names;

        StringBuilder text = new StringBuilder(current);
        for (var e : inserts.descendingMap().entrySet()) { // from the end, so earlier offsets stay valid
            int at = e.getKey();
            boolean lineStart = at == 0 || text.charAt(at - 1) == '\n';
            text.insert(at, (lineStart ? "" : nl) + e.getValue().toString().replace("\n", nl));
        }
        if (!appended.isEmpty()) {
            if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') text.append(nl);
            text.append(appended.toString().replace("\n", nl));
        }
        write(file, text.toString());
        return names;
    }

    /**
     * Replaces the file in one step (a crash mid-write must not leave a truncated config). Writes through symbolic
     * links (some hosts share one config.yml between proxies).
     */
    private static void write(Path file, String text) throws IOException {
        Path target = file.toRealPath();
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
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

    /** A bundled section's keys one level down (indented 2), each with the indented comments just above it. */
    static Map<String, String> children(String section) {
        Map<String, StringBuilder> children = new LinkedHashMap<>();
        StringBuilder pending = new StringBuilder();
        StringBuilder current = null;
        for (String line : section.lines().toList()) {
            Matcher key = CHILD_KEY.matcher(line);
            if (line.isBlank()) {
                pending.append('\n');
            } else if (!line.startsWith(" ")) { // the section's own key, or the comments above it
                pending.setLength(0);
            } else if (key.find() && key.group(1).length() == 2) {
                current = new StringBuilder(strip(pending)).append(line).append('\n');
                pending.setLength(0);
                children.put(key.group(2), current);
            } else if (line.stripLeading().startsWith("#")) {
                pending.append(line).append('\n');
            } else if (current != null) { // deeper: still the current key
                current.append(pending).append(line).append('\n');
                pending.setLength(0);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        children.forEach((k, v) -> out.put(k, v.toString()));
        return out;
    }

    /** The user's top-level sections, read line by line with their offsets ('\r' kept out of the matching). */
    static Map<String, Block> blocks(String yaml) {
        Map<String, Block> blocks = new LinkedHashMap<>();
        String key = null;
        int end = 0;
        int indent = -1;
        boolean scalar = false;
        Set<String> keys = new HashSet<>();
        for (int pos = 0; pos < yaml.length(); ) {
            int eol = yaml.indexOf('\n', pos);
            int next = eol < 0 ? yaml.length() : eol + 1;
            String line = yaml.substring(pos, eol < 0 ? yaml.length() : eol).replace("\r", "");
            Matcher top = TOP_LEVEL_KEY.matcher(line);
            if (top.find()) {
                if (key != null) blocks.putIfAbsent(key, new Block(end, scalar, indent < 0 ? 2 : indent, keys));
                key = top.group(1);
                end = next;
                indent = -1;
                String value = line.substring(top.end()).strip();
                scalar = !value.isEmpty() && !value.startsWith("#");
                keys = new HashSet<>();
            } else if (key != null && line.startsWith(" ") && !line.isBlank()) {
                end = next;
                if (!line.stripLeading().startsWith("#")) {
                    int lead = line.length() - line.stripLeading().length();
                    if (indent < 0) indent = lead;
                    Matcher child = CHILD_KEY.matcher(line);
                    if (lead == indent && child.find()) keys.add(child.group(2));
                }
            }
            pos = next;
        }
        if (key != null) blocks.putIfAbsent(key, new Block(end, scalar, indent < 0 ? 2 : indent, keys));
        return blocks;
    }

    /** Bundled lines are indented by 2 per level: use the user's step instead. */
    private static String reindent(String text, int step) {
        if (step == 2) return text;
        StringBuilder out = new StringBuilder();
        for (String line : text.lines().toList()) {
            int lead = line.length() - line.stripLeading().length();
            out.append(" ".repeat(lead / 2 * step)).append(line.stripLeading()).append('\n');
        }
        return out.toString();
    }

    /** Drops the blank lines before a section's comment (they separated it from the previous one). */
    private static String strip(CharSequence comment) {
        return comment.toString().replaceFirst("^(\\s*\\n)+", "");
    }
}
