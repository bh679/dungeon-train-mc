package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.ediblebackpacks.client.BackpackEffectPlacement;
import games.brennan.ediblebackpacks.menu.EffectPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * In the inventory the streamer's own box sits behind what the inventory draws, as a viewer's box does,
 * and comes to the front while the cursor is on it, except over a backpack panel.
 *
 * <p>The streamer's box is drawn after the frame grab ({@link LiveHeadViewer#onFrameEnd}) so it never
 * reaches the broadcast, which means it is drawn after the inventory too. "Behind" is therefore a clip:
 * the box is drawn only outside the inventory's parts ({@link #occluders}): the panel (and the creative
 * tabs), Edible Backpacks' panels, the effect list, the screen's buttons and this frame's tooltip.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class StreamerBoxOcclusion {

    /** A GUI-pixel rectangle, {@code x1}/{@code y1} exclusive. */
    record Rect(int x0, int y0, int x1, int y1) {
        static Rect of(LiveHeadViewer.Box b, int border) {
            return new Rect(b.x() - border, b.y() - border, b.x() + b.w() + border, b.y() + b.h() + border);
        }

        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }

        boolean overlaps(Rect o) {
            return x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1;
        }

        boolean empty() {
            return x1 <= x0 || y1 <= y0;
        }

        Rect union(Rect o) {
            return new Rect(Math.min(x0, o.x0), Math.min(y0, o.y0), Math.max(x1, o.x1), Math.max(y1, o.y1));
        }
    }

    /** What the inventory covers ({@code all}), and which of it is a backpack panel. */
    record Layers(List<Rect> all, List<Rect> backpack) {
        boolean overBackpack(double x, double y) {
            for (Rect r : backpack) if (r.contains(x, y)) return true;
            return false;
        }
    }

    /** Chrome around a backpack panel's slots: the panel border reaches this far past them. */
    static final int PANEL_PAD = 7;
    /** The creative screen's tabs hang this far above and below its panel. */
    static final int CREATIVE_TABS = 32;
    /** A tooltip's frame reaches past its text by this much. */
    static final int TOOLTIP_PAD = 4;

    private static long frame;
    private static long tooltipFrame = -1;
    private static Rect tooltip;

    private StreamerBoxOcclusion() {}

    /**
     * The parts of {@code r} that no occluder covers: {@code r} itself when none overlaps, nothing when one
     * covers it. Pure.
     */
    static List<Rect> visible(Rect r, List<Rect> occluders) {
        List<Rect> parts = new ArrayList<>(List.of(r));
        for (Rect o : occluders) {
            List<Rect> next = new ArrayList<>();
            for (Rect p : parts) {
                if (!p.overlaps(o)) { next.add(p); continue; }
                addIfAny(next, new Rect(p.x0, p.y0, p.x1, o.y0));                                       // above
                addIfAny(next, new Rect(p.x0, o.y1, p.x1, p.y1));                                       // below
                int top = Math.max(p.y0, o.y0), bottom = Math.min(p.y1, o.y1);
                addIfAny(next, new Rect(p.x0, top, o.x0, bottom));                                      // left
                addIfAny(next, new Rect(o.x1, top, p.x1, bottom));                                      // right
            }
            parts = next;
        }
        return parts;
    }

    private static void addIfAny(List<Rect> into, Rect r) {
        if (!r.empty()) into.add(r);
    }

    /** Overlapping rectangles merged into their bounding boxes, so a backpack's slots become its panel. Pure. */
    static List<Rect> clusters(List<Rect> rects) {
        List<Rect> out = new ArrayList<>(rects);
        boolean merged = true;
        while (merged) {
            merged = false;
            for (int i = 0; i < out.size() && !merged; i++) {
                for (int j = i + 1; j < out.size(); j++) {
                    if (!out.get(i).overlaps(out.get(j))) continue;
                    out.set(i, out.get(i).union(out.get(j)));
                    out.remove(j);
                    merged = true;
                    break;
                }
            }
        }
        return out;
    }

    /** What {@code screen} draws over the box, and which of it is a backpack. */
    static Layers occluders(Screen screen) {
        List<Rect> all = new ArrayList<>();
        List<Rect> backpack = List.of();
        if (screen instanceof AbstractContainerScreen<?> acs) {
            int left = acs.getGuiLeft(), top = acs.getGuiTop();
            int tabs = screen instanceof CreativeModeInventoryScreen ? CREATIVE_TABS : 0;
            Rect panel = new Rect(left, top - tabs, left + acs.getXSize(), top + acs.getYSize() + tabs);
            all.add(panel);
            // Slots outside the panel belong to a side panel (Edible Backpacks'): each, with its border, clustered.
            List<Rect> outside = new ArrayList<>();
            for (Slot slot : acs.getMenu().slots) {
                if (!slot.isActive()) continue;
                Rect s = new Rect(left + slot.x - 1, top + slot.y - 1, left + slot.x + 17, top + slot.y + 17);
                if (panel.overlaps(s)) continue;
                outside.add(new Rect(s.x0 - PANEL_PAD, s.y0 - PANEL_PAD, s.x1 + PANEL_PAD, s.y1 + PANEL_PAD));
            }
            backpack = clusters(outside);
            all.addAll(backpack);
            Rect effects = effects(acs);
            if (effects != null) all.add(effects);
        }
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget w && w.visible) {
                all.add(new Rect(w.getX(), w.getY(), w.getX() + w.getWidth(), w.getY() + w.getHeight()));
            }
        }
        if (tooltip != null && tooltipFrame == frame) all.add(tooltip);
        return new Layers(all, backpack);
    }

    /** Where the inventory's effect list is: where Edible Backpacks puts it, else vanilla's spot. Null when none shows. */
    private static Rect effects(AbstractContainerScreen<?> acs) {
        if (!(acs instanceof EffectRenderingInventoryScreen<?>)) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        int n = 0;
        for (MobEffectInstance e : mc.player.getActiveEffects()) if (ClientHooks.shouldRenderEffect(e)) n++;
        if (n == 0) return null;
        if (ModList.get().isLoaded("ediblebackpacks")) {
            EffectPlacement.Placement p = BackpackEffectPlacement.resolve(acs);
            if (p != null) {
                int w = p.compact() ? EffectPlacement.NARROW : EffectPlacement.WIDE;
                return new Rect(p.left(), p.top(), p.left() + w, p.top() + EffectPlacement.stackHeight(n));
            }
        }
        int left = acs.getGuiLeft() + acs.getXSize() + 2;
        int space = acs.width - left;
        if (space < 32) return null;
        int step = n > 5 ? 132 / (n - 1) : 33;
        return new Rect(left, acs.getGuiTop(), left + (space >= 120 ? 120 : 32), acs.getGuiTop() + step * (n - 1) + 32);
    }

    @SubscribeEvent
    public static void onFrameStart(RenderFrameEvent.Pre event) {
        frame++;
    }

    /** The screen's tooltip this frame, placed as the default positioner places it (right of and above the cursor). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTooltip(RenderTooltipEvent.Pre event) {
        if (event.isCanceled()) return;
        int w = 0, h = 0;
        List<ClientTooltipComponent> parts = event.getComponents();
        for (ClientTooltipComponent c : parts) {
            w = Math.max(w, c.getWidth(event.getFont()));
            h += c.getHeight();
        }
        if (parts.size() > 1) h += 2; // the gap under the title line
        int x = event.getX() + 12, y = event.getY() - 12;
        if (x + w > event.getScreenWidth()) x = Math.max(event.getX() - 24 - w, 4);
        if (y + h + 3 > event.getScreenHeight()) y = event.getScreenHeight() - h - 3;
        tooltip = new Rect(x - TOOLTIP_PAD, y - TOOLTIP_PAD, x + w + TOOLTIP_PAD, y + h + TOOLTIP_PAD);
        tooltipFrame = frame;
    }
}
