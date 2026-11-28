package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.client.support.RecipientFaces;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.effects.archivist.ArchivistPerks;
import games.brennan.dungeontrain.perks.effects.archivist.follow.FavouriteAuthor;
import games.brennan.dungeontrain.perks.effects.archivist.follow.FollowRequestPacket;
import games.brennan.dungeontrain.perks.effects.archivist.follow.FollowsClientState;
import games.brennan.dungeontrain.perks.effects.archivist.follow.FollowsSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Favourite Author's Followed Authors menu, a small screen over the perks menu: one row per follow
 * (face, name, this life's finds or "No more books", Unfollow; the ones past the player's points greyed
 * as inactive), and a Follow box that autocompletes from the players whose books and photos this life
 * found, shown only while a point is free for one more follow (else a line saying so). Enter asks the relay; a name it knows becomes a row, an unknown one shows in red with Remove.
 * It never shows how many books anyone has written.
 */
public final class FollowedAuthorsScreen extends Screen {

    private static final String KEY = "gui.dungeontrain.follows.";
    private static final int W = 280, ROW_H = 16, PAD = 8, MAX_ROWS = 8, BTN_W = 56;
    private static final int GREEN = 0xFF7EE07E, RED = 0xFFFF7070;

    private final Screen parent;
    private EditBox box;
    private int seenVersion = -1;
    private int scroll;
    private final List<Button> rowButtons = new ArrayList<>();
    private Button removeButton;
    /** Whether the Follow box is up: a point is free for one more follow. */
    private boolean followOpen;
    private int left, top, rowsTop, boxY;

    public FollowedAuthorsScreen(Screen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        int h = PAD + 12 + MAX_ROWS * ROW_H + 6 + 12 + 20 + 2 * ROW_H + PAD;
        top = Math.max(4, (height - h) / 2);
        rowsTop = top + PAD + 14;
        boxY = rowsTop + MAX_ROWS * ROW_H + 18;
        String typed = box == null ? "" : box.getValue();
        followOpen = canFollowMore();
        box = null;
        if (followOpen) {
            box = new EditBox(font, left + PAD, boxY, W - 2 * PAD - BTN_W - 4, 18, Component.translatable(KEY + "follow"));
            box.setMaxLength(16);
            box.setFilter(s -> s.matches("[A-Za-z0-9_]*"));
            box.setValue(typed);
            box.setResponder(s -> updateSuggestion());
            addRenderableWidget(box);
            addRenderableWidget(Button.builder(Component.translatable(KEY + "follow"), b -> submit())
                .bounds(left + W - PAD - BTN_W, boxY - 1, BTN_W, 20).build());
            setInitialFocus(box);
        }
        seenVersion = -1;
        rebuildRows();
        DungeonTrainNet.sendToServer(new FollowRequestPacket(FollowRequestPacket.Op.REFRESH, ""));
    }

    /** A point free for one more follow (the server checks the same before asking the relay). */
    private boolean canFollowMore() {
        return FollowsClientState.canFollowMore(rank());
    }

    private void submit() {
        if (box == null) return;
        String name = box.getValue().trim();
        if (name.isEmpty()) return;
        DungeonTrainNet.sendToServer(new FollowRequestPacket(FollowRequestPacket.Op.FOLLOW_NAME, name));
        box.setValue("");
    }

    private void updateSuggestion() {
        String typed = box.getValue();
        List<String> s = typed.isEmpty() ? List.of() : FollowsClientState.suggestions(typed);
        box.setSuggestion(s.isEmpty() ? null : s.get(0).substring(Math.min(typed.length(), s.get(0).length())));
    }

    private List<FollowsSyncPacket.Row> rows() {
        return FollowsClientState.get().rows();
    }

    private int rank() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? 0 : Perks.rank(mc.player, ArchivistPerks.FAV_AUTHOR);
    }

    /** Unfollow buttons for the rows in view, plus Remove for a name the relay did not know. */
    private void rebuildRows() {
        rowButtons.forEach(this::removeWidget);
        rowButtons.clear();
        if (removeButton != null) removeWidget(removeButton);
        removeButton = null;
        List<FollowsSyncPacket.Row> rows = rows();
        scroll = Math.max(0, Math.min(scroll, rows.size() - visibleRows()));
        for (int i = 0; i < visibleRows() && scroll + i < rows.size(); i++) {
            String token = rows.get(scroll + i).token();
            Button b = Button.builder(Component.translatable(KEY + "unfollow"),
                    x -> DungeonTrainNet.sendToServer(new FollowRequestPacket(FollowRequestPacket.Op.UNFOLLOW, token)))
                .bounds(left + W - PAD - BTN_W, rowsTop + i * ROW_H + 1, BTN_W, ROW_H - 2).build();
            rowButtons.add(addRenderableWidget(b));
        }
        FollowsSyncPacket sync = FollowsClientState.get();
        if (sync.lookup() == FollowsSyncPacket.Lookup.NOT_FOUND || sync.lookup() == FollowsSyncPacket.Lookup.UNREACHABLE) {
            removeButton = addRenderableWidget(Button.builder(Component.translatable(KEY + "remove"),
                    x -> DungeonTrainNet.sendToServer(new FollowRequestPacket(FollowRequestPacket.Op.DISMISS, "")))
                .bounds(left + W - PAD - BTN_W, boxY + 22, BTN_W, ROW_H - 2).build());
        }
    }

    /** Rows shown at once: all of them up to {@link #MAX_ROWS}. */
    private int visibleRows() {
        return MAX_ROWS;
    }

    @Override
    public void tick() {
        if (followOpen != canFollowMore()) {
            rebuildWidgets();
            return;
        }
        if (seenVersion != FollowsClientState.version()) {
            seenVersion = FollowsClientState.version();
            rebuildRows();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        List<FollowsSyncPacket.Row> rows = rows();
        int active = FavouriteAuthor.activeCount(rows.size(), rank());
        int bottom = boxY + 22 + 2 * ROW_H + PAD - 4;
        g.fill(left, top, left + W, bottom, SkyPalette.PANEL);
        g.fill(left, top, left + W, top + 1, 0xFF3A3F6A);
        g.drawString(font, title, left + PAD, top + PAD, SkyPalette.GOLD, true);
        Component count = Component.translatable(KEY + "active", active, rows.size());
        g.drawString(font, count, left + W - PAD - font.width(count), top + PAD, SkyPalette.MUTED, false);

        FollowsSyncPacket sync = FollowsClientState.get();
        if (rows.isEmpty() && followOpen) {
            g.drawString(font, Component.translatable(KEY + "empty"), left + PAD, rowsTop + 4, SkyPalette.MUTED, false);
        }
        for (int i = 0; i < visibleRows() && scroll + i < rows.size(); i++) {
            int index = scroll + i;
            FollowsSyncPacket.Row r = rows.get(index);
            boolean on = index < active;
            int y = rowsTop + i * ROW_H;
            boolean justFound = sync.lookup() == FollowsSyncPacket.Lookup.FOUND && r.name().equalsIgnoreCase(sync.lookupName());
            renderRow(g, r, on, justFound, y);
        }
        if (followOpen) {
            g.drawString(font, Component.translatable(KEY + "follow"), left + PAD, boxY - 11, SkyPalette.MUTED, false);
        } else {
            g.drawString(font, font.plainSubstrByWidth(Component.translatable(KEY + "full").getString(), W - 2 * PAD),
                left + PAD, boxY + (18 - font.lineHeight) / 2 + 1, SkyPalette.MUTED, false);
        }
        renderLookup(g, sync);
    }

    private void renderRow(GuiGraphics g, FollowsSyncPacket.Row r, boolean on, boolean justFound, int y) {
        int x = left + PAD;
        int textY = y + (ROW_H - font.lineHeight) / 2 + 1;
        int alpha = on ? 0xFF000000 : 0x80000000;
        g.setColor(1F, 1F, 1F, on ? 1F : 0.45F);
        PlayerFaceRenderer.draw(g, RecipientFaces.of(r.name(), true), x, y + 3, 8);
        g.setColor(1F, 1F, 1F, 1F);
        x += 12;
        if (justFound) {
            g.drawString(font, "✓", x, textY, GREEN, false);
            x += font.width("✓ ");
        }
        int nameColour = justFound ? GREEN : on ? SkyPalette.FG : (SkyPalette.MUTED & 0xFFFFFF) | alpha;
        g.drawString(font, r.name(), x, textY, nameColour, false);
        Component status = !on ? Component.translatable(KEY + "inactive")
            : r.exhausted() ? Component.translatable(KEY + "no_more")
            : Component.translatable(KEY + "found", r.found());
        int statusRight = left + W - PAD - BTN_W - 6;
        g.drawString(font, status, statusRight - font.width(status), textY, (SkyPalette.MUTED & 0xFFFFFF) | alpha, false);
    }

    /** Under the box: waiting, or a name the relay did not know (red, with Remove), or the relay away. */
    private void renderLookup(GuiGraphics g, FollowsSyncPacket sync) {
        int y = boxY + 22 + (ROW_H - font.lineHeight) / 2;
        int x = left + PAD;
        switch (sync.lookup()) {
            case PENDING -> g.drawString(font, Component.translatable(KEY + "looking", sync.lookupName()), x, y, SkyPalette.MUTED, false);
            case NOT_FOUND -> {
                // the name, then why on the line under it: both stop short of Remove
                int room = W - 2 * PAD - BTN_W - 6;
                g.drawString(font, font.plainSubstrByWidth("✗ " + sync.lookupName(), room), x, y, RED, false);
                g.drawString(font, font.plainSubstrByWidth(Component.translatable(KEY + "not_found").getString(), room),
                    x, y + font.lineHeight + 2, RED, false);
            }
            case UNREACHABLE -> {
                // stops short of Remove, wrapping to the line above the box's label if it must
                int room = W - 2 * PAD - BTN_W - 6;
                List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.translatable(KEY + "unreachable"), room);
                for (int i = 0; i < lines.size(); i++) {
                    g.drawString(font, lines.get(i), x, y + i * (font.lineHeight + 1), RED, false);
                }
            }
            default -> { }
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (box != null && box.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            submit();
            return true;
        }
        if (box != null && box.isFocused() && key == GLFW.GLFW_KEY_TAB) {
            List<String> s = FollowsClientState.suggestions(box.getValue());
            if (!s.isEmpty()) {
                box.setValue(s.get(0));
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int max = Math.max(0, rows().size() - visibleRows());
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(dy)));
        if (next != scroll) {
            scroll = next;
            rebuildRows();
        }
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return parent != null && parent.isPauseScreen();
    }
}
