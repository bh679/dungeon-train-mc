package games.brennan.dungeontrain.client;

/**
 * Which heart container the health bar should draw as half a heart.
 *
 * <p>Vanilla draws {@code ceil(maxHealth / 2)} whole containers, so an odd max health (the Tank
 * perk adds half a heart per rank) shows a whole empty heart the player can never fill.
 * {@code mixin/client/GuiHalfHeartContainerMixin} draws that last container's left half only.
 */
public final class HalfHeartContainer {

    /** No container is drawn as half. */
    public static final int NONE = -1;

    private HalfHeartContainer() {
    }

    /** Index of the container to draw as half a heart, or {@link #NONE}. */
    public static int halfIndex(float maxHealth) {
        int containers = containers(maxHealth);
        if (containers <= 0) {
            return NONE;
        }
        return 2.0f * containers - maxHealth >= 1.0f ? containers - 1 : NONE;
    }

    /** Containers vanilla draws for this max health (or absorption amount). */
    public static int containers(float amount) {
        return (int) Math.ceil(amount / 2.0);
    }
}
