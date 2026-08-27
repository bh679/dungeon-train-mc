package games.brennan.dungeontrain.mixin.betteradvancements;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.compat.AdvancementHintText;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Keeps Better Advancements' criteria list out of the fog of war.
 *
 * <p>BA's tooltip adds something vanilla's advancement screen has no equivalent for: a grid of the
 * advancement's criteria, controlled by its {@code criteriaDetail} config (default {@code Default},
 * i.e. list what you have already obtained). For an unearned {@code dungeontrain:*} advancement that
 * leaks exactly what
 * {@link games.brennan.dungeontrain.mixin.client.AdvancementWidgetHideDescMixin} exists to conceal —
 * the biome set behind the diversity achievements, for instance — even though the description itself
 * is masked to a hint by {@link BetterAdvancementWidgetCompatMixin}.</p>
 *
 * <p>Rather than blank the widget's {@code criterionGrid} field (BA dereferences it unguarded in
 * several {@code drawHover} paths, so a null would NPE), this forces the detail level to
 * {@code OFF} for masked advancements only. That routes through BA's own supported branch, which
 * returns its {@code empty} grid singleton — a state the rest of BA already handles, because it is
 * what any player who sets {@code criteriaDetail = "Off"} gets.</p>
 *
 * <p>{@code findOptimalCriterionGrid} is a static shared by every advancement in the screen, so the
 * namespace and progress checks have to be exact or this would suppress criteria for vanilla
 * advancements too. Both come from the call's own arguments via
 * {@link Local}: the holder identifies the advancement, the progress says whether it is earned.</p>
 *
 * <p>{@code CriteriaDetail} is not on the compile classpath (BA is a bundled companion, not a
 * dependency), so the enum is handled as {@link Object} via {@link Coerce} and its {@code OFF}
 * constant is resolved from the value BA hands us — no {@code Class.forName}, no BA import.</p>
 */
@Mixin(targets = "betteradvancements.common.util.CriterionGrid", remap = false)
public abstract class BetterAdvancementsCriteriaMixin {

    /** Resolved once from the live enum; BA's {@code CriteriaDetail} constants are JVM-lifetime singletons. */
    @Unique
    private static Object dungeontrain$offDetail;

    /**
     * Force {@code detailLevel} to {@code OFF} while building the grid for a masked DT advancement.
     * Every read of the field inside the method is covered, so the early bail-out and the later
     * obtained/unobtained branches all agree.
     */
    @ModifyExpressionValue(
        method = "findOptimalCriterionGrid",
        at = @At(value = "FIELD",
                 target = "Lbetteradvancements/common/util/CriterionGrid;detailLevel:Lbetteradvancements/common/util/CriteriaDetail;")
    )
    private static @Coerce Object dungeontrain$hideCriteriaForMasked(
        @Coerce Object original,
        @Local(argsOnly = true) AdvancementHolder holder,
        @Local(argsOnly = true) AdvancementProgress progress
    ) {
        if (holder == null) return original;
        if (!AdvancementHintText.shouldMask(holder.id(), progress)) return original;
        Object off = dungeontrain$resolveOff(original);
        return off != null ? off : original;
    }

    /**
     * Pull the {@code OFF} constant off the enum class BA passed in, matched by
     * {@link Enum#name()}. Falls back to null (leaving BA's configured detail level alone) if the
     * constant ever disappears, so a future BA refactor degrades to "criteria visible" rather than
     * crashing the tooltip.
     */
    @Unique
    private static Object dungeontrain$resolveOff(Object sample) {
        if (dungeontrain$offDetail != null) return dungeontrain$offDetail;
        if (!(sample instanceof Enum<?> e)) return null;
        for (Object constant : e.getDeclaringClass().getEnumConstants()) {
            if (constant instanceof Enum<?> c && "OFF".equals(c.name())) {
                dungeontrain$offDetail = constant;
                return constant;
            }
        }
        return null;
    }
}
