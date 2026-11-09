package games.brennan.dungeontrain.client.menu.containercontents;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.editor.ContainerContentsEnchants;
import games.brennan.dungeontrain.net.ContainerContentsSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.enchantment.Enchantment;

import javax.annotation.Nullable;
import java.util.List;

import static games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenuRenderer.*;

/**
 * The C menu's book-enchantment list ({@link ContainerContentsMenu.Screen#BOOK_ENCHANTS}): every
 * enchantment a book row's random roll could pick, each clickable on or off, plus All on / All off.
 * Green is on, grey off, red a curse that is off, amber one the builder has not decided on yet (a mod
 * added it since the list was saved; it never rolls until decided).
 */
final class BookEnchantListPanel {

    /** Rows per column: the list runs to 46 with this modpack, so three columns. */
    static final int ROWS = 16;
    static final double COLUMN_WIDTH = 2.2;
    private static final double BACK_W = 0.6;
    private static final double ALL_W = 0.9;

    /** One list row's state. */
    enum State { ON, OFF, CURSE_OFF, NEW }

    private BookEnchantListPanel() {}

    /** Every enchantment the list shows, read from the client's own registry and tags. */
    static List<Holder<Enchantment>> candidates() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return List.of();
        return mc.level.registryAccess().lookup(Registries.ENCHANTMENT)
            .map(ContainerContentsEnchants::listCandidates)
            .orElse(List.of());
    }

    @Nullable
    static ContainerContentsSyncPacket.Entry row() {
        int i = ContainerContentsMenu.bookRow();
        List<ContainerContentsSyncPacket.Entry> entries = ContainerContentsMenu.entries();
        return i >= 0 && i < entries.size() ? entries.get(i) : null;
    }

    static State stateOf(ContainerContentsSyncPacket.Entry entry, Holder<Enchantment> holder) {
        String id = holder.unwrapKey().map(k -> k.location().toString()).orElse("");
        Boolean on = entry.bookEnchList() == null ? Boolean.valueOf(ContainerContentsEnchants.onByDefault(holder))
            : entry.bookEnchList().get(id);
        if (on == null) return State.NEW;
        if (on) return State.ON;
        return holder.is(EnchantmentTags.CURSE) ? State.CURSE_OFF : State.OFF;
    }

    /** "List: 38 of 42", or "List: 3 new" while some are undecided: the root row's List cell. */
    static String summary(ContainerContentsSyncPacket.Entry entry) {
        int on = 0;
        int undecided = 0;
        List<Holder<Enchantment>> all = candidates();
        for (Holder<Enchantment> holder : all) {
            State s = stateOf(entry, holder);
            if (s == State.ON) on++;
            if (s == State.NEW) undecided++;
        }
        return undecided > 0 ? MenuLang.t("container.book_list_new", undecided)
            : MenuLang.t("container.book_list", on, all.size());
    }

    static boolean hasUndecided(ContainerContentsSyncPacket.Entry entry) {
        for (Holder<Enchantment> holder : candidates()) {
            if (stateOf(entry, holder) == State.NEW) return true;
        }
        return false;
    }

    private static int columns(int n) {
        return Math.max(1, (n + ROWS - 1) / ROWS);
    }

    static PanelSize size() {
        int n = candidates().size();
        double w = Math.max(MIN_PANEL_WIDTH, columns(n) * COLUMN_WIDTH);
        return new PanelSize(w, HEADER_HEIGHT + Math.max(1, Math.min(n, ROWS)) * ROW_HEIGHT);
    }

    static void draw(PoseStack ps, MultiBufferSource buffer, Font font) {
        ContainerContentsSyncPacket.Entry entry = row();
        List<Holder<Enchantment>> all = candidates();
        PanelSize size = size();
        double halfW = size.panelW() / 2.0;
        double halfH = size.panelH() / 2.0;
        ContainerContentsMenu.Hit hovered = ContainerContentsMenu.hovered();
        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, 0xC8000000);

        double top = halfH;
        double bottom = halfH - HEADER_HEIGHT;
        double cy = (top + bottom) / 2.0;
        double backR = -halfW + BACK_W;
        double allOffL = halfW - ALL_W;
        double allOnL = allOffL - ALL_W;
        headerCell(ps, buffer, font, -halfW, backR, bottom, top, MenuLang.t("common.back"),
            hovered.kind() == ContainerContentsMenu.CellKind.BOOK_LIST_BACK, 0x6099CCFF);
        drawQuad(ps, buffer, backR + 0.005, bottom + 0.005, allOnL - 0.005, top - 0.005, 0x4099CCFF);
        drawCenteredText(ps, buffer, font, MenuLang.t("container.book_list_title"), (backR + allOnL) / 2.0, cy, 0xFF99CCFF);
        headerCell(ps, buffer, font, allOnL, allOffL, bottom, top, MenuLang.t("common.all_on"),
            hovered.kind() == ContainerContentsMenu.CellKind.BOOK_LIST_ALL_ON, 0x6033AA55);
        headerCell(ps, buffer, font, allOffL, halfW, bottom, top, MenuLang.t("common.all_off"),
            hovered.kind() == ContainerContentsMenu.CellKind.BOOK_LIST_ALL_OFF, 0x60555555);
        if (entry == null) return;

        double colW = size.panelW() / columns(all.size());
        for (int i = 0; i < all.size(); i++) {
            Holder<Enchantment> holder = all.get(i);
            double colL = -halfW + (i / ROWS) * colW;
            double rowTop = bottom - (i % ROWS) * ROW_HEIGHT;
            boolean hover = hovered.kind() == ContainerContentsMenu.CellKind.BOOK_LIST_ROW && hovered.index() == i;
            int tint = switch (stateOf(entry, holder)) {
                case ON -> hover ? 0xC066DD88 : 0x802E7A44;
                case NEW -> hover ? 0xC0FFCC66 : 0x80B07A10;
                case CURSE_OFF -> hover ? 0xC0DD7777 : 0x50A02828;
                case OFF -> hover ? 0xC0AAAAAA : 0x30FFFFFF;
            };
            drawQuad(ps, buffer, colL + 0.01, rowTop - ROW_HEIGHT + 0.005, colL + colW - 0.01, rowTop - 0.005, tint);
            String name = Enchantment.getFullname(holder, holder.value().getMaxLevel()).getString();
            drawLeftText(ps, buffer, font, name, colL + 0.04, rowTop - ROW_HEIGHT / 2.0, hover ? 0xFF000000 : 0xFFFFFFFF);
        }
    }

    private static void headerCell(PoseStack ps, MultiBufferSource buffer, Font font, double l, double r,
                                   double bottom, double top, String label, boolean hover, int tint) {
        drawQuad(ps, buffer, l + 0.01, bottom + 0.005, r - 0.005, top - 0.005, hover ? 0xC0FFCC33 : tint);
        drawCenteredText(ps, buffer, font, label, (l + r) / 2.0, (top + bottom) / 2.0, hover ? 0xFF000000 : 0xFFFFFFFF);
    }

    static ContainerContentsMenu.Hit hit(double x, double y) {
        int n = candidates().size();
        PanelSize size = size();
        double halfW = size.panelW() / 2.0;
        double halfH = size.panelH() / 2.0;
        if (x < -halfW || x > halfW || y < -halfH || y > halfH) return ContainerContentsMenu.Hit.NONE;
        double bottom = halfH - HEADER_HEIGHT;
        if (y > bottom) {
            if (x <= -halfW + BACK_W) return new ContainerContentsMenu.Hit(ContainerContentsMenu.CellKind.BOOK_LIST_BACK, -1);
            if (x >= halfW - ALL_W) return new ContainerContentsMenu.Hit(ContainerContentsMenu.CellKind.BOOK_LIST_ALL_OFF, -1);
            if (x >= halfW - 2 * ALL_W) return new ContainerContentsMenu.Hit(ContainerContentsMenu.CellKind.BOOK_LIST_ALL_ON, -1);
            return ContainerContentsMenu.Hit.NONE;
        }
        int rowIdx = (int) Math.floor((bottom - y) / ROW_HEIGHT);
        int col = (int) Math.floor((x + halfW) / (size.panelW() / columns(n)));
        int i = col * ROWS + rowIdx;
        return rowIdx < ROWS && i >= 0 && i < n
            ? new ContainerContentsMenu.Hit(ContainerContentsMenu.CellKind.BOOK_LIST_ROW, i)
            : ContainerContentsMenu.Hit.NONE;
    }
}
