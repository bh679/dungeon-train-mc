package games.brennan.dungeontrain.client.menu.number;

/**
 * The one place a click on a number turns into what it does. Every surface that shows an editable
 * number — the world-space panels, the X menu, the editor screen, the builder chips — resolves the
 * click into a {@link NumberGesture} and hands it here, so step-up, step-down and type behave the
 * same on all of them.
 *
 * <p>Only the typing surface differs, and the caller supplies it: the number pad
 * ({@link NumberPad}) wherever there is no better place, and the inline field over the cell inside
 * the editor screen, which keeps the author's place on the sheet.</p>
 */
public final class NumberStepper {

    /** Where a cmd-click types the value. */
    @FunctionalInterface
    public interface Typing {
        void type(NumberField field);
    }

    private NumberStepper() {}

    public static void click(NumberField field, NumberGesture gesture, Typing typing) {
        if (field == null) return;
        switch (gesture) {
            case INC -> field.inc().run();
            case DEC -> field.dec().run();
            case TYPE -> typing.type(field);
        }
    }

    /** {@link #click(NumberField, NumberGesture, Typing)} resolving the gesture from the raw click. */
    public static void click(NumberField field, int button, boolean shift, boolean cmd, Typing typing) {
        click(field, NumberGesture.resolve(button, shift, cmd), typing);
    }
}
