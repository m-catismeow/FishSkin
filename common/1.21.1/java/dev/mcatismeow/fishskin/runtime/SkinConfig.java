package dev.mcatismeow.fishskin.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature switches. Mods made in the launcher get them baked into the jar (fishskin.config.json). A jar baked with
 * {@code "userConfig": true} (the standalone mod) instead reads config/&lt;mod id&gt;.json, which players and server
 * owners edit; in multiplayer the server's values for the [server] keys replace the client's (see {@link #applyRemote}).
 */
public final class SkinConfig {
    public static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ZONE = 256;
    /** The parts of the settings the server decides; sent to clients when they join. */
    private static final String[] SERVER_KEYS = {"key", "zone", "block", "maxSkins", "maxSkinSize"};

    private static JsonObject baked = new JsonObject();
    private static volatile JsonObject root;
    /** the server's settings while connected to one that sent them, else null */
    private static volatile JsonObject remote;

    static {
        try (var in = SkinConfig.class.getResourceAsStream("/fishskin.config.json")) {
            if (in != null) {
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    baked = new Gson().fromJson(r, JsonObject.class);
                }
            }
        } catch (Exception e) {
            SkinMod.LOGGER.warn("Could not read fishskin.config.json, using defaults", e);
        }
        if (baked == null) baked = new JsonObject();
    }

    private SkinConfig() {}

    /** The standalone mod: settings come from the config file, and every block and the key always exist. */
    public static boolean userConfig() {
        return baked.has("userConfig") && baked.get("userConfig").getAsBoolean();
    }

    private static JsonObject root() {
        JsonObject r = root;
        if (r != null) return r;
        if (!userConfig()) return root = baked;
        if (SkinMod.platform == null) return fromFile(new JsonObject()); // too early to know the config folder; ask again later
        return root = fromFile(readFile());
    }

    // ---- the config file (standalone mod) -----------------------------------------------------------------------

    private static Path file() {
        return SkinMod.platform.configDir().resolve(SkinMod.ID + ".json");
    }

    private static JsonObject readFile() {
        Path f = file();
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, DEFAULT_FILE, StandardCharsets.UTF_8);
                return new JsonObject();
            }
            try (JsonReader r = new JsonReader(Files.newBufferedReader(f, StandardCharsets.UTF_8))) {
                JsonElement e = JsonParser.parseReader(r); // lenient: // comments are fine
                if (e.isJsonObject()) return e.getAsJsonObject();
            }
            SkinMod.LOGGER.warn("{} is not a JSON object, using defaults", f);
        } catch (Exception e) {
            SkinMod.LOGGER.warn("Could not read {}, using defaults (fix or delete the file)", f, e);
        }
        return new JsonObject();
    }

    /** Turns the file's friendly keys into the shape the getters read (the same shape the launcher bakes). */
    private static JsonObject fromFile(JsonObject f) {
        JsonObject zones = sub(f, "zones"), blocks = sub(f, "blocks"), figura = sub(f, "figura"), voice = sub(f, "plasmoVoice");
        JsonObject out = new JsonObject();

        JsonObject key = new JsonObject();
        key.addProperty("enabled", true);
        key.addProperty("anywhere", bool(f, "openKeyAnywhere", true));
        out.add("key", key);

        JsonObject zone = new JsonObject();
        zone.addProperty("enabled", bool(zones, "enabled", true));
        zone.addProperty("mode", bool(zones, "openOnEnter", false) ? "instant" : "hint");
        zone.addProperty("message", str(zones, "message", "Press {{button}} to change your skin"));
        zone.addProperty("width", num(zones, "defaultWidth", 8));
        zone.addProperty("length", num(zones, "defaultLength", 8));
        zone.addProperty("height", num(zones, "defaultHeight", 4));
        out.add("zone", zone);

        JsonObject block = new JsonObject();
        block.addProperty("enabled", bool(blocks, "enabled", true));
        block.addProperty("builtin", bool(blocks, "skinStation", true));
        block.addProperty("message", str(blocks, "message", "Right-click to change your skin"));
        JsonArray entries = new JsonArray();
        JsonElement list = blocks.get("list");
        if (list == null) {
            list = new JsonArray();
            list.getAsJsonArray().add("minecraft:loom");
        }
        if (list.isJsonArray()) {
            for (JsonElement e : list.getAsJsonArray()) {
                if (!e.isJsonPrimitive() || e.getAsString().isBlank()) continue;
                JsonObject o = new JsonObject();
                o.addProperty("vanilla", e.getAsString().trim());
                entries.add(o);
            }
        }
        block.add("entries", entries);
        out.add("block", block);

        out.addProperty("maxSkins", num(f, "maxOutfits", 10));
        out.addProperty("maxSkinSize", num(f, "maxSkinSize", 2048));

        JsonObject fig = new JsonObject();
        fig.addProperty("enabled", bool(figura, "enabled", true));
        out.add("figura", fig);

        JsonObject pv = new JsonObject();
        pv.addProperty("enabled", bool(voice, "enabled", true));
        pv.addProperty("minLevelDb", num(voice, "minLevelDb", -40));
        pv.addProperty("holdMs", num(voice, "holdMs", 200));
        out.add("plasmo", pv);
        return out;
    }

    private static JsonObject sub(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        try { return o.has(k) ? o.get(k).getAsBoolean() : def; } catch (Exception e) { return def; }
    }

    private static int num(JsonObject o, String k, int def) {
        try { return o.has(k) ? o.get(k).getAsInt() : def; } catch (Exception e) { return def; }
    }

    private static String str(JsonObject o, String k, String def) {
        try { return o.has(k) ? o.get(k).getAsString() : def; } catch (Exception e) { return def; }
    }

    // ---- server -> client ---------------------------------------------------------------------------------------

    /** The [server] settings as JSON, for clients that join (standalone mod only; launcher mods bake the same values). */
    public static String serverSettingsJson() {
        if (!userConfig()) return null;
        JsonObject r = root(), out = new JsonObject();
        for (String k : SERVER_KEYS) if (r.has(k)) out.add(k, r.get(k));
        return out.toString();
    }

    /** Use the server's settings for the [server] keys until {@link #clearRemote()}. */
    public static void applyRemote(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            if (e.isJsonObject()) remote = e.getAsJsonObject();
        } catch (Exception e) {
            SkinMod.LOGGER.warn("Ignoring bad settings from the server", e);
        }
    }

    public static void clearRemote() { remote = null; }

    // ---- getters ------------------------------------------------------------------------------------------------

    /** -1 means unlimited. */
    public static int maxSkins() {
        JsonElement e = value("maxSkins");
        return e != null ? e.getAsInt() : 10;
    }

    /** Largest skin side the server accepts (64..2048). */
    public static int maxSkinSize() {
        JsonElement e = value("maxSkinSize");
        return e != null ? Math.max(64, Math.min(2048, e.getAsInt())) : 2048;
    }

    public static boolean keyEnabled() {
        JsonObject k = obj("key");
        return k == null || !k.has("enabled") || k.get("enabled").getAsBoolean();
    }

    public static boolean keyAnywhere() {
        JsonObject k = obj("key");
        return k == null || !k.has("anywhere") || k.get("anywhere").getAsBoolean();
    }

    /** GLFW key name like "K" or "F7"; falls back to K. */
    public static String defaultKey() {
        JsonObject k = obj("key");
        return k != null && k.has("default") ? k.get("default").getAsString() : "K";
    }

    /** A block that opens the wardrobe: a game block (vanilla = its id) or a new block of ours (id). */
    public record BlockEntry(String id, String vanilla, String name) {}

    /** The built-in Skin Station block exists when the skin block is on and no Block node is connected. */
    public static boolean builtinStation() {
        JsonObject b = obj("block");
        return blockEnabled() && (b == null || !b.has("builtin") || b.get("builtin").getAsBoolean());
    }

    public static List<BlockEntry> blocks() {
        List<BlockEntry> out = new ArrayList<>();
        JsonObject b = obj("block");
        if (b == null || !b.has("entries") || !b.get("entries").isJsonArray()) return out;
        JsonArray arr = b.getAsJsonArray("entries");
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String id = o.has("id") ? o.get("id").getAsString() : "";
            String vanilla = o.has("vanilla") ? o.get("vanilla").getAsString() : "";
            if (id.isEmpty() && vanilla.isEmpty()) continue;
            out.add(new BlockEntry(id, vanilla, o.has("name") ? o.get("name").getAsString() : id));
        }
        return out;
    }

    public static List<String> items() {
        List<String> out = new ArrayList<>();
        JsonObject b = obj("block");
        if (b == null || !b.has("items") || !b.get("items").isJsonArray()) return out;
        for (JsonElement e : b.getAsJsonArray("items")) {
            if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out;
    }

    public static String blockMessage() {
        JsonObject b = obj("block");
        return b != null && b.has("message") && !b.get("message").getAsString().isBlank() ? b.get("message").getAsString() : "Right-click to change your skin";
    }

    public static boolean blockEnabled() {
        JsonObject b = obj("block");
        return b != null && b.has("enabled") && b.get("enabled").getAsBoolean();
    }

    public static String blockName() {
        JsonObject b = obj("block");
        return b != null && b.has("name") ? b.get("name").getAsString() : "Skin Station";
    }

    public static boolean figuraEnabled() {
        JsonObject f = obj("figura");
        return f != null && f.has("enabled") && f.get("enabled").getAsBoolean();
    }

    public static boolean plasmoEnabled() {
        JsonObject p = obj("plasmo");
        return p != null && p.has("enabled") && p.get("enabled").getAsBoolean();
    }

    /** dB the mic must reach for the talking skin (-127 silent … 0 loudest). */
    public static double plasmoMinLevelDb() {
        JsonObject p = obj("plasmo");
        return p != null && p.has("minLevelDb") ? Math.max(-127, Math.min(0, p.get("minLevelDb").getAsDouble())) : -40;
    }

    /** How long the mouth stays open after the voice drops, in milliseconds. */
    public static long plasmoHoldMs() {
        JsonObject p = obj("plasmo");
        return p != null && p.has("holdMs") ? Math.max(0, Math.min(2000, p.get("holdMs").getAsLong())) : 200;
    }

    public static boolean zoneEnabled() {
        JsonObject z = obj("zone");
        return z != null && z.has("enabled") && z.get("enabled").getAsBoolean();
    }

    /** "hint" (message on the hotbar, key opens the window) or "instant" (window opens on entering). */
    public static String zoneMode() {
        JsonObject z = obj("zone");
        return z != null && z.has("mode") ? z.get("mode").getAsString() : "hint";
    }

    public static String zoneMessage() {
        JsonObject z = obj("zone");
        return z != null && z.has("message") ? z.get("message").getAsString() : "Press {{button}} to change your skin";
    }

    public static int zoneDefault(String axis) {
        JsonObject z = obj("zone");
        int fallback = axis.equals("height") ? 4 : 8;
        return z != null && z.has(axis) ? Math.max(1, Math.min(MAX_ZONE, z.get(axis).getAsInt())) : fallback;
    }

    /** A [server] key from the server while connected to one, else from our own settings. */
    private static JsonElement value(String name) {
        JsonObject rem = remote;
        if (rem != null && rem.has(name)) return rem.get(name);
        JsonObject r = root();
        return r.has(name) ? r.get(name) : null;
    }

    private static JsonObject obj(String name) {
        JsonElement e = value(name);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** Written to config/&lt;mod id&gt;.json the first time the standalone mod starts. */
    private static final String DEFAULT_FILE = """
            // FishSkin settings. Restart the game (or the server) after editing.
            //
            // [server] = on a server, the SERVER's value is used for every player (a player's own file is ignored there).
            //            In singleplayer your own file is used.
            // [client] = always your own file.
            //
            // This file uses JSON with // comments. If it breaks, delete it and a fresh one is written.
            {
              // [server] true: the open key (K by default, change it in Options > Controls) opens the wardrobe anywhere.
              //          false: the key only works inside a skin zone or while looking at a skin block.
              "openKeyAnywhere": true,

              "zones": {
                // [server] Skin zones: an area marked by a Skin Zone block (creative menu, operator blocks tab).
                //          Right-click the Skin Zone block in creative to set its size.
                "enabled": true,
                // [server] Needs "enabled": true. true: the wardrobe opens by itself when a player walks into a zone.
                //          false: a message shows instead and the player presses the open key.
                "openOnEnter": false,
                // [server] The message shown inside a zone. {{button}} becomes the player's open key.
                "message": "Press {{button}} to change your skin",
                // [server] Size of a newly placed Skin Zone block, in blocks (1..256).
                "defaultWidth": 8,
                "defaultLength": 8,
                "defaultHeight": 4
              },

              "blocks": {
                // [server] true: right-clicking a skin block opens the wardrobe. Sneak + right-click still uses the block normally.
                "enabled": true,
                // [server] The mod's own Skin Station block (creative menu, functional blocks tab) opens the wardrobe.
                "skinStation": true,
                // [server] Game or modded blocks that open the wardrobe, by id, e.g. "minecraft:loom", "minecraft:barrel".
                //          "loom" alone means "minecraft:loom". Their normal screen will not open while this is on.
                "list": ["minecraft:loom"],
                // [server] The message shown while looking at a skin block.
                "message": "Right-click to change your skin"
              },

              // [server] How many outfits each player can keep in the wardrobe. -1 = unlimited.
              "maxOutfits": 10,
              // [server] Largest skin the server accepts: 64, 128, 256, 512, 1024 or 2048 (pixels, square PNG).
              //          Bigger skins look sharper but use more video memory on every player's PC.
              "maxSkinSize": 2048,

              "figura": {
                // [client] Lets each outfit carry a Figura avatar (needs the Figura mod).
                "enabled": true
              },

              "plasmoVoice": {
                // [client] Talking skins: a second skin shown while you speak (needs the Plasmo Voice mod).
                "enabled": true,
                // [client] How loud your voice must be for the talking skin, in dB: -127 = silence, 0 = loudest.
                //          Normal speech is about -30 to -15. Lower it (e.g. -50) if the mouth opens too rarely.
                "minLevelDb": -40,
                // [client] How long the mouth stays open after you stop making sound, in milliseconds.
                "holdMs": 200
              }
            }
            """;
}
