package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.discord.SharedBookReporter;
import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.event.SharedBookGate;
import games.brennan.dungeontrain.narrative.BookFactory;
import games.brennan.dungeontrain.narrative.DeathNoteSigning;
import games.brennan.dungeontrain.narrative.DeathNoteTitle;
import games.brennan.dungeontrain.narrative.LetterLecternEvents;
import games.brennan.dungeontrain.narrative.LetterSigning;
import games.brennan.dungeontrain.narrative.PlayerWrittenBookTag;
import games.brennan.dungeontrain.narrative.SharedBookMessage;
import games.brennan.dungeontrain.narrative.SharedBookTag;
import games.brennan.dungeontrain.narrative.SignedCarriageTag;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import games.brennan.dungeontrain.platform.DtAttachments;

/**
 * Community shared books — CONTRIBUTION half. Intercepts vanilla book signing so a signed book &amp;
 * quill is <b>uploaded</b> to the Dungeon Train relay and <b>burned away</b> in the player's hand
 * instead of becoming a kept written book.
 *
 * <p>Target: {@code ServerGamePacketListenerImpl#signBook(FilteredText, List&lt;FilteredText&gt;, int)}
 * (1.21.1 Mojmap; the {@code slot} is the player's inventory slot holding the writable book &amp; quill).
 * At HEAD, when {@link SharedBookGate#canContribute} passes, we:</p>
 * <ol>
 *   <li>fire-and-forget upload the raw title + author + pages to the relay,</li>
 *   <li>remove the writable book from the player's slot,</li>
 *   <li>build a plain written book, stamp it {@link SharedBookTag} ("burn me"), and drop it — the
 *       existing {@code StartingBookEvents.onEntityJoinLevel} handler animates + consumes it, and</li>
 *   <li>cancel vanilla signing so no written book is ever placed back in the slot.</li>
 * </ol>
 *
 * <p>When the gate fails (feature off, or the client hasn't granted network consent), we return
 * without cancelling — vanilla signs the book normally and the player keeps it. The whole body is
 * wrapped no-throw: any failure before {@code ci.cancel()} logs and returns without cancelling, so a
 * bug here degrades to vanilla signing rather than dropping the player's packet handler.</p>
 *
 * <p>Independent of the above: every signed book — either branch — is stamped with the carriage index
 * the signer was standing in ({@link SignedCarriageTag}), via a carriage index captured at HEAD and
 * consumed either inline (community branch) or by a second {@code @At("RETURN")} injector once vanilla
 * finishes signing (the branch where the player actually keeps the book).</p>
 *
 * <p>That second, vanilla-signing branch is also where {@link PlayerWrittenBookTag} gets stamped —
 * unconditionally, regardless of carriage — so a book you write and keep (rather than contribute) still
 * burns once it's been read, the same as starting/random books. See
 * {@link games.brennan.dungeontrain.narrative.BurnableBookTag}.</p>
 */
@Mixin(net.minecraft.server.network.ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplSignBookMixin {

    @Shadow public ServerPlayer player;

    private static final Logger DUNGEONTRAIN$LOGGER = LogUtils.getLogger();

    /**
     * Carriage index ({@link TrainCarriageAppender#lastCarriageIndex}) captured at the top of
     * {@link #dungeontrain$interceptSignBook}, consumed by {@link #dungeontrain$stampVanillaSignedBook}
     * once vanilla finishes signing. One {@code ServerGamePacketListenerImpl} per connection, packets
     * processed sequentially on the server thread, so a plain field is safe here.
     */
    private Integer dungeontrain$pendingSignCarriage;

    @Inject(method = "signBook", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$interceptSignBook(FilteredText title, List<FilteredText> pages, int slot,
                                                CallbackInfo ci) {
        ServerPlayer earlyPlayer = this.player;
        this.dungeontrain$pendingSignCarriage = earlyPlayer != null
                ? TrainCarriageAppender.lastCarriageIndex(earlyPlayer.getUUID())
                : null;
        try {
            ServerPlayer serverPlayer = this.player;
            if (serverPlayer == null) return;

            // Only intercept a real writable book & quill in the target slot — mirrors vanilla's own
            // WRITABLE_BOOK_CONTENT guard so a spoofed slot index can't make us burn something else.
            // (Read-only; validated ahead of either branch below.)
            ItemStack writable = serverPlayer.getInventory().getItem(slot);
            if (writable.isEmpty() || !writable.has(DataComponents.WRITABLE_BOOK_CONTENT)) {
                return;
            }

            String titleStr = title.raw();
            String author = serverPlayer.getName().getString();
            List<String> pageStrs = pages.stream().map(FilteredText::raw).toList();

            // Lectern letter — the sign was opened from a lectern (LetterLecternEvents recorded it on the
            // right-click). A lectern sign is always a letter, taking priority over the Death Note /
            // shared-book branches: route to the per-life narrative-series upload + burn at the lectern.
            GlobalPos pendingLectern = LetterLecternEvents.consumePending(serverPlayer.getUUID());
            if (pendingLectern != null) {
                LetterSigning.handleSigning(serverPlayer, pendingLectern, titleStr, author, pageStrs, writable);
                ci.cancel();
                DUNGEONTRAIN$LOGGER.debug("[DungeonTrain] Letter: {} signed a lectern letter", author);
                return;
            }

            // Death Note curse — a book titled "Death Note" (any caps/spacing) is a personal + relay
            // mechanic, NOT a community contribution: it runs independently of the shared-book consent
            // gate and never uploads its text. Handled locally; vanilla signing is cancelled.
            if (DeathNoteTitle.isDeathNoteTitle(titleStr)
                    && games.brennan.dungeontrain.config.DungeonTrainConfig.isDeathNotesEnabled()) {
                DeathNoteSigning.handleSigning(serverPlayer, titleStr, author, pageStrs, writable);
                ci.cancel();
                DUNGEONTRAIN$LOGGER.debug("[DungeonTrain] DeathNote: {} signed a Death Note", author);
                return;
            }

            // Community shared book — gated on feature flag + client network consent. Gate fails →
            // let vanilla sign normally (player keeps the written book, no upload, no burn).
            if (!SharedBookGate.canContribute(serverPlayer)) return;

            // Fire-and-forget upload of the authored text (no-throw internally). The author's client
            // language (vanilla-synced ClientInformation, "" when unknown) is stamped so the relay can
            // store it for language-matched delivery.
            String lang = WorldInfoReporter.clientLanguage(serverPlayer);
            SharedBookReporter.submit(serverPlayer.getUUID(), author, titleStr, pageStrs, lang);

            // Remove one writable book from the player's slot — they keep nothing.
            writable.shrink(1);

            // Build the burn book and drop it; the EntityJoinLevelEvent burn handler registers it.
            ItemStack bookStack = BookFactory.buildPlainBook(titleStr, author, pageStrs);
            SharedBookTag.stamp(bookStack);
            if (this.dungeontrain$pendingSignCarriage != null) {
                SignedCarriageTag.stamp(bookStack, this.dungeontrain$pendingSignCarriage);
            }
            this.dungeontrain$pendingSignCarriage = null;
            serverPlayer.drop(bookStack, /*dropAround*/ false, /*includeThrowerName*/ false);

            // A gray "sent into the void" chat line as it burns — same style as the offline @dev reply.
            serverPlayer.sendSystemMessage(SharedBookMessage.random(serverPlayer.getRandom()));

            // One-shot "signed a book" advancement — fires regardless of whether the fire-and-forget
            // upload above eventually succeeds; signing is the local action being rewarded here.
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(serverPlayer, "signed_shared_book");

            // Count it for the death-screen "books written" cargo icon (per-run tally).
            DtAttachments.PLAYER_RUN_STATE.get(serverPlayer).incrementBooksWritten();

            ci.cancel();
            DUNGEONTRAIN$LOGGER.debug("[DungeonTrain] SharedBook: {} signed a book — uploaded + dropped to burn",
                    author);
        } catch (Throwable t) {
            // Anything went wrong before we cancelled: do NOT cancel — fall through to vanilla signing
            // so the player at least keeps a normal book rather than losing it to a half-run intercept.
            DUNGEONTRAIN$LOGGER.warn("[DungeonTrain] SharedBook: sign interception failed, falling back to vanilla: {}",
                    t.toString());
        }
    }

    /**
     * Fires after vanilla's own {@code signBook} body runs — only reached when the HEAD injector above
     * did NOT cancel (i.e. the vanilla-signing branch: the community-contribution gate failed, or the
     * HEAD injector's own try block threw before reaching {@code ci.cancel()}). Stamps whatever ended up
     * in {@code slot} — if vanilla actually put a written book there — with {@link PlayerWrittenBookTag}
     * (so it burns after being read, see {@link games.brennan.dungeontrain.narrative.BurnableBookTag})
     * and, when a carriage index was captured at HEAD, with {@link SignedCarriageTag}. The two stamps
     * are independent: the carriage stamp is a no-op when the player wasn't near a train at sign time,
     * but the burn-after-read stamp always applies to a real signed book regardless.
     */
    @Inject(method = "signBook", at = @At("RETURN"))
    private void dungeontrain$stampVanillaSignedBook(FilteredText title, List<FilteredText> pages, int slot,
                                                      CallbackInfo ci) {
        Integer carriage = this.dungeontrain$pendingSignCarriage;
        this.dungeontrain$pendingSignCarriage = null;

        ServerPlayer serverPlayer = this.player;
        if (serverPlayer == null) return;

        ItemStack signed = serverPlayer.getInventory().getItem(slot);
        if (signed.isEmpty() || !signed.has(DataComponents.WRITTEN_BOOK_CONTENT)) return;

        PlayerWrittenBookTag.stamp(signed);

        if (carriage != null) {
            SignedCarriageTag.stamp(signed, carriage);
        }
    }
}
