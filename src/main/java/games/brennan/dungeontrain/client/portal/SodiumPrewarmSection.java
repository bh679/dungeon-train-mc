package games.brennan.dungeontrain.client.portal;

/**
 * What the Sodium prewarm needs to know about one of Sodium's render sections, asked without naming
 * Sodium's type.
 *
 * <p>Sodium is not a compile dependency, so DT code cannot hold a {@code RenderSection}. It can hold
 * an {@code Object} that happens to implement this — which every {@code RenderSection} does once
 * {@code RenderSectionPrewarmMixin} has applied — and ask the one question the prewarm has: is this
 * section still waiting for its first build?</p>
 */
public interface SodiumPrewarmSection {

    /**
     * Whether this section has a build pending that nothing has queued yet.
     *
     * <p>Built or not: a dimensional carriage is placed by the server into chunks the client already
     * holds, so Sodium registered its sections while they were still air and marked them built-empty;
     * the room then arrives as block updates, which Sodium stamps as a pending <em>rebuild</em>. Both
     * a first build and that rebuild wait for the occlusion walk the same way, and both are what the
     * prewarm is for. False while a build is running: submitting a job clears the pending update, so
     * a section is offered to the queue once per build rather than once per frame.</p>
     */
    boolean dungeontrain$wantsBuild();
}
