package games.brennan.dungeontrain.client.menu;

import net.minecraft.resources.ResourceLocation;

/**
 * An icon button in the breadcrumb band of a menu panel — see {@link MenuScreen#headerAction()}.
 *
 * @param icon    a 16px GUI sprite, e.g. {@code dungeontrain:icon/save}
 * @param label   what the icon means; shown as its hover tooltip, since the icon carries no text
 * @param command the bare command (no leading slash) sent through {@link CommandRunner} on click.
 *                The menu stays open afterwards, as a toolbar button would leave it.
 */
public record MenuHeaderAction(ResourceLocation icon, String label, String command) {}
