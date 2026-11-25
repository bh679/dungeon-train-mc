package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.entity.PhotographFrameBlockEntity;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.LeasePhoto;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.ExposureServer;
import io.github.mortuusars.exposure.data.ColorPalettes;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.item.component.StackedPhotographs;
import io.github.mortuusars.exposure.world.item.component.album.AlbumContent;
import io.github.mortuusars.exposure.world.item.component.album.AlbumPage;
import io.github.mortuusars.exposure.world.item.component.album.SignedAlbumContent;
import io.github.mortuusars.exposure.world.item.component.album.SignedAlbumPage;
import io.github.mortuusars.exposure.world.level.storage.ExposureData;
import io.github.mortuusars.exposure.world.level.storage.ExposureIdentifier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Photos that travel with a drifting build — a carriage or a dimensional carriage's room.
 *
 * <p>A photograph only names its picture; the pixels stay in the world that took it. So every upload
 * of a build lists the community photos it holds ({@link #refsIn}), and the relay keeps those
 * pictures — even past their last view, when they leave the pool — until the build lets go of them.
 * A world that leases the build {@link #adopt adopts} each photo it has no picture for: the stack is
 * pointed at the relay's copy, tagged like any found photo, and the picture is downloaded.</p>
 *
 * <p>A photo is known to the relay by its id (a found photo) or by the upload key its photographer's
 * print was sent with (their own print, whose id the print never learns). A Free Play print carries
 * neither, was never shared, and stays blank in other worlds.</p>
 */
public final class CarriagePhotos {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The most photos one build reports — the relay keeps the same cap. */
    public static final int MAX_REFS = 64;

    /** How deep {@link #visitHeld} looks into held items (a print in an album in a box in a bundle…). */
    static final int MAX_DEPTH = 6;

    /** Exposure's id for {@code Exposure.DataComponents.PHOTOGRAPH_FRAME}, as a saved item's components name it. */
    static final String PHOTOGRAPH_FRAME_KEY = Exposure.ID + ":photograph_frame";

    /** Ids whose picture is being downloaded, so a build leased twice in a row asks once. */
    private static final Set<Integer> fetching = new HashSet<>();

    private CarriagePhotos() {}

    /** A community photo as a build reports it: its relay {@code id}, or else its upload {@code key}. */
    public record Ref(int id, String key) {
        public Ref {
            if (key == null) key = "";
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            if (id > 0) o.addProperty("id", id);
            else o.addProperty("key", key);
            return o;
        }
    }

    // ---- what a build holds ---------------------------------------------------

    /** The relay's name for the photo whose frame carries {@code extraData}, if it has one. */
    static Optional<Ref> refOf(CompoundTag extraData) {
        int id = extraData.getInt(SharedPhotos.SHARED_ID_KEY);
        if (id > 0) return Optional.of(new Ref(id, ""));
        String key = extraData.getString(SharedPhotos.UPLOAD_KEY);
        return key.isBlank() ? Optional.empty() : Optional.of(new Ref(0, key));
    }

    /** The list an upload carries: every ref once, at most {@link #MAX_REFS}. */
    public static JsonArray toJson(Collection<Ref> refs) {
        JsonArray out = new JsonArray();
        refs.stream().distinct().limit(MAX_REFS).forEach(ref -> out.add(ref.toJson()));
        return out;
    }

    /** Every community photo inside {@code tag} — a snapshot, an entity list, an item — at any depth. */
    public static Set<Ref> refsIn(Tag tag, HolderLookup.Provider registries) {
        Set<Ref> refs = new LinkedHashSet<>();
        visitPhotos(tag, registries, stack -> {
            refOfStack(stack).ifPresent(refs::add);
            return stack;
        });
        return refs;
    }

    /** Every community photo a live block entity holds: a photo frame's picture, a container's items. */
    public static Set<Ref> refsIn(BlockEntity be, HolderLookup.Provider registries) {
        Set<Ref> refs = new LinkedHashSet<>();
        if (be instanceof PhotographFrameBlockEntity frame) {
            addRefs(frame.photo(), registries, refs);
        } else if (be instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) addRefs(container.getItem(i), registries, refs);
        }
        return refs;
    }

    /**
     * What an upload of a whole build lists: the photos in its live {@code blockEntities} and in the
     * entity list the upload carries ({@code ents} — the item frames hanging in it).
     */
    public static JsonArray uploadList(List<BlockEntity> blockEntities, ListTag ents, HolderLookup.Provider registries) {
        Set<Ref> refs = new LinkedHashSet<>();
        for (BlockEntity be : blockEntities) refs.addAll(refsIn(be, registries));
        if (ents != null) refs.addAll(refsIn(ents, registries));
        return toJson(refs);
    }

    private static void addRefs(ItemStack stack, HolderLookup.Provider registries, Set<Ref> refs) {
        visitHeld(stack, registries, s -> refOfStack(s).ifPresent(refs::add), 0);
    }

    /** Every community photo {@code stack} is or holds. Package-private for tests. */
    static Set<Ref> refsIn(ItemStack stack, HolderLookup.Provider registries) {
        Set<Ref> refs = new LinkedHashSet<>();
        addRefs(stack, registries, refs);
        return refs;
    }

    /**
     * Hand {@code visit} every photograph {@code stack} is or holds, read straight off its components:
     * a stack of prints, an album, a shulker box or bundle, at any depth up to {@link #MAX_DEPTH}. An
     * ordinary item is a few component lookups; nothing is serialised. Only item data that is already
     * NBT (custom or block-entity data) is walked as NBT. Package-private for tests.
     */
    static void visitHeld(ItemStack stack, HolderLookup.Provider registries, Consumer<ItemStack> visit, int depth) {
        if (stack.isEmpty() || depth > MAX_DEPTH) return;
        if (stack.has(Exposure.DataComponents.PHOTOGRAPH_FRAME)) visit.accept(stack);
        int next = depth + 1;
        StackedPhotographs stacked = stack.get(Exposure.DataComponents.STACKED_PHOTOGRAPHS);
        if (stacked != null) for (ItemStack s : stacked.photographs()) visitHeld(s, registries, visit, next);
        AlbumContent album = stack.get(Exposure.DataComponents.ALBUM_CONTENT);
        if (album != null) for (AlbumPage p : album.pages()) visitHeld(p.photograph(), registries, visit, next);
        SignedAlbumContent signed = stack.get(Exposure.DataComponents.SIGNED_ALBUM_CONTENT);
        if (signed != null) for (SignedAlbumPage p : signed.pages()) visitHeld(p.photograph(), registries, visit, next);
        ItemContainerContents box = stack.get(DataComponents.CONTAINER);
        if (box != null) for (ItemStack s : box.nonEmptyItems()) visitHeld(s, registries, visit, next);
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) for (ItemStack s : bundle.items()) visitHeld(s, registries, visit, next);
        visitData(stack.get(DataComponents.CUSTOM_DATA), registries, visit);
        visitData(stack.get(DataComponents.BLOCK_ENTITY_DATA), registries, visit);
    }

    private static void visitData(CustomData data, HolderLookup.Provider registries, Consumer<ItemStack> visit) {
        if (data == null || data.isEmpty()) return;
        visitPhotos(data.getUnsafe(), registries, s -> {
            visit.accept(s);
            return s;
        });
    }

    private static Optional<Ref> refOfStack(ItemStack stack) {
        Frame frame = stack.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        return frame == null ? Optional.empty() : refOf(frame.extraData());
    }

    // ---- leasing a build ------------------------------------------------------

    /**
     * {@code snapshot} with every photo this world has no picture for pointed at the relay's copy, and
     * those pictures sent for. A photo the relay did not list, or that this world already shows, is
     * left exactly as it came. Server thread: it reads this world's Exposure storage.
     */
    public static CompoundTag adopt(CompoundTag snapshot, List<LeasePhoto> photos, MinecraftServer server,
                                    HolderLookup.Provider registries) {
        if (server == null || snapshot == null) return snapshot;
        String base = DungeonTrain.relayBaseUrl();
        if (base == null || base.isBlank()) return snapshot;
        CompoundTag copy = snapshot.copy();
        visitPhotos(copy, registries, stack -> adoptStack(stack, photos, server, base));
        return copy;
    }

    private static ItemStack adoptStack(ItemStack stack, List<LeasePhoto> photos, MinecraftServer server, String base) {
        Frame frame = stack.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        if (frame == null || !frame.identifier().isId()) return stack;
        if (SharedPhotos.peekExposure(server, frame.identifier().id()).isPresent()) return stack;
        Optional<Ref> ref = refOf(frame.extraData());
        if (ref.isEmpty()) return stack;
        Optional<LeasePhoto> listed = match(ref.get(), photos);
        int id = ref.get().id() > 0 ? ref.get().id() : listed.map(LeasePhoto::id).orElse(0);
        if (id <= 0) return stack;
        String author = authorFor(frame, listed);
        Frame adopted = frame.toMutable()
                .setIdentifier(ExposureIdentifier.id(SharedPhotos.exposureIdFor(base, id)))
                .updateExtraData(extra -> adoptInto(extra, id, listed.orElse(null), author))
                .toImmutable();
        ItemStack out = stack.copy();
        out.set(Exposure.DataComponents.PHOTOGRAPH_FRAME, adopted);
        // A photograph carries an empty lore component by default, so look at its lines, not its presence.
        if (!author.isBlank() && out.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().isEmpty()) {
            out.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.translatable("item.dungeontrain.shared_photo.by", author)
                            .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)))));
        }
        fetchImage(server, base, id, listed.map(LeasePhoto::image).orElse(""));
        return out;
    }

    /** The relay's entry for {@code ref}: matched by id, or for a photographer's own print by upload key. */
    static Optional<LeasePhoto> match(Ref ref, List<LeasePhoto> photos) {
        if (photos == null) return Optional.empty();
        return photos.stream()
                .filter(p -> ref.id() > 0 ? p.id() == ref.id() : !ref.key().isBlank() && ref.key().equals(p.key()))
                .findFirst();
    }

    private static String authorFor(Frame frame, Optional<LeasePhoto> listed) {
        String shared = frame.extraData().getString(SharedPhotos.SHARED_AUTHOR_KEY);
        if (!shared.isBlank()) return shared;
        String fromRelay = listed.map(LeasePhoto::author).orElse("");
        return fromRelay.isBlank() ? frame.photographer().name() : fromRelay;
    }

    /**
     * Tag {@code extra} as the found photo {@code id}: what the relay said about it, the credited
     * author, and no upload key — a copy in someone else's world must not speak for its photographer.
     */
    static void adoptInto(CompoundTag extra, int id, LeasePhoto listed, String author) {
        extra.putInt(SharedPhotos.SHARED_ID_KEY, id);
        if (author != null && !author.isBlank()) extra.putString(SharedPhotos.SHARED_AUTHOR_KEY, author);
        extra.remove(SharedPhotos.UPLOAD_KEY);
        if (listed == null) return;
        if (listed.viewsLeft() >= 0) extra.putInt(SharedPhotos.SHARED_VIEWS_LEFT_KEY, listed.viewsLeft());
        if (listed.views() >= 0) extra.putInt(SharedPhotos.SHARED_VIEWS_KEY, listed.views());
        if (listed.maxViews() >= 0) extra.putInt(SharedPhotos.SHARED_MAX_VIEWS_KEY, listed.maxViews());
        if (listed.tributes() >= 0) extra.putInt(SharedPhotos.SHARED_TRIBUTES_KEY, listed.tributes());
    }

    /** Download photo {@code id}'s picture into this world unless it is here already or on its way. */
    private static void fetchImage(MinecraftServer server, String base, int id, String cdnUrl) {
        String exposureId = SharedPhotos.exposureIdFor(base, id);
        if (SharedPhotos.peekExposure(server, exposureId).isPresent()) return;
        synchronized (fetching) {
            if (!fetching.add(id)) return;
        }
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        // The relay's CDN copy when the lease names one, else the relay's own route.
                        HttpResponse<byte[]> resp = PhotoCdn.send(SharedPhotos.HTTP, cdnUrl,
                                base + "/photos/" + id + "/image", SharedPhotos.REQUEST_TIMEOUT);
                        if (resp.statusCode() != 200) {
                            LOGGER.info("[DungeonTrain] Carriage photo {} has no picture on the relay ({}); its frame stays blank.",
                                    id, resp.statusCode());
                            return null;
                        }
                        return PhotoPngCodec.decode(resp.body());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    } catch (Exception e) {
                        LOGGER.warn("[DungeonTrain] Carriage photo {} could not be downloaded: {}", id, e.toString());
                        return null;
                    }
                })
                .thenAccept(image -> server.execute(() -> {
                    synchronized (fetching) {
                        fetching.remove(id);
                    }
                    if (image == null || SharedPhotos.peekExposure(server, exposureId).isPresent()) return;
                    // Exposure tells every client the picture changed, so a frame already in view fills in.
                    ExposureServer.exposureRepository().save(exposureId, new ExposureData(image.width(), image.height(),
                            image.pixels(), ColorPalettes.DEFAULT.location(), ExposureData.Tag.EMPTY));
                }));
    }

    // ---- the walk -------------------------------------------------------------

    /**
     * Hand every photograph stack inside {@code tag} to {@code visit} and write back what it returns.
     * An item is any compound with an {@code id} and a {@code count} — block entities and entities
     * have the id but no count. A container slot keeps its {@code Slot} key across the rewrite.
     */
    private static void visitPhotos(Tag tag, HolderLookup.Provider registries, UnaryOperator<ItemStack> visit) {
        if (tag instanceof ListTag list) {
            for (Tag child : list) visitPhotos(child, registries, visit);
            return;
        }
        if (!(tag instanceof CompoundTag compound)) return;
        if (isItemTag(compound)) rewritePhoto(compound, registries, visit);
        for (String key : List.copyOf(compound.getAllKeys())) visitPhotos(compound.get(key), registries, visit);
    }

    static boolean isItemTag(CompoundTag compound) {
        return compound.contains("id", Tag.TAG_STRING) && compound.contains("count", Tag.TAG_ANY_NUMERIC)
                && compound.contains("components", Tag.TAG_COMPOUND);
    }

    /** Whether a saved item's components name a photograph's frame — the only items worth parsing. */
    static boolean isPhotoTag(CompoundTag item) {
        return item.getCompound("components").contains(PHOTOGRAPH_FRAME_KEY);
    }

    private static void rewritePhoto(CompoundTag item, HolderLookup.Provider registries, UnaryOperator<ItemStack> visit) {
        if (!isPhotoTag(item)) return;
        Optional<ItemStack> parsed = ItemStack.parse(registries, item);
        if (parsed.isEmpty() || !parsed.get().has(Exposure.DataComponents.PHOTOGRAPH_FRAME)) return;
        ItemStack out = visit.apply(parsed.get());
        if (out == parsed.get()) return;
        if (!(out.save(registries) instanceof CompoundTag saved)) return;
        item.remove("id");
        item.remove("count");
        item.remove("components");
        item.merge(saved);
    }
}
