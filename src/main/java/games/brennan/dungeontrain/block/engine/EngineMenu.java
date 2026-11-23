package games.brennan.dungeontrain.block.engine;

import games.brennan.dungeontrain.registry.ModMenuTypes;
import games.brennan.dungeontrain.train.engine.DriftEngine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Drifting Engine Block screen's container: the input slot over the player inventory, plus the
 * banked coal and the whole carriage's coal (every engine in it) synced as menu data, so the gauge and
 * the "more worlds" line read them without a packet of their own. The input takes coal or charcoal only, up to what the banked coal leaves of
 * {@link DriftEngine#MAX_COAL}, and gives it back on request. The screen draws the faded coal an empty input shows.
 *
 * <p>The drifting carriage the engine stands in rides the open-screen packet ({@link #writeExtra} /
 * {@link #fromNetwork}): whether there is one, and its name id; then who placed the engine, and whether
 * that is the player opening it. Read once, when the screen opens.</p>
 */
public class EngineMenu extends AbstractContainerMenu {

    /**
     * How far the two lines under the title (the carriage id, then who placed the engine) push everything
     * below them down the screen. Both are always reserved, so the layout never shifts.
     */
    public static final int CARRIAGE_LINE_H = 20;
    /** The longest player name the screen shows: Minecraft's own limit. */
    public static final int MAX_NAME_LENGTH = 16;
    /** Where the coal slot sits on the screen texture. */
    public static final int COAL_X = 13, COAL_Y = 20 + CARRIAGE_LINE_H;

    private final Container engine;
    private final ContainerData data;
    private final boolean inCarriage;
    private final String carriageId;
    private final String placedBy;
    private final boolean placedByYou;

    /** Client side: rebuilt from {@link #writeExtra}'s data, with a stand-in container filled by the slot sync. */
    public static EngineMenu fromNetwork(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        boolean inCarriage = buf.readBoolean();
        String carriageId = buf.readUtf(64);
        String placedBy = buf.readUtf(MAX_NAME_LENGTH);
        boolean placedByYou = buf.readBoolean();
        return new EngineMenu(id, inventory, new SimpleContainer(1), new SimpleContainerData(2), inCarriage, carriageId,
                placedBy, placedByYou);
    }

    /**
     * The open-screen data {@link #fromNetwork} reads: whether the engine is in a drifting carriage, its name
     * id, who placed the engine ({@code ""} when unknown) and whether that is the player opening it.
     */
    public static void writeExtra(RegistryFriendlyByteBuf buf, boolean inCarriage, String carriageId,
                                  String placedBy, boolean placedByYou) {
        buf.writeBoolean(inCarriage);
        buf.writeUtf(carriageId == null ? "" : carriageId, 64);
        String name = clampName(placedBy);
        buf.writeUtf(name, MAX_NAME_LENGTH);
        buf.writeBoolean(placedByYou && !name.isEmpty());
    }

    /**
     * A placer name as the screen may show it: a Minecraft player name (letters, digits, underscore, at
     * most {@value #MAX_NAME_LENGTH}), or {@code ""}. The name reaches this world in a blob another world
     * wrote, so anything else is dropped rather than shown.
     */
    public static String clampName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH) return "";
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return "";
        }
        return name;
    }

    public EngineMenu(int id, Inventory inventory, Container engine, ContainerData data,
                      boolean inCarriage, String carriageId, String placedBy, boolean placedByYou) {
        super(ModMenuTypes.ENGINE_BLOCK.get(), id);
        checkContainerSize(engine, 1);
        checkContainerDataCount(data, 2);
        this.engine = engine;
        this.data = data;
        this.inCarriage = inCarriage;
        this.carriageId = carriageId == null ? "" : carriageId;
        this.placedBy = clampName(placedBy);
        this.placedByYou = placedByYou && !this.placedBy.isEmpty();
        engine.startOpen(inventory.player);
        addSlot(new Slot(engine, 0, COAL_X, COAL_Y) {
            // A full engine (banked coal at the cap) takes nothing: without the room check, a shift-click
            // "moves" 0 coal, reports success, and vanilla's quick-move loop spins forever.
            @Override public boolean mayPlace(ItemStack stack) {
                return EngineBlockEntity.isFuel(stack) && getMaxStackSize() > 0;
            }
            @Override public int getMaxStackSize() { return Math.max(0, DriftEngine.MAX_COAL - banked()); }
            @Override public int getMaxStackSize(ItemStack stack) { return getMaxStackSize(); }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + CARRIAGE_LINE_H + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 142 + CARRIAGE_LINE_H));
        addDataSlots(data);
    }

    /** True when the engine stands in a drifting carriage (or dimensional room); its fuel only matters then. */
    public boolean inCarriage() {
        return inCarriage;
    }

    /** The drifting carriage's name id, {@code ""} when it has none or there is no carriage. */
    public String carriageId() {
        return carriageId;
    }

    /** Who placed the engine, {@code ""} when unknown. */
    public String placedBy() {
        return placedBy;
    }

    /** Whether the player looking at the screen is the one who placed the engine. */
    public boolean placedByYou() {
        return placedByYou;
    }

    /** Coal the engine arrived with, as last synced: shown, never taken out. */
    public int banked() {
        return data.get(0);
    }

    /** Coal put in during this world: the input slot. */
    public int input() {
        ItemStack stack = slots.get(0).getItem();
        return EngineBlockEntity.isFuel(stack) ? stack.getCount() : 0;
    }

    /** This engine's fuel: banked plus input. */
    public int coal() {
        return banked() + input();
    }

    /**
     * Coal in every engine of this carriage together, as last synced: the worlds it can still reach. Never
     * less than this engine's own, which the slot shows a tick before the sync catches up.
     */
    public int carriageCoal() {
        return Math.max(data.get(1), coal());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (index == 0) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
        } else if (EngineBlockEntity.isFuel(stack)) {
            if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY;
        }
        // Nothing actually moved: say so, or vanilla's quick-move loop calls us again forever.
        if (stack.getCount() == before.getCount()) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return before;
    }

    @Override
    public boolean stillValid(Player player) {
        return engine.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        engine.stopOpen(player);
    }
}
