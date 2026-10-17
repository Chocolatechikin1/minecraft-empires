package com.devc.minecraftempires.state;

/** Initial playtest values, deliberately centralized rather than embedded in UI or handlers. */
//TODO: modify or merge this file into another file, or make it configurable via a JSON file or similar.
public final class StateBalance {
    private StateBalance() {}
    public static final long TICKS_PER_DAY = 24_000L;
    public static final long INTRODUCTORY_TICKS = 14 * TICKS_PER_DAY;
    public static final int SETTLEMENT_RADIUS = 64;
    public static final int LEGACY_SETTLEMENT_RADIUS = 100;
    public static final int CAMPAIGN_RADIUS = 250;
    public static final int POST_BATTLE_RETREAT_DISTANCE = 200;
    public static final double ROUT_SPEED_MULTIPLIER = 1.25;
    public static final int STARTING_POPULATION = 100;
    public static final int TAX_PER_RESIDENT = 6;
    public static final int SETTLEMENT_UPKEEP_PER_TIER = 50;
    public static final int DAILY_MINIMUM_GROWTH = 20;
    public static final int CITY_POPULATION = 500;
    public static final int CITY_UPGRADE_COST = 1_000;
    public static final int MAXIMUM_SETTLEMENT_TIER = 2;
    public static final int CAPTURE_IMMUNITY_TICKS = 24_000;

    // Sprint 7 logistics playtest values; distances are horizontal blocks unless noted.
    public static final int SUPPLY_RANGE = 1_024;
    public static final int FINAL_DELIVERY_RANGE = 512;
    public static final int CAMP_SUPPLY_RANGE = 512;
    public static final int CARRIED_SUPPLY_DAYS = 7;
    public static final int SUPPLY_INTERCEPTION_RANGE = 50;
    public static final double ROAD_SPEED_MULTIPLIER = 1.5;
    public static final double ROAD_TRANSPORT_MULTIPLIER = 0.5;
    public static final int ROAD_COST_PER_CHUNK = 5;
    public static final int CAMP_COST = 250;
    public static final int CAMP_STOCK_CAPACITY = 1_100; // One unit feeds one soldier for one day.
    public static final int CAMP_DAILY_REFILL = 550;
    public static final int CAMP_OPERATION_RANGE = 16;
    public static final int CAMP_MIN_SPACING = 128;
    public static final int MAX_CAMPS = 64;
    public static final int MAX_ROAD_POINTS = 64;
    public static final int MAX_ROAD_CHUNKS = 1_024;
    public static final int SUPPLY_MORALE_PENALTY = 5;
    public static final int SUPPLY_FATIGUE_PER_DAY = 10;
    public static final int MAX_SUPPLY_FATIGUE = 40;
    public static final int SUPPLY_FATIGUE_RECOVERY = 10;
    public static final long CAPTURED_SUPPLY_PREPARATION_TICKS = TICKS_PER_DAY;
}
