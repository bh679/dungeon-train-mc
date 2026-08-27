package games.brennan.dungeontrain.client.menu.templateblocks;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.EditorMenuSpace;
import games.brennan.dungeontrain.net.TemplateBlocksSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client singleton state for the template-blocks world-space menu. Mirrors
 * {@link games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenu}
 * but is a flat, read-mostly list: one row per block used in the plot, each
 * with a usage count and a Swap button.
 *
 * <p>Driven by {@link TemplateBlocksSyncPacket} from the server — pushed on
 * open and re-pushed after a swap so counts stay live; an inactive packet
 * closes the panel.</p>
 */
public final class TemplateBlocksMenu {

    /** Maximum rows per column before the grid wraps to a new column. */
    public static final int ROWS_PER_COLUMN = 12;

    public enum CellKind {
        NONE,
        CLOSE,
        /** The main body of a row — click to preview the block across its variant cells. */
        ROW,
        /** The row's Swap button — click to replace the block with the held item. */
        ROW_SWAP
    }

    public record Hit(CellKind kind, int index) {
        public static final Hit NONE = new Hit(CellKind.NONE, -1);
    }

    private TemplateBlocksMenu() {}

    private static boolean active;
    private static String key = "";
    private static List<TemplateBlocksSyncPacket.Entry> entries = Collections.emptyList();
    private static Vec3 anchorPos = Vec3.ZERO;
    private static Vec3 anchorRight = new Vec3(1, 0, 0);
    private static Vec3 anchorUp = new Vec3(0, 1, 0);
    private static Vec3 anchorNormal = new Vec3(0, 0, 1);

    private static Hit hovered = Hit.NONE;

    /**
     * Where this opening of the menu draws, latched when the server activates it rather than
     * read live. The two modes tear down differently — a Screen to pop versus an event
     * subscriber to stop drawing — so the menu must close in the mode it opened in.
     */
    private static EditorMenuSpace space = ClientDisplayConfig.DEFAULT_TEMPLATE_BLOCKS_MENU_SPACE;

    /** Cache of resolved icon stacks keyed by block id — rebuilt on every fresh sync. */
    private static final Map<String, ItemStack> ICON_CACHE = new HashMap<>();

    public static boolean isActive() { return active; }
    public static String key() { return key; }
    public static List<TemplateBlocksSyncPacket.Entry> entries() { return entries; }
    public static Vec3 anchorPos() { return anchorPos; }
    public static Vec3 anchorRight() { return anchorRight; }
    public static Vec3 anchorUp() { return anchorUp; }
    public static Vec3 anchorNormal() { return anchorNormal; }
    public static Hit hovered() { return hovered; }

    /** Where this opening of the menu draws. See {@link #space}. */
    public static EditorMenuSpace space() { return space; }

    /**
     * Active <em>and</em> drawing in the world — the guard the world-space renderer and input
     * handler use. In screen-space {@link TemplateBlocksMenuScreen} owns rendering and input,
     * and both paths running would double-draw and double-hit-test.
     */
    public static boolean isActiveWorldspace() { return active && space.isWorldspace(); }

    public static void setHovered(Hit h) {
        hovered = h == null ? Hit.NONE : h;
    }

    /** Reset to closed — invoked on client logout so the menu doesn't carry across worlds. */
    public static void clearForLogout() {
        dismissScreen();
        active = false;
        entries = Collections.emptyList();
        hovered = Hit.NONE;
        ICON_CACHE.clear();
    }

    public static void applySync(TemplateBlocksSyncPacket packet) {
        if (!packet.active()) {
            dismissScreen();
            active = false;
            entries = Collections.emptyList();
            hovered = Hit.NONE;
            ICON_CACHE.clear();
            return;
        }
        boolean wasActive = active;
        active = true;
        if (!wasActive) {
            space = ClientDisplayConfig.getTemplateBlocksMenuSpace();
        }
        key = packet.key();
        entries = List.copyOf(packet.entries());
        anchorPos = packet.anchorPos();
        anchorRight = packet.anchorRight();
        anchorUp = packet.anchorUp();
        anchorNormal = anchorRight.cross(anchorUp).normalize();
        hovered = Hit.NONE;
        ICON_CACHE.clear();
        // Re-pushed after every swap so the counts stay live, so only the first sync of an
        // opening puts the screen up; the ones after it just refresh what it is drawing.
        if (!wasActive && space.isScreenspace()) {
            Minecraft.getInstance().setScreen(new TemplateBlocksMenuScreen());
        }
    }

    /** Pop our screen if it is the active one — guarded so another mod's GUI isn't clobbered. */
    private static void dismissScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TemplateBlocksMenuScreen)) return;
        try {
            mc.setScreen(null);
        } catch (IllegalStateException disconnectRace) {
            // setScreen throws during world disconnect ("Trying to return to in-game GUI during
            // disconnection"). MC is tearing the screen down anyway, so our state is already clean.
        }
    }

    /**
     * Resolve (and cache) the inventory icon stack for a block id. Falls back
     * to {@link Items#BARRIER} when the block has no item form.
     */
    public static ItemStack iconStack(String blockId) {
        ItemStack cached = ICON_CACHE.get(blockId);
        if (cached != null) return cached;
        ItemStack stack = new ItemStack(Items.BARRIER);
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id != null) {
            Block block = BuiltInRegistries.BLOCK.get(id);
            Item item = block == null ? null : block.asItem();
            if (item != null && item != Items.AIR) stack = new ItemStack(item);
        }
        ICON_CACHE.put(blockId, stack);
        return stack;
    }

    /** Short label for a block id — the path segment (e.g. {@code oak_stairs}). */
    public static String shortLabel(@Nullable String blockId) {
        if (blockId == null) return "";
        int colon = blockId.indexOf(':');
        return colon >= 0 ? blockId.substring(colon + 1) : blockId;
    }
}
