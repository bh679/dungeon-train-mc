package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.discord.SharedBookReporter;
import games.brennan.dungeontrain.event.SharedBookGate;
import games.brennan.dungeontrain.narrative.BookFactory;
import games.brennan.dungeontrain.narrative.SharedBookTag;
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
 */
@Mixin(net.minecraft.server.network.ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplSignBookMixin {

    @Shadow public ServerPlayer player;

    private static final Logger DUNGEONTRAIN$LOGGER = LogUtils.getLogger();

    @Inject(method = "signBook", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$interceptSignBook(FilteredText title, List<FilteredText> pages, int slot,
                                                CallbackInfo ci) {
        try {
            ServerPlayer serverPlayer = this.player;
            if (serverPlayer == null) return;
            // Gate fails → let vanilla sign normally (player keeps the written book, no upload, no burn).
            if (!SharedBookGate.canContribute(serverPlayer)) return;

            // Only intercept a real writable book & quill in the target slot — mirrors vanilla's own
            // WRITABLE_BOOK_CONTENT guard so a spoofed slot index can't make us burn something else.
            ItemStack writable = serverPlayer.getInventory().getItem(slot);
            if (writable.isEmpty()
                || !writable.has(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT)) {
                return;
            }

            String titleStr = title.raw();
            String author = serverPlayer.getName().getString();
            List<String> pageStrs = pages.stream().map(FilteredText::raw).toList();

            // Fire-and-forget upload of the authored text (no-throw internally).
            SharedBookReporter.submit(serverPlayer.getUUID(), author, titleStr, pageStrs);

            // Remove one writable book from the player's slot — they keep nothing.
            writable.shrink(1);

            // Build the burn book and drop it; the EntityJoinLevelEvent burn handler registers it.
            ItemStack bookStack = BookFactory.buildPlainBook(titleStr, author, pageStrs);
            SharedBookTag.stamp(bookStack);
            serverPlayer.drop(bookStack, /*dropAround*/ false, /*includeThrowerName*/ false);

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
}
