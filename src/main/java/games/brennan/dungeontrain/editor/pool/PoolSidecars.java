package games.brennan.dungeontrain.editor.pool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuildCredits;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.CarriageVariantContentsAllowStore;
import games.brennan.dungeontrain.editor.CarriageVariantPartsStore;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.editor.WholeVariantBlocks;
import games.brennan.dungeontrain.editor.relay.EditorRelayWrite;
import games.brennan.dungeontrain.template.BuilderCredit;
import games.brennan.dungeontrain.template.FlipOptions;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageWeights;
import games.brennan.dungeontrain.train.WholeKind;
import games.brennan.dungeontrain.train.WholeWeights;
import net.minecraft.core.Vec3i;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What travels with a template across a pool move, and what cannot.
 *
 * <p>Travels: the per-cell variants sidecar and the chest → loot-prefab links, each re-keyed into the
 * destination's frame (contents cells sit one block in from the box corner — see
 * {@link Pool#frameOffset}); the weight, gate, Stage link, label and builder credit, copied field by
 * field into the destination's weights store; and the relay build credit. Dropped, and said so: a
 * shell's parts assignment and contents allow-list (only shells have them), and contents-only flip
 * options. Cells that fall outside the destination box when the frame shrinks are dropped too.</p>
 */
public final class PoolSidecars {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The objects inside a sidecar document whose keys are {@code "x,y,z"} cells. */
    static final String[] CELL_KEYED = {"variants", "pools", "links"};

    private PoolSidecars() {}

    /**
     * Carry everything that can travel from {@code from} to {@code to}, which already has its
     * template on disk. Never throws past a single sidecar: each step is isolated and what did not
     * make it is named in the returned list, so a bad sidecar cannot cost the move.
     *
     * @return the roles left behind, in player-facing words
     */
    public static List<String> carry(PoolRef from, PoolRef to, CarriageDims box) {
        List<String> dropped = new ArrayList<>();
        int shift = from.pool().frameOffset() - to.pool().frameOffset();
        Vec3i keep = to.pool() == Pool.CONTENTS ? CarriageContentsPlacer.interiorSize(box) : null;

        carryFile(variantsPath(from), variantsPath(to), shift, keep, "variants", dropped);
        carryFile(containersPath(from), containersPath(to), shift, keep, "container links", dropped);
        invalidate(to);

        if (from.pool() == Pool.CARRIAGE) {
            if (CarriageVariantPartsStore.exists(from.asShell())) dropped.add("parts assignment");
            if (CarriageVariantContentsAllowStore.exists(from.asShell())) dropped.add("contents allow-list");
        }
        try {
            carryWeights(from, to, dropped);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Pool move: weights for '{}' did not carry: {}", to.id(), e.toString());
            dropped.add("weight");
        }
        carryCredit(from, to, dropped);
        return dropped;
    }

    // ---- cell-keyed documents --------------------------------------------------------------------

    private static void carryFile(Path source, Path target, int shift, Vec3i keep, String role, List<String> dropped) {
        if (source == null || target == null || !Files.isRegularFile(source)) return;
        try {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            String translated = translate(text, shift, keep);
            if (translated.isEmpty()) {
                dropped.add(role);
                return;
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, translated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Pool move: the {} sidecar did not carry: {}", role, e.toString());
            dropped.add(role);
        }
    }

    /**
     * Shift every {@code "x,y,z"} key in a sidecar document by {@code shift} on every axis, dropping
     * any cell that lands outside {@code keep} (when given), and leave the rest of the document as it
     * was. Returns {@code ""} when the text will not parse — a half-translated sidecar would put
     * variant pools on the wrong blocks, which is worse than none.
     */
    public static String translate(String source, int shift, Vec3i keep) {
        try {
            JsonObject root = JsonParser.parseString(source).getAsJsonObject();
            if (shift != 0 || keep != null) {
                for (String section : CELL_KEYED) {
                    if (!root.has(section) || !root.get(section).isJsonObject()) continue;
                    root.add(section, shiftKeys(root.getAsJsonObject(section), shift, keep));
                }
            }
            return root.toString();
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Pool move: a sidecar would not parse, leaving it behind: {}", e.toString());
            return "";
        }
    }

    private static JsonObject shiftKeys(JsonObject section, int shift, Vec3i keep) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : section.entrySet()) {
            int[] cell = parseCell(entry.getKey());
            if (cell == null) {
                out.add(entry.getKey(), entry.getValue());
                continue;
            }
            int x = cell[0] + shift, y = cell[1] + shift, z = cell[2] + shift;
            if (keep != null && (x < 0 || y < 0 || z < 0 || x >= keep.getX() || y >= keep.getY() || z >= keep.getZ())) {
                continue;
            }
            out.add(x + "," + y + "," + z, entry.getValue());
        }
        return out;
    }

    private static int[] parseCell(String key) {
        String[] parts = key.split(",");
        if (parts.length != 3) return null;
        try {
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- where each pool keeps its sidecars ----------------------------------------------------

    static Path variantsPath(PoolRef ref) {
        return switch (ref.pool()) {
            case CARRIAGE -> CarriageVariantBlocks.configPathFor(ref.asShell());
            case CONTENTS -> CarriageContentsVariantBlocks.configPathForId(ref.id());
            case WHOLE -> WholeVariantBlocks.configPathFor(ref.tier().wholeKind(), ref.id());
        };
    }

    static Path containersPath(PoolRef ref) {
        return UserContentPaths.dir(ContainerContentsStore.SUBDIR).resolve(ContainerContentsStore.basenameFor(plotKey(ref)));
    }

    /** The container-store key for a template at {@code ref}. */
    public static String plotKey(PoolRef ref) {
        return switch (ref.pool()) {
            case CARRIAGE -> BlockVariantPlot.carriageKey(ref.id());
            case CONTENTS -> BlockVariantPlot.contentsKey(ref.id());
            case WHOLE -> BlockVariantPlot.wholeKey(ref.tier().wholeKind(), ref.id());
        };
    }

    private static void invalidate(PoolRef ref) {
        switch (ref.pool()) {
            case CARRIAGE -> CarriageVariantBlocks.invalidate(ref.id());
            case CONTENTS -> CarriageContentsVariantBlocks.invalidate(ref.id());
            case WHOLE -> WholeVariantBlocks.invalidate(ref.tier().wholeKind(), ref.id());
        }
        ContainerContentsStore.invalidate(plotKey(ref));
    }

    // ---- weights -----------------------------------------------------------------------------------

    private static void carryWeights(PoolRef from, PoolRef to, List<String> dropped) throws IOException {
        int weight;
        TemplateGate gate;
        String stage;
        String name;
        BuilderCredit builder;
        switch (from.pool()) {
            case CARRIAGE -> {
                CarriageWeights w = CarriageWeights.current();
                weight = w.weightFor(from.id()); gate = w.gateFor(from.id()); stage = w.stageIdFor(from.id());
                name = w.nameFor(from.id()); builder = w.builderFor(from.id());
            }
            case CONTENTS -> {
                CarriageContentsWeights w = CarriageContentsWeights.current();
                weight = w.weightFor(from.id()); gate = w.gateFor(from.id()); stage = w.stageIdFor(from.id());
                name = w.nameFor(from.id()); builder = w.builderFor(from.id());
                if (!FlipOptions.DEFAULT.equals(w.flipFor(from.id()))) dropped.add("flip options");
            }
            default -> {
                WholeKind kind = from.tier().wholeKind();
                weight = WholeWeights.weightFor(kind, from.id()); gate = WholeWeights.gateFor(kind, from.id());
                stage = WholeWeights.stageIdFor(kind, from.id()); name = WholeWeights.nameFor(kind, from.id());
                builder = WholeWeights.builderFor(kind, from.id());
            }
        }
        switch (to.pool()) {
            case CARRIAGE -> {
                CarriageWeights.set(to.id(), weight);
                CarriageWeights.setGate(to.id(), gate);
                CarriageWeights.setStage(to.id(), stage);
                if (name != null) CarriageWeights.setName(to.id(), name);
                if (builder != null && builder.known()) CarriageWeights.setBuilder(to.id(), builder);
            }
            case CONTENTS -> {
                CarriageContentsWeights.set(to.id(), weight);
                CarriageContentsWeights.setGate(to.id(), gate);
                CarriageContentsWeights.setStage(to.id(), stage);
                if (name != null) CarriageContentsWeights.setName(to.id(), name);
                if (builder != null && builder.known()) CarriageContentsWeights.setBuilder(to.id(), builder);
            }
            default -> {
                WholeKind kind = to.tier().wholeKind();
                WholeWeights.set(kind, to.id(), weight);
                WholeWeights.setGate(kind, to.id(), gate);
                WholeWeights.setStage(kind, to.id(), stage);
                if (name != null) WholeWeights.setName(kind, to.id(), name);
                if (builder != null && builder.known()) WholeWeights.setBuilder(kind, to.id(), builder);
            }
        }
    }

    // ---- relay build credit -------------------------------------------------------------------------

    /** The relay naming a template at {@code ref} is credited under, or null when that pool has none. */
    static EditorRelayWrite.Naming namingOf(PoolRef ref) {
        return switch (ref.pool()) {
            case CARRIAGE -> new EditorRelayWrite.Naming(BuilderPhotoPaths.Kind.CARRIAGE, "", ref.id());
            case CONTENTS -> new EditorRelayWrite.Naming(BuilderPhotoPaths.Kind.CONTENTS, "", ref.id());
            case WHOLE -> switch (ref.tier()) {
                case ROOM -> new EditorRelayWrite.Naming(BuilderPhotoPaths.Kind.CARRIAGE, EditorRelayWrite.WHOLE_ROOM_SUBKIND, ref.id());
                case GROUP -> new EditorRelayWrite.Naming(BuilderPhotoPaths.Kind.CARRIAGE_GROUP, "", ref.id());
                case HALF -> null;
            };
        };
    }

    /** The player-facing name of a credit that could not travel — {@code to} has no relay naming. */
    static final String DROPPED_CREDIT = "builder credit";

    private static void carryCredit(PoolRef from, PoolRef to, List<String> dropped) {
        EditorRelayWrite.Naming source = namingOf(from);
        if (source == null) return;
        BuildCredits.Credit credit = BuildCredits.get(source.kind(), source.subKind(), source.id());
        EditorRelayWrite.Naming target = namingOf(to);
        boolean known = credit != null && credit.known();
        if (known && target != null) {
            BuildCredits.put(target.kind(), target.subKind(), target.id(), credit);
        } else if (known) {
            // The destination pool (a whole half) has no relay naming to file it under; the source is
            // about to be removed, so say so rather than losing it silently.
            dropped.add(DROPPED_CREDIT);
        }
        BuildCredits.forget(source.kind(), source.subKind(), source.id());
    }
}
