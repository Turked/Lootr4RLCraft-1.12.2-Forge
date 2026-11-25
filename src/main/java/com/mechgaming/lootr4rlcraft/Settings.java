package com.mechgaming.lootr4rlcraft;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Loader;

public final class Settings {
    public static boolean privateLootEnabled = true;
    public static boolean allowManualConversion = true;
    public static boolean debugLogging;
    public static boolean compactCheckpoints = true;
    public static boolean allowInspect = true;
    private static Set<String> allowed = new HashSet<>();
    private static Set<String> excluded = new HashSet<>();
    private static Set<String> adapters = new HashSet<>(Arrays.asList("native", "charm", "storage", "cart"));
    private static boolean enrollWithLocks = true;
    private static boolean enrollWithMimics = true;
    public static boolean registerNewChests;
    public static boolean includeTrapped;
    public static boolean generatedFixedChests;
    public static boolean indicators;
    public static int unopenedColor;
    public static int openedColor;

    private Settings() {}

    /** Applies to new enrollment only. Existing private inventories retain their original adapter. */
    public static boolean blockAllowed(String registry, String adapter) {
        return privateLootEnabled && adapters.contains(adapter) && !excluded.contains(registry)
                && (allowed.isEmpty() || allowed.contains(registry))
                && (enrollWithLocks || !Loader.isModLoaded("locks"))
                && (enrollWithMimics || !Loader.isModLoaded("artifacts"));
    }

    public static void debug(String format, Object... arguments) {
        if (debugLogging) Lootr4RLCraft.LOGGER.info(format, arguments);
    }

    static void load(File file) {
        Configuration config = new Configuration(file);
        config.load();
        compactCheckpoints = config.getBoolean("compactIdenticalCheckpoints", "storage", true,
                "Keep one copy when a container recovery baseline equals its latest slots. Player allocations are retained.");
        privateLootEnabled = config.getBoolean("enablePrivateLoot", "loot", true,
                "Enable registration and player access. Disabling suspends private menus and preserves saved contents.");
        allowManualConversion = config.getBoolean("allowOperatorConversion", "migration", true,
                "Permit individual operator-selected conversions. Existing chunks are never bulk migrated.");
        allowInspect = config.getBoolean("allowInspect", "diagnostics", true, "Enable the operator inspect command.");
        debugLogging = config.getBoolean("debugLogging", "diagnostics", false,
                "Log registration decisions, record access, and indicator synchronization.");
        String[] builtIn = {"native", "charm", "storage", "cart"};
        adapters = new HashSet<>(Arrays.asList(config.getStringList("enabledAdapters", "adapters", builtIn,
                "Adapters allowed for new records. native covers vanilla, Quark and Rustic, storage covers Varied Commodities.")));
        allowed = new HashSet<>(Arrays.asList(config.getStringList("includedRegistryNames", "adapters", new String[0],
                "Optional exact block or entity IDs. An empty list permits all supported types.")));
        excluded = new HashSet<>(Arrays.asList(config.getStringList("excludedRegistryNames", "adapters", new String[0],
                "Exact block or entity IDs to leave ordinary. Exclusions take priority.")));
        enrollWithLocks = config.getBoolean("enrollWhenLocksInstalled", "compatibility", true,
                "Allow new enrollment with Locks installed. Existing managed blocks always enforce authorization.");
        enrollWithMimics = config.getBoolean("enrollWhenRLArtifactsInstalled", "compatibility", true,
                "Allow new enrollment with RLArtifacts installed, using its native Mimic events.");
        registerNewChests = config.getBoolean("registerNewChests", "loot", true,
                "Register loot-bearing inventories created during world generation. Applies to newly generated areas. Restart to apply.");
        includeTrapped = config.getBoolean("includeTrappedChests", "loot", true,
                "Also register vanilla trapped chests during world generation.");
        generatedFixedChests = config.getBoolean("generatedFixedChests", "loot", true,
                "Preserve fixed contents separately for each player when a generated inventory has items and no retained loot table. Existing chunks are unchanged.");
        indicators = config.getBoolean("indicators", "visuals", true, "Draw player-specific loot indicators.");
        unopenedColor = config.getInt("unopenedColor", "visuals", 0xF2BB4B, 0, 0xFFFFFF,
                "Color for unopened chests, as the decimal value of an RGB color.");
        openedColor = config.getInt("openedColor", "visuals", 0xA1A7AE, 0, 0xFFFFFF,
                "Color for opened chests, as the decimal value of an RGB color.");
        if (config.hasChanged()) {
            config.save();
        }
    }
}
