package games.brennan.dungeontrain.perks.aura;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * One aura's rolls this life: the effects in the order they were drawn, and for each level round the
 * draw index it raised ({@code -1} when no effect could take it). Immutable; every change is a copy.
 */
public record AuraDraws(List<ResourceLocation> effects, List<Integer> levelUps) {

    public static final AuraDraws EMPTY = new AuraDraws(List.of(), List.of());

    public static final Codec<AuraDraws> CODEC = RecordCodecBuilder.create(i -> i.group(
        ResourceLocation.CODEC.listOf().fieldOf("effects").forGetter(AuraDraws::effects),
        Codec.INT.listOf().fieldOf("level_ups").forGetter(AuraDraws::levelUps)
    ).apply(i, AuraDraws::new));

    public static final StreamCodec<ByteBuf, AuraDraws> STREAM_CODEC = StreamCodec.composite(
        ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), AuraDraws::effects,
        ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), AuraDraws::levelUps,
        AuraDraws::new);

    public AuraDraws {
        effects = List.copyOf(effects);
        levelUps = List.copyOf(levelUps);
    }

    public AuraDraws withEffect(ResourceLocation effect) {
        List<ResourceLocation> next = new ArrayList<>(effects);
        next.add(effect);
        return new AuraDraws(next, levelUps);
    }

    public AuraDraws withLevelUp(int index) {
        List<Integer> next = new ArrayList<>(levelUps);
        next.add(index);
        return new AuraDraws(effects, next);
    }

    /** Draw {@code index}'s level (1 = I) after the first {@code rounds} level rounds. */
    public int level(int index, int rounds) {
        int level = 1;
        for (int r = 0; r < Math.min(rounds, levelUps.size()); r++) {
            if (levelUps.get(r) == index) level++;
        }
        return level;
    }
}
