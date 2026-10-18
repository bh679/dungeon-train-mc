package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.editor.StageStore;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;

/**
 * Decides each stamped creaking heart's Level / Stage / Band gate once, when its carriage contents
 * land. A block on the train has no level or band of its own to ask for later, so the answer is
 * written into the heart ({@link CreakingHeartSettings#gatedOff}) and the heart stays dormant when
 * it failed. Test the Carriage stamps inside {@code TestGate}, so the test's Stage / Diff level /
 * Band overrides are what {@link GateContext#forCarriage} sees.
 */
public final class CreakingHeartStampGate {

    private CreakingHeartStampGate() {}

    /** Every heart inside {@code origin + size}; {@code carriageIndex} is the stamped carriage's pIdx. */
    public static void apply(ServerLevel level, BlockPos origin, Vec3i size, int carriageIndex) {
        GateContext context = null;
        for (BlockPos pos : BlockPos.betweenClosed(origin,
                origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
            if (!(level.getBlockEntity(pos) instanceof CreakingHeartSettingsHolder holder)) continue;
            CreakingHeartSettings settings = holder.dungeontrain$settings();
            if (context == null) {
                int length = DungeonTrainWorldData.get(level.getServer().overworld()).dims().length();
                context = GateContext.forCarriage(level, carriageIndex, length);
            }
            boolean off = !context.allows(effectiveGate(settings));
            if (off != settings.gatedOff()) holder.dungeontrain$setSettings(settings.withGatedOff(off));
        }
    }

    /** The heart's linked Stage's gate, or its own Custom gate. */
    public static TemplateGate effectiveGate(CreakingHeartSettings settings) {
        return settings.stageId().isEmpty()
            ? settings.gate() : StageStore.effectiveGate(settings.gate(), settings.stageId());
    }
}
