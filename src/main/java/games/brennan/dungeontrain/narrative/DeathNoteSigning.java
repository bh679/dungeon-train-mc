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
 * Sign-time handling for a signed note book — the "Death Note" curse or its mirror the "Love Note"
 * (invoked from the sign mixin). Runs entirely locally for either kind: it consumes the writable
 * book &amp; quill and drops a tinted, burning written book (cosmetic — see {@link DeathNoteBookTag}
 * / {@link LoveNoteBookTag} + {@code StartingBookEvents}), then records a PENDING note naming the
 * target. The note only reaches the relay when the author later dies ({@code DeathNoteEvents}),
 * because "the carriage the author died at" isn't known until then.
 */
public final class DeathNoteSigning {

    /** CUSTOM_MODEL_DATA value selecting the black book model (assets/minecraft/models/item/written_book.json). */
    public static final int BLACK_BOOK_MODEL_DATA = NoteKind.DEATH.modelData();

    private DeathNoteSigning() {}

    /**
     * Handle a confirmed note signing of {@code kind}. The sign mixin has already validated
     * {@code writable} as a real writable book in the player's slot and cancels vanilla signing
     * after this returns. Both kinds follow the identical flow — only the chat lines, advancements,
     * book tag and model differ, all of them read off {@code kind}.
     */
    public static void handleSigning(ServerPlayer player, NoteKind kind, String title, String author,
                                     List<String> pages, ItemStack writable) {
        String targetName = DeathNoteTitle.firstLineTarget(pages);

        // "A Death Note" / "A Love Note" advancement — granted the moment one is signed, regardless
        // of whether a valid target name was written (the title match is already caps/space-
        // insensitive via NoteKind.of). Signing the note is the rewarded action.
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, signedActionId(kind));

        // Per-run tally, for the Faulthurst stat book's twin of the notes-written leaderboards.
        // Counted here rather than at delivery for the same reason the advancement is: signing is
        // the act, and whether the note ever finds a target is somebody else's world's business.
        var run = player.getData(games.brennan.dungeontrain.registry.ModDataAttachments.PLAYER_RUN_STATE.get());
        if (kind == NoteKind.LOVE) {
            run.incrementLoveNotesWritten();
        } else {
            run.incrementDeathNotesWritten();
        }

        // Consume the writable book & quill — the player keeps nothing (as when signing a shared book).
        writable.shrink(1);

        // Drop the note: it burns with this kind's flame variant and renders tinted while it burns.
        ItemStack book = BookFactory.buildPlainBook(title, author, pages);
        if (kind == NoteKind.LOVE) {
            LoveNoteBookTag.stamp(book);
        } else {
            DeathNoteBookTag.stamp(book);
        }
        book.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(kind.modelData()));
        player.drop(book, /*dropAround*/ false, /*includeThrowerName*/ false);

        // Count it as a written book for the death-screen cargo tally (a book was authored + signed).
        player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).incrementBooksWritten();

        if (targetName == null || targetName.isBlank()) {
            player.sendSystemMessage(Component.translatable(chatKey(kind, "no_name"))
                .withStyle(bodyStyle(kind)));
            return;
        }

        // Self-naming: allowed in every build, for either kind. Your own next life becomes the
        // target — grant the discovery advancement, then let the note record normally.
        if (targetName.equalsIgnoreCase(author)) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, selfTargetActionId(kind));
        }

        // One note per target per life. The pending list holds exactly the notes signed since the
        // author last died, so a name already in it has already been noted this life — the book still
        // burns (it was written and signed), but a second echo is not armed for the same player.
        PendingDeathNotes pending = PendingDeathNotes.get(player.serverLevel());
        if (pending.hasPendingFor(player.getUUID(), targetName, kind)) {
            player.sendSystemMessage(
                Component.translatable(chatKey(kind, "already"),
                        Component.literal(targetName).withStyle(nameStyle(kind)))
                    .withStyle(bodyStyle(kind)));
            return;
        }

        String targetUuid = resolveTargetUuid(player.getServer(), targetName);
        // What the author wrote below the name travels with the note: their echo reads it aloud when
        // it reaches the target (see NoteSpokenLines + DeathNoteEchoController). A note that is only
        // a name yields an empty script and a silent echo, as every note was before this.
        pending.add(new PendingDeathNotes.PendingDeathNote(player.getUUID(), author, targetName,
                targetUuid, kind, NoteSpokenLines.fromPages(pages)));

        player.sendSystemMessage(
            Component.translatable(chatKey(kind, "taken"),
                    Component.literal(targetName).withStyle(nameStyle(kind)))
                .withStyle(bodyStyle(kind)));
    }

    /** Colour the target's name is written in for {@code kind}'s sign-time chat lines. */
    private static ChatFormatting nameStyle(NoteKind kind) {
        return kind == NoteKind.LOVE ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY;
    }

    /** Colour the surrounding line is written in for {@code kind}'s sign-time chat lines. */
    private static ChatFormatting bodyStyle(NoteKind kind) {
        return kind == NoteKind.LOVE ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY;
    }

    /** Advancement action id granted for signing a note of {@code kind}. */
    private static String signedActionId(NoteKind kind) {
        return kind == NoteKind.LOVE ? "submitted_love_note" : "submitted_death_note";
    }

    /** Advancement action id granted for naming yourself in a note of {@code kind}. */
    private static String selfTargetActionId(NoteKind kind) {
        return kind == NoteKind.LOVE ? "lovenote_self_target" : "deathnote_self_target";
    }

    /** Translation key {@code chat.dungeontrain.<kind>.<suffix>} for this kind's sign-time chat lines. */
    private static String chatKey(NoteKind kind, String suffix) {
        return "chat.dungeontrain." + kind.englishTitle() + "." + suffix;
    }

    /**
     * Build the keepable trophy book an echo of {@code kind} drops when it dies: a tinted written
     * book (CUSTOM_MODEL_DATA) crediting the author. Deliberately NOT stamped with either note tag
     * so it does <em>not</em> burn — the player keeps it.
     */
    public static ItemStack buildTrophyBook(String author, NoteKind kind) {
        String safeAuthor = author == null || author.isBlank() ? "Unknown" : author;
        String page = kind == NoteKind.LOVE
            ? "Left behind by the echo of " + safeAuthor + "."
            : "Torn from the echo of " + safeAuthor + ".";
        ItemStack book = BookFactory.buildPlainBook(kind.trophyTitle(), safeAuthor, List.of(page));
        book.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(kind.modelData()));
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
