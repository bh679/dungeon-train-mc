package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.narrative.LetterLecternEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

/**
 * Pulls the player-authored, human-readable text out of a shared carriage's block-entities so the
 * relay can run it through moderation (see {@code CarriageBlockSnapshot.capture}). This is the "serve
 * now, scan text" half of shared-carriage moderation — the blocks themselves are opaque, but signs,
 * lecterns, named items and books carry free text a player could abuse. {@link #appendEntity} covers the
 * same ground for a carriage's ENTITIES: a named armor stand and a book in an item frame are exactly as
 * authored, and as abusable, as a sign.
 *
 * <p>Only <b>authored</b> text is collected — sign lines, book pages (book &amp; quill and signed
 * written books, incl. the title), and item <b>custom</b> names ({@link DataComponents#CUSTOM_NAME}).
 * Default item names ("Stone", "Oak Planks") are deliberately skipped: they are not player content, and
 * feeding them to the reviewer would only waste calls and risk false positives. Container contents are
 * scanned one level deep (a chest's items), not recursively into shulker boxes.</p>
 *
 * <p>Output is newline-joined and hard-capped at {@link #MAX_TOTAL_CHARS} (the relay clamps to the same
 * 65536); an empty result means "no text to moderate" and the relay stores the carriage approved
 * without spending a reviewer call.</p>
 */
public final class CarriageTextScan {

    /** Hard cap on the collected text — mirrors the relay's per-carriage {@code text} clamp. */
    public static final int MAX_TOTAL_CHARS = 65536;

    private CarriageTextScan() {}

    /**
     * Append every authored text string carried by {@code be} to {@code sb}. Handles signs, lecterns
     * (the held book), and containers (each item's custom name + book pages). Unknown block-entities
     * contribute nothing. Never throws — a malformed component is skipped.
     */
    public static void appendBlockEntity(BlockEntity be, StringBuilder sb) {
        if (be == null) return;
        try {
            if (be instanceof SignBlockEntity sign) {
                appendSign(sign.getFrontText(), sb);
                appendSign(sign.getBackText(), sb);
            } else if (be instanceof LecternBlockEntity lectern) {
                appendItem(lectern.getBook(), sb);
            } else if (be instanceof Container container) {
                int size = container.getContainerSize();
                for (int i = 0; i < size; i++) {
                    appendItem(container.getItem(i), sb);
                }
            }
        } catch (Throwable ignored) {
            // Best-effort: a single weird block-entity must never break a whole carriage's capture.
        }
    }

    /**
     * Append every authored text string carried by {@code entity} to {@code sb}: its custom name, the
     * items it holds or wears, and the contents of an entity container (a chest minecart). Anything the
     * carriage's blocks would have been scanned for, an entity can carry too — capturing entities without
     * this would open an unscanned channel straight past moderation. Never throws.
     */
    public static void appendEntity(Entity entity, StringBuilder sb) {
        if (entity == null) return;
        try {
            Component custom = entity.getCustomName();
            if (custom != null) append(sb, custom.getString());

            if (entity instanceof ItemEntity item) appendItem(item.getItem(), sb);
            if (entity instanceof ItemFrame frame) appendItem(frame.getItem(), sb);
            // Armour + hands for anything that wears gear; an armor stand's whole content is its outfit.
            if (entity instanceof LivingEntity living) {
                for (ItemStack stack : living.getAllSlots()) appendItem(stack, sb);
            }
            if (entity instanceof Container container) {
                int size = container.getContainerSize();
                for (int i = 0; i < size; i++) appendItem(container.getItem(i), sb);
            }
            // A vehicle's riders are captured inside it, so their text belongs to the same carriage.
            for (Entity passenger : entity.getPassengers()) appendEntity(passenger, sb);
        } catch (Throwable ignored) {
            // Best-effort: one weird entity must never break a whole carriage's capture.
        }
    }

    private static void appendSign(SignText text, StringBuilder sb) {
        if (text == null) return;
        for (Component line : text.getMessages(false)) {
            if (line != null) append(sb, line.getString());
        }
    }

    /** Append an item's authored text (custom name + book pages/title). Package-private for tests. */
    static void appendItem(ItemStack stack, StringBuilder sb) {
        if (stack == null || stack.isEmpty()) return;

        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        if (custom != null) append(sb, custom.getString());

        // Book & quill (unsigned) — raw page strings.
        for (String page : LetterLecternEvents.readPages(stack)) {
            append(sb, page);
        }
        // Signed written book — title + rendered page components.
        WrittenBookContent written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (written != null) {
            append(sb, written.title().raw());
            for (Filterable<Component> page : written.pages()) {
                if (page != null && page.raw() != null) append(sb, page.raw().getString());
            }
        }
    }

    /** Append {@code s} (trimmed, newline-separated) unless blank or the cap is already reached. */
    private static void append(StringBuilder sb, String s) {
        if (s == null || sb.length() >= MAX_TOTAL_CHARS) return;
        String t = s.strip();
        if (t.isEmpty()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(t);
        if (sb.length() > MAX_TOTAL_CHARS) sb.setLength(MAX_TOTAL_CHARS);
    }
}
