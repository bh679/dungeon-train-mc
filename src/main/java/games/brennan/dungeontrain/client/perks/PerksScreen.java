package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PerkSpendPacket;
import games.brennan.dungeontrain.net.PerkUnlockPacket;
import games.brennan.dungeontrain.perks.PerkRegistry;
import games.brennan.dungeontrain.perks.PerkTree;
import games.brennan.dungeontrain.perks.PerkTreeLoader;
import games.brennan.dungeontrain.perks.PerkUnlocks;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The perks constellation, working the way the web page's test mode does ({@code perks.js}; see
 * {@code docs/design/perks-menu.md}). Hovering a star shows its card; clicking it acts: unlock it,
 * add a point, or unlock it again for room for one more. Every class starts hidden and appears once a
 * tier 1 perk in it is unlocked; the Classes list down the right edge shows, hides and previews
 * classes. The classes on the circle share it, and stars swing to a new layout. The two counters at
 * the bottom right hold the Advancement Points and Active Points.
 *
 * <p>Every action is a packet the server judges ({@link PerkUnlockPacket}, {@link PerkSpendPacket}); the
 * screen reacts to the re-synced state (animation, sound, HUD line), never its own guess. Not a pause
 * screen: the train keeps moving while you choose.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PerksScreen extends Screen {

    private static final long FADE_MS = 200;
    private static final long CARD_LINGER_MS = 250;
    private static final int CLOSE_W = 12;
    private static final double DOUBLE_CLICK_MS = 300;

    private final PerkTree tree;
    private final boolean fromApple;
    private final StarField stars;
    private final PerkCard card;
    private final ClassesList classesList;
    private final PerkCounters counters;
    private final SkyLayout layout = new SkyLayout();
    private final Map<String, Long> unlockedAt = new HashMap<>();
    private final Map<String, Long> pointAt = new HashMap<>();
    private final long openedAt = Util.getMillis();
    private final UUID player;

    private SkyView view;
    private PerkMenuSnapshot last = PerkMenuSnapshot.EMPTY;
    private Set<String> off;
    private String preview;
    private String glow;
    private String selected;
    private String hovered;
    private String cardFor;
    private long cardLeftAt = -1;
    private PerkTree drawn;
    private ClassVisibility vis;
    private boolean dragging;
    private double dragMoved;
    private long lastSkyClickAt;
    private long spentAt;
    private boolean firstSnapshot = true;

    private PerksScreen(boolean fromApple) {
        super(Component.translatable("gui.dungeontrain.perks.title"));
        Minecraft mc = Minecraft.getInstance();
        this.tree = PerkTreeLoader.get();
        this.fromApple = fromApple;
        this.stars = new StarField(openedAt);
        this.card = new PerkCard(tree, mc.font);
        this.classesList = new ClassesList(tree, mc.font);
        this.counters = new PerkCounters(mc.font);
        this.player = mc.player != null ? mc.player.getUUID() : new UUID(0, 0);
        this.off = ClassChoiceStore.load(player).orElseGet(this::allClasses);
        this.drawn = tree;
    }

    private Set<String> allClasses() {
        Set<String> all = new HashSet<>();
        ClassVisibility.liveClasses(tree).forEach(c -> all.add(c.id()));
        return Set.copyOf(all);
    }

    /** Open the menu over the game. {@code fromApple}: an apple was just eaten (header mode). */
    public static void open(boolean fromApple) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen instanceof PerksScreen) return;
        mc.setScreen(new PerksScreen(fromApple));
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 0.4F));
    }

    /** A refusal from the server for the open menu, if any. */
    public static void onRefused(String perkId, String reasonKey) {
        if (Minecraft.getInstance().screen instanceof PerksScreen s) s.refused(perkId, reasonKey);
    }

    @Override
    protected void init() {
        view = view == null ? SkyView.fit(width, height) : view.clamped(width, height);
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
                int before = last.unlockLevel(id), after = now.unlockLevel(id);
                if (after <= before) continue;
                unlockedAt.put(id, ms);
                play(SoundEvents.PLAYER_LEVELUP, 0.7F, 1.0F);
                tree.perk(id).ifPresent(p -> {
                    PerksHud.show(Component.translatable("gui.dungeontrain.perks.hud_unlocked", card.name(p)), colourOf(id));
                    // the first unlock of a tier 1 perk shows its class (web testAct)
                    if (before == 0 && p.tier() == 1 && p.cls() != null && off.contains(p.cls())) setOff(without(off, p.cls()));
                });
            }
            for (Map.Entry<String, Integer> e : now.active().entrySet()) {
                int before = last.points(e.getKey());
                if (e.getValue() > before) {
                    pointAt.put(e.getKey(), ms);
                    play(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 1.2F + 0.05F * before);
                    tree.perk(e.getKey()).ifPresent(p -> PerksHud.show(
                        Component.translatable("gui.dungeontrain.perks.hud_points", card.name(p), e.getValue()), colourOf(e.getKey())));
                    spentAt = ms;
                }
            }
        }
        firstSnapshot = false;
        last = now;
        return now;
    }

    private static Set<String> without(Set<String> set, String id) {
        Set<String> next = new HashSet<>(set);
        next.remove(id);
        return Set.copyOf(next);
    }

    private static Set<String> with(Set<String> set, String id) {
        Set<String> next = new HashSet<>(set);
        next.add(id);
        return Set.copyOf(next);
    }

    private void setOff(Set<String> next) {
        off = next;
        ClassChoiceStore.save(player, off);
    }

    private int colourOf(String id) {
        return tree.perk(id).map(p -> SkyPalette.classColour(tree, p)).orElse(SkyPalette.GOLD);
    }

    private void play(SoundEvent sound, float volume, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    private void refused(String perkId, String reasonKey) {
        long now = Util.getMillis();
        card.refused(perkId, reasonKey, now);
        if (reasonKey.endsWith(".no_active")) counters.shake(PerkCounters.Kind.ACTIVE, now);
        else if (reasonKey.endsWith(".points") || reasonKey.endsWith(".gate")) counters.shake(PerkCounters.Kind.UNLOCK, now);
        play(SoundEvents.UI_BUTTON_CLICK.value(), 0.5F, 0.6F);
    }

    /**
     * A click on a star (web {@code testClick}): unlock it when locked and reachable; add a point while it
     * holds fewer than its unlock level; once full, unlock it again for room for one more. Short of
     * points, the matching counter shakes and nothing is sent.
     */
    private void act(PerkTree.Perk p) {
        if (p.isRoot()) return;
        PerkMenuSnapshot s = last;
        long now = Util.getMillis();
        PerkRegistry.bootstrap();
        if (!PerkRegistry.isKnown(p.id())) {
            card.refused(p.id(), "gui.dungeontrain.perks.refused.not_built", now);
            return;
        }
        int level = s.unlockLevel(p.id());
        if (level <= 0 && !tree.reachable(p.id(), s::isUnlocked)) return;
        if (level > 0 && s.points(p.id()) < level) {
            if (!s.canAffordRank()) { counters.shake(PerkCounters.Kind.ACTIVE, now); return; }
            DungeonTrainNet.sendToServer(new PerkSpendPacket(p.id()));
            return;
        }
        if (s.unlockPoints() < p.unlockCost() || !PerkUnlocks.allowed(level + 1, s.unlockSpent(tree))) {
            counters.shake(PerkCounters.Kind.UNLOCK, now);
            return;
        }
        DungeonTrainNet.sendToServer(new PerkUnlockPacket(p.id()));
    }

    // ---- render ----------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        PerkMenuSnapshot snap = snapshot();
        long now = Util.getMillis();
        double fade = Math.min(1.0, (now - openedAt) / (double) FADE_MS);

        int listLeft = classesList.layout(width);
        int countersTop = counters.layout(width, height, snap.unlockPoints(), snap.activePoints());
        String listHover = classesList.classAt(mouseX, mouseY);
        glow = listHover;
        preview = listHover != null && off.contains(listHover) ? listHover : null;

        vis = new ClassVisibility(tree, off, preview, snap.unlocks(), snap.active(), snap.unlockPoints());
        drawn = tree.withPositions(layout.positions(tree, vis, now));
        hovered = card.contains(mouseX, mouseY) || listHover != null ? null : starAt(mouseX, mouseY);
        updateCard(snap, now, mouseX, mouseY, listLeft, countersTop);

        g.fill(0, 0, width, height, SkyPalette.BG);
        double gx = view.toScreenX(PerkTree.CENTRE, width), gy = view.toScreenY(PerkTree.CENTRE, height);
        // the web's soft centre glow (radial, gold, fading out by a third of the sky)
        SkyDraw.glow(g, gx, gy, 256 * view.zoom(), SkyDraw.withAlpha(SkyPalette.GOLD, 0.07 * fade));
        stars.render(g, view, width, height, now, fade);
        LinkPainter.render(g, drawn, snap, vis, view, width, height, fade);
        ClassTitlePainter.render(g, font, drawn, vis, glow, view, width, height, fade);

        boolean allNames = view.showsAllNames();
        boolean spendable = fromApple && snap.canAffordRank() && spentAt == 0;
        String focus = cardFor != null ? cardFor : selected;
        for (PerkTree.Perk p : drawn.perks()) {
            if (vis.hidden(p)) continue;
            boolean sel = p.id().equals(selected), hov = p.id().equals(hovered) || p.id().equals(cardFor);
            boolean glows = glow != null && glow.equals(p.cls());
            boolean name = allNames || sel || hov || glows || (focus != null && view.zoom() >= 0.7 && linked(focus, p.id()));
            StarPainter.render(g, font, drawn, p, snap, view, width, height,
                new StarPainter.Look(hov, sel, name, spendable, vis.ghost(p), glows), unlockedAt, pointAt, now, fade);
        }

        renderHeader(g, snap, now, fade);
        classesList.render(g, off, listHover, fade);
        counters.render(g, snap.unlockPoints(), snap.activePoints(), now, fade);
        g.flush(); // titles and names are batched: push them out so the card paints over them
        card.render(g, now);
        g.flush();
        super.render(g, mouseX, mouseY, partialTick);

        PerkCounters.Kind counter = counters.at(mouseX, mouseY);
        if (counter != null) g.renderComponentTooltip(font, PerkCounters.tooltip(counter), mouseX, mouseY);
    }

    /**
     * The card follows the hovered star and stays while the mouse is on the star or the card, closing
     * {@link #CARD_LINGER_MS} after it leaves both (web {@code hoverShow}/{@code hoverHide}). With no hover
     * it shows the keyboard selection.
     */
    private void updateCard(PerkMenuSnapshot snap, long now, int mx, int my, int right, int bottom) {
        if (hovered != null) {
            cardFor = hovered;
            cardLeftAt = -1;
        } else if (cardFor != null && card.contains(mx, my)) {
            cardLeftAt = -1;
        } else if (cardFor != null && !cardFor.equals(selected)) {
            if (cardLeftAt < 0) cardLeftAt = now;
            if (now - cardLeftAt > CARD_LINGER_MS) cardFor = null;
        }
        String id = cardFor != null ? cardFor : selected;
        PerkTree.Perk p = id == null ? null : tree.perk(id).orElse(null);
        if (p == null || vis.hidden(p)) {
            card.close();
            return;
        }
        card.open(p, drawn, snap, view, width, height, right, bottom);
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
        if (fromApple && snap.canAffordRank() && spentAt == 0) {
            boolean none = snap.unlocked().isEmpty();
            Component line = none ? Component.translatable("gui.dungeontrain.perks.unlock_first")
                : Component.translatable("gui.dungeontrain.perks.point_to_spend", snap.activePoints());
            double pulse = 0.75 + 0.25 * Math.sin(now / 600.0);
            g.drawString(font, line, x, y, (SkyPalette.GOLD & 0xFFFFFF) | ((int) (pulse * fade * 255) << 24), true);
        }
        g.drawString(font, "x", width - 8 - font.width("x"), y, (SkyPalette.MUTED & 0xFFFFFF) | alpha, false);
    }

    private boolean linked(String a, String b) {
        for (PerkTree.Link l : tree.linksOf(a)) if (l.other(a).equals(b)) return true;
        return false;
    }

    /** The drawn, visible star under the mouse, or null. */
    private String starAt(double mx, double my) {
        if (my < SkyView.HEADER || vis == null) return null;
        String best = null;
        double bestD = Double.MAX_VALUE;
        for (PerkTree.Perk p : drawn.perks()) {
            if (vis.hidden(p)) continue;
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
        if (card.contains(mx, my) || classesList.classAt(mx, my) != null || counters.at(mx, my) != null) return true;
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
        if (button != 0 || wasDrag || my < SkyView.HEADER) return true;
        String cls = classesList.classAt(mx, my);
        if (cls != null) {
            setOff(off.contains(cls) ? without(off, cls) : with(off, cls));
            play(SoundEvents.UI_BUTTON_CLICK.value(), 0.3F, 1.0F);
            return true;
        }
        if (card.contains(mx, my) || counters.at(mx, my) != null) return true;
        String id = starAt(mx, my);
        long now = Util.getMillis();
        if (id != null) {
            selected = id;
            tree.perk(id).ifPresent(this::act);
        } else {
            if (now - lastSkyClickAt < DOUBLE_CLICK_MS) view = SkyView.fit(width, height);
            lastSkyClickAt = now;
            selected = null;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!dragging) return super.mouseDragged(mx, my, button, dx, dy);
        dragMoved += Math.abs(dx) + Math.abs(dy);
        view = view.panned(dx, dy, width, height);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (scrollY == 0) return false;
        view = view.zoomedAt(scrollY > 0 ? SkyView.ZOOM_STEP : 1 / SkyView.ZOOM_STEP, mx, my, width, height);
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
                cardFor = null;
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (selected != null) tree.perk(selected).ifPresent(this::act);
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                selected = null;
                cardFor = null;
                return true;
            }
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> { zoomKey(SkyView.ZOOM_STEP); return true; }
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> { zoomKey(1 / SkyView.ZOOM_STEP); return true; }
            default -> { }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private PerkTree.Perk drawnPerk(String id) {
        return drawn.perk(id).orElse(drawn.root());
    }

    private void zoomKey(double factor) {
        PerkTree.Perk p = drawnPerk(selected == null ? PerkTree.ROOT : selected);
        view = view.zoomedAt(factor, view.toScreenX(p.x(), width), view.toScreenY(p.y(), height), width, height);
    }

    private void arrow(int keyCode) {
        double ax = keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 0;
        double ay = keyCode == GLFW.GLFW_KEY_DOWN ? 1 : keyCode == GLFW.GLFW_KEY_UP ? -1 : 0;
        if (selected == null) {
            view = view.panned(-ax * 40, -ay * 40, width, height);
            return;
        }
        PerkTree.Perk from = drawn.perk(selected).orElse(null);
        if (from == null) return;
        String best = pick(from, ax, ay, true);
        if (best == null) best = pick(from, ax, ay, false);
        if (best != null) {
            selected = best;
            cardFor = null;
            PerkTree.Perk p = drawnPerk(best);
            if (!onScreen(p)) view = view.centredOn(p.x(), p.y(), width, height);
        }
    }

    /** The linked (or, failing that, nearby) visible star whose direction best matches the arrow. */
    private String pick(PerkTree.Perk from, double ax, double ay, boolean linkedOnly) {
        String best = null;
        double bestScore = 0.3;
        List<PerkTree.Perk> candidates = linkedOnly
            ? drawn.linksOf(from.id()).stream().map(l -> drawn.perk(l.other(from.id())).orElse(null)).filter(p -> p != null).toList()
            : drawn.perks();
        for (PerkTree.Perk p : candidates) {
            if (p.id().equals(from.id()) || (vis != null && vis.hidden(p))) continue;
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

    /** Tab: the next class on the circle (shown classes, or hidden ones with a drawn perk). */
    private void hopClass(int dir) {
        List<PerkTree.PerkClass> classes = ClassVisibility.circleOrder(tree).stream()
            .filter(c -> vis == null || drawn.perksOf(c.id()).stream().anyMatch(p -> !vis.hidden(p))).toList();
        if (classes.isEmpty()) return;
        int idx = -1;
        if (selected != null) {
            String cls = tree.perk(selected).map(PerkTree.Perk::cls).orElse(null);
            for (int i = 0; i < classes.size(); i++) if (classes.get(i).id().equals(cls)) idx = i;
        }
        int next = ((idx + dir) % classes.size() + classes.size()) % classes.size();
        PerkTree.PerkClass c = classes.get(next);
        PerkTree.Perk p = drawn.perksOf(c.id()).stream().filter(q -> vis == null || !vis.hidden(q)).findFirst().orElseThrow();
        selected = p.id();
        cardFor = null;
        view = view.centredOn(p.x(), p.y(), width, height);
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
