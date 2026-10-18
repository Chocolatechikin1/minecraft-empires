package com.devc.minecraftempires.army;

import com.devc.minecraftempires.state.StateBalance;
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

    //soldier supply tracking variables
    // Soldier-ticks preserve partial days and stay with the permanent cohort when it is deployed or garrisoned. The supply manager uses these to track
    private long carriedSupplySoldierTicks;
    private long unsupportedSoldierTicks;
    private long suppliedRecoveryTicks;
    private int logisticsFatigue;

    private UUID assignedArmyId = null; //null=not deployed, otherwise the Army's UUID
    private boolean isGarrisoned = false;
    private UUID garrisonedSettlementId = null;
    private BlockPos storedPosition;
    private double preciseX;
    private double preciseZ;
    private BlockPos retreatTarget;
    private UUID retreatOpponent;
    private CompoundTag marchState = new CompoundTag();
    public CompoundTag getMarchState() { return marchState.copy(); }
    public void setMarchState(CompoundTag value) { marchState = value.copy(); }
    public String getTerrainStatus() { return marchState.getString("TerrainStatus").orElse(""); }
    public boolean isRetreatBlocked() { return marchState.getBoolean("BlockedRetreat").orElse(false); }

    //if a battle is interrupted (e.g. server crash, player disconnect), this method applies the outcome of the battle to the cohort, updating soldier count, morale, and routing status
    public void applyInterruptedBattle(UUID battleId, int survivors, int finalMorale) {
        if (hasResolvedBattle(battleId)) return;
        applyTerrainLoss(Math.max(0, soldierCount - survivors));
        setMorale(finalMorale); setRouting(false); lastResolvedBattleId = battleId;
    }
    //terrain loss application: applies losses to the cohort due to terrain effects, ensuring soldier count does not go below zero and clamping supply after losses
    public void applyTerrainLoss(int losses) {
        int previous = soldierCount;
        soldierCount = Math.max(0, soldierCount - Math.max(0, losses)); clampSupplyAfterLosses(previous);
    }
    private boolean detached, regrouping;

    //waypoint tracking
    private final Queue<BlockPos> waypoints = new LinkedList<>();

    //constructors
    public Cohort(UUID cohortId, CohortType type, String biomeTag) {
        this.cohortId = cohortId;
        this.type = type;
        this.biomeTag = biomeTag;
        this.maxSoldiers = (type == CohortType.CAVALRY) ? MAX_SOLDIERS_CAVALRY : MAX_SOLDIERS_STANDARD;
        this.soldierCount = this.maxSoldiers;
        this.carriedSupplySoldierTicks = getMaxCarriedSupplySoldierTicks();

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
        int previousSoldiers = this.soldierCount;
        this.soldierCount = Math.max(0, this.soldierCount - Math.max(0, casualties));
        clampSupplyAfterLosses(previousSoldiers);
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
        int previousSoldiers = soldierCount;
        soldierCount = Math.min(soldierCount, Math.max(0, survivors));
        clampSupplyAfterLosses(previousSoldiers);
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
    /** Training remains permanent; only effective endurance includes logistics fatigue. */
    public int getEffectiveEndurance() { return Math.max(1, endurance - logisticsFatigue); }
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

    // Administrative legion membership does not make a detached cohort follow its parent physically.
    public boolean isDetached() { return detached; }
    public void setDetached(boolean value) { detached = value; if (!value) regrouping = false; }
    public boolean isRegrouping() { return regrouping; }
    public void setRegrouping(boolean value) { regrouping = value; if (value) detached = true; }

    //below is all retreat logic, which is separate from routing logic. Routing is a tactical panic response, while retreating is a strategic decision to disengage from an opponent.
    // Strategic retreat is separate from tactical zero-morale routing: intercepted survivors can defend themselves.
    public boolean isRetreating() { return retreatTarget != null; }
    public BlockPos getRetreatTarget() { return retreatTarget; }
    public UUID getRetreatOpponent() { return retreatOpponent; }
    public boolean isDisengagingFrom(UUID opponent) { return isRetreating() && retreatOpponent != null && retreatOpponent.equals(opponent); }
    public void beginRetreat(BlockPos target, UUID opponent) {
        detached = true; regrouping = false;
        retreatTarget = target; retreatOpponent = opponent;
        marchState.putBoolean("BlockedRetreat", false); marchState.remove("TerrainStatus");
        clearWaypoints(); addWaypoint(target);
    }
    public void finishRetreat() { retreatTarget = null; retreatOpponent = null; marchState.putBoolean("BlockedRetreat", false); marchState.remove("TerrainStatus"); clearWaypoints(); }
    public void clearRetreatOpponent() { retreatOpponent = null; }

    public long getCarriedSupplySoldierTicks() { return carriedSupplySoldierTicks; }
    public long getMaxCarriedSupplySoldierTicks() { return (long) soldierCount * StateBalance.CARRIED_SUPPLY_DAYS * StateBalance.TICKS_PER_DAY; }
    public void setCarriedSupplySoldierTicks(long value) { carriedSupplySoldierTicks = Math.clamp(value, 0L, getMaxCarriedSupplySoldierTicks()); }
    public double getCarriedSupplyDays() { return soldierCount == 0 ? 0.0 : carriedSupplySoldierTicks / ((double) soldierCount * StateBalance.TICKS_PER_DAY); }
    public boolean isSupplyExhausted() { return carriedSupplySoldierTicks == 0; }
    public int getLogisticsFatigue() { return logisticsFatigue; }
    public void setLogisticsFatigue(int value) { logisticsFatigue = Math.clamp(value, 0, StateBalance.MAX_SUPPLY_FATIGUE); }
    public long getUnsupportedTicks() { return soldierCount == 0 ? 0L : unsupportedSoldierTicks / soldierCount; }
    public long getUnsupportedSoldierTicks() { return unsupportedSoldierTicks; }
    public long getSuppliedRecoveryTicks() { return suppliedRecoveryTicks; }

    /**
     * Supply manager calls this only for online, unengaged cohorts. Camp stock pays
     * current needs only; settlement-rooted supply also refills one reserve day/day.
     * Returns the camp soldier-ticks actually used, so stock cannot be allocated twice.
     */
    public long processSupply(long elapsedTicks, boolean networkSupplied, long campProvidedSoldierTicks) {
        if (elapsedTicks <= 0 || !isAlive()) return 0L;
        elapsedTicks = Math.min(elapsedTicks, Long.MAX_VALUE / soldierCount);
        long demand = elapsedTicks * soldierCount;
        long campUsed = networkSupplied ? 0L : Math.clamp(campProvidedSoldierTicks, 0L, demand);
        if (networkSupplied) {
            long missingReserve = getMaxCarriedSupplySoldierTicks() - carriedSupplySoldierTicks;
            carriedSupplySoldierTicks += Math.min(demand, missingReserve);
        } else {
            long reserveUsed = Math.min(carriedSupplySoldierTicks, demand - campUsed);
            carriedSupplySoldierTicks -= reserveUsed;
            long uncovered = demand - campUsed - reserveUsed;
            if (uncovered > 0) {
                suppliedRecoveryTicks = 0;
                long dailyDemand = soldierCount * StateBalance.TICKS_PER_DAY;
                // Seven complete reserve days are protected. The first penalty needs
                // a further complete day of genuinely unmet demand, including fractions.
                long completeDays = uncovered / dailyDemand;
                unsupportedSoldierTicks += uncovered % dailyDemand;
                completeDays += unsupportedSoldierTicks / dailyDemand;
                unsupportedSoldierTicks %= dailyDemand;
                if (completeDays > 0) {
                    tickMorale(-(int) Math.min(100L, completeDays * StateBalance.SUPPLY_MORALE_PENALTY));
                    setLogisticsFatigue((int) Math.min(StateBalance.MAX_SUPPLY_FATIGUE,
                            logisticsFatigue + Math.min(completeDays, 100L) * StateBalance.SUPPLY_FATIGUE_PER_DAY));
                }
                return campUsed;
            }
        }
        unsupportedSoldierTicks = 0;
        if (networkSupplied || campUsed == demand) {
            long completeDays = elapsedTicks / StateBalance.TICKS_PER_DAY;
            suppliedRecoveryTicks += elapsedTicks % StateBalance.TICKS_PER_DAY;
            completeDays += suppliedRecoveryTicks / StateBalance.TICKS_PER_DAY;
            suppliedRecoveryTicks %= StateBalance.TICKS_PER_DAY;
            if (completeDays > 0) setLogisticsFatigue((int) Math.max(0L,
                    logisticsFatigue - Math.min(completeDays, 100L) * StateBalance.SUPPLY_FATIGUE_RECOVERY));
        } else {
            suppliedRecoveryTicks = 0; // Carried reserve prevents penalties but does not restore readiness.
        }
        return campUsed;
    }

    //supply clamp after losses: ensures that carried supply and unsupported supply are within valid ranges after soldier count changes
    private void clampSupplyAfterLosses(int previousSoldiers) {
        carriedSupplySoldierTicks = Math.min(carriedSupplySoldierTicks, getMaxCarriedSupplySoldierTicks());
        // Preserve the elapsed fraction of a shortage day when the formation shrinks.
        unsupportedSoldierTicks = previousSoldiers <= 0 ? 0 : unsupportedSoldierTicks * soldierCount / previousSoldiers;
        if (soldierCount == 0) suppliedRecoveryTicks = 0;
    }

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

    //serialization methods below
    /**
     * Converts the cohort to a CompoundTag for saving to disk.
     * @return The CompoundTag representing the cohort.
     */
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
        tag.putBoolean("Detached", detached);
        tag.putBoolean("Regrouping", regrouping);
        tag.putInt("LogisticsVersion", 1);
        tag.putLong("CarriedSupplySoldierTicks", carriedSupplySoldierTicks);
        tag.putLong("UnsupportedSoldierTicks", unsupportedSoldierTicks);
        tag.putLong("SuppliedRecoveryTicks", suppliedRecoveryTicks);
        tag.putInt("LogisticsFatigue", logisticsFatigue);
        if (lastResolvedBattleId != null) tag.putString("LastResolvedBattle", lastResolvedBattleId.toString());
        if (retreatTarget != null) tag.putLong("RetreatTarget", retreatTarget.asLong());
        if (retreatOpponent != null) tag.putString("RetreatOpponent", retreatOpponent.toString());
        tag.put("March", marchState.copy());
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

    /**
     * Creates a cohort from a CompoundTag.
     * @param tag The CompoundTag representing the cohort.
     * @return The created cohort.
     */
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
        // Legacy cohorts receive their starting reserve once; subsequent reloads
        // respect even an explicitly saved zero instead of granting free resupply.
        c.setCarriedSupplySoldierTicks(tag.getInt("LogisticsVersion").orElse(0) >= 1
                ? tag.getLong("CarriedSupplySoldierTicks").orElse(0L) : c.getMaxCarriedSupplySoldierTicks());
        c.setLogisticsFatigue(tag.getInt("LogisticsFatigue").orElse(0));
        long dailyDemand = c.soldierCount * StateBalance.TICKS_PER_DAY;
        c.unsupportedSoldierTicks = dailyDemand == 0 ? 0 : Math.clamp(tag.getLong("UnsupportedSoldierTicks").orElse(0L), 0L, dailyDemand - 1);
        c.suppliedRecoveryTicks = Math.clamp(tag.getLong("SuppliedRecoveryTicks").orElse(0L), 0L, StateBalance.TICKS_PER_DAY - 1);
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
        tag.getLong("RetreatTarget").ifPresent(value -> c.retreatTarget = BlockPos.of(value));
        tag.getString("RetreatOpponent").ifPresent(value -> c.retreatOpponent = UUID.fromString(value));
        tag.getCompound("March").ifPresent(value -> c.marchState = value.copy());
        c.detached = tag.getBoolean("Detached").orElse(false);
        c.regrouping = tag.getBoolean("Regrouping").orElse(false);
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
