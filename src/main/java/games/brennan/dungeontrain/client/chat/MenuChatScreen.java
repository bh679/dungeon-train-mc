package games.brennan.dungeontrain.client.chat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The dev conversation in its own window, opened from the title screen's envelope button (see
 * {@link MenuChatButtonHandler}). Hosts a standalone {@link ChatMessageList} — full opacity, live send
 * box, no title-logo z-order games — over the plain menu background, with a vanilla Done button.
 *
 * <p>Opening the window is what "reads" the messages: the relay inbox is <b>drained</b> here (cursor
 * advances — once per screen instance, surviving resize re-inits), which clears the title screen's
 * unread popup for next time; individual messages still earn their 👀 as they scroll into view. While
 * the window is open, {@link MenuChatLivePoll} streams new replies straight into the list.</p>
 */
public final class MenuChatScreen extends Screen {

    private static final int LIST_MAX_WIDTH = 340;
    private static final int LIST_TOP = 24;
    private static final int LIST_BOTTOM_MARGIN = 40; // clears the Done row (height-28, 20 tall)

    private final Screen parent;
    private final AtomicReference<String> threadId = new AtomicReference<>();
    private ChatMessageList list;
    private UUID uuid;
    private boolean drained; // drain once per open — init() re-fires on resize on the SAME instance
    // The two async halves of a visit, applied together once both have landed (MenuChatMerge): a
    // relay-only note (whitelist approval, outdated build) exists only in the drain, never in the
    // Discord history, so applying the history alone would show "offline" over a consumed note.
    private ChatHistory history;
    private boolean historyDone;
    private ChatInbox inbox;
    private boolean inboxDone = true; // false only while the one drain per open is in flight

    public MenuChatScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.menu_chat.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Minecraft mc = this.minecraft;
        uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;

        int w = Math.min(LIST_MAX_WIDTH, this.width - 40);
        int h = this.height - LIST_TOP - LIST_BOTTOM_MARGIN;
        ChatMessageList l = new ChatMessageList((this.width - w) / 2, LIST_TOP, w, h, true);
        this.list = l;
        l.setOnSeen(m -> {
            String tid = threadId.get();
            if (tid != null) {
                // Through the receipts queue: the ✅ for a just-arrived message fires a frame earlier,
                // and two unspaced reaction PUTs on one message lose the second to Discord's rate limit.
                ChatReceipts.markSeen(uuid, tid, m.id());
            }
        });
        // Submit → echo locally now, deliver via the durable outbox (immediate when online, queued and
        // flushed on the next open/launch when the relay is unreachable).
        l.setOnSubmit(text -> {
            String name = mc != null && mc.getUser() != null ? mc.getUser().getName() : "Me";
            l.appendOutgoing(name, text);
            ChatOutbox.get().submit(uuid, text);
        });
        addRenderableWidget(l);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());
        setInitialFocus(l);

        // Flush anything queued from a prior offline session as soon as the window opens.
        ChatOutbox.get().flush();

        historyDone = false;
        RelayChatClient.fetchHistory(uuid).thenAcceptAsync(fetched -> {
            if (fetched != null) {
                // ✅ the fetched messages regardless of which init owns the list — the data arrived.
                ChatReceipts.markLoaded(uuid, fetched.threadId(), fetched.messages());
            }
            if (this.list != l) {
                return; // resized while in flight — the newer init's fetch owns the current list
            }
            this.history = fetched;
            this.historyDone = true;
            applyVisit(l);
        }, mc);

        // Reading happens here: drain the inbox (advances the relay's delivery cursor), which clears the
        // title screen's unread popup for next time. The "+N" badge shows how many were new this visit.
        if (!drained) {
            drained = true;
            inboxDone = false;
            RelayChatClient.drainInbox(uuid).thenAcceptAsync(drainedNow -> {
                if (drainedNow != null) {
                    ChatReceipts.markLoaded(uuid, drainedNow.threadId(), drainedNow.messages());
                }
                this.inbox = drainedNow;
                this.inboxDone = true;
                if (this.list == l) {
                    applyVisit(l);
                }
            }, mc);
        }
    }

    /** Once the history fetch and the drain have both answered, show them as one list. */
    private void applyVisit(ChatMessageList l) {
        if (!historyDone || !inboxDone) {
            return;
        }
        ChatHistory merged = MenuChatMerge.merge(history, inbox);
        if (merged == null) {
            l.setStatus(Component.translatable("gui.dungeontrain.menu_chat.offline"));
        } else {
            threadId.set(merged.threadId());
            l.setHistory(merged);
        }
        if (inbox != null) {
            l.setUnread(inbox.unread());
        }
    }

    @Override
    public void tick() {
        // drain=true: a reply arriving while the window is open is shown immediately, so it's read —
        // advancing the cursor keeps the title screen's unread popup from resurrecting it after close.
        MenuChatLivePoll.poll(uuid, true, inbox -> {
            ChatReceipts.markLoaded(uuid, inbox.threadId(), inbox.messages());
            if (list != null && inbox.messages() != null) {
                for (ChatHistory.Message m : inbox.messages()) {
                    list.appendInbound(m);
                }
            }
        });
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
