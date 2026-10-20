package dev.mcatismeow.fishskin.runtime.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mcatismeow.fishskin.runtime.SkinConfig;
import dev.mcatismeow.fishskin.runtime.block.SkinBlocks;
import dev.mcatismeow.fishskin.runtime.block.ZoneBlockEntity;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

/** Client-side setup shared by every loader. The loader registers {@link #OPEN_KEY} and calls the hooks below. */
public final class ClientInit {
    /** null when the mod has neither the key nor skin zones */
    public static KeyMapping OPEN_KEY;

    private ClientInit() {}

    /** Call once on the client, before key mappings are registered. */
    public static void init() {
        ClientSkins.removeOldCache();
        SkinBlocks.openWardrobe = () -> Minecraft.getInstance().setScreen(new SkinScreen());
        SkinBlocks.openZoneEditor = be -> Minecraft.getInstance().setScreen(new ZoneScreen(be));
        // The key opens the wardrobe everywhere, unless the mod has skin zones or a skin block:
        // then the key is only registered if allowed or needed for the zone hint.
        boolean needKey = SkinConfig.keyEnabled()
                || (SkinConfig.zoneEnabled() && "hint".equals(SkinConfig.zoneMode()));
        if (needKey) {
            OPEN_KEY = new KeyMapping("key.fishskin.open", InputConstants.Type.KEYSYM, glfwKey(SkinConfig.defaultKey()), "key.categories.fishskin");
        }
        if (SkinConfig.plasmoEnabled()) PlasmoBridge.init();
    }

    /** Loader hook: every client tick. */
    public static void tick(Minecraft mc) {
        if (OPEN_KEY != null) {
            while (OPEN_KEY.consumeClick()) {
                boolean inZone = SkinConfig.zoneEnabled() && ZoneClient.isInside();
                boolean nearBlock = SkinConfig.blockEnabled() && mc.level != null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && SkinBlocks.isSkinBlock(mc.level.getBlockState(hit.getBlockPos()));
                boolean allowed = SkinConfig.keyEnabled() && (SkinConfig.keyAnywhere() || inZone || nearBlock);
                if (!allowed && SkinConfig.zoneEnabled() && inZone && "hint".equals(SkinConfig.zoneMode())) {
                    allowed = true;
                }
                if (allowed && mc.screen == null) mc.setScreen(new SkinScreen());
            }
        }
        ZoneClient.tick(mc);
        Hint.update(mc, mc.player == null || mc.screen != null ? null : currentHint(mc));
        if (SkinConfig.plasmoEnabled()) PlasmoBridge.tick();
        if (SkinConfig.figuraEnabled()) FiguraBridge.tick();
    }

    private static Component cachedBlockHint;

    /** The server sent its settings, or we left it: drop what was worked out from the old ones. */
    static void settingsChanged() {
        cachedBlockHint = null;
    }

    /** The message to show above the hotbar right now: the zone hint, or the skin block hint, or none. */
    private static Component currentHint(Minecraft mc) {
        Component zone = ZoneClient.hint(OPEN_KEY);
        if (zone != null) return zone;
        if (SkinConfig.blockEnabled() && mc.level != null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && SkinBlocks.isSkinBlock(mc.level.getBlockState(hit.getBlockPos()))) {
            if (cachedBlockHint == null) cachedBlockHint = Component.literal(SkinConfig.blockMessage());
            return cachedBlockHint;
        }
        return null;
    }

    /** Loader hook: joined a world or server (re-sends the worn outfit). */
    public static void onJoin(Minecraft mc) {
        mc.execute(Wardrobe::applySelected);
    }

    /** Loader hook: left a world or server. */
    public static void onDisconnect() {
        SkinConfig.clearRemote();
        SkinBlocks.reloadMatches();
        settingsChanged();
        ClientSkins.reset();
        ZoneClient.reset();
        ZoneBlockEntity.clearLoaded();
        Hint.reset();
    }

    /** "K" -> GLFW_KEY_K, "F7" -> GLFW_KEY_F7; unknown names fall back to K. */
    private static int glfwKey(String name) {
        String n = name.trim().toUpperCase();
        try {
            if (n.length() == 1 && Character.isLetterOrDigit(n.charAt(0))) return n.charAt(0);
            return GLFW.class.getField("GLFW_KEY_" + n).getInt(null);
        } catch (ReflectiveOperationException e) {
            return GLFW.GLFW_KEY_K;
        }
    }
}
