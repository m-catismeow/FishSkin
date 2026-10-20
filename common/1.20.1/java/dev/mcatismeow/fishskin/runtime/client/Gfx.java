package dev.mcatismeow.fishskin.runtime.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** GUI drawing calls whose signature differs between Minecraft versions. */
final class Gfx {
    private Gfx() {}

    /** Draws a region (u, v, rw x rh) of a texture (tw x th) scaled into a w x h box. */
    static void blit(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h, float u, float v, int rw, int rh, int tw, int th) {
        g.blit(tex, x, y, w, h, u, v, rw, rh, tw, th);
    }

    /** {@link #blit} multiplied by an RGB colour (alpha stays as in the texture). */
    static void blitTinted(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h, float u, float v, int rw, int rh, int tw, int th, int rgb) {
        g.setColor(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1f);
        g.blit(tex, x, y, w, h, u, v, rw, rh, tw, th);
        g.setColor(1f, 1f, 1f, 1f);
    }
}
