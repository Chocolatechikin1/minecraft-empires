package com.devc.minecraftempires.army;

//class that tracks the equipment tier of a cohort
/* STONE  — starting equipment; raw recruits
 * IRON   — veteran equipment; requires 500 XP
 * DIAMOND — elite equipment; requires 1500 XP */
public enum GearTier {
    STONE(0),
    IRON(500),
    DIAMOND(1500);

    private final int xpThreshold;

    GearTier(int xpThreshold) {
        this.xpThreshold = xpThreshold;
    }

    public int getXpThreshold() {
        return xpThreshold;
    }

    //gets the highest GearTier for a given XP value
    public static GearTier fromXp(int xp) {
        GearTier[] values = values();
        for (int i = values.length - 1; i >= 0; i--) {
            if (xp >= values[i].xpThreshold) {
                return values[i];
            }
        }
        return STONE;
    }

    //gets the next tier
    public GearTier next() {
        GearTier[] values = values();
        int idx = this.ordinal();
        return idx < values.length - 1 ? values[idx + 1] : this;
    }

    //defines if the current tier is the maximum tier
    public boolean isMaxTier() {
        return this == DIAMOND;
    }
}
