package dev.mcatismeow.fishskin.runtime.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Pick which Figura avatar goes with an outfit (folders, .moon, .zip and .rar in figura/avatars), as a searchable table (mark, name, authors). */
public class FiguraPicker extends Screen {
    private static final int ROW_H = 20;
    private static final int GREY = 0xFF8C9199;

    private final SkinScreen parent;
    private final Wardrobe.Outfit outfit;
    private final List<String> all = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private String query = "";
    private int scroll = 0;

    FiguraPicker(SkinScreen parent, Wardrobe.Outfit outfit) {
        super(Component.translatable("fishskin.figura_pick"));
        this.parent = parent;
        this.outfit = outfit;
        FiguraMeta.clear();
        all.add(""); // "none"
        all.addAll(FiguraBridge.list());
        filter();
    }

    /** Name, authors or file name contain the search text; "none" only shows while not searching. */
    private void filter() {
        shown.clear();
        for (String n : all) {
            if (n.isEmpty() ? query.isEmpty() : FiguraMeta.of(n).matches(query)) shown.add(n);
        }
        scroll = 0;
    }

    private int x1() { return Math.max(14, width / 2 - 220); }
    private int x2() { return Math.min(width - 14, width / 2 + 220); }
    private int y1() { return 66; }
    private int y2() { return height - 44; }

    @Override
    protected void init() {
        EditBox search = new EditBox(font, x1(), 30, x2() - x1(), 18, Component.translatable("fishskin.figura_search"));
        search.setHint(Component.translatable("fishskin.figura_search").withStyle(s -> s.withColor(GREY)));
        search.setValue(query);
        search.setResponder(s -> { query = s.trim(); filter(); });
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> minecraft.setScreen(parent))
                .bounds(width / 2 - 50, height - 34, 100, 20).build());
        setInitialFocus(search);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
        int x1 = x1(), x2 = x2(), w = x2 - x1;
        int nameX = x1 + 26, authX = x1 + 26 + w * 50 / 100;
        g.drawString(font, Component.translatable("fishskin.figura_col_name"), nameX, y1() - 11, GREY);
        g.drawString(font, Component.translatable("fishskin.figura_col_authors"), authX, y1() - 11, GREY);
        g.fill(x1, y1(), x2, y2(), 0x99101418);
        g.enableScissor(x1, y1(), x2, y2());
        for (int i = 0; i < shown.size(); i++) {
            int y = y1() + 4 + i * ROW_H - scroll;
            if (y + ROW_H < y1() || y > y2()) continue;
            String n = shown.get(i);
            boolean sel = n.equals(outfit.figura);
            boolean hover = mouseX >= x1 && mouseX < x2 && mouseY >= y && mouseY < y + ROW_H - 2 && mouseY >= y1() && mouseY < y2();
            g.fill(x1 + 4, y, x2 - 4, y + ROW_H - 2, sel ? 0xCC3A7BD5 : hover ? 0x33FFFFFF : 0x1AFFFFFF);
            if (n.isEmpty()) {
                g.drawString(font, Component.translatable("fishskin.figura_use_current"), nameX, y + 5, GREY);
                continue;
            }
            FiguraMeta.Info info = FiguraMeta.of(n);
            SkinScreen.drawFiguraIcon(g, x1 + 8, y + 3, 12, n);
            g.drawString(font, font.plainSubstrByWidth(info.title(), authX - nameX - 6), nameX, y + 5, 0xFFFFFFFF);
            g.drawString(font, font.plainSubstrByWidth(info.authors(), x2 - 8 - authX), authX, y + 5, 0xFFC8CCD2);
        }
        g.disableScissor();
        if (all.size() == 1) {
            g.drawCenteredString(font, Component.translatable("fishskin.figura_empty"), width / 2, y1() + 44, GREY);
        } else if (shown.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("fishskin.figura_no_match", query), width / 2, y1() + 44, GREY);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (mx >= x1() && mx < x2() && my >= y1() && my < y2()) {
            int i = (int) ((my - y1() - 4 + scroll) / ROW_H);
            if (i >= 0 && i < shown.size()) { parent.onFiguraPicked(outfit, shown.get(i)); return true; }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        int max = Math.max(0, shown.size() * ROW_H + 8 - (y2() - y1()));
        scroll = (int) Math.max(0, Math.min(max, scroll - v * ROW_H));
        return true;
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}
