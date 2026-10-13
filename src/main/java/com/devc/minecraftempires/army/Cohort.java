package com.devc.minecraftempires.army;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import java.util.UUID;
import java.util.LinkedList;
import java.util.Queue;
import net.minecraft.core.BlockPos;

//ai generated summary i like it so its staying
/**
 * Represents a single tactical unit within a Legion.
 *
 * INFANTRY / AUXILIARY cohorts hold up to 50 soldiers.
 * CAVALRY squadrons hold up to 10 soldiers (5 squadrons = 1 full Cavalry Wing of 50).
 *
 * Stats (all clamped 0–100):
 *   endurance  — fatigue resistance; affects sustained-combat effectiveness
 *   strength   — raw damage output
 *   health     — surviving soldiers as a percentage of original strength
 *   speed      — movement and flanking capability
 *   morale     — unit resolve; hits 0 → unit routes
 *
 * XP & Progression:
 *   xp is gained after surviving battles. Each gain is multiplied by a
 *   factor derived from battlesParticipated so veteran units improve faster.
 *   Every STAT_XP_PER_POINT XP, one stat point is distributed round-robin
 *   across endurance, strength, speed and morale. GearTier auto-promotes when XP crosses thresholds
 *   defined in GearTier.
 *
 * Rout:
 *   When morale reaches 0, isRouting is set true. The Legion's chain-panic
 *   logic will then propagate a morale debuff to adjacent cohorts in the array.
 */
public class Cohort {
    public static final int MAX_SOLDIERS_STANDARD = 50; //applies to both normal cohorts and auxiliaries
    public static final int MAX_SOLDIERS_CAVALRY = 10; //base per squadron, can form up to 5 squadrons
    private static final int STAT_XP_PER_POINT = 50;
    private static final int ROUTE_PANIC_DEBUFF = 15;
    private static final double BATTLE_XP_SCALE = 0.1; //battle xp scaling - more battles = more xp per battle
    private final UUID cohortId;
    private final CohortType type;

    //biome tag for auxiliaries
    private final String biomeTag;

    private int soldierCount;
    private final int maxSoldiers;

    //stats
    private int endurance;
    private int strength;
    private int speed;
    private int morale;

   //progression counters
    private GearTier gearTier;
    private int xp;
    private int battlesParticipated;

    // Round robin: endurance, strength, speed, morale. Health is derived.
    private int statLevelIndex;
    private boolean isRouting;
    private UUID lastResolvedBattleId;

    private UUID assignedArmyId = null; //null=not deployed, otherwise the Army's UUID
    private boolean isGarrisoned = false;
    private UUID garrisonedSettlementId = null;
    private BlockPos storedPosition;
    private double preciseX;
    private double preciseZ;

    //waypoint tracking
    private final Queue<BlockPos> waypoints = new LinkedList<>();

    //constructors
    public Cohort(UUID cohortId, CohortType type, String biomeTag) {
        this.cohortId = cohortId;
        this.type = type;
        this.biomeTag = biomeTag;
        this.maxSoldiers = (type == CohortType.CAVALRY) ? MAX_SOLDIERS_CAVALRY : MAX_SOLDIERS_STANDARD;
        this.soldierCount = this.maxSoldiers;

        // All stats start at 50 — average, not elite, not incompetent
        this.endurance = 50;
        this.strength  = 50;
        this.speed     = 50;
        this.morale    = 50;

        this.gearTier          = GearTier.STONE;
        this.xp                = 0;
        this.battlesParticipated = 0;
        this.statLevelIndex    = 0;
        this.isRouting         = false;
    }

    //create cohort
    public static Cohort createInfantry() {
        return new Cohort(UUID.randomUUID(), CohortType.INFANTRY, null);
    }
    //create squadron
    public static Cohort createCavalrySquadron() {
        return new Cohort(UUID.randomUUID(), CohortType.CAVALRY, null);
    }
    //create auxiliary cohort
    public static Cohort createAuxiliary(String biomeTag) {
        return new Cohort(UUID.randomUUID(), CohortType.AUXILIARY, biomeTag);
    }

    //casualty calculator
    public void applyAttrition(int casualties) {
        this.soldierCount = Math.max(0, this.soldierCount - Math.max(0, casualties));
    }
    public boolean isAlive() {
        return this.soldierCount > 0;
    }

    //morale methods
    public void tickMorale(int delta) {
        this.morale = Math.max(0, Math.min(100, this.morale + delta));
        if (this.morale == 0) {
            this.isRouting = true;
        }
    }

   //return morale panic debuff value
    public int getMoralePanicDebuff() {
        return ROUTE_PANIC_DEBUFF;
    }

    //xp tracking system
    public void gainXp(int baseXp) {
        double multiplier = 1.0 + (battlesParticipated * BATTLE_XP_SCALE); // Scale XP based on battle experience
        int effectiveXp = (int) Math.min(Integer.MAX_VALUE - xp, Math.round(Math.max(0, baseXp) * multiplier));

        int xpBefore = this.xp;
        this.xp += effectiveXp;

        int pointsBefore = xpBefore / STAT_XP_PER_POINT;
        int pointsAfter  = this.xp  / STAT_XP_PER_POINT;
        int newPoints    = pointsAfter - pointsBefore;

        for (int i = 0; i < newPoints; i++) { //calls round robin method
            distributeStatPoint();
        }

        //promote gear tier if XP crosses threshold
        this.gearTier = GearTier.fromXp(this.xp);
    }

    //battle counter incrementer
    public void incrementBattleCount() {
        this.battlesParticipated++;
    }

    /** The saved marker makes shutdown recovery and normal result delivery idempotent. */
    public boolean hasResolvedBattle(UUID battleId) { return battleId.equals(lastResolvedBattleId); }

    //post auto engagement battle outcome application: updates soldier count, morale, routing status, xp, and battle count
    public void applyBattleOutcome(UUID battleId, int survivors, int finalMorale, boolean won) {
        if (hasResolvedBattle(battleId)) return;
        soldierCount = Math.min(soldierCount, Math.max(0, survivors));
        setMorale(Math.max(10, finalMorale));
        setRouting(false);
        if (isAlive()) {
            gainXp(won ? 100 : 50);
            incrementBattleCount();
        }
        lastResolvedBattleId = battleId;
    }

    //xp distribution method (round robin)
    private void distributeStatPoint() {
        switch (statLevelIndex % 4) {
            case 0 -> this.endurance = Math.min(100, this.endurance + 1);
            case 1 -> this.strength  = Math.min(100, this.strength  + 1);
            case 2 -> this.speed     = Math.min(100, this.speed     + 1);
            case 3 -> this.morale    = Math.min(100, this.morale    + 1);
        }
        statLevelIndex++;
    }

    //getters and setters
    public UUID getCohortId()          { return cohortId; }
    public CohortType getType()        { return type; }
    public String getBiomeTag()        { return biomeTag; }

    public int getSoldierCount()       { return soldierCount; }
    public int getMaxSoldiers()        { return maxSoldiers; }

    public int getEndurance()          { return endurance; }
    public int getStrength()           { return strength; }
    public int getHealth()             { return (int) Math.round(100.0 * soldierCount / maxSoldiers); }
    public int getSpeed()              { return speed; }
    public int getMorale()             { return morale; }

    public void setMorale(int morale)  { this.morale = Math.max(0, Math.min(100, morale)); }

    public GearTier getGearTier()      { return gearTier; }
    public int getXp()                 { return xp; }
    public int getBattlesParticipated(){ return battlesParticipated; }

    public boolean isRouting()         { return isRouting; }
    public void setRouting(boolean v)  { this.isRouting = v; }

    // ── Deployment tracking getters/setters ───────────────────────────────────
    public UUID getAssignedArmyId()                    { return assignedArmyId; }
    public void setAssignedArmyId(UUID id)             { this.assignedArmyId = id; }
    public boolean isDeployed()                        { return assignedArmyId != null; }

    public boolean isGarrisoned()                      { return isGarrisoned; }
    public void setIsGarrisoned(boolean garrisoned)    { this.isGarrisoned = garrisoned; }

    public UUID getGarrisonedSettlementId()            { return garrisonedSettlementId; }
    public void setGarrisonedSettlementId(UUID id)     { this.garrisonedSettlementId = id; }

    public boolean hasStoredPosition() { return storedPosition != null; }
    public BlockPos getStoredPosition() { return storedPosition == null ? BlockPos.ZERO : storedPosition; }
    public double getPreciseX() { return preciseX; }
    public double getPreciseZ() { return preciseZ; }
    public void setStoredPosition(BlockPos pos) {
        storedPosition = pos.immutable();
        preciseX = pos.getX() + 0.5;
        preciseZ = pos.getZ() + 0.5;
    }
    public void setPrecisePos(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) return;
        preciseX = x;
        preciseZ = z;
        storedPosition = new BlockPos((int) Math.floor(x), getStoredPosition().getY(), (int) Math.floor(z));
    }

    //serialization methods
    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putString("CohortId",   cohortId.toString());
        tag.putString("Type",       type.name());
        tag.putString("BiomeTag",   biomeTag != null ? biomeTag : "");
        tag.putInt("SoldierCount",  soldierCount);
        tag.putInt("Endurance",     endurance);
        tag.putInt("Strength",      strength);
        tag.putInt("Health",        getHealth());
        tag.putInt("StatRotationVersion", 2);
        tag.putInt("Speed",         speed);
        tag.putInt("Morale",        morale);
        tag.putString("GearTier",   gearTier.name());
        tag.putInt("XP",            xp);
        tag.putInt("Battles",       battlesParticipated);
        tag.putInt("StatLevelIdx",  statLevelIndex);
        tag.putBoolean("IsRouting", isRouting);
        if (lastResolvedBattleId != null) tag.putString("LastResolvedBattle", lastResolvedBattleId.toString());
        // Deployment tracking
        tag.putString("AssignedArmyId",         assignedArmyId != null ? assignedArmyId.toString() : "");
        tag.putBoolean("IsGarrisoned",           isGarrisoned);
        tag.putString("GarrisonedSettlementId",  garrisonedSettlementId != null ? garrisonedSettlementId.toString() : "");
        if (storedPosition != null) {
            tag.putLong("StoredPos", storedPosition.asLong());
            tag.putDouble("PreciseX", preciseX);
            tag.putDouble("PreciseZ", preciseZ);
        }
        ListTag waypointTags = new ListTag();
        for (BlockPos waypoint : waypoints) {
            CompoundTag waypointTag = new CompoundTag();
            waypointTag.putLong("Pos", waypoint.asLong());
            waypointTags.add(waypointTag);
        }
        tag.put("Waypoints", waypointTags);
        return tag;
    }

    public static Cohort fromNBT(CompoundTag tag) {
        UUID id        = UUID.fromString(tag.getString("CohortId").orElseThrow());
        CohortType t   = CohortType.valueOf(tag.getString("Type").orElse(CohortType.INFANTRY.name()));
        String biome   = tag.getString("BiomeTag").orElse("");

        Cohort c = new Cohort(id, t, biome.isEmpty() ? null : biome);
        c.soldierCount       = Math.max(0, Math.min(c.maxSoldiers, tag.getInt("SoldierCount").orElse(c.maxSoldiers)));
        c.endurance          = tag.getInt("Endurance").orElse(50);
        c.strength           = tag.getInt("Strength").orElse(50);
        c.speed              = tag.getInt("Speed").orElse(50);
        c.morale             = tag.getInt("Morale").orElse(50);
        c.gearTier           = GearTier.valueOf(tag.getString("GearTier").orElse(GearTier.STONE.name()));
        c.xp                 = tag.getInt("XP").orElse(0);
        c.battlesParticipated = tag.getInt("Battles").orElse(0);
        c.statLevelIndex     = tag.getInt("StatLevelIdx").orElse(0);
        if (tag.getInt("StatRotationVersion").orElse(1) < 2) {
            // Keep the next legacy stat's meaning, skipping the retired health slot.
            int oldNext = Math.floorMod(c.statLevelIndex, 5);
            c.statLevelIndex = oldNext < 2 ? oldNext : Math.max(2, oldNext - 1);
        }
        c.isRouting          = tag.getBoolean("IsRouting").orElse(false);
        tag.getString("LastResolvedBattle").ifPresent(value -> {
            try { c.lastResolvedBattleId = UUID.fromString(value); } catch (IllegalArgumentException ignored) {}
        });
        // Deployment tracking
        String assignedArmyStr = tag.getString("AssignedArmyId").orElse("");
        c.assignedArmyId = assignedArmyStr.isEmpty() ? null : UUID.fromString(assignedArmyStr);
        c.isGarrisoned   = tag.getBoolean("IsGarrisoned").orElse(false);
        String garrisonStr = tag.getString("GarrisonedSettlementId").orElse("");
        c.garrisonedSettlementId = garrisonStr.isEmpty() ? null : UUID.fromString(garrisonStr);
        tag.getLong("StoredPos").ifPresent(pos -> c.setStoredPosition(BlockPos.of(pos)));
        c.setPrecisePos(tag.getDouble("PreciseX").orElse(c.preciseX), tag.getDouble("PreciseZ").orElse(c.preciseZ));
        if (!tag.contains("StoredPos")) c.storedPosition = null; // Parent/army resolves legacy location.
        tag.getList("Waypoints").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                list.getCompound(i).ifPresent(wp -> wp.getLong("Pos").ifPresent(pos -> c.addWaypoint(BlockPos.of(pos))));
            }
        });
        return c;
    }

    //rts Waypoint Logic
    public Queue<BlockPos> getWaypoints() { 
        return this.waypoints; 
    } 

    public void addWaypoint(BlockPos pos) { 
        this.waypoints.offer(pos); 
    } 

    public void clearWaypoints() { 
        this.waypoints.clear(); 
    } 
}
