package com.devc.minecraftempires;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    // Sprint 8 generation stays opt-in pending live gameplay, multiplayer and performance validation.
    public static final ModConfigSpec.BooleanValue AI_GENERATION_ENABLED = BUILDER
            .comment("Initialize abstract NPC nations around actual Overworld players. Opt-in pending gameplay validation; physical construction has its own switch.")
            .define("aiGenerationEnabled", false);
    public static final ModConfigSpec.IntValue AI_NATION_DENSITY = BUILDER
            .comment("NPC coverage target and hard ceiling as a percent of ALL estimated land per region, capped at 60 for new generation. Excluded biomes count as free land. Version 1 worlds retain the old district-density meaning. Saved on first activation.")
            .defineInRange("aiNationDensity", 60, 0, 100);
    public static final ModConfigSpec.IntValue AI_MAX_NATIONS = BUILDER
            .comment("Resident generation identity limit including retired nations and independent-only districts; no origin-distance boundary. Existing records are retained if this is lowered.")
            .defineInRange("aiMaxNations", 64, 1, 256);
    public static final ModConfigSpec.IntValue AI_MAX_REGIONS = BUILDER
            .comment("Resident strategic-region limit. Paging is not implemented yet; initialization defers at this limit.")
            .defineInRange("aiMaxRegions", 8192, 256, 65536);
    public static final ModConfigSpec.IntValue AI_GEOGRAPHY_SAMPLES_PER_TICK = BUILDER
            .comment("Maximum coarse generator columns per tick. A shared one-millisecond cooperative budget also applies.")
            .defineInRange("aiGeographySamplesPerTick", 4, 1, 16);
    public static final ModConfigSpec.IntValue AI_MAX_SETTLEMENTS = BUILDER
            .comment("Resident settlement admission limit for new version-3 districts. Existing settlements are preserved when capacity is reached.")
            .defineInRange("aiMaxSettlements", 16384, 4096, 16384);

    public static final ModConfigSpec.BooleanValue PHYSICAL_CONSTRUCTION_ENABLED = BUILDER
            .comment("Materialize generated state-owned and independent settlements near real players in loaded new terrain. Existing builds are preserved.")
            .define("physicalConstructionEnabled", true);
    public static final ModConfigSpec.BooleanValue VANILLA_VILLAGE_ADOPTION = BUILDER
            .comment("register loaded, generated vanilla villages near players and place one safe altar.")
            .define("vanillaVillageAdoption", true);
    public static final ModConfigSpec.IntValue INDEPENDENT_SITE_CHANCE = BUILDER
            .comment("Percent of new strategic regions that may admit one independent site, separately from state-owned density. Saved in the generation manifest.")
            .defineInRange("independentSiteChance", 25, 0, 100);
    public static final ModConfigSpec.IntValue CONSTRUCTION_RANGE = BUILDER
            .comment("Player proximity required to inspect/place construction. This never grants chunk tickets or generates missing chunks.")
            .defineInRange("constructionRange", 160, 80, 256);
    public static final ModConfigSpec.IntValue CONSTRUCTION_BLOCKS_PER_TICK = BUILDER
            .comment("Shared construction inspection/placement work limit per tick, also subject to a cooperative two-millisecond deadline.")
            .defineInRange("constructionBlocksPerTick", 256, 16, 1024);
    public static final ModConfigSpec.IntValue CONSTRUCTION_HEIGHT_RANGE = BUILDER
            .comment("Maximum natural ground-height range across a complete new settlement footprint. Only our generated construction uses this rule.")
            .defineInRange("constructionHeightRange", 10, 0, 16);
    public static final ModConfigSpec.IntValue CONSTRUCTION_LOCAL_SLOPE = BUILDER
            .comment("Maximum adjacent natural ground-height difference on exterior construction approaches. Interiors use bounded level foundations; finished gate ramps require one-block steps.")
            .defineInRange("constructionLocalSlope", 2, 1, 4);
    public static final ModConfigSpec.IntValue CONSTRUCTION_SEARCH_RADIUS = BUILDER
            .comment("Nearby search radius for entirely unbuilt settlements. Search starts at chunk intervals, then refines promising positions; missing chunks are never loaded by this search.")
            .defineInRange("constructionSearchRadius", 96, 0, 128);

    static final ModConfigSpec SPEC = BUILDER.build();

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(Identifier.parse(itemName));
    }
}
