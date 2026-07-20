package com.devc.minecraftempires.state;

public enum StateTier { //as object is only one state at a time, we'll use enums as variables can be adjusted as player progresses
    NOMADIC   (0,      0,     0,  0),   //for nomadic states; cannot field armies
    COUNTY    (1024,   0,     0,  1),   //for counties, requires 1 Town
    DUCHY     (1024,   0,     1,  1),   //for duchies, requires 1 City
    CITY_STATE(16384,  0,     1,  2),   //for city-states, requires 1 City, 2 Towns
    KINGDOM   (65536,  5000,  0,  5),  //for kingdoms, removes city limits
    STATE     (147456, 15000, 5,  8),  //for states, massive economy
    REPUBLIC  (262144, 30000, 1,  40),  //for republics, senate unlocked (Capital City req)
    EMPIRE    (262144, 30000, 1,  28);  //for empires, same requirements as republic

    private final int minChunks;
    private final int minPopulation;
    private final int minCities;
    private final int maxLegions;

    //constructor
    StateTier(int minChunks, int minPopulation, int minCities, int maxLegions) {
        this.minChunks     = minChunks;
        this.minPopulation = minPopulation;
        this.minCities     = minCities;
        this.maxLegions    = maxLegions;
    }

    //getters
    public int getMinChunks(){ 
        return minChunks; 
    }
    public int getMinPopulation(){ 
        return minPopulation; 
    }
    public int getMinCities(){ 
        return minCities; 
    }

    //gets the maximum number of legions allowed for the state tier
    public int getMaxLegions() {
        return maxLegions;
    }

    //helper to check if state is an empire
    public boolean isPermanent() {
        return this == EMPIRE;
    }
}