package dev.mcatismeow.fishskin.runtime.server;

import dev.mcatismeow.fishskin.runtime.PngInfo;
import dev.mcatismeow.fishskin.runtime.SkinConfig;
import dev.mcatismeow.fishskin.runtime.SkinMod;
import dev.mcatismeow.fishskin.runtime.net.Messages;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Server-side relay. Works on dedicated servers and the integrated server. Loaders call the on* hooks.
 * Skins live in memory only, for the players online right now: nothing is written to the world folder, and a
 * player's skin is dropped when they leave (their client sends it again when they join).
 */
public final class ServerSkins {
    private static final long MIN_UPLOAD_GAP_MS = 3000;
    private static final long MIN_SPEAK_GAP_MS = 150;

    private record Entry(String hash, boolean slim, String openHash) {
        String open() { return openHash == null ? "" : openHash; }
    }

    private static final class Assembly {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int next = 0;
        boolean slim;
        int kind;
    }

    private static final Map<UUID, Entry> players = new ConcurrentHashMap<>();
    private static final Map<UUID, Assembly> uploads = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastUpload = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastSpeak = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> speaking = new ConcurrentHashMap<>();
    /** PNG bytes by hash, only for skins an online player wears */
    private static final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static MinecraftServer server;

    private ServerSkins() {}

    // ---- loader hooks ---------------------------------------------------------------------------

    public static void onServerStarted(MinecraftServer s) {
        server = s;
        players.clear();
        files.clear();
        removeOldStore(s.getWorldPath(LevelResource.ROOT).resolve("fishskin"));
    }

    public static void onServerStopping() {
        server = null;
        players.clear();
        uploads.clear();
        files.clear();
    }

    public static void onJoin(ServerPlayer joiner) {
        if (server == null || !SkinMod.platform.net().canSendToPlayer(joiner, Messages.Announce.class)) return;
        String settings = SkinConfig.serverSettingsJson();
        if (settings != null && SkinMod.platform.net().canSendToPlayer(joiner, Messages.Settings.class)) {
            SkinMod.platform.net().toPlayer(joiner, new Messages.Settings(settings));
        }
        // tell the joiner about everybody online who has a custom skin; the joiner's own skin arrives as an upload
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Entry e = players.get(p.getUUID());
            if (e != null) SkinMod.platform.net().toPlayer(joiner, new Messages.Announce(p.getUUID(), e.hash(), e.slim(), e.open()));
        }
    }

    public static void onDisconnect(ServerPlayer player) {
        UUID id = player.getUUID();
        uploads.remove(id);
        lastUpload.remove(id);
        lastSpeak.remove(id);
        if (speaking.remove(id) != null) broadcastSpeak(id, false);
        if (players.remove(id) != null) prune();
    }

    // ---- packet handlers ------------------------------------------------------------------------

    public static void onReset(Messages.Reset msg, ServerPlayer player) {
        UUID id = player.getUUID();
        uploads.remove(id);
        if (players.remove(id) == null) return;
        prune();
        broadcast(id, new Entry("", false, ""));
    }

    public static void onSpeak(Messages.Speak msg, ServerPlayer player) {
        UUID id = player.getUUID();
        long t = System.currentTimeMillis();
        Long last = lastSpeak.get(id);
        if (last != null && t - last < MIN_SPEAK_GAP_MS) return; // flapping protection
        lastSpeak.put(id, t);
        Boolean was = speaking.put(id, msg.speaking());
        if (was == null ? !msg.speaking() : was == msg.speaking()) return;
        broadcastSpeak(id, msg.speaking());
    }

    public static void onUpload(Messages.Upload up, ServerPlayer player) {
        UUID id = player.getUUID();
        if (server == null || up.total() <= 0 || up.total() > SkinConfig.MAX_UPLOAD_BYTES / 1000) return;
        if (up.kind() != 0 && up.kind() != 1) return;
        if (up.index() == 0) {
            if (up.kind() == 0) {
                long now = System.currentTimeMillis();
                Long last = lastUpload.get(id);
                if (last != null && now - last < MIN_UPLOAD_GAP_MS) { uploads.remove(id); return; }
                lastUpload.put(id, now);
            } else if (!players.containsKey(id)) {
                return; // a talking skin needs a main skin first
            }
            Assembly fresh = new Assembly();
            fresh.slim = up.slim();
            fresh.kind = up.kind();
            uploads.put(id, fresh);
        }
        Assembly a = uploads.get(id);
        if (a == null || a.next != up.index() || a.kind != up.kind()) { uploads.remove(id); return; }
        if (a.out.size() + up.data().length > SkinConfig.MAX_UPLOAD_BYTES) { uploads.remove(id); return; }
        a.out.writeBytes(up.data());
        a.next++;
        if (a.next < up.total()) return;

        uploads.remove(id);
        byte[] png = a.out.toByteArray();
        PngInfo info = PngInfo.read(png);
        if (info == null || !info.supported() || info.width > SkinConfig.maxSkinSize()) {
            SkinMod.LOGGER.warn("Rejected skin upload from {}", player.getName().getString());
            return;
        }
        try {
            String hash = sha256(png);
            files.put(hash, png);
            Entry cur = players.get(id);
            if (a.kind == 1 && cur == null) { prune(); return; }
            Entry e = a.kind == 0 ? new Entry(hash, a.slim, "") : new Entry(cur.hash(), cur.slim(), hash);
            players.put(id, e);
            prune();
            broadcast(id, e);
        } catch (Exception ex) {
            SkinMod.LOGGER.warn("Failed to take skin upload", ex);
        }
    }

    public static void onRequest(Messages.Request req, ServerPlayer player) {
        String hash = req.hash();
        if (server == null || !PngInfo.validHash(hash)) return;
        byte[] png = files.get(hash);
        if (png == null) return;
        int total = (png.length + Messages.DOWNLOAD_CHUNK - 1) / Messages.DOWNLOAD_CHUNK;
        for (int i = 0; i < total; i++) {
            int from = i * Messages.DOWNLOAD_CHUNK;
            int to = Math.min(png.length, from + Messages.DOWNLOAD_CHUNK);
            SkinMod.platform.net().toPlayer(player, new Messages.Download(hash, i, total, Arrays.copyOfRange(png, from, to)));
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Forgets PNGs no online player wears any more. */
    private static void prune() {
        files.keySet().removeIf(h -> players.values().stream().noneMatch(e -> h.equals(e.hash()) || h.equals(e.open())));
    }

    /** Older versions kept every skin in world/fishskin; delete those files so they cannot be copied from there. */
    private static void removeOldStore(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                return n.equals("players.json") || (n.endsWith(".png") && PngInfo.validHash(n.substring(0, n.length() - 4)));
            }).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception e) {
            SkinMod.LOGGER.warn("Could not clear the old skin store in {}", dir, e);
        }
        try (Stream<Path> s = Files.list(dir)) {
            if (s.findAny().isEmpty()) Files.deleteIfExists(dir);
        } catch (Exception ignored) {}
    }

    private static void broadcast(UUID who, Entry e) {
        if (server == null) return;
        var msg = new Messages.Announce(who, e.hash(), e.slim(), e.open());
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (SkinMod.platform.net().canSendToPlayer(p, Messages.Announce.class)) SkinMod.platform.net().toPlayer(p, msg);
        }
    }

    private static void broadcastSpeak(UUID who, boolean now) {
        if (server == null) return;
        var msg = new Messages.SpeakState(who, now);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (SkinMod.platform.net().canSendToPlayer(p, Messages.SpeakState.class)) SkinMod.platform.net().toPlayer(p, msg);
        }
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        char[] out = new char[d.length * 2];
        for (int i = 0; i < d.length; i++) {
            int v = d[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
