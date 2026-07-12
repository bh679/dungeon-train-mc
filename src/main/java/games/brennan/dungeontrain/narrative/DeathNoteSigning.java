package games.brennan.dungeontrain.narrative;

import com.mojang.authlib.GameProfile;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.world.PendingDeathNotes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

import java.util.List;
import java.util.Optional;

/**
 * Sign-time handling for a "Death Note" curse book (invoked from the sign mixin). Runs entirely
 * locally: it consumes the writable book &amp; quill and drops a black, soul-burning written book
 * (cosmetic — see {@link DeathNoteBookTag} + {@code StartingBookEvents}), then records a PENDING
 * curse naming the target. The curse only reaches the relay when the author later dies
 * ({@code DeathNoteEvents}), because "the carriage the author died at" isn't known until then.
 */
public final class DeathNoteSigning {

    /** CUSTOM_MODEL_DATA value selecting the black book model (assets/minecraft/models/item/written_book.json). */
    public static final int BLACK_BOOK_MODEL_DATA = 1;

    private DeathNoteSigning() {}

    /**
     * Handle a confirmed Death Note signing. The sign mixin has already validated {@code writable}
     * as a real writable book in the player's slot and cancels vanilla signing after this returns.
     */
    public static void handleSigning(ServerPlayer player, String title, String author,
                                     List<String> pages, ItemStack writable) {
        String targetName = DeathNoteTitle.firstLineTarget(pages);

        // "A Death Note" advancement — granted the moment a Death Note is signed, regardless of
        // whether a valid target name was written (title match is already caps/space-insensitive via
        // DeathNoteTitle.isDeathNoteTitle). Signing the note is the rewarded action.
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "submitted_death_note");

        // Consume the writable book & quill — the player keeps nothing (as when signing a shared book).
        writable.shrink(1);

        // Drop the cursed book: it burns with the SOUL variant and renders black while it burns.
        ItemStack book = BookFactory.buildPlainBook(title, author, pages);
        DeathNoteBookTag.stamp(book);
        book.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(BLACK_BOOK_MODEL_DATA));
        player.drop(book, /*dropAround*/ false, /*includeThrowerName*/ false);

        // Count it as a written book for the death-screen cargo tally (a book was authored + signed).
        ModDataAttachments.DT_PLAYER_RUN_STATE.get(player).incrementBooksWritten();

        if (targetName == null || targetName.isBlank()) {
            player.sendSystemMessage(Component.literal("The Death Note finds no name; it burns to nothing.")
                .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        // Self-curse: allowed in every build. Your own next life becomes the target — grant the
        // "Self-Fulfilling Prophecy" advancement for the discovery, then let the curse record normally.
        if (targetName.equalsIgnoreCase(author)) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "deathnote_self_target");
        }

        String targetUuid = resolveTargetUuid(player.getServer(), targetName);
        PendingDeathNotes.get(player.serverLevel())
            .add(new PendingDeathNotes.PendingDeathNote(player.getUUID(), author, targetName, targetUuid));

        player.sendSystemMessage(
            Component.literal("The Death Note takes ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(targetName).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(". When you fall, your echo will hunt them.")
                    .withStyle(ChatFormatting.DARK_GRAY)));
    }

    /**
     * Build the keepable "Death Note" trophy an echo drops when it dies: a black-textured written
     * book (CUSTOM_MODEL_DATA) crediting the author. Deliberately NOT stamped {@link DeathNoteBookTag}
     * so it does <em>not</em> soul-burn — the player keeps it.
     */
    public static ItemStack buildTrophyBook(String author) {
        String safeAuthor = author == null || author.isBlank() ? "Unknown" : author;
        ItemStack book = BookFactory.buildPlainBook("Death Note", safeAuthor,
            List.of("Torn from the echo of " + safeAuthor + "."));
        book.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(BLACK_BOOK_MODEL_DATA));
        return book;
    }

    /**
     * Best-effort resolve of {@code name} to a dash-stripped UUID via online players then the server
     * profile cache; "" when unknown (the relay still matches the note by target name, case-
     * insensitively, so an unresolved UUID is not fatal — it just hardens matching across renames).
     */
    private static String resolveTargetUuid(MinecraftServer server, String name) {
        if (server == null) return "";
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID().toString().replace("-", "");
        GameProfileCache cache = server.getProfileCache();
        Optional<GameProfile> profile = cache == null ? Optional.empty() : cache.get(name);
        return profile.map(p -> p.getId().toString().replace("-", "")).orElse("");
    }
}
