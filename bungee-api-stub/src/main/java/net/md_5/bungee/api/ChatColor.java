package net.md_5.bungee.api;

public class ChatColor {
    // Basic color constants (§ codes)
    public static final String BLACK = "§0";
    public static final String DARK_BLUE = "§1";
    public static final String DARK_GREEN = "§2";
    public static final String DARK_AQUA = "§3";
    public static final String DARK_RED = "§4";
    public static final String DARK_PURPLE = "§5";
    public static final String GOLD = "§6";
    public static final String GRAY = "§7";
    public static final String DARK_GRAY = "§8";
    public static final String BLUE = "§9";
    public static final String GREEN = "§a";
    public static final String AQUA = "§b";
    public static final String RED = "§c";
    public static final String LIGHT_PURPLE = "§d";
    public static final String YELLOW = "§e";
    public static final String WHITE = "§f";
    public static final String RESET = "§r";

    // Formatting
    public static final String BOLD = "§l";
    public static final String ITALIC = "§o";
    public static final String UNDERLINE = "§n";

    // Simple translator: replaces alternate color char (&) with '§'
    public static String translateAlternateColorCodes(char altColorChar, String textToTranslate) {
        if (textToTranslate == null) return null;
        return textToTranslate.replace(altColorChar, '§');
    }
}
