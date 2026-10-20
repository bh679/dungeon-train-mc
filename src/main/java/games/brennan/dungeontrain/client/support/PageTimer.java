package games.brennan.dungeontrain.client.support;

/**
 * A screen whose page-time analytics should not count a side trip: {@link ContributePanel#openRewards}
 * pauses the host before swapping to the Community Rewards page, and that page's Done resumes it.
 */
interface PageTimer {

    void pauseTimer();

    void resumeTimer();
}
