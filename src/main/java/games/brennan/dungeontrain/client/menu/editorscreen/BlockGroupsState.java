package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TemplateBlockGroupsEditPacket;
import games.brennan.dungeontrain.net.TemplateBlockGroupsSyncPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * The client's copy of the Blocks page cells for the plot the player stands in, as the server last
 * sent them. Asked for again at most once a {@link #REFRESH_MILLIS} while the page is up, so blocks
 * placed by hand show without reopening the screen.
 */
public final class BlockGroupsState {

    /** One cell as the page shows it. */
    public record Entry(Block block, int count) {}

    static final long REFRESH_MILLIS = 1000L;

    private static volatile String key = "";
    private static volatile List<Entry> entries = List.of();
    private static long lastRequest;

    private BlockGroupsState() {}

    public static void applySync(TemplateBlockGroupsSyncPacket packet) {
        List<Entry> out = new ArrayList<>(packet.entries().size());
        for (TemplateBlockGroupsSyncPacket.Entry e : packet.entries()) {
            // Kept in place even when unknown: a cell's index is what a click sends back.
            ResourceLocation id = ResourceLocation.tryParse(e.blockId());
            Block block = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(id);
            out.add(new Entry(block, e.count()));
        }
        entries = List.copyOf(out);
        key = packet.key();
    }

    /** The plot the cells belong to; empty when the player stands in none, or nothing has come yet. */
    public static String key() { return key; }

    public static List<Entry> entries() { return entries; }

    /** Ask for the cells now when {@code force}, else when the last ask is older than the refresh period. */
    static void request(long nowMillis, boolean force) {
        if (!force && nowMillis - lastRequest < REFRESH_MILLIS) return;
        lastRequest = nowMillis;
        DungeonTrainNet.sendToServer(TemplateBlockGroupsEditPacket.request());
    }

    /** Re-skin cell {@code index} with the held block. */
    static void reskin(int index) {
        DungeonTrainNet.sendToServer(new TemplateBlockGroupsEditPacket(
            TemplateBlockGroupsEditPacket.Op.RESKIN, key, index));
    }
}
