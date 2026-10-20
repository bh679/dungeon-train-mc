package games.brennan.dungeontrain.client.links;

/**
 * A screen whose buttons depend on the relay's link list ({@link OfficialLinks}): the Contribute
 * screens' price tiers need {@code payment_checkout}, Give needs {@code rewards_pay}. When the list
 * lands after such a screen opened, {@link OfficialLinksFetcher} lays it out again, so it never
 * keeps showing the offline layout once the links are known.
 */
public interface RefreshOnLinks {
}
