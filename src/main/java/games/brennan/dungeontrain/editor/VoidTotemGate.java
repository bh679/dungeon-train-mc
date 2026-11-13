package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Whether the train has reached the void yet: the first End band, where the overworld breaks apart into
 * void and then the End (lap 1's {@code end:vanilla}). Until then no Void Totem is found, in DT's loot
 * ({@code ContainerContentsRoller}) or from evokers ({@code compat.EvokerTotemLootModifier}).
 *
 * <p>{@link WorldGenCycle#endPassIndex} is -1 before the first End band starts and counts End bands after
 * it, so "reached" is any pass at all, on the train's side of the cycle anchor: behind it the layout is
 * mirrored for players walking off the back, and a mirrored End band there is not the train's void.
 * The train only runs forward (+X), so once reached it stays reached.</p>
 */
public final class VoidTotemGate {

    private VoidTotemGate() {}

    /** Pure test: has {@code worldX} reached {@code cycle}'s first End band? */
    public static boolean reached(WorldGenCycle cycle, int worldX) {
        return worldX >= cycle.startX() && cycle.endPassIndex(worldX) >= 0;
    }

    /** {@link #reached(WorldGenCycle, int)} on the configured cycle. */
    public static boolean reached(int worldX) {
        return reached(WorldGenCycle.fromConfig(), worldX);
    }

    /**
     * Whether the carriage at position index {@code diffIndex} has reached the void. Editor previews
     * ({@code diffIndex < 0}) and calls with no server running count as not reached.
     */
    public static boolean reachedAtCarriage(int diffIndex) {
        if (diffIndex < 0) return false;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;
        int length = DungeonTrainWorldData.get(server.overworld()).dims().length();
        long worldX = (long) diffIndex * length;
        return reached((int) Math.min(Integer.MAX_VALUE, worldX));
    }
}
