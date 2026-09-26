package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageGroupRegistry;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
import games.brennan.dungeontrain.train.WholeKind;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The per-kind half of {@link EditorSaveAs}: for each kind of template, which files make it up,
 * what a new name has to satisfy, how a copy is made under that name, which other plots the copy
 * reloads from disk, and how the source is put back afterwards.
 *
 * <p>Each copy is the kind's existing New-copy-from path, run after the source has been saved — so
 * it copies the player's edits, and everything that path already carries (sidecars, weights, the
 * plot, the teleport) comes with them.</p>
 */
public final class EditorSaveAsKinds {

    private EditorSaveAsKinds() {}

    /** One kind's steps. */
    public interface Adapter {
        /** The dirty scan's category id for {@code source}, or null for a kind it does not scan. */
        String categoryId(Template source);

        /** Why {@code name} will not do, or empty when it will. {@code name} is already lower-case. */
        Optional<Component> validate(Template source, String name);

        /** The plots the copy reloads from disk — other than the copy itself. */
        List<Template> reloaded(Template source, String name);

        /** Every user-tier file the source's save writes, whether or not it exists now. */
        List<Path> userFiles(Template source);

        /** Make {@code name} from {@code source}'s saved state; returns the new template. */
        Template copy(ServerPlayer player, Template source, String name) throws IOException;

        /** After the files are back: drop the source's caches and restamp its plot from them. */
        void restoreAndRestamp(ServerLevel level, Template source, CarriageDims dims) throws IOException;
    }

    public static Adapter of(Template model) {
        return switch (model) {
            case Template.Carriage ignored -> CARRIAGE;
            case Template.Contents ignored -> CONTENTS;
            case Template.WholeCarriage ignored -> WHOLE;
            case Template.CarriageGroup ignored -> WHOLE;
            case Template.Part ignored -> PART;
            case Template.PortalRoom ignored -> PORTAL_ROOM;
            case Template.Track ignored -> TRACK_SIDE;
            case Template.Pillar ignored -> TRACK_SIDE;
            case Template.Adjunct ignored -> TRACK_SIDE;
            case Template.Tunnel ignored -> TRACK_SIDE;
            case Template.ChunkFrame ignored -> CHUNK_FRAME;
        };
    }

    // ---- shared ----

    private static Optional<Component> invalid(String name) {
        return Optional.of(Component.translatable("chat.dungeontrain.editor.invalid_name_use_lowercase", name));
    }

    private static Optional<Component> reserved(String name) {
        return Optional.of(Component.translatable("chat.dungeontrain.editor.name_reserved_built", name));
    }

    private static Optional<Component> taken(String name) {
        return Optional.of(Component.translatable("chat.dungeontrain.editor.name_already_taken", name));
    }

    /** The template's own file plus every sidecar {@link TemplateSidecars} lists, in the user tier. */
    private static List<Path> withSidecars(Path nbt, BuilderPhotoPaths.Kind kind, String subKind, String id) {
        List<Path> out = new ArrayList<>();
        out.add(nbt);
        for (TemplateSidecars.Sidecar s : TemplateSidecars.filesFor(kind, subKind, id)) {
            out.add(UserContentPaths.dir(s.subdir()).resolve(s.basename()));
        }
        return out;
    }

    // ---- carriages ----

    private static final Adapter CARRIAGE = new Adapter() {
        @Override public String categoryId(Template source) { return "carriages"; }

        @Override public Optional<Component> validate(Template source, String name) {
            if (!CarriageVariant.NAME_PATTERN.matcher(name).matches()) return invalid(name);
            if (CarriageVariant.isReservedBuiltinName(name)) return reserved(name);
            if (CarriageVariantRegistry.find(name).isPresent()) return taken(name);
            return Optional.empty();
        }

        /** Customs sort by name after the built-ins, so every custom after {@code name} moves one slot. */
        @Override public List<Template> reloaded(Template source, String name) {
            List<Template> out = new ArrayList<>();
            for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
                if (v instanceof CarriageVariant.Custom && v.id().compareTo(name) > 0) {
                    out.add(new Template.Carriage(v));
                }
            }
            return out;
        }

        @Override public List<Path> userFiles(Template source) {
            String id = source.id();
            return withSidecars(CarriageTemplateStore.fileForId(id), BuilderPhotoPaths.Kind.CARRIAGE, "", id);
        }

        @Override public Template copy(ServerPlayer player, Template source, String name) throws IOException {
            CarriageVariant from = ((Template.Carriage) source).variant();
            CarriageVariant.Custom target = (CarriageVariant.Custom) CarriageVariant.custom(name);
            CarriageEditor.duplicate(player, from, target);
            ServerLevel level = player.serverLevel().getServer().overworld();
            CarriageDims dims = games.brennan.dungeontrain.world.DungeonTrainWorldData.get(level).dims();
            CarriageEditor.restampRowFrom(level, CarriageEditor.slotOf(target.id()), dims);
            CarriageEditor.enter(player, target);
            return new Template.Carriage(target);
        }

        @Override public void restoreAndRestamp(ServerLevel level, Template source, CarriageDims dims) {
            CarriageTemplateStore.clearCache();
            TemplateSidecars.invalidateCaches(BuilderPhotoPaths.Kind.CARRIAGE, "", source.id());
            CarriageEditor.stampPlot(level, ((Template.Carriage) source).variant(), dims);
        }
    };

    // ---- contents ----

    private static final Adapter CONTENTS = new Adapter() {
        @Override public String categoryId(Template source) { return "contents"; }

        @Override public Optional<Component> validate(Template source, String name) {
            if (!CarriageContents.NAME_PATTERN.matcher(name).matches()) return invalid(name);
            if (CarriageContents.isReservedBuiltinName(name)) return reserved(name);
            if (CarriageContentsRegistry.find(name).isPresent()) return taken(name);
            return Optional.empty();
        }

        /**
         * A sub-variant's copy joins the end of its parent's column, which moves nothing. A top-level
         * copy moves every top-level template after it one slot along — and each one's column of
         * sub-variants with it.
         */
        @Override public List<Template> reloaded(Template source, String name) {
            List<Template> out = new ArrayList<>();
            if (parentOf(source).isPresent()) return out;
            for (CarriageContents c : CarriageContentsRegistry.allContents()) {
                if (!(c instanceof CarriageContents.Custom) || c.id().compareTo(name) <= 0) continue;
                if (CarriageContentsGroupStore.allChildIds().contains(c.id())) continue;
                out.add(new Template.Contents(c));
                CarriageContentsGroupStore.get(c.id()).ifPresent(g -> {
                    for (var m : g.members()) {
                        CarriageContentsRegistry.find(m.id()).ifPresent(child -> out.add(new Template.Contents(child)));
                    }
                });
            }
            return out;
        }

        @Override public List<Path> userFiles(Template source) {
            String id = source.id();
            return withSidecars(CarriageContentsStore.fileForId(id), BuilderPhotoPaths.Kind.CONTENTS, "", id);
        }

        @Override public Template copy(ServerPlayer player, Template source, String name) throws IOException {
            CarriageContents from = ((Template.Contents) source).contents();
            CarriageContents.Custom target = (CarriageContents.Custom) CarriageContents.custom(name);
            ServerLevel level = player.serverLevel().getServer().overworld();
            CarriageDims dims = games.brennan.dungeontrain.world.DungeonTrainWorldData.get(level).dims();
            Optional<String> parent = parentOf(source);
            if (parent.isPresent()) {
                CarriageContentsEditor.duplicateIntoGroup(player, from, target, parent.get());
            } else {
                // Clear what is about to move while it still stands where it was stamped, register
                // the copy, then stamp everything at its new slot.
                List<Template> moving = reloaded(source, name);
                for (Template t : moving) {
                    CarriageContentsEditor.clearPlot(level, ((Template.Contents) t).contents(), dims);
                }
                CarriageContentsEditor.duplicate(player, from, target);
                CarriageContentsEditor.stampPlot(level, target, dims);
                for (Template t : moving) {
                    CarriageContentsEditor.stampPlot(level, ((Template.Contents) t).contents(), dims);
                }
            }
            CarriageContentsEditor.enter(player, target, null);
            return new Template.Contents(target);
        }

        @Override public void restoreAndRestamp(ServerLevel level, Template source, CarriageDims dims) {
            CarriageContentsStore.clearCache();
            TemplateSidecars.invalidateCaches(BuilderPhotoPaths.Kind.CONTENTS, "", source.id());
            CarriageContentsEditor.stampPlot(level, ((Template.Contents) source).contents(), dims);
        }

        private Optional<String> parentOf(Template source) {
            return CarriageContentsGroupStore.findParentOf(source.id());
        }
    };

    // ---- whole rooms and groups ----

    private static final Adapter WHOLE = new Adapter() {
        @Override public String categoryId(Template source) {
            return kindOf(source) == WholeKind.GROUP ? "whole_group" : "whole";
        }

        @Override public Optional<Component> validate(Template source, String name) {
            boolean group = kindOf(source) == WholeKind.GROUP;
            boolean valid = group ? CarriageGroup.isValidName(name) : WholeCarriage.isValidName(name);
            if (!valid) return invalid(name);
            boolean exists = group ? CarriageGroupRegistry.find(name).isPresent()
                : WholeCarriageRegistry.find(name).isPresent();
            return exists ? taken(name) : Optional.empty();
        }

        /** {@link WholeTemplateNew#create} clears and restamps the kind's whole row from disk. */
        @Override public List<Template> reloaded(Template source, String name) {
            List<Template> out = new ArrayList<>();
            if (kindOf(source) == WholeKind.GROUP) {
                for (CarriageGroup g : CarriageGroupRegistry.all()) out.add(new Template.CarriageGroup(g));
            } else {
                for (WholeCarriage w : WholeCarriageRegistry.all()) out.add(new Template.WholeCarriage(w));
            }
            return out;
        }

        @Override public List<Path> userFiles(Template source) {
            WholeKind kind = kindOf(source);
            String id = source.id();
            List<Path> out = new ArrayList<>();
            out.add(kind == WholeKind.GROUP ? CarriageGroupTemplateStore.fileForId(id)
                : WholeCarriageTemplateStore.fileForId(id));
            out.add(WholeVariantBlocks.configPathFor(kind, id));
            out.add(UserContentPaths.dir(ContainerContentsStore.SUBDIR)
                .resolve(ContainerContentsStore.basenameFor(BlockVariantPlot.wholeKey(kind, id))));
            return out;
        }

        @Override public Template copy(ServerPlayer player, Template source, String name) throws IOException {
            WholeKind kind = kindOf(source);
            WholeTemplateNew.create(player, kind, name, source.id());
            return kind == WholeKind.GROUP
                ? new Template.CarriageGroup(new CarriageGroup(name))
                : new Template.WholeCarriage(new WholeCarriage(name));
        }

        @Override public void restoreAndRestamp(ServerLevel level, Template source, CarriageDims dims) {
            WholeKind kind = kindOf(source);
            if (kind == WholeKind.GROUP) CarriageGroupTemplateStore.clearCache();
            else WholeCarriageTemplateStore.clearCache();
            WholeVariantBlocks.invalidate(kind, source.id());
            ContainerContentsStore.invalidate(BlockVariantPlot.wholeKey(kind, source.id()));
            WholeCarriageEditor.stampPlot(level, source, dims);
        }

        private WholeKind kindOf(Template source) {
            return source instanceof Template.CarriageGroup ? WholeKind.GROUP : WholeKind.ROOM;
        }
    };

    // ---- parts ----

    private static final Adapter PART = new Adapter() {
        /** Part plots are not in the dirty scan. */
        @Override public String categoryId(Template source) { return null; }

        @Override public Optional<Component> validate(Template source, String name) {
            if (!CarriagePartRegistry.NAME_PATTERN.matcher(name).matches()) {
                return Optional.of(Component.translatable("chat.dungeontrain.editor.invalid_part_name_use", name));
            }
            if (CarriagePartKind.NONE.equals(name)) {
                return Optional.of(Component.translatable("chat.dungeontrain.editor.reserved_it_means_skip",
                    CarriagePartKind.NONE));
            }
            if (CarriagePartRegistry.isKnown(((Template.Part) source).partKind(), name)) return taken(name);
            return Optional.empty();
        }

        /** A new part takes the next free slot at the end of its kind's row; nothing moves. */
        @Override public List<Template> reloaded(Template source, String name) {
            return List.of();
        }

        @Override public List<Path> userFiles(Template source) {
            Template.Part part = (Template.Part) source;
            return withSidecars(CarriagePartTemplateStore.fileFor(part.partKind(), part.name()),
                BuilderPhotoPaths.Kind.PART, part.partKind().id(), part.name());
        }

        @Override public Template copy(ServerPlayer player, Template source, String name) throws IOException {
            Template.Part part = (Template.Part) source;
            CarriagePartEditor.createCopyOf(player, part.partKind(), part.name(), name);
            return new Template.Part(part.partKind(), name);
        }

        @Override public void restoreAndRestamp(ServerLevel level, Template source, CarriageDims dims) {
            Template.Part part = (Template.Part) source;
            CarriagePartTemplateStore.clearCache();
            TemplateSidecars.invalidateCaches(BuilderPhotoPaths.Kind.PART, part.partKind().id(), part.name());
            EditorSidecarBaseline.forgetFile(CarriagePartVariantBlocks.configPathFor(part.partKind(), part.name()));
            CarriagePartEditor.stampPlot(level, part.partKind(), part.name(), dims);
        }
    };

    // ---- track tiles, pillars, adjuncts, tunnels ----

    private static final Adapter TRACK_SIDE = new Adapter() {
        @Override public String categoryId(Template source) { return "tracks"; }

        @Override public Optional<Component> validate(Template source, String name) {
            if (!TrackVariantRegistry.NAME_PATTERN.matcher(name).matches()) {
                return Optional.of(Component.translatable("chat.dungeontrain.editor.invalid_variant_name_allowed", name));
            }
            if (TrackKind.DEFAULT_NAME.equals(name)) {
                return Optional.of(Component.translatable("chat.dungeontrain.editor.default_reserved_pick_another"));
            }
            if (TrackVariantRegistry.contains(trackKindOf(source), name)) return taken(name);
            return Optional.empty();
        }

        /** Registering a name restamps every plot of the kind from disk. */
        @Override public List<Template> reloaded(Template source, String name) {
            List<Template> out = new ArrayList<>();
            for (String n : TrackVariantRegistry.namesFor(trackKindOf(source))) out.add(withName(source, n));
            return out;
        }

        @Override public List<Path> userFiles(Template source) {
            TrackKind kind = trackKindOf(source);
            String name = source.variantName();
            return withSidecars(TrackVariantStore.fileFor(kind, name), BuilderPhotoPaths.Kind.TRACK, kind.id(), name);
        }

        @Override public Template copy(ServerPlayer player, Template source, String name) throws IOException {
            TrackKind kind = trackKindOf(source);
            ServerLevel level = player.serverLevel().getServer().overworld();
            CarriageDims dims = games.brennan.dungeontrain.world.DungeonTrainWorldData.get(level).dims();
            var template = TrackVariantStore.get(level, kind, source.variantName(), dims)
                .orElseThrow(() -> new IOException("'" + source.variantName() + "' has no saved template to copy."));
            TrackVariantStore.save(kind, name, template);
            TemplateCopy.copy(BuilderPhotoPaths.Kind.TRACK, kind.id(), source.variantName(), name);
            TrackVariantRegistry.register(kind, name);
            restampKind(level, kind, dims);
            teleportTo(player, level, kind, name, dims);
            return withName(source, name);
        }

        @Override public void restoreAndRestamp(ServerLevel level, Template source, CarriageDims dims) {
            TrackKind kind = trackKindOf(source);
            String name = source.variantName();
            TrackVariantStore.invalidate(kind, name);
            TemplateSidecars.invalidateCaches(BuilderPhotoPaths.Kind.TRACK, kind.id(), name);
            switch (source) {
                case Template.Track t -> TrackEditor.stampPlot(level, name, dims);
                case Template.Pillar p -> PillarEditor.stampPlot(level, p.section(), name, dims);
                case Template.Adjunct a -> PillarEditor.stampPlotAdjunct(level, a.adjunct(), name, dims);
                case Template.Tunnel t -> TunnelEditor.stampPlot(level, t.variant(), name);
                default -> { }
            }
        }
    };

    /** The track-side kind a template's variants live under. */
    static TrackKind trackKindOf(Template source) {
        return switch (source) {
            case Template.Track t -> TrackKind.TILE;
            case Template.Pillar p -> TrackPlotLocator.pillarKind(p.section());
            case Template.Adjunct a -> PillarTemplateStore.adjunctKind(a.adjunct());
            case Template.Tunnel t -> TrackPlotLocator.tunnelKind(t.variant());
            case Template.PortalRoom r -> TrackKind.PORTAL_ROOM;
            default -> throw new IllegalArgumentException("not a track-side template: " + source.id());
        };
    }

    /** The same kind of track-side template as {@code source}, called {@code name}. */
    private static Template withName(Template source, String name) {
        return switch (source) {
            case Template.Track t -> new Template.Track(name);
            case Template.Pillar p -> new Template.Pillar(p.section(), name);
            case Template.Adjunct a -> new Template.Adjunct(a.adjunct(), name);
            case Template.Tunnel t -> new Template.Tunnel(t.variant(), name);
            case Template.PortalRoom r -> new Template.PortalRoom(name);
            default -> throw new IllegalArgumentException("not a track-side template: " + source.id());
        };
    }

    /** Restamp every plot of {@code kind} — what a new name in the kind's row needs. */
    private static void restampKind(ServerLevel level, TrackKind kind, CarriageDims dims) {
        switch (kind) {
            case TILE -> TrackEditor.stampPlot(level, dims);
            case PILLAR_TOP -> PillarEditor.stampPlot(level, PillarSection.TOP, dims);
            case PILLAR_MIDDLE -> PillarEditor.stampPlot(level, PillarSection.MIDDLE, dims);
            case PILLAR_BOTTOM -> PillarEditor.stampPlot(level, PillarSection.BOTTOM, dims);
            case TUNNEL_SECTION -> TunnelEditor.stampPlot(level, TunnelPlacer.TunnelVariant.SECTION);
            case TUNNEL_PORTAL -> TunnelEditor.stampPlot(level, TunnelPlacer.TunnelVariant.PORTAL);
            case ADJUNCT_STAIRS -> PillarEditor.stampPlot(level, PillarAdjunct.STAIRS, dims);
            case PORTAL_ROOM -> PortalRoomEditor.stampAllPlots(level, dims);
        }
    }

    private static void teleportTo(ServerPlayer player, ServerLevel level, TrackKind kind, String name,
                                   CarriageDims dims) {
        BlockPos origin = TrackSidePlots.plotOrigin(kind, name, dims);
        if (origin == null) return;
        Vec3i fp = TrackSidePlots.footprint(kind, dims);
        player.teleportTo(level, origin.getX() + fp.getX() / 2.0, origin.getY() + 1.0,
            origin.getZ() + fp.getZ() / 2.0, player.getYRot(), player.getXRot());
    }

    // ---- dimensional carriages (portal rooms) ----

    private static final Adapter PORTAL_ROOM = new Adapter() {
        @Override public String categoryId(Template source) { return "portals"; }

        @Override public Optional<Component> validate(Template source, String name) {
            return TRACK_SIDE.validate(source, name);
        }

        /** {@link PortalRoomEditor#relayout} carries every plot it moves live, so nothing reloads. */
        @Override public List<Template> reloaded(Template source, String name) {
            return List.of();
        }

        @Override public List<Path> userFiles(Template source) {
            String name = ((Template.PortalRoom) source).name();
            return withSidecars(TrackVariantStore.fileFor(TrackKind.PORTAL_ROOM, name),
                BuilderPhotoPaths.Kind.PORTAL_ROOM, TrackKind.PORTAL_ROOM.id(), name);
        }

        @Override public Template copy(ServerPlayer player, Template source, String name) throws IOException {
            String from = ((Template.PortalRoom) source).name();
            ServerLevel level = player.serverLevel().getServer().overworld();
            CarriageDims dims = games.brennan.dungeontrain.world.DungeonTrainWorldData.get(level).dims();
            var template = PortalRoomTemplateStore.get(level, from, dims)
                .orElseThrow(() -> new IOException("'" + from + "' has no saved room to copy."));
            // A sub-variant's copy joins its parent's group — membership first, since it decides
            // where the plot lands; see runPortalRoomGroupNew.
            Optional<String> parent = TrackVariantGroupStore.findParentOf(TrackKind.PORTAL_ROOM, from);
            if (parent.isPresent()) {
                var group = TrackVariantGroupStore.get(TrackKind.PORTAL_ROOM, parent.get())
                    .orElse(games.brennan.dungeontrain.track.variant.TrackVariantGroup.EMPTY);
                TrackVariantGroupStore.save(TrackKind.PORTAL_ROOM, parent.get(), group.withMember(
                    new games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member(
                        name, games.brennan.dungeontrain.track.variant.TrackVariantGroup.DEFAULT_WEIGHT)));
            }
            PortalRoomTemplateStore.save(name, template);
            TemplateCopy.copy(BuilderPhotoPaths.Kind.PORTAL_ROOM, TrackKind.PORTAL_ROOM.id(), from, name);
            Vec3i size = games.brennan.dungeontrain.portal.PortalRoomSizes.sizeOf(from, dims);
            PortalRoomEditor.relayout(level, dims, () -> {
                games.brennan.dungeontrain.portal.PortalRoomSizes.pending(name, size);
                TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, name);
            });
            PortalRoomEditor.enter(player, name);
            return new Template.PortalRoom(name);
        }

        @Override public void restoreAndRestamp(ServerLevel level, Template source, CarriageDims dims) {
            String name = ((Template.PortalRoom) source).name();
            TrackVariantStore.invalidate(TrackKind.PORTAL_ROOM, name);
            TemplateSidecars.invalidateCaches(BuilderPhotoPaths.Kind.PORTAL_ROOM, TrackKind.PORTAL_ROOM.id(), name);
            // Measured again from the restored file on the next ask; the save settled it at the
            // edited size.
            games.brennan.dungeontrain.portal.PortalRoomSizes.forget(name);
            PortalRoomEditor.resetToSaved(level, name, dims);
        }
    };

    /** A frame is one structure plus its variant sidecar, both under {@code chunk_frames/}. */
    private static final Adapter CHUNK_FRAME = new Adapter() {
        @Override public String categoryId(Template source) { return null; }

        @Override public Optional<Component> validate(Template source, String name) {
            if (!games.brennan.dungeontrain.portal.chunkframe.ChunkFrameRegistry.NAME.matcher(name).matches()) {
                return invalid(name);
            }
            if (games.brennan.dungeontrain.portal.chunkframe.ChunkFrameRegistry.names().contains(name)) {
                return taken(name);
            }
            return Optional.empty();
        }

        @Override public List<Template> reloaded(Template source, String name) {
            return List.of();
        }

        @Override public List<Path> userFiles(Template source) {
            String name = ((Template.ChunkFrame) source).name();
            return List.of(games.brennan.dungeontrain.portal.chunkframe.ChunkFrameStore.fileFor(name),
                games.brennan.dungeontrain.portal.chunkframe.ChunkFrameVariants.configPathFor(name),
                games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMetaStore.configPathFor(name));
        }

        @Override public Template copy(ServerPlayer player, Template source, String name) throws IOException {
            String from = ((Template.ChunkFrame) source).name();
            ServerLevel level = player.serverLevel().getServer().overworld();
            var tag = games.brennan.dungeontrain.portal.chunkframe.ChunkFrameStore.readTag(from)
                .orElseThrow(() -> new IOException("'" + from + "' has no saved frame to copy."));
            boolean toSource = EditorDevMode.isEnabled();
            games.brennan.dungeontrain.portal.chunkframe.ChunkFrameStore.save(name, tag, toSource);
            var variants = games.brennan.dungeontrain.track.variant.TrackVariantBlocks.copyOf(
                games.brennan.dungeontrain.portal.chunkframe.ChunkFrameVariants.loadFor(from));
            games.brennan.dungeontrain.portal.chunkframe.ChunkFrameVariants.save(name, variants, toSource);
            games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMetaStore.save(name,
                games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMetaStore.get(from), toSource);
            ChunkFrameEditor.enter(player, level, name, null);
            return new Template.ChunkFrame(name);
        }

        @Override public void restoreAndRestamp(ServerLevel level, Template source, CarriageDims dims) {
            String name = ((Template.ChunkFrame) source).name();
            games.brennan.dungeontrain.portal.chunkframe.ChunkFrameVariants.clearCache();
            games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMetaStore.clearCache();
            ChunkFrameEditor.restamp(level, name);
        }
    };
}
