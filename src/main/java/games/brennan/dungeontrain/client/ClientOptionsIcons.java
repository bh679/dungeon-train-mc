package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.SettingsRowList.Icon;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The icon each row of {@link DungeonTrainClientOptionsScreen} wears, so a tab can be scanned by
 * picture before it is read.
 *
 * <p>Every one is a vanilla item, a vanilla GUI sprite or a sprite the mod already ships — nothing
 * new to draw, and the same two draw paths ({@code renderItem} / {@code blitSprite}) the X menu and
 * the pause-menu icon row already use. The choices are the obvious object for each setting (a book
 * for the book line, a bundle for the backpack button, a minecart for the train's catch-up), and any
 * of them can be swapped here without touching the screen.</p>
 *
 * <p>Kept apart from {@link ClientOptionsTab} on purpose: that enum stays free of Minecraft types so
 * its row lists can be tested headlessly.</p>
 */
@OnlyIn(Dist.CLIENT)
final class ClientOptionsIcons {

    // Vanilla GUI sprites and their natural sizes (they are not all 16px).
    private static final Icon NEWS = Icon.sprite(ResourceLocation.withDefaultNamespace("icon/news"), 14);
    private static final Icon LANGUAGE = Icon.sprite(ResourceLocation.withDefaultNamespace("icon/language"), 15);
    private static final Icon ACCESSIBILITY = Icon.sprite(ResourceLocation.withDefaultNamespace("icon/accessibility"), 15);
    private static final Icon SEARCH = Icon.sprite(ResourceLocation.withDefaultNamespace("icon/search"), 12);
    private static final Icon FREE_PLAY = Icon.sprite(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/free_play"), 16);
    private static final Icon FILTER = Icon.sprite(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/filter"), 16);

    /** The Discord link row on the Account tab. */
    static final Icon DISCORD_LINK = Icon.sprite(ResourceLocation.withDefaultNamespace("icon/invite"), 14);
    /** The master "all pings" switch and each kind of ping. */
    static final Icon PING = Icon.item(Items.BELL);
    /** How a donation is announced. */
    static final Icon DONATION = Icon.item(Items.EMERALD);
    /** Whether the announcement tags the player's Discord. */
    static final Icon DONATION_TAG = Icon.item(Items.NAME_TAG);

    private ClientOptionsIcons() {}

    /** The icon for one row. Rows the screen expands into several ({@code DISCORD_ACCOUNT}, {@code PERFORMANCE_TIPS}) have none. */
    static Icon of(ClientOptionsTab.Row row) {
        return switch (row) {
            case DIFFICULTY -> Icon.item(Items.IRON_SWORD);
            case CONTENT_MODE -> Icon.item(Items.CAKE);
            case DEV_CHAT -> Icon.item(Items.WRITABLE_BOOK);
            case POLITICAL_FILTER -> Icon.item(Items.BARRIER);
            case BOOK_AUTHOR_CHAT -> Icon.item(Items.BOOK);
            case UPDATE_NOTICE_CHAT -> NEWS;
            case LOW_MEMORY_NOTICE_CHAT -> Icon.item(Items.REDSTONE);
            case OTHER_ADVANCEMENT_TABS -> Icon.item(Items.EXPERIENCE_BOTTLE);
            case CINEMATIC_HOTKEY -> Icon.item(Items.SPYGLASS);
            case DEV_HUD -> Icon.item(Items.CLOCK);
            case BACKPACK_BUTTON -> Icon.item(games.brennan.ediblebackpacks.registry.ModItems.EDIBLE_BACKPACK.get());
            case BACKPACK_AUTO_COLLECT -> Icon.item(Items.HOPPER);
            case BOOK_FILE_BUTTONS -> Icon.item(Items.KNOWLEDGE_BOOK);
            case BOOK_INSERT_PAGE_BUTTON -> Icon.item(Items.PAPER);
            case AI_POLICY -> Icon.item(Items.PAPER); // the same paper AiPolicyIconButton carries
            case TRANSLATE -> LANGUAGE;
            case BOOK_LANGUAGES -> Icon.item(Items.BOOKSHELF);

            case BACKUPS -> Icon.item(Items.CHEST);
            case BACKUPS_PER_VERSION -> Icon.item(Items.HOPPER);
            case CLEAR_BACKUPS -> Icon.item(Items.LAVA_BUCKET);
            case CONFIRM_BUILD_RESTORE -> Icon.item(Items.WRITABLE_BOOK);

            case TRAIN_VOLUME -> Icon.item(Items.NOTE_BLOCK);
            case CUSTOM_CONTENT -> FREE_PLAY;
            case SNAPSHOT_MAX_RES -> Icon.item(Items.PAINTING);
            case SNAPSHOT_CHAT_LOG -> Icon.item(Items.PAPER);
            case CATCH_UP_BURST -> Icon.item(Items.MINECART);

            case LIVESTREAM -> Icon.item(ModItems.LIVE_HEADPIECE.get());
            case STREAM_GUI -> Icon.item(Items.ITEM_FRAME);
            case STREAM_CHAT -> Icon.item(Items.OAK_SIGN);
            case STREAM_AUDIO -> Icon.item(Items.JUKEBOX);
            case STREAM_NAME_TAG -> Icon.item(Items.NAME_TAG);
            case LIVE_CHAT -> Icon.item(Items.BELL);
            case LIVE_FEED_VIEWING -> Icon.item(Items.ENDER_EYE);
            case STREAM_SIZE -> Icon.item(Items.MAP);
            case HEAD_VIEWER_SIZE -> Icon.item(Items.GLASS);

            case SCALE_ALL -> ACCESSIBILITY;
            case SCALE_WORLDSPACE -> Icon.item(Items.FILLED_MAP);
            case SCALE_HUD -> Icon.item(Items.COMPASS);
            case MENU_SPACE_COMMAND -> Icon.item(Items.LECTERN);
            case MENU_SPACE_TEMPLATE_BLOCKS -> Icon.item(Items.BOOKSHELF);
            case MENU_SPACE_CONTAINER_CONTENTS -> Icon.item(Items.ENDER_CHEST);
            case MENU_SPACE_BLOCK_VARIANT -> Icon.item(Items.CRAFTING_TABLE);
            case CREATIVE_MOD_BLOCK_TABS -> FILTER;
            case CREATIVE_MOD_BLOCKS_IN_SEARCH -> SEARCH;

            case DISCORD_ACCOUNT, PERFORMANCE_TIPS ->
                    throw new IllegalArgumentException(row + " is expanded into several rows and has no icon of its own");
        };
    }
}
