package com.devc.minecraftempires.state;

public enum StateTier { //as object is only one state at a time, we'll use enums as variables can be adjusted as player progresses
    NOMADIC   (0,      0,     0,  0),   //for nomadic states; cannot field armies
    COUNTY    (0,      0,     0,  1),   //all founded states start as counties
    DUCHY     (0,      0,     1,  1),   //county-range territory with a city
    CITY_STATE(16384,  0,     1,  2),   //for city-states, requires 1 City, 2 Towns
    KINGDOM   (65536,  5000,  0,  5),  //for kingdoms, removes city limits
    STATE     (147456, 15000, 5,  8),  //for states, massive economy
    REPUBLIC  (262144, 15000, 5,  40),  //Final choice retains the preceding state's requirements
    EMPIRE    (262144, 15000, 5,  28); //for empires, requires 5 cities and 15k population

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
        return this == EMPIRE || this == REPUBLIC;
    }
}
