package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.PillarAdjunctTemplateId;
import games.brennan.dungeontrain.template.PillarTemplateId;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.Optional;

/**
 * Editor plots for pillar sections (TOP/MIDDLE/BOTTOM) and the stairs adjuncts: the static entry
 * points the commands and packets still call, each a thin delegate to
 * {@link TrackSideEditors#pillar} / {@link TrackSideEditors#adjunct}. The lifecycle lives in
 * {@link PlotEditor}; new code should call the instance.
 */
public final class PillarEditor {

    /** Resolved section + name pair for a player position. */
    public record SectionPlot(PillarSection section, String name) {}

    /** Resolved adjunct + name pair for a player position. */
    public record AdjunctPlot(PillarAdjunct adjunct, String name) {}

    private PillarEditor() {}

    private static TrackSidePlotEditor<Template.Pillar> sections(PillarSection section) {
        return TrackSideEditors.pillar(section);
    }

    private static TrackSidePlotEditor<Template.Adjunct> adjuncts(PillarAdjunct adjunct) {
        return TrackSideEditors.adjunct(adjunct);
    }

    // ---- sections ----------------------------------------------------------------------------

    /** Plot origin for {@code (section, default)}. */
    public static BlockPos plotOrigin(PillarSection section, CarriageDims dims) {
        return plotOrigin(section, TrackKind.DEFAULT_NAME, dims);
    }

    /** Plot origin for an id-record-shaped pillar template. */
    public static BlockPos plotOrigin(PillarTemplateId id, CarriageDims dims) {
        return plotOrigin(id.section(), id.name(), dims);
    }

    /** Plot origin for {@code (section, name)}. */
    public static BlockPos plotOrigin(PillarSection section, String name, CarriageDims dims) {
        return TrackSidePlots.plotOrigin(PillarTemplateStore.pillarKind(section), name, dims);
    }

    /** Resolve the section + variant name {@code pos} sits inside, or null. */
    public static SectionPlot plotContaining(BlockPos pos, CarriageDims dims) {
        for (PillarSection section : PillarSection.values()) {
            Optional<Template.Pillar> hit = sections(section).plotContaining(pos, dims);
            if (hit.isPresent()) return new SectionPlot(section, hit.get().name());
        }
        return null;
    }

    /** Teleport {@code player} to the default plot for {@code section}, stamping the row first. */
    public static void enter(ServerPlayer player, PillarSection section) {
        enter(player, section, true);
    }

    /** Always restamps — see {@link PlotEditor#enter(ServerPlayer, Template, boolean)}. */
    public static void enter(ServerPlayer player, PillarSection section, boolean onTop) {
        sections(section).enter(player, new Template.Pillar(section), onTop);
    }

    /** Go here / the panel's Enter: a walk to the section's default plot, not a reload. */
    public static void walkTo(ServerPlayer player, PillarSection section, boolean onTop) {
        sections(section).walkTo(player, new Template.Pillar(section), onTop);
    }

    /** @param stamp whether to erase + restamp the plots before teleporting. */
    public static void enter(ServerPlayer player, PillarSection section, boolean onTop, boolean stamp) {
        sections(section).enter(player, new Template.Pillar(section), onTop, stamp);
    }

    /** Erase + restamp every variant for {@code section}. Idempotent. */
    public static void stampPlot(ServerLevel overworld, PillarSection section, CarriageDims dims) {
        sections(section).stampAll(overworld, dims);
    }

    /** Erase + restamp the single plot for {@code (section, name)}. */
    public static void stampPlot(ServerLevel overworld, PillarSection section, String name, CarriageDims dims) {
        sections(section).stampPlot(overworld, new Template.Pillar(section, name), dims);
    }

    /** Erase every variant plot for {@code section}. */
    public static void clearPlot(ServerLevel overworld, PillarSection section, CarriageDims dims) {
        sections(section).clearAll(overworld, dims);
    }

    /** Erase a single named variant plot for {@code section}. */
    public static void clearPlot(ServerLevel overworld, PillarSection section, String name, CarriageDims dims) {
        sections(section).clearPlot(overworld, new Template.Pillar(section, name), dims);
    }

    /** Snapshot key shared with {@link EditorDirtyCheck} for pillar-section rows. */
    public static String sectionSnapshotKey(PillarSection section, String name) {
        return sections(section).snapshotKey(new Template.Pillar(section, name));
    }

    /** Save the {@code section} plot the player is standing in. */
    public static SaveResult save(ServerPlayer player, PillarSection section) throws IOException {
        SectionPlot loc = plotContaining(player.blockPosition(), dimsOf(player));
        if (loc == null || loc.section() != section) {
            throw new IOException("Player is not inside any " + section.id() + " plot.");
        }
        return save(player, new PillarTemplateId(section, loc.name()));
    }

    /** Save the explicitly-named pillar variant; does not consult player position. */
    public static SaveResult save(ServerPlayer player, PillarTemplateId id) throws IOException {
        return sections(id.section()).save(player, new Template.Pillar(id.section(), id.name()));
    }

    // ---- adjuncts ----------------------------------------------------------------------------

    /** Plot origin for {@code (adjunct, default)}. */
    public static BlockPos plotOriginAdjunct(PillarAdjunct adjunct, CarriageDims dims) {
        return plotOriginAdjunct(adjunct, TrackKind.DEFAULT_NAME, dims);
    }

    /** Plot origin for an id-record-shaped pillar-adjunct template. */
    public static BlockPos plotOriginAdjunct(PillarAdjunctTemplateId id, CarriageDims dims) {
        return plotOriginAdjunct(id.adjunct(), id.name(), dims);
    }

    /** Plot origin for {@code (adjunct, name)}. */
    public static BlockPos plotOriginAdjunct(PillarAdjunct adjunct, String name, CarriageDims dims) {
        return TrackSidePlots.plotOrigin(PillarTemplateStore.adjunctKind(adjunct), name, dims);
    }

    /** Resolve the adjunct + variant name {@code pos} sits inside, or null. */
    public static AdjunctPlot plotContainingAdjunct(BlockPos pos, CarriageDims dims) {
        for (PillarAdjunct adjunct : PillarAdjunct.values()) {
            Optional<Template.Adjunct> hit = adjuncts(adjunct).plotContaining(pos, dims);
            if (hit.isPresent()) return new AdjunctPlot(adjunct, hit.get().name());
        }
        return null;
    }

    /** Teleport {@code player} to the default plot for {@code adjunct}, stamping the row first. */
    public static void enter(ServerPlayer player, PillarAdjunct adjunct) {
        enter(player, adjunct, true);
    }

    /** Always restamps. */
    public static void enter(ServerPlayer player, PillarAdjunct adjunct, boolean onTop) {
        adjuncts(adjunct).enter(player, new Template.Adjunct(adjunct), onTop);
    }

    /** Restamps only when not already inside. */
    public static void walkTo(ServerPlayer player, PillarAdjunct adjunct, boolean onTop) {
        adjuncts(adjunct).walkTo(player, new Template.Adjunct(adjunct), onTop);
    }

    /** @param stamp whether to erase + restamp the plots before teleporting. */
    public static void enter(ServerPlayer player, PillarAdjunct adjunct, boolean onTop, boolean stamp) {
        adjuncts(adjunct).enter(player, new Template.Adjunct(adjunct), onTop, stamp);
    }

    /** Erase + restamp every variant for {@code adjunct}. Idempotent. */
    public static void stampPlot(ServerLevel overworld, PillarAdjunct adjunct, CarriageDims dims) {
        adjuncts(adjunct).stampAll(overworld, dims);
    }

    /** Erase + restamp the single plot for {@code (adjunct, name)}. */
    public static void stampPlotAdjunct(ServerLevel overworld, PillarAdjunct adjunct, String name, CarriageDims dims) {
        adjuncts(adjunct).stampPlot(overworld, new Template.Adjunct(adjunct, name), dims);
    }

    /** Erase every variant plot for {@code adjunct}. */
    public static void clearPlotAdjunct(ServerLevel overworld, PillarAdjunct adjunct, CarriageDims dims) {
        adjuncts(adjunct).clearAll(overworld, dims);
    }

    /** Erase a single named variant plot for {@code adjunct}. */
    public static void clearPlotAdjunct(ServerLevel overworld, PillarAdjunct adjunct, String name, CarriageDims dims) {
        adjuncts(adjunct).clearPlot(overworld, new Template.Adjunct(adjunct, name), dims);
    }

    /** Snapshot key shared with {@link EditorDirtyCheck} for adjunct rows. */
    public static String adjunctSnapshotKey(PillarAdjunct adjunct, String name) {
        return adjuncts(adjunct).snapshotKey(new Template.Adjunct(adjunct, name));
    }

    /** Save the {@code adjunct} plot the player is standing in. */
    public static SaveResult save(ServerPlayer player, PillarAdjunct adjunct) throws IOException {
        AdjunctPlot loc = plotContainingAdjunct(player.blockPosition(), dimsOf(player));
        if (loc == null || loc.adjunct() != adjunct) {
            throw new IOException("Player is not inside any " + adjunct.id() + " plot.");
        }
        return save(player, new PillarAdjunctTemplateId(adjunct, loc.name()));
    }

    /** Save the explicitly-named adjunct variant; does not consult player position. */
    public static SaveResult save(ServerPlayer player, PillarAdjunctTemplateId id) throws IOException {
        return adjuncts(id.adjunct()).save(player, new Template.Adjunct(id.adjunct(), id.name()));
    }

    private static CarriageDims dimsOf(ServerPlayer player) throws IOException {
        if (player.getServer() == null) throw new IOException("No server context.");
        return games.brennan.dungeontrain.world.DungeonTrainWorldData.get(player.getServer().overworld()).dims();
    }
}
