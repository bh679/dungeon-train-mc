package games.brennan.dungeontrain.player;

import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.level.storage.ExposureIdentifier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A nap carries the whole life: the codec fields ride in the player tag, and {@link
 * PlayerRunState#napExtras} carries every other field. Plus the unique-chest streak, which keeps its
 * count across a nap but not the napped world's positions.
 */
final class PlayerRunStateNapExtrasTest {

    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    /** Every instance field, sorted into what carries it. A new field fails here until it is placed. */
    private static final Set<String> IN_CODEC = Set.of("uniqueChests", "cartsSinceDeath",
        "cartsBackwardSinceDeath", "travelledCarriageIndex", "mobKills", "distanceBlocks", "containersOpened",
        "booksReadCount", "weaponKills", "playerKills", "damageDealt", "damageTaken", "encounteredMobs",
        "befriendedMobs", "trainTimeTicks");
    private static final Set<String> IN_NAP_EXTRAS = Set.of("echoesKilled", "carriagesSinceChest",
        "maxCarriagesNoChest", "killedMobs", "narrativeLetters", "booksWrittenCount", "ownShelfBoostPending",
        "deathNotesWritten", "loveNotesWritten", "photosTaken", "photosTributed", "photosPassengers",
        "photosHostile", "photosAnimals", "photoShots", "photosViewed", "tributesMade", "tributeEmeralds",
        "liveStreamSeconds", "liveWatchSeconds", "friendAppearance", "friendFeeling", "friendUuid",
        "killedAppearance", "earnedAdvancements", "servedBookToCarriage", "tamedAnimals", "tamedCount",
        "cameraFrames", "napGateX", "napGateDir", "reachMin", "reachMax");
    /** A library room of the napped world, which is deleted: see {@link PlayerRunState#napExtras}. */
    private static final Set<String> SKIPPED = Set.of("lastOwnShelfPair");

    @Test
    @DisplayName("every run-state field is carried by the codec or the nap extras, or skipped on purpose")
    void everyFieldIsPlaced() {
        Set<String> fields = Arrays.stream(PlayerRunState.class.getDeclaredFields())
            .filter(f -> !Modifier.isStatic(f.getModifiers()))
            .map(Field::getName)
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> placed = new TreeSet<>(IN_CODEC);
        placed.addAll(IN_NAP_EXTRAS);
        placed.addAll(SKIPPED);
        assertEquals(placed, fields);
    }

    private static PlayerMobAppearance appearance(String name, ItemStack mainHand) {
        return new PlayerMobAppearance(3, "https://example.test/skin.png", true, name,
            mainHand, ItemStack.EMPTY, new ItemStack(Items.IRON_HELMET), ItemStack.EMPTY,
            ItemStack.EMPTY, new ItemStack(Items.LEATHER_BOOTS));
    }

    private static Frame frame(String id) {
        return Frame.EMPTY.toMutable().setIdentifier(ExposureIdentifier.id(id)).toImmutable();
    }

    private static PlayerRunState lived() {
        PlayerRunState s = new PlayerRunState();
        s.incrementEchoesKilled();
        s.incrementEchoesKilled();
        s.recordCartMovement(10);
        s.openedLootContainer();          // carriagesSinceChest 0, max 10
        s.recordCartMovement(4);          // carriagesSinceChest 4
        UUID friend = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID killed = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        s.recordBefriended(friend);
        s.recordKilledPlayerMob(killed);
        s.captureFriendAppearance(friend, appearance("Pal", new ItemStack(Items.DIAMOND_SWORD)), 8.5f);
        s.setKilledAppearance(appearance("Foe", new ItemStack(Items.BOW, 1)));
        s.recordNarrativeRead("story_a#2");
        s.incrementBooksWritten();
        s.incrementBooksWritten();
        s.incrementDeathNotesWritten();
        s.incrementLoveNotesWritten();
        s.incrementLoveNotesWritten();
        s.recordPhotoTaken(true, true, false);
        s.recordPhotoTaken(false, false, true);
        s.incrementPhotosTributed();
        s.incrementPhotoShots();
        s.incrementPhotoShots();
        s.incrementPhotoShots();
        s.incrementPhotosViewed();
        s.recordTribute(5);
        s.recordTribute(2);
        s.addLiveStreamSeconds(90);
        s.addLiveWatchSeconds(45);
        s.recordEarnedAdvancement(ResourceLocation.parse("dungeontrain:first_carriage"));
        s.recordEarnedAdvancement(ResourceLocation.parse("dungeontrain:echo_killer"));
        s.markServed(42, -17);
        s.recordTame(ResourceLocation.parse("minecraft:wolf"));
        s.recordTame(ResourceLocation.parse("minecraft:cat"));
        s.recordCameraFrame(frame("dt_photo_1"));
        s.recordCameraFrame(frame("dt_photo_2"));
        return s;
    }

    private static PlayerRunState wake(PlayerRunState napped) {
        CompoundTag extras = napped.napExtras(registries);
        PlayerRunState woken = PlayerRunState.CODEC.parse(NbtOps.INSTANCE,
            PlayerRunState.CODEC.encodeStart(NbtOps.INSTANCE, napped).getOrThrow()).getOrThrow();
        woken.applyNapExtras(extras, registries);
        return woken;
    }

    @Test
    @DisplayName("nap extras round trip: every non-codec field comes back")
    void napExtrasRoundTrip() {
        PlayerRunState woken = wake(lived());

        assertEquals(2, woken.echoesKilled());
        assertEquals(10, woken.maxCarriagesNoChest());
        woken.recordCartMovement(7); // 4 carried + 7 = 11 beats the carried high-water mark of 10
        assertEquals(11, woken.maxCarriagesNoChest(), "carriagesSinceChest was carried");

        UUID killed = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        assertFalse(woken.recordBefriended(killed), "a mob killed before the nap stays un-befriendable");
        assertEquals(1, woken.befriendedCount());

        PlayerMobAppearance friend = woken.friendAppearance();
        assertNotNull(friend);
        assertEquals("Pal", friend.name());
        assertEquals(3, friend.skinIndex());
        assertTrue(friend.slim());
        assertEquals("https://example.test/skin.png", friend.skinTextureUrl());
        assertTrue(ItemStack.matches(new ItemStack(Items.DIAMOND_SWORD), friend.mainHand()));
        assertTrue(ItemStack.matches(new ItemStack(Items.IRON_HELMET), friend.head()));
        assertTrue(ItemStack.matches(new ItemStack(Items.LEATHER_BOOTS), friend.feet()));
        assertTrue(friend.offHand().isEmpty());
        assertEquals(8.5f, woken.friendFeeling());
        // friendUuid came too: killing that friend drops the portrait.
        woken.recordKilledPlayerMob(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
        assertNull(woken.friendAppearance());

        assertNotNull(woken.killedAppearance());
        assertEquals("Foe", woken.killedAppearance().name());
        assertTrue(ItemStack.matches(new ItemStack(Items.BOW), woken.killedAppearance().mainHand()));

        assertFalse(woken.recordNarrativeRead("story_a#2"), "the letter was already read this life");
        assertEquals(2, woken.booksWrittenCount());
        assertTrue(woken.ownShelfBoostPending());
        assertEquals(1, woken.deathNotesWritten());
        assertEquals(2, woken.loveNotesWritten());
        assertEquals(2, woken.photosTaken());
        assertEquals(1, woken.photosTributed());
        assertEquals(1, woken.photosPassengers());
        assertEquals(1, woken.photosHostile());
        assertEquals(1, woken.photosAnimals());
        assertEquals(3, woken.photoShots());
        assertEquals(1, woken.photosViewed());
        assertEquals(2, woken.tributesMade());
        assertEquals(7, woken.tributeEmeralds());
        assertEquals(90L, woken.liveStreamSeconds());
        assertEquals(45L, woken.liveWatchSeconds());
        assertEquals(List.of(ResourceLocation.parse("dungeontrain:first_carriage"),
            ResourceLocation.parse("dungeontrain:echo_killer")), woken.earnedAdvancements());
        assertTrue(woken.wasServed(42));
        assertEquals(-17, woken.servedCarriage(42));
        assertEquals(2, woken.tamedCount());
        assertEquals(List.of(ResourceLocation.parse("minecraft:wolf"), ResourceLocation.parse("minecraft:cat")),
            woken.tamedAnimals());
        assertEquals(List.of(frame("dt_photo_1"), frame("dt_photo_2")), woken.cameraFrames());
    }

    @Test
    @DisplayName("the own-books library visited before the nap is not remembered: the next one spends the boost")
    void lastOwnShelfIsNotCarried() {
        PlayerRunState napped = new PlayerRunState();
        napped.visitOwnShelves(12);
        napped.incrementBooksWritten(); // pending again
        PlayerRunState woken = wake(napped);
        assertTrue(woken.ownShelfBoostPending());
        woken.visitOwnShelves(12);
        assertFalse(woken.ownShelfBoostPending(), "room 12 of the new world is a new visit");
    }

    @Test
    @DisplayName("an empty or missing extras tag changes nothing")
    void emptyExtrasIsANoOp() {
        PlayerRunState s = new PlayerRunState();
        s.incrementEchoesKilled();
        s.applyNapExtras(new CompoundTag(), registries);
        s.applyNapExtras(null, registries);
        assertEquals(1, s.echoesKilled());
    }

    @Test
    @DisplayName("a fresh life's extras round trip to a fresh life")
    void freshRoundTrip() {
        PlayerRunState woken = wake(new PlayerRunState());
        assertNull(woken.friendAppearance());
        assertNull(woken.killedAppearance());
        assertEquals(Float.NEGATIVE_INFINITY, woken.friendFeeling());
        assertTrue(woken.cameraFrames().isEmpty());
        assertEquals(0, woken.tamedCount());
        assertTrue(woken.napExtras(registries).getList("cameraFrames", Tag.TAG_COMPOUND).isEmpty());
    }

    // --- unique-chest streak ----------------------------------------------------------------------

    @Test
    @DisplayName("a nap keeps the chest streak's count but forgets the napped world's positions")
    void chestStreakKeepsCountForgetsPositions() {
        PlayerRunState s = new PlayerRunState();
        BlockPos a = new BlockPos(10, 64, 20);
        BlockPos b = new BlockPos(-5, 70, 15);
        s.addChestPos(a);
        s.addChestPos(b);
        s.forgetChestPositionsKeepingCount();

        assertEquals(2, s.chestStreak());
        assertEquals(2, s.uniqueChestsCarried());
        assertFalse(s.uniqueChests().contains(a));
        assertTrue(s.addChestPos(a), "the same position in the new world is a new chest");
        assertEquals(3, s.chestStreak());
        assertEquals(2, s.uniqueChestsCarried());
    }

    @Test
    @DisplayName("the carried chest count survives a relog through the codec")
    void carriedChestsSurviveCodec() {
        PlayerRunState s = new PlayerRunState();
        for (int i = 0; i < 5; i++) s.addChestPos(new BlockPos(i, 64, 0));
        s.forgetChestPositionsKeepingCount();
        s.addChestPos(new BlockPos(100, 65, 0));
        PlayerRunState reloaded = PlayerRunState.CODEC.parse(NbtOps.INSTANCE,
            PlayerRunState.CODEC.encodeStart(NbtOps.INSTANCE, s).getOrThrow()).getOrThrow();
        assertEquals(6, reloaded.chestStreak());
        assertEquals(5, reloaded.uniqueChestsCarried());
        assertFalse(reloaded.addChestPos(new BlockPos(100, 65, 0)), "the new world's chest is remembered");
    }

    @Test
    @DisplayName("a streak reset drops the carried count too, and a second nap carries the combined count")
    void resetDropsCarriedAndSecondNapCombines() {
        PlayerRunState s = new PlayerRunState();
        s.addChestPos(new BlockPos(1, 64, 1));
        s.addChestPos(new BlockPos(2, 64, 2));
        s.forgetChestPositionsKeepingCount();
        s.addChestPos(new BlockPos(3, 64, 3));
        s.forgetChestPositionsKeepingCount(); // a second nap: 2 carried + 1 new = 3
        assertEquals(3, s.chestStreak());
        assertEquals(3, s.uniqueChestsCarried());

        s.clearChests();                      // what AchievementEvents does on a duplicate open
        s.addChestPos(new BlockPos(3, 64, 3));
        assertEquals(1, s.chestStreak());
        assertEquals(0, s.uniqueChestsCarried());

        s.forgetChestPositionsKeepingCount();
        s.resetAll();                         // a death
        assertEquals(0, s.chestStreak());
        assertEquals(0, s.uniqueChestsCarried());
    }

    @Test
    @DisplayName("without a nap the streak behaves as before: nothing carried")
    void noNapNothingCarried() {
        PlayerRunState s = new PlayerRunState();
        s.addChestPos(new BlockPos(1, 64, 1));
        assertEquals(0, s.uniqueChestsCarried());
        assertFalse(s.addChestPos(new BlockPos(1, 64, 1)));
        assertEquals(1, s.chestStreak());
    }
}
