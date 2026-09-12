package games.brennan.dungeontrain.client.version.compare;

import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.shaders.ShaderDetailPane;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * The Versions page's notes box, taking the whole screen, with a tab per section across the top —
 * one per version for a release row, one per mod for the companion row. Opened from the small
 * button in the notes box's corner; Done (or Escape) returns to the page with its selection intact.
 *
 * <p>Tabs are laid out left to right at a shared width; when more exist than fit, the arrows at
 * either end scroll the strip one tab at a time rather than shrinking tabs until their labels
 * vanish.</p>
 */
@OnlyIn(Dist.CLIENT)
final class ChangelogFullscreenScreen extends Screen {

    private static final int MARGIN = 12;
    private static final int GAP = 6;
    private static final int TAB_H = 20;
    private static final int TAB_MIN_W = 64;
    private static final int TAB_PAD = 12;
    private static final int ARROW_W = 20;
    private static final int BOTTOM_ROW_H = 20;
    private static final int PANE_BG = 0x66000000;
    private static final float SELECTED_R = 0.45F, SELECTED_G = 0.6F, SELECTED_B = 1.0F;

    private final Screen parent;
    private final List<NotesSection> sections;
    private int selected;
    /** Index of the first tab shown in the strip. */
    private int firstTab;
    /** Set when the selection moved, so the next layout brings that tab into view — and only then. */
    private boolean followSelection = true;
    private ShaderDetailPane pane;
    private int paneX, paneY, paneW, paneH;

    ChangelogFullscreenScreen(Screen parent, Component title, List<NotesSection> sections, int initial) {
        super(title);
        this.parent = parent;
        this.sections = List.copyOf(sections);
        this.selected = Math.max(0, Math.min(initial, this.sections.size() - 1));
    }

    @Override
    protected void init() {
        int x = MARGIN;
        int w = this.width - MARGIN * 2;
        int tabY = MARGIN;
        layoutTabs(x, tabY, w);

        paneX = x;
        paneY = tabY + TAB_H + GAP;
        paneW = w;
        int bottomY = this.height - MARGIN - BOTTOM_ROW_H;
        paneH = bottomY - GAP - paneY;
        ShaderDetailPane previous = pane;
        pane = addRenderableWidget(new ShaderDetailPane(this.font, paneX + 2, paneY + 2, paneW - 4,
                Math.max(this.font.lineHeight, paneH - 4)));
        pane.setLines(sections.isEmpty() ? List.of() : sections.get(selected).lines());
        if (previous == null) {
            pane.resetScroll();
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, bottomY, 200, BOTTOM_ROW_H)
                .build());
    }

    private void layoutTabs(int x, int y, int w) {
        if (sections.isEmpty()) return;
        int tabW = TAB_MIN_W;
        for (NotesSection s : sections) {
            tabW = Math.max(tabW, this.font.width(s.title()) + TAB_PAD);
        }
        int fit = Math.max(1, (w - ARROW_W * 2 - GAP * 2) / (tabW + GAP));
        boolean paged = sections.size() > fit;
        int stripX = x;
        int stripW = w;
        if (paged) {
            // Bring the selected tab into view only when the selection itself moved; otherwise the
            // arrows would be snapped straight back to it and could never scroll away.
            if (followSelection) {
                if (selected < firstTab) firstTab = selected;
                if (selected >= firstTab + fit) firstTab = selected - fit + 1;
                followSelection = false;
            }
            firstTab = Math.max(0, Math.min(firstTab, sections.size() - fit));
            Button left = addRenderableWidget(new DarkTintedButton(x, y, ARROW_W, TAB_H,
                    Component.literal("<"), b -> { firstTab--; rebuildWidgets(); }));
            Button right = addRenderableWidget(new DarkTintedButton(x + w - ARROW_W, y, ARROW_W, TAB_H,
                    Component.literal(">"), b -> { firstTab++; rebuildWidgets(); }));
            left.active = firstTab > 0;
            right.active = firstTab + fit < sections.size();
            stripX = x + ARROW_W + GAP;
            stripW = w - (ARROW_W + GAP) * 2;
        } else {
            firstTab = 0;
        }
        int shown = Math.min(fit, sections.size() - firstTab);
        // Spread the visible tabs across the strip so a short list does not huddle at the left.
        int spacing = shown <= 1 ? 0 : (stripW - tabW * shown) / (shown - 1);
        spacing = Math.min(spacing, GAP * 2);
        int total = tabW * shown + spacing * (shown - 1);
        int tx = stripX + (stripW - total) / 2;
        for (int i = 0; i < shown; i++) {
            int index = firstTab + i;
            boolean isSelected = index == selected;
            DarkTintedButton tab = isSelected
                    ? new DarkTintedButton(tx, y, tabW, TAB_H, sections.get(index).title(), b -> selectTab(index),
                            SELECTED_R, SELECTED_G, SELECTED_B)
                    : new DarkTintedButton(tx, y, tabW, TAB_H, sections.get(index).title(), b -> selectTab(index));
            addRenderableWidget(tab);
            tx += tabW + spacing;
        }
    }

    private void selectTab(int index) {
        selected = index;
        followSelection = true;
        rebuildWidgets();
        if (pane != null) {
            pane.resetScroll();
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(paneX, paneY, paneX + paneW, paneY + paneH, PANE_BG);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
