package com.friends.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.friends.api.PlayerActivity;

/**
 * {@code presence.rules}: backend server-name patterns, each mapped to a {@link PlayerActivity}. A pattern is a regex
 * that must match the whole server name, ignoring case; the first matching rule wins.
 */
public final class Activities {
    static final Activities NONE = new Activities(List.of());

    record Rule(Pattern pattern, PlayerActivity activity) {}

    private final List<Rule> rules;

    private Activities(List<Rule> rules) {
        this.rules = rules;
    }

    /**
     * Rules as the YAML list of maps ({@code pattern}, {@code game}, optional {@code mode}). A rule without a valid
     * pattern or a game is skipped and reported to {@code warn}: a typo in a cosmetic setting never disables Friends.
     */
    static Activities parse(List<? extends Map<?, ?>> rules, Consumer<String> warn) {
        List<Rule> out = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            Map<?, ?> rule = rules.get(i);
            String pattern = text(rule.get("pattern"));
            String game = text(rule.get("game"));
            String where = "presence.rules[" + i + "]";
            if (pattern == null || game == null) {
                warn.accept(where + " needs both a pattern and a game; skipped");
                continue;
            }
            try {
                out.add(new Rule(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE), new PlayerActivity(game, text(rule.get("mode")))));
            } catch (PatternSyntaxException e) {
                warn.accept(where + ": '" + pattern + "' is not a valid regex (" + e.getDescription() + "); skipped");
            }
        }
        return new Activities(List.copyOf(out));
    }

    /** YAML scalars may be numbers (e.g. {@code mode: 4}); blank means absent. */
    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().strip();
    }

    /**
     * The activity on {@code server}, or null if it is null or matches no rule.
     * ponytail: tries every rule on each server switch (microseconds for dozens of rules). No per-name cache: cloud
     * systems name servers with ever-growing ids (BW-1S-1042...), so it would grow without bound.
     */
    public PlayerActivity match(String server) {
        if (server == null) return null;
        for (Rule r : rules) {
            if (r.pattern().matcher(server).matches()) return r.activity();
        }
        return null;
    }

    public int size() {
        return rules.size();
    }
}
