package games.brennan.dungeontrain.editor.pool;

import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.editor.WholeTemplateNew;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.ShellPool;
import games.brennan.dungeontrain.train.WholeKind;

import java.util.Locale;
import java.util.Optional;

/**
 * One template named by pool, tier and id — the address a {@link PoolMove} starts from and lands at.
 *
 * <p>Not every template has one: flatbed and portal shells keep the shape the train cuts and the
 * portal needs, so they are not movable and {@link #of} declines them.</p>
 */
public record PoolRef(Pool pool, PoolTier tier, String id) {

    public PoolRef {
        id = id == null ? "" : id.toLowerCase(Locale.ROOT);
    }

    /** The address of {@code model}, or empty when it is of a kind or shape that never moves. */
    public static Optional<PoolRef> of(Template model) {
        if (model == null) return Optional.empty();
        return switch (model) {
            case Template.Carriage c -> ofShell(c.variant());
            case Template.Contents c -> Optional.of(
                new PoolRef(Pool.CONTENTS, PoolTier.of(CarriageContentsPlacer.sizeOf(c.contents())), c.contents().id()));
            case Template.WholeCarriage w -> Optional.of(new PoolRef(Pool.WHOLE, PoolTier.ROOM, w.id()));
            case Template.WholeHalf h -> Optional.of(new PoolRef(Pool.WHOLE, PoolTier.HALF, h.id()));
            case Template.CarriageGroup g -> Optional.of(new PoolRef(Pool.WHOLE, PoolTier.GROUP, g.id()));
            default -> Optional.empty();
        };
    }

    private static Optional<PoolRef> ofShell(CarriageVariant variant) {
        if (variant instanceof CarriageVariant.Builtin b && b.type() == CarriagePlacer.CarriageType.FLATBED) {
            return Optional.empty();
        }
        if (ShellPool.poolOf(variant.id()) == ShellPool.FLATBED) return Optional.empty();
        if (PortalCarriageBuilder.isPortalVariant(variant)) return Optional.empty();
        return Optional.of(new PoolRef(Pool.CARRIAGE, PoolTier.of(CarriagePlacer.sizeOf(variant)), variant.id()));
    }

    /** The address of the template {@code id} names in {@code pool}, or empty when nothing there has it. */
    public static Optional<PoolRef> resolve(Pool pool, String rawId) {
        if (pool == null || rawId == null || rawId.isBlank()) return Optional.empty();
        String id = rawId.trim().toLowerCase(Locale.ROOT);
        return switch (pool) {
            case CARRIAGE -> CarriageVariantRegistry.find(id).flatMap(v -> of(new Template.Carriage(v)));
            case CONTENTS -> CarriageContentsRegistry.find(id).flatMap(c -> of(new Template.Contents(c)));
            case WHOLE -> {
                for (WholeKind kind : WholeKind.values()) {
                    if (WholeTemplateNew.ids(kind).contains(id)) {
                        yield Optional.of(new PoolRef(Pool.WHOLE, PoolTier.of(kind), id));
                    }
                }
                yield Optional.empty();
            }
        };
    }

    /** The live template at this address, or null when it is no longer registered. */
    public Template template() {
        return switch (pool) {
            case CARRIAGE -> CarriageVariantRegistry.find(id).<Template>map(Template.Carriage::new).orElse(null);
            case CONTENTS -> CarriageContentsRegistry.find(id).<Template>map(Template.Contents::new).orElse(null);
            case WHOLE -> WholeTemplateNew.ids(tier.wholeKind()).contains(id)
                ? WholeTemplateNew.template(tier.wholeKind(), id) : null;
        };
    }

    /** The X-menu category a template at this address is listed under. */
    public PlotCategory plotCategory() {
        return switch (pool) {
            case CARRIAGE -> PlotCategory.CARRIAGES;
            case CONTENTS -> PlotCategory.CONTENTS;
            case WHOLE -> switch (tier) {
                case ROOM -> PlotCategory.WHOLE;
                case HALF -> PlotCategory.WHOLE_HALF;
                case GROUP -> PlotCategory.WHOLE_GROUP;
            };
        };
    }

    /** The same tier and id in another pool. */
    public PoolRef in(Pool other) {
        return new PoolRef(other, tier, id);
    }

    /** The same pool and tier under another id. */
    public PoolRef named(String otherId) {
        return new PoolRef(pool, tier, otherId);
    }

    /** The dirty-scan category id this address's plots file under. */
    public String dirtyCategoryId() {
        return switch (pool) {
            case CARRIAGE -> "carriages";
            case CONTENTS -> "contents";
            case WHOLE -> games.brennan.dungeontrain.editor.WholeCarriageEditor.categoryIdOf(tier.wholeKind());
        };
    }

    /** A custom contents record for this id — the pools share one name pattern. */
    public CarriageContents.Custom asContents() {
        return new CarriageContents.Custom(id);
    }

    public CarriageVariant.Custom asShell() {
        return new CarriageVariant.Custom(id);
    }
}
