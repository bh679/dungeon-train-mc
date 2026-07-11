package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.discord.LetterReporter;
import games.brennan.dungeontrain.event.PlayerLocaleMirror;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Sign-time handling for a player-written lectern letter (invoked from the sign mixin when the sign
 * originated from a lectern — see {@link LetterLecternEvents#consumePending}). Assigns the letter its
 * place in the player's current-life series, uploads it to the relay, consumes the book &amp; quill,
 * and spawns a burnable copy at the lectern so the existing burn lifecycle animates it away.
 *
 * <p>Unlike a Death Note (local + delayed) this is a straightforward upload, mirroring the shared-book
 * contribution: the letter is authored by the player with the title they typed in the sign screen
 * (falling back to "Letter X" when left blank).</p>
 */
public final class LetterSigning {

    private LetterSigning() {}

    /**
     * Handle a confirmed letter signing. The sign mixin has already validated {@code writable} as a
     * real writable book in the player's slot and cancels vanilla signing after this returns.
     *
     * @param lectern the lectern the sign screen was opened from — where the burn is spawned
     * @param title   the player's typed sign title ("Letter X" fallback when blank)
     * @param author  the author to credit (the signing player's name)
     */
    public static void handleSigning(ServerPlayer player, GlobalPos lectern, String title, String author,
                                     List<String> pages, ItemStack writable) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        long deaths = GlobalPlayerStats.totalDeaths(player.getUUID());
        LetterSeries series = NarrativeProgressData.get(server.overworld()).nextLetter(player.getUUID(), deaths);
        String finalTitle = title == null || title.isBlank() ? "Letter " + series.letterIndex() : title;

        // Fire-and-forget upload of the authored letter as the next entry in the life-series (no-throw).
        // The author's client language (synced on login, null when unknown) is stamped for the relay.
        String lang = PlayerLocaleMirror.get(player);
        LetterReporter.submit(player.getUUID(), series.seriesId(), series.letterIndex(), author, finalTitle, pages, lang);

        // One-shot "wrote a letter" advancement — signing the letter is the rewarded action.
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "wrote_letter");

        // Consume the writable book & quill — the player keeps nothing (as when signing a shared book).
        writable.shrink(1);

        // Build the burn copy and spawn it AT the lectern; the EntityJoinLevelEvent burn handler
        // registers it (LetterBookTag → BurnableBookTag). No owner is set, so it never counts toward
        // the "burned unread" milestone.
        ItemStack book = BookFactory.buildPlainBook(finalTitle, author, pages);
        LetterBookTag.stamp(book);
        ServerLevel lecternLevel = server.getLevel(lectern.dimension());
        if (lecternLevel == null) lecternLevel = player.serverLevel();
        BlockPos p = lectern.pos();
        ItemEntity entity = new ItemEntity(lecternLevel, p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, book);
        lecternLevel.addFreshEntity(entity);

        // Leave the lectern visibly EMPTY. In the normal flow the book was never placed server-side
        // (vanilla placement is suppressed so the book can be signed from the hand), but the signing
        // client PREDICTED a placement onto the empty lectern — re-assert the true server state to the
        // signer so that phantom book clears. Also clear a real book & quill draft if one is resting on
        // a plain lectern. A narrative lectern keeps its own (mod story) state: re-asserting its current
        // state is a no-op, and the plain-lectern guard skips clearing its book.
        BlockState lstate = lecternLevel.getBlockState(p);
        if (lstate.getBlock() instanceof LecternBlock) {
            if (lstate.is(Blocks.LECTERN)
                    && lecternLevel.getBlockEntity(p) instanceof LecternBlockEntity le && le.hasBook()) {
                le.setBook(ItemStack.EMPTY);
                le.setChanged();
                lecternLevel.setBlock(p, lstate
                        .setValue(LecternBlock.HAS_BOOK, Boolean.FALSE)
                        .setValue(LecternBlock.POWERED, Boolean.FALSE), 3);
            }
            player.connection.send(new ClientboundBlockUpdatePacket(p, lecternLevel.getBlockState(p)));
        }

        // Count it for the death-screen "books written" cargo tally (a book was authored + signed).
        player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).incrementBooksWritten();

        player.sendSystemMessage(Component.literal("\"" + finalTitle + "\" is sealed and sent — Letter "
                + series.letterIndex() + " of this life.").withStyle(ChatFormatting.GRAY));
    }
}
