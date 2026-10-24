package games.brennan.dungeontrain.client.perks;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PerkSpendPacket;
import games.brennan.dungeontrain.net.PerkUnlockPacket;
import games.brennan.dungeontrain.perks.PerkTree;
import games.brennan.dungeontrain.perks.PerkTreeLoader;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The perks constellation: the web page's night sky drawn on a Minecraft screen (see
 * {@code docs/design/perks-menu.md}). The whole sky fits by default; wheel zooms around the cursor,
 * drag pans, arrows walk the links, Tab hops classes. Clicking a star opens its card; the card's one
 * button sends {@link PerkUnlockPacket} or {@link PerkSpendPacket} and the server's re-sync is what the
 * screen reacts to (animation, sound, HUD line), never its own guess.
 *
 * <p>Not a pause screen: the train keeps moving while you choose.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PerksScreen extends Screen {

    private static final long FADE_MS = 200;
    private static final int CLOSE_W = 12;
    private static final double DOUBLE_CLICK_MS = 300;

    private final PerkTree tree;
    private final boolean fromDebris;
    private final StarField stars;
    private final PerkCard card;
    private final Map<String, Long> unlockedAt = new HashMap<>();
    private final Map<String, Long> pointAt = new HashMap<>();
    private final long openedAt = Util.getMillis();

    private SkyView view;
    private PerkMenuSnapshot last = PerkMenuSnapshot.EMPTY;
    private String selected;
    private String hovered;
    private Button cardButton;
    private boolean dragging;
    private double dragMoved;
    private long lastSkyClickAt;
    private long balanceFlashAt;
    private long spentAt;
    private boolean firstSnapshot = true;

    private PerksScreen(boolean fromDebris) {
        super(Component.translatable("gui.dungeontrain.perks.title"));
        this.tree = PerkTreeLoader.get();
        this.fromDebris = fromDebris;
        this.stars = new StarField(openedAt);
        this.card = new PerkCard(tree, Minecraft.getInstance().font, this::sendUnlock, this::sendSpend);
    }

    /** Open the menu over the game. {@code fromDebris}: a point was just eaten (header mode). */
    public static void open(boolean fromDebris) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen instanceof PerksScreen) return;
        mc.setScreen(new PerksScreen(fromDebris));
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 0.4F));
    }

    /** A refusal from the server for the open menu, if any. */
    public static void onRefused(String perkId, String reasonKey) {
        if (Minecraft.getInstance().screen instanceof PerksScreen s) s.refused(perkId, reasonKey);
    }

    @Override
    protected void init() {
        view = view == null ? SkyView.fit(width, height) : view.clamped(width, height);
        reopenCard();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        PerksHud.onMenuClosed();
        super.onClose();
    }

    // ---- state -----------------------------------------------------------------------------------------

    private PerkMenuSnapshot snapshot() {
        PerkMenuSnapshot now = PerkMenuSnapshot.current();
        long ms = Util.getMillis();
        if (!firstSnapshot) {
            for (String id : now.unlocked()) {
                if (!last.unlocked().contains(id)) {
                    unlockedAt.put(id, ms);
                    play(SoundEvents.PLAYER_LEVELUP, 0.7F, 1.0F);
                    PerksHud.show(Component.translatable("gui.dungeontrain.perks.hud_unlocked", card.name(tree.perk(id).orElseThrow())), colourOf(id));
                }
            }
            for (Map.Entry<String, Integer> e : now.active().entrySet()) {
                int before = last.points(e.getKey());
                if (e.getValue() > before) {
                    pointAt.put(e.getKey(), ms);
                    play(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 1.2F + 0.05F * before);
                    PerksHud.show(Component.translatable("gui.dungeontrain.perks.hud_points", card.name(tree.perk(e.getKey()).orElseThrow()), e.getValue()), colourOf(e.getKey()));
                    spentAt = ms;
                }
            }
            if (now.unlockPoints() > last.unlockPoints() || now.activePoints() > last.activePoints()) balanceFlashAt = ms;
            if (!now.equals(last)) reopenCard();
        }
        firstSnapshot = false;
        last = now;
        return now;
    }

    private int colourOf(String id) {
        return tree.perk(id).map(p -> SkyPalette.classColour(tree, p)).orElse(SkyPalette.GOLD);
    }

    private void play(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    private void refused(String perkId, String reasonKey) {
        card.refused(perkId, reasonKey, Util.getMillis());
        play(SoundEvents.UI_BUTTON_CLICK.value(), 0.5F, 0.6F);
    }

    private void sendUnlock(String id) {
        DungeonTrainNet.sendToServer(new PerkUnlockPacket(id));
    }

    private void sendSpend(String id) {
        DungeonTrainNet.sendToServer(new PerkSpendPacket(id));
    }

    // ---- card ------------------------------------------------------------------------------------------

    private void reopenCard() {
        if (cardButton != null) {
            removeWidget(cardButton);
            cardButton = null;
        }
        PerkTree.Perk p = selected == null ? null : tree.perk(selected).orElse(null);
        if (p == null) {
            card.close();
            return;
        }
        cardButton = card.open(p, last, view, width, height, Util.getMillis());
        if (cardButton != null) addRenderableWidget(cardButton);
    }

    private void select(String id) {
        if (id != null && id.equals(selected)) {
            selected = null;
        } else {
            selected = id;
            if (id != null) play(SoundEvents.UI_BUTTON_CLICK.value(), 0.3F, 1.0F);
        }
        reopenCard();
    }

    // ---- render ----------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        PerkMenuSnapshot snap = snapshot();
        long now = Util.getMillis();
        double fade = Math.min(1.0, (now - openedAt) / (double) FADE_MS);
        hovered = card.contains(mouseX, mouseY) ? null : starAt(mouseX, mouseY);

        g.fill(0, 0, width, height, SkyPalette.BG);
        double gx = view.toScreenX(PerkTree.CENTRE, width), gy = view.toScreenY(PerkTree.CENTRE, height);
        SkyDraw.disc(g, gx, gy, 260 * view.zoom(), SkyDraw.withAlpha(SkyPalette.GOLD, 0.05 * fade));
        SkyDraw.disc(g, gx, gy, 110 * view.zoom(), SkyDraw.withAlpha(SkyPalette.GOLD, 0.04 * fade));
        stars.render(g, view, width, height, now, fade);
        LinkPainter.render(g, tree, snap, view, width, height, fade);
        ClassTitlePainter.render(g, font, tree, view, width, height, fade);

        boolean allNames = view.showsAllNames();
        boolean spendable = fromDebris && snap.activePoints() > 0 && spentAt == 0;
        for (PerkTree.Perk p : tree.perks()) {
            boolean sel = p.id().equals(selected), hov = p.id().equals(hovered);
            boolean name = allNames || sel || hov || (selected != null && view.zoom() >= 0.7 && linked(selected, p.id()));
            StarPainter.render(g, font, tree, p, snap, view, width, height, hov, sel, name, spendable || (snap.activePoints() > 0 && fromDebris),
                unlockedAt, pointAt, now, fade);
        }

        renderHeader(g, snap, now, fade);
        card.render(g, snap, now);
        super.render(g, mouseX, mouseY, partialTick);

        if (hovered != null && !hovered.equals(selected)) {
            tree.perk(hovered).ifPresent(p -> g.renderComponentTooltip(font, card.tooltip(p, snap), mouseX, mouseY));
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // the sky is the background
    }

    private void renderHeader(GuiGraphics g, PerkMenuSnapshot snap, long now, double fade) {
        int alpha = (int) (fade * 255) << 24;
        int x = 8, y = (SkyView.HEADER - font.lineHeight) / 2 + 1;
        g.drawString(font, title, x, y, (SkyPalette.GOLD & 0xFFFFFF) | alpha, true);
        x += font.width(title) + 16;
        boolean flash = now - balanceFlashAt < 500;
        int balanceColour = (flash ? SkyPalette.GOLD : SkyPalette.FG) & 0xFFFFFF | alpha;
        if (fromDebris && snap.activePoints() > 0 && spentAt == 0) {
            boolean none = snap.unlocked().isEmpty();
            Component line = none ? Component.translatable("gui.dungeontrain.perks.unlock_first")
                : Component.translatable("gui.dungeontrain.perks.point_to_spend", snap.activePoints());
            double pulse = 0.75 + 0.25 * Math.sin(now / 600.0);
            g.drawString(font, line, x, y, (SkyPalette.GOLD & 0xFFFFFF) | ((int) (pulse * fade * 255) << 24), true);
        } else {
            drawStar(g, x + 3, y + 4, 3.5, (SkyPalette.GOLD & 0xFFFFFF) | alpha);
            x += 10;
            Component unlock = Component.translatable("gui.dungeontrain.perks.unlock_points", snap.unlockPoints());
            g.drawString(font, unlock, x, y, balanceColour, true);
            x += font.width(unlock) + 14;
            if (snap.activePoints() > 0 || !snap.active().isEmpty()) {
                SkyDraw.disc(g, x + 3, y + 4, 3.0, (0xA07CFF & 0xFFFFFF) | alpha);
                x += 10;
                g.drawString(font, Component.translatable("gui.dungeontrain.perks.points", snap.activePoints()), x, y, balanceColour, true);
            }
        }
        g.drawString(font, "x", width - 8 - font.width("x"), y, (SkyPalette.MUTED & 0xFFFFFF) | alpha, false);
    }

    /** A five-point star for the unlock-point icon. */
    private static void drawStar(GuiGraphics g, double cx, double cy, double r, int colour) {
        for (int i = 0; i < 5; i++) {
            double a0 = -Math.PI / 2 + i * Math.PI * 2 / 5, a1 = a0 + Math.PI / 5;
            SkyDraw.line(g, cx + Math.cos(a0) * r, cy + Math.sin(a0) * r, cx + Math.cos(a1) * r * 0.45, cy + Math.sin(a1) * r * 0.45, 1.2, colour, colour);
            SkyDraw.line(g, cx + Math.cos(a1) * r * 0.45, cy + Math.sin(a1) * r * 0.45, cx + Math.cos(a1 + Math.PI / 5) * r, cy + Math.sin(a1 + Math.PI / 5) * r, 1.2, colour, colour);
        }
        SkyDraw.disc(g, cx, cy, r * 0.45, colour);
    }

    private boolean linked(String a, String b) {
        for (PerkTree.Link l : tree.linksOf(a)) if (l.other(a).equals(b)) return true;
        return false;
    }

    private String starAt(double mx, double my) {
        if (my < SkyView.HEADER) return null;
        String best = null;
        double bestD = Double.MAX_VALUE;
        for (PerkTree.Perk p : tree.perks()) {
            double dx = view.toScreenX(p.x(), width) - mx, dy = view.toScreenY(p.y(), height) - my;
            double d = Math.hypot(dx, dy);
            if (d <= SkyPalette.hitRadius(p, view.zoom()) && d < bestD) {
                best = p.id();
                bestD = d;
            }
        }
        return best;
    }

    // ---- input -----------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button == 0 && mx >= width - 8 - CLOSE_W && my < SkyView.HEADER) {
            onClose();
            return true;
        }
        if (card.contains(mx, my)) return true;
        if (button == 0 || button == 2) {
            dragging = true;
            dragMoved = 0;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (super.mouseReleased(mx, my, button)) return true;
        boolean wasDrag = dragging && dragMoved > 3;
        dragging = false;
        if (button != 0 || wasDrag || card.contains(mx, my) || my < SkyView.HEADER) return true;
        String id = starAt(mx, my);
        long now = Util.getMillis();
        if (id != null) {
            select(id);
        } else {
            if (now - lastSkyClickAt < DOUBLE_CLICK_MS) view = SkyView.fit(width, height);
            lastSkyClickAt = now;
            if (selected != null) select(null);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!dragging) return super.mouseDragged(mx, my, button, dx, dy);
        dragMoved += Math.abs(dx) + Math.abs(dy);
        view = view.panned(dx, dy, width, height);
        reopenCard();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (scrollY == 0) return false;
        view = view.zoomedAt(scrollY > 0 ? SkyView.ZOOM_STEP : 1 / SkyView.ZOOM_STEP, mx, my, width, height);
        reopenCard();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (PerksHotkeyClient.key().matches(keyCode, scanCode) || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> {
                arrow(keyCode);
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                hopClass((modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                view = SkyView.fit(width, height);
                selected = PerkTree.ROOT;
                reopenCard();
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (cardButton != null && cardButton.active) cardButton.onPress();
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (selected != null) { selected = null; reopenCard(); }
                return true;
            }
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> { zoomKey(SkyView.ZOOM_STEP); return true; }
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> { zoomKey(1 / SkyView.ZOOM_STEP); return true; }
            default -> { }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void zoomKey(double factor) {
        PerkTree.Perk p = tree.perk(selected == null ? PerkTree.ROOT : selected).orElse(tree.root());
        view = view.zoomedAt(factor, view.toScreenX(p.x(), width), view.toScreenY(p.y(), height), width, height);
        reopenCard();
    }

    private void arrow(int keyCode) {
        double ax = keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 0;
        double ay = keyCode == GLFW.GLFW_KEY_DOWN ? 1 : keyCode == GLFW.GLFW_KEY_UP ? -1 : 0;
        if (selected == null) {
            view = view.panned(-ax * 40, -ay * 40, width, height);
            reopenCard();
            return;
        }
        PerkTree.Perk from = tree.perk(selected).orElse(null);
        if (from == null) return;
        String best = pick(from, ax, ay, true);
        if (best == null) best = pick(from, ax, ay, false);
        if (best != null) {
            selected = best;
            PerkTree.Perk p = tree.perk(best).orElseThrow();
            if (!onScreen(p)) view = view.centredOn(p.x(), p.y(), width, height);
            reopenCard();
        }
    }

    /** The linked (or, failing that, nearby) star whose direction best matches the arrow. */
    private String pick(PerkTree.Perk from, double ax, double ay, boolean linkedOnly) {
        String best = null;
        double bestScore = 0.3;
        List<PerkTree.Perk> candidates = linkedOnly
            ? tree.linksOf(from.id()).stream().map(l -> tree.perk(l.other(from.id())).orElse(null)).filter(p -> p != null).toList()
            : tree.perks();
        for (PerkTree.Perk p : candidates) {
            if (p.id().equals(from.id())) continue;
            double dx = p.x() - from.x(), dy = p.y() - from.y(), d = Math.hypot(dx, dy);
            if (d == 0 || (!linkedOnly && d > 160)) continue;
            double cos = (dx * ax + dy * ay) / d;
            double score = cos - (linkedOnly ? 0 : d / 800.0);
            if (cos > 0.3 && score > bestScore) {
                best = p.id();
                bestScore = score;
            }
        }
        return best;
    }

    private void hopClass(int dir) {
        List<PerkTree.PerkClass> classes = tree.classes().stream().filter(c -> !tree.perksOf(c.id()).isEmpty()).toList();
        if (classes.isEmpty()) return;
        int idx = -1;
        if (selected != null) {
            String cls = tree.perk(selected).map(PerkTree.Perk::cls).orElse(null);
            for (int i = 0; i < classes.size(); i++) if (classes.get(i).id().equals(cls)) idx = i;
        }
        int next = ((idx + dir) % classes.size() + classes.size()) % classes.size();
        PerkTree.PerkClass c = classes.get(next);
        PerkTree.Perk p = tree.firstPerkOf(c.id()).orElse(tree.perksOf(c.id()).get(0));
        selected = p.id();
        view = view.centredOn(p.x(), p.y(), width, height);
        reopenCard();
    }

    private boolean onScreen(PerkTree.Perk p) {
        double sx = view.toScreenX(p.x(), width), sy = view.toScreenY(p.y(), height);
        return sx > 20 && sx < width - 20 && sy > SkyView.HEADER + 20 && sy < height - 20;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
