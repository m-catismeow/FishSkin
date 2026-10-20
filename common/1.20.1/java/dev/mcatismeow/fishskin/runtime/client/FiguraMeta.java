package dev.mcatismeow.fishskin.runtime.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What avatar.json (or a .moon's metadata) says about an avatar in figura/avatars: its name, authors, and the
 * colour Figura gives its mark in the player list. Read straight from the files, so it works without Figura.
 */
final class FiguraMeta {
    /** Figura's ColorUtils.Colors.DEFAULT, used when an avatar sets no colour. */
    static final int DEFAULT_COLOR = 0x5AAAFF;
    private static final Map<String, Integer> FIGURA_NAMES = Map.ofEntries(
            Map.entry("awesome_blue", 0x5EA5FF), Map.entry("purple", 0xA672EF), Map.entry("figura_blue", 0x00F0FF),
            Map.entry("soft_blue", 0x99BBEE), Map.entry("figura_red", 0xFF2400), Map.entry("orange", 0xFFC400),
            Map.entry("cheese", 0xF8C53A), Map.entry("lua_log", 0x5555FF), Map.entry("lua_error", 0xFF5555),
            Map.entry("lua_ping", 0xA155DA), Map.entry("default", DEFAULT_COLOR), Map.entry("discord", 0x5865F2),
            Map.entry("kofi", 0x27AAE0), Map.entry("github", 0xFFFFFF), Map.entry("modrinth", 0x1BD96A),
            Map.entry("curseforge", 0xF16436));
    private static final Map<String, Info> cache = new ConcurrentHashMap<>();

    /** {@code title} falls back to the file name; {@code authors} may be empty. */
    record Info(String file, String title, String authors, int color) {
        boolean matches(String query) {
            if (query.isEmpty()) return true;
            String q = query.toLowerCase(Locale.ROOT);
            return file.toLowerCase(Locale.ROOT).contains(q) || title.toLowerCase(Locale.ROOT).contains(q)
                    || authors.toLowerCase(Locale.ROOT).contains(q);
        }
    }

    private FiguraMeta() {}

    /** Info of the named avatar in figura/avatars; cached until {@link #clear()}. */
    static Info of(String file) {
        if (file == null || file.isEmpty()) return new Info("", "", "", DEFAULT_COLOR);
        return cache.computeIfAbsent(file, FiguraMeta::read);
    }

    static int color(String file) { return of(file).color(); }

    static void clear() { cache.clear(); }

    private static Info read(String file) {
        String name = null, authors = null, color = null;
        try {
            JsonObject json = null;
            CompoundTag moon = null;
            Path dir = FiguraBridge.avatarsDir().normalize();
            Path p = dir.resolve(file).normalize();
            if (p.startsWith(dir) && Files.exists(p)) {
                String lower = file.toLowerCase(Locale.ROOT);
                if (Files.isDirectory(p)) {
                    json = jsonFile(p.resolve("avatar.json"));
                } else if (lower.endsWith(".moon")) {
                    try (InputStream in = Files.newInputStream(p)) { moon = NbtIo.readCompressed(in).getCompound("metadata"); }
                } else {
                    // an archive: prefer the copy FiguraZip already extracted, else peek inside a .zip
                    Path extracted = dir.resolve(".cache").resolve(file);
                    if (Files.isDirectory(extracted)) {
                        try (Stream<Path> s = Files.walk(extracted, 4)) {
                            Path j = s.filter(f -> f.getFileName().toString().equals("avatar.json")).findFirst().orElse(null);
                            if (j != null) json = jsonFile(j);
                        }
                    }
                    if (json == null && lower.endsWith(".zip")) json = jsonInZip(p);
                }
            }
            if (json != null) {
                name = string(json.get("name"));
                color = string(json.get("color"));
                JsonElement a = json.has("authors") ? json.get("authors") : json.get("author");
                if (a != null && a.isJsonArray()) {
                    List<String> l = new ArrayList<>();
                    for (JsonElement e : (JsonArray) a) {
                        String s = string(e);
                        if (s != null && !s.isBlank()) l.add(s);
                    }
                    authors = String.join(", ", l);
                } else {
                    authors = string(a);
                }
            } else if (moon != null) {
                name = moon.getString("name");
                authors = moon.getString("authors");
                color = moon.getString("color");
            }
        } catch (Exception ignored) {}
        String title = name == null || name.isBlank() ? file : ChatFormatting.stripFormatting(name);
        String by = authors == null ? "" : ChatFormatting.stripFormatting(authors);
        return new Info(file, title, by, parseColor(color));
    }

    private static String string(JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static JsonObject jsonFile(Path json) throws Exception {
        if (!Files.isRegularFile(json) || Files.size(json) > 1_000_000) return null;
        try (Reader r = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
            JsonElement e = JsonParser.parseReader(r);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        }
    }

    private static JsonObject jsonInZip(Path zipPath) throws Exception {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry best = null; // the shallowest avatar.json is the avatar's root
            for (var it = zip.entries(); it.hasMoreElements(); ) {
                ZipEntry e = it.nextElement();
                String en = e.getName().replace('\\', '/');
                if (!e.isDirectory() && (en.equals("avatar.json") || en.endsWith("/avatar.json"))
                        && (best == null || en.length() < best.getName().length())) best = e;
            }
            if (best == null || best.getSize() > 1_000_000) return null;
            try (Reader r = new InputStreamReader(zip.getInputStream(best), StandardCharsets.UTF_8)) {
                JsonElement e = JsonParser.parseReader(r);
                return e.isJsonObject() ? e.getAsJsonObject() : null;
            }
        }
    }

    /** Same order as Figura's ColorUtils.userInputHex: Minecraft colour name, Figura colour name, then hex. */
    static int parseColor(String s) {
        if (s == null || s.isBlank()) return DEFAULT_COLOR;
        String t = s.trim().toLowerCase(Locale.ROOT);
        ChatFormatting f = ChatFormatting.getByName(t);
        if (f != null && f.getColor() != null) return f.getColor();
        Integer named = FIGURA_NAMES.get(t);
        if (named != null) return named;
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() == 3) t = "" + t.charAt(0) + t.charAt(0) + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2);
        t = (t + "000000").substring(0, 6);
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return DEFAULT_COLOR;
        }
    }
}
