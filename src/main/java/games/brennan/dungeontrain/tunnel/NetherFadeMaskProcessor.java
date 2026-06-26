package games.brennan.dungeontrain.tunnel;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.worldgen.NetherFade;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * {@link StructureProcessor} that masks the <b>Nether-dark</b> tunnel variant stamp down to the
 * cells the per-block crossfade dither selects. The Overworld variant is stamped first (the full
 * tunnel); the dark variant is then stamped <em>through this processor</em>, which keeps a block
 * only where {@link NetherFade#selectsNether} is true for its world position — everywhere else it
 * returns {@code null}, leaving the Overworld block beneath untouched. The net result is a per-block
 * composite of the two authored variants that turns Nether in the same clumps the ground does.
 *
 * <p>Air cells are always dropped: the Overworld stamp (and the tunnel's interior-airspace erase)
 * already opened the corridor, so the dark stamp only ever <em>recolours solid blocks</em>, never
 * re-carves. Mirrors the runtime-only-codec / {@code processBlock}-veto shape of
 * {@link games.brennan.dungeontrain.train.PartRegionFilterProcessor}.</p>
 */
public final class NetherFadeMaskProcessor extends StructureProcessor {

    /**
     * Runtime-only processor — attached programmatically to a placement, never serialised into a
     * template, so the codec just yields an inert sentinel on decode and writes nothing on encode.
     */
    private static final StructureProcessorType<NetherFadeMaskProcessor> TYPE =
        () -> MapCodec.unit(new NetherFadeMaskProcessor(null, 0L));

    private final @Nullable ServerLevel overworld;
    private final long genSeed;

    public NetherFadeMaskProcessor(@Nullable ServerLevel overworld, long genSeed) {
        this.overworld = overworld;
        this.genSeed = genSeed;
    }

    @Override
    @Nullable
    public StructureTemplate.StructureBlockInfo processBlock(
        LevelReader level, net.minecraft.core.BlockPos origin, net.minecraft.core.BlockPos pivot,
        StructureTemplate.StructureBlockInfo source,
        StructureTemplate.StructureBlockInfo target,
        StructurePlaceSettings settings
    ) {
        // Inert sentinel (decoded from the unit codec) — never masks anything.
        if (overworld == null) return target;
        // Never lay the dark variant's air over the already-carved Overworld interior.
        if (target.state().isAir()) return null;
        int wx = target.pos().getX();
        int wy = target.pos().getY();
        int wz = target.pos().getZ();
        double ramp = NetherFade.rampAt(overworld, wx);
        return NetherFade.selectsNether(genSeed, wx, wy, wz, ramp) ? target : null;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
