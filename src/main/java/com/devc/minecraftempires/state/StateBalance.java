package com.devc.minecraftempires.state;

/** Initial playtest values, deliberately centralized rather than embedded in UI or handlers. */
//TODO: modify or merge this file into another file, or make it configurable via a JSON file or similar.
public final class StateBalance {
    private StateBalance() {}
    public static final long TICKS_PER_DAY = 24_000L;
    public static final long INTRODUCTORY_TICKS = 14 * TICKS_PER_DAY;
    public static final int SETTLEMENT_RADIUS = 100;
    public static final int CAMPAIGN_RADIUS = 250;
    public static final int STARTING_POPULATION = 100;
    public static final int TAX_PER_RESIDENT = 6;
    public static final int SETTLEMENT_UPKEEP_PER_TIER = 50;
    public static final int DAILY_MINIMUM_GROWTH = 20;
    public static final int CITY_POPULATION = 500;
    public static final int CITY_UPGRADE_COST = 1_000;
    public static final int MAXIMUM_SETTLEMENT_TIER = 2;
    public static final int CAPTURE_IMMUNITY_TICKS = 24_000;
}
