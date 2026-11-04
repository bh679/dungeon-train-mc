package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriageWeights;

import java.io.IOException;
import java.util.Map;

/**
 * The {@code weights.json} a {@link Template} kind keeps its per-id entry in, seen as one shape.
 *
 * <p>Answered by {@link Template#weightStore()} — empty for the kinds with no entry of their own
 * (parts are weighted inside the carriage's {@code .parts.json}; whole rooms, groups and halves,
 * frames, buildings, staged builds have nothing the relay document or a copy should carry). The
 * three stores behind it keep their own static APIs; this is the view the relay upload, the relay
 * install and the editor's New copy read and write through, so none of them switches on the kind.</p>
 */
public interface TemplateWeightStore {

    /** What the store currently holds for {@code id}, at the store's defaults when it holds nothing. */
    TemplateMeta meta(String id);

    /** Write {@code meta}'s weight, gate, groups and mode for {@code id}. The stage link is never written here. */
    void apply(String id, TemplateMeta meta) throws IOException;

    /** Copy {@code from}'s entry to {@code to}, label excluded. @return whether {@code from} had one. */
    boolean copy(String from, String to) throws IOException;

    static TemplateWeightStore carriages() { return CARRIAGES; }

    static TemplateWeightStore contents() { return CONTENTS; }

    /** The shared track-side store, keyed by {@code kind} — tiles, pillars, adjuncts, tunnels and portal rooms. */
    static TemplateWeightStore trackSide(TrackKind kind) { return new TrackSide(kind); }

    TemplateWeightStore CARRIAGES = new TemplateWeightStore() {
        @Override public TemplateMeta meta(String id) {
            return new TemplateMeta(CarriageWeights.current().weightFor(id),
                    CarriageWeights.current().gateFor(id), CarriageWeights.current().stageIdFor(id));
        }
        @Override public void apply(String id, TemplateMeta meta) throws IOException {
            CarriageWeights.set(id, meta.weight());
            CarriageWeights.setGate(id, meta.gate());
        }
        @Override public boolean copy(String from, String to) throws IOException {
            return CarriageWeights.copy(from, to);
        }
    };

    TemplateWeightStore CONTENTS = new TemplateWeightStore() {
        @Override public TemplateMeta meta(String id) {
            return new TemplateMeta(CarriageContentsWeights.current().weightFor(id),
                    CarriageContentsWeights.current().gateFor(id),
                    CarriageContentsWeights.current().stageIdFor(id));
        }
        @Override public void apply(String id, TemplateMeta meta) throws IOException {
            CarriageContentsWeights.set(id, meta.weight());
            CarriageContentsWeights.setGate(id, meta.gate());
        }
        @Override public boolean copy(String from, String to) throws IOException {
            return CarriageContentsWeights.copy(from, to);
        }
    };

    /** One {@link TrackKind}'s slice of {@link TrackVariantWeights}. */
    record TrackSide(TrackKind kind) implements TemplateWeightStore {
        @Override public TemplateMeta meta(String id) {
            return new TemplateMeta(
                    TrackVariantWeights.weightFor(kind, id),
                    TrackVariantWeights.gateFor(kind, id),
                    TrackVariantWeights.stageIdFor(kind, id),
                    TrackVariantWeights.modeFor(kind, id))
                    // Tunnel group memberships travel with the build, so a grouped set installs grouped.
                    .withGroups(TrackVariantWeights.groupsFor(kind, id))
                    .withGroupWeights(TrackVariantWeights.groupWeightsFor(kind, id));
        }
        @Override public void apply(String id, TemplateMeta meta) throws IOException {
            TrackVariantWeights.set(kind, id, meta.weight());
            TrackVariantWeights.setGate(kind, id, meta.gate());
            // Only a build that carries groups sets them — one uploaded before groups existed must
            // not wipe memberships this install already gave the template.
            if (meta.hasGroups()) {
                TrackVariantWeights.setGroups(kind, id, meta.groups());
                for (Map.Entry<String, Integer> w : meta.groupWeights().entrySet()) {
                    TrackVariantWeights.setGroupWeight(kind, id, w.getKey(), w.getValue());
                }
            }
            // The last of the three on purpose: this is the one carrying a portal room's door
            // position, and the two above rewrite the same entry.
            TrackVariantWeights.setMode(kind, id, meta.mode());
        }
        @Override public boolean copy(String from, String to) throws IOException {
            return TrackVariantWeights.copy(kind, from, to);
        }
    }
}
