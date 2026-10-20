package dev.mcatismeow.fishskin.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature switches. Mods made in the launcher get them baked into the jar (fishskin.config.json). A jar baked with
 * {@code "userConfig": true} (the standalone mod) instead reads config/&lt;mod id&gt;.toml, which players and server
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
        if (SkinMod.platform == null) return new JsonObject(); // too early to know the config folder or the mods; ask again later
        return root = fromFile(readFile());
    }

    // ---- the config file (standalone mod) -----------------------------------------------------------------------

    private static Path file() {
        return SkinMod.platform.configDir().resolve(SkinMod.ID + ".toml");
    }

    private static JsonObject readFile() {
        Path f = file();
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, DEFAULT_FILE, StandardCharsets.UTF_8);
                return new JsonObject();
            }
            return parseToml(Files.readString(f, StandardCharsets.UTF_8), f);
        } catch (Exception e) {
            SkinMod.LOGGER.warn("Could not read {}, using defaults (fix or delete the file)", f, e);
        }
        return new JsonObject();
    }

    /**
     * The small part of TOML this file uses: # comments, [section] headers, and key = value with true/false,
     * whole numbers, "strings" and ["string", ...] arrays (an array may span lines). Bad lines are skipped.
     */
    static JsonObject parseToml(String text, Path from) {
        JsonObject root = new JsonObject(), table = root;
        String[] lines = text.replace("\r\n", "\n").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = stripComment(lines[i]).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]") && !line.contains("=")) {
                table = new JsonObject();
                root.add(line.substring(1, line.length() - 1).trim(), table);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) { badLine(from, i, lines[i]); continue; }
            String key = unquote(line.substring(0, eq).trim());
            String value = line.substring(eq + 1).trim();
            // an array may go on over the next lines until its ]
            while (value.startsWith("[") && !closed(value) && i + 1 < lines.length) {
                value += " " + stripComment(lines[++i]).trim();
            }
            JsonElement e = tomlValue(value);
            if (key == null || e == null) badLine(from, i, lines[i]);
            else table.add(key, e);
        }
        return root;
    }

    private static JsonElement tomlValue(String v) {
        if (v.equals("true") || v.equals("false")) return new JsonPrimitive(Boolean.parseBoolean(v));
        if (v.startsWith("\"")) {
            String s = unquote(v);
            return s == null ? null : new JsonPrimitive(s);
        }
        if (v.startsWith("[") && closed(v)) {
            JsonArray arr = new JsonArray();
            String inner = v.substring(1, v.lastIndexOf(']')).trim();
            StringBuilder cur = new StringBuilder();
            boolean inStr = false;
            for (int k = 0; k < inner.length(); k++) {
                char c = inner.charAt(k);
                if (c == '"' && (k == 0 || inner.charAt(k - 1) != '\\')) inStr = !inStr;
                if (c == ',' && !inStr) {
                    addItem(arr, cur);
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            addItem(arr, cur);
            return arr;
        }
        try {
            return new JsonPrimitive(Long.parseLong(v.replace("_", "")));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void addItem(JsonArray arr, StringBuilder raw) {
        String s = raw.toString().trim();
        if (s.isEmpty()) return;
        JsonElement e = tomlValue(s);
        if (e != null) arr.add(e);
    }

    private static boolean closed(String v) {
        boolean inStr = false;
        int depth = 0;
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            if (c == '"' && (k == 0 || v.charAt(k - 1) != '\\')) inStr = !inStr;
            else if (!inStr && c == '[') depth++;
            else if (!inStr && c == ']' && --depth == 0) return true;
        }
        return false;
    }

    /** Drops a # comment, but not a # inside a "string". */
    private static String stripComment(String line) {
        boolean inStr = false;
        for (int k = 0; k < line.length(); k++) {
            char c = line.charAt(k);
            if (c == '"' && (k == 0 || line.charAt(k - 1) != '\\')) inStr = !inStr;
            else if (c == '#' && !inStr) return line.substring(0, k);
        }
        return line;
    }

    /** A "quoted" key or value without its quotes (\" \\ \n \t understood); a bare key as it is; broken → null. */
    private static String unquote(String s) {
        if (!s.startsWith("\"")) return s;
        if (s.length() < 2 || !s.endsWith("\"")) return null;
        String body = s.substring(1, s.length() - 1);
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < body.length(); k++) {
            char c = body.charAt(k);
            if (c == '\\' && k + 1 < body.length()) {
                char n = body.charAt(++k);
                out.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void badLine(Path from, int index, String line) {
        SkinMod.LOGGER.warn("{} line {}: cannot read \"{}\", skipped", from, index + 1, line.trim());
    }

    /** Turns the file's friendly keys into the shape the getters read (the same shape the launcher bakes). */
    private static JsonObject fromFile(JsonObject f) {
        JsonObject zones = sub(f, "zones"), blocks = sub(f, "blocks"), voice = sub(f, "plasmoVoice");
        JsonObject out = new JsonObject();

        JsonObject key = new JsonObject();
        key.addProperty("enabled", true);
        key.addProperty("anywhere", bool(f, "openKeyAnywhere", true));
        out.add("key", key);

        JsonObject zone = new JsonObject();
        zone.addProperty("enabled", bool(zones, "enabled", false));
        zone.addProperty("mode", bool(zones, "openOnEnter", false) ? "instant" : "hint");
        zone.addProperty("message", str(zones, "message", "Press {{button}} to change your skin"));
        zone.addProperty("width", num(zones, "defaultWidth", 8));
        zone.addProperty("length", num(zones, "defaultLength", 8));
        zone.addProperty("height", num(zones, "defaultHeight", 4));
        out.add("zone", zone);

        JsonObject block = new JsonObject();
        block.addProperty("enabled", bool(blocks, "enabled", false));
        block.addProperty("builtin", true); // the Skin Station follows blocks.enabled
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

        out.addProperty("maxSkins", num(f, "maxOutfits", -1));
        out.addProperty("maxSkinSize", num(f, "maxSkinSize", 2048));

        // Figura and Plasmo Voice are on whenever the mod is installed; without it their buttons do not show
        JsonObject fig = new JsonObject();
        fig.addProperty("enabled", SkinMod.platform.isModLoaded("figura"));
        out.add("figura", fig);

        JsonObject pv = new JsonObject();
        pv.addProperty("enabled", SkinMod.platform.isModLoaded("plasmovoice"));
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

    /** Written to config/&lt;mod id&gt;.toml the first time the standalone mod starts. */
    private static final String DEFAULT_FILE = """
            # FishSkin settings. Restart the game (or the server) after editing.
            #
            # [server] = on a server, the SERVER's value is used for every player (a player's own file is ignored there).
            #            In singleplayer your own file is used.
            # [client] = always your own file.
            #
            # If this file breaks, delete it and a fresh one is written.

            # [server] true: the open key (K by default, change it in Options > Controls) opens the wardrobe anywhere.
            #          false: the key only works inside a skin zone or while looking at a skin block.
            openKeyAnywhere = true

            # [server] How many outfits each player can keep in the wardrobe. -1 = unlimited.
            maxOutfits = -1

            # [server] Largest skin the server accepts: 64, 128, 256, 512, 1024 or 2048 (pixels, square PNG).
            #          Bigger skins look sharper but use more video memory on every player's PC.
            maxSkinSize = 2048

            [zones]
            # [server] Skin zones: an area marked by a Skin Zone block (creative menu, operator blocks tab).
            #          Right-click the Skin Zone block in creative to set its size.
            enabled = false
            # [server] Needs enabled = true. true: the wardrobe opens by itself when a player walks into a zone.
            #          false: a message shows instead and the player presses the open key.
            openOnEnter = false
            # [server] The message shown inside a zone. {{button}} becomes the player's open key.
            message = "Press {{button}} to change your skin"
            # [server] Size of a newly placed Skin Zone block, in blocks (1..256).
            defaultWidth = 8
            defaultLength = 8
            defaultHeight = 4

            [blocks]
            # [server] true: right-clicking a skin block opens the wardrobe: the mod's own Skin Station block
            #          (creative menu, functional blocks tab) and every block in list below.
            #          Sneak + right-click still uses the block normally.
            enabled = false
            # [server] Game or modded blocks that open the wardrobe, by id, e.g. "minecraft:loom", "minecraft:barrel".
            #          "loom" alone means "minecraft:loom". Their normal screen will not open while this is on.
            list = ["minecraft:loom"]
            # [server] The message shown while looking at a skin block.
            message = "Right-click to change your skin"

            # Figura avatars and talking skins (Plasmo Voice) turn on by themselves when those mods are installed.

            [plasmoVoice]
            # [client] Only used when Plasmo Voice is installed. How loud your voice must be for the talking skin,
            #          in dB: -127 = silence, 0 = loudest. Normal speech is about -30 to -15.
            #          Lower it (e.g. -50) if the mouth opens too rarely.
            minLevelDb = -40
            # [client] How long the mouth stays open after you stop making sound, in milliseconds.
            holdMs = 200
            """;
}
