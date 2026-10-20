package com.devc.minecraftempires.state;

import com.devc.minecraftempires.army.*;
import com.devc.minecraftempires.network.MapDataService;
import com.devc.minecraftempires.territory.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Player contact and national handover; assets remain owned by their existing domain managers. */
/*
Player and NPC state conflict management class
This class is responsible for managing conflicts between player and NPC states in the game. 
It handles the negotiation and resolution of conflicts, including surrender offers, acceptance of terms, and the transfer of assets between states. 
The class ensures that all interactions are stable and consistent, preventing any conflicts or inconsistencies in the game state.
*/
public final class PlayerConflictManager {
    // Match the existing domain-store read bound; never truncate an accepted nation's assets.
    private static final long LIMIT = 64 * 1024 * 1024;
    private final StateManager states;
    public record Terms(UUID token, UUID donor, UUID recipient, long expires, String revision, String summary) {}
    private final Map<UUID, Terms> offers = new LinkedHashMap<>();
    private CompoundTag applying;
    private CompletableFuture<?> io;
    private Path file;
    private String phase = "NEW", failure = "";
    private long sequence, lastTick = Long.MIN_VALUE;
    // Each queue rotates independently; equal-sized queues must not repeatedly skip the same candidates.
    private int promotionCursor, siteCursor, contactCursor, demotionCursor;
    private int observationCursor;
    private record Observation(UUID owner, BlockPos position) {}
    private final Map<UUID, Integer> centerChecks = new HashMap<>();
    public PlayerConflictManager(StateManager states) { this.states = states; }
    public boolean busy() { return !phase.equals("IDLE") && !phase.equals("NEW"); }
    public boolean blocks(UUID owner) {
        if (owner == null || phase.equals("NEW") || phase.equals("IDLE")) return false;
        return applying == null || owner.toString().equals(applying.getString("Donor").orElse(""))
                || owner.toString().equals(applying.getString("Recipient").orElse(""));
    }
    public String status() { return phase + (failure.isEmpty() ? "" : ": " + failure); }
    public void removeOffers(UUID donor) { offers.remove(donor); }
    public Terms terms(UUID viewer, UUID donor, long now) {
        Terms offer = offers.get(donor);
        return offer != null && viewer.equals(offer.recipient()) && now <= offer.expires() ? offer : null;
    }
    public ListTag toNBT() {
        ListTag result = new ListTag();
        for (Terms offer : offers.values()) {
            CompoundTag tag = new CompoundTag(); tag.putString("Token", offer.token().toString()); tag.putString("Donor", offer.donor().toString());
            tag.putString("Recipient", offer.recipient().toString()); tag.putLong("Expires", offer.expires());
            tag.putString("Revision", offer.revision()); tag.putString("Summary", offer.summary()); result.add(tag);
        }
        return result;
    }
    public void loadOffers(ListTag saved) {
        for (var value : saved) try {
            if (offers.size() == 64) break; CompoundTag tag = (CompoundTag) value;
            Terms offer = new Terms(UUID.fromString(tag.getString("Token").orElseThrow()), UUID.fromString(tag.getString("Donor").orElseThrow()),
                    UUID.fromString(tag.getString("Recipient").orElseThrow()), tag.getLong("Expires").orElseThrow(), tag.getString("Revision").orElseThrow(), tag.getString("Summary").orElseThrow());
            offers.put(offer.donor(), offer);
        } catch (RuntimeException malformed) { /* Optional expired offers never confer transfer authority. */ }
    }
    private boolean stable(ServerLevel level, UUID owner) {
        ArmyManager armies = ArmyManager.get(level);
        if (!armies.interactionStable(owner)) return false;
        // A split save can retain a battle result before an army's engaged marker is reconciled.
        return com.devc.minecraftempires.combat.BattleManager.get(level).getAllBattles().stream().noneMatch(battle ->
                java.util.stream.Stream.concat(battle.getAttackerCohorts().stream(), battle.getDefenderCohorts().stream()).anyMatch(c -> {
                    Legion parent = armies.getParentLegion(c.getCohortId()); return parent != null && owner.equals(parent.getOwningStateId());
                }));
    }
    /** Shared willingness for autonomous offers and player demands. No request can compel acceptance. */
    //this method checks if a state is eligible for surrender to the player, and returns a message indicating the reason if it is not eligible
    public String surrenderProblem(ServerLevel level, UUID donor, UUID recipient) {
        StateData from = states.getState(donor), to = states.getState(recipient); ArmyManager armies = ArmyManager.get(level);
        if (from == null || to == null || !from.isAI() || donor.equals(recipient) || from.isInitializationPending() || to.isInitializationPending()) return "Choose an initialized AI nation.";
        if (!states.areAtWar(donor, recipient)) return "Surrender requires an existing war.";
        to.initializeEconomyTime(states.getLastEconomyGameTime() < 0 ? level.getGameTime() : states.getLastEconomyGameTime());
        if (from.hasEconomicDayDue(level.getGameTime()) || to.hasEconomicDayDue(level.getGameTime())) return "Due upkeep is still being reconciled.";
        if (blocks(donor) || states.getAIWars().blocks(donor) || states.getAIWars().blocks(recipient)) return "A military outcome is being reconciled.";
        if (!stable(level, donor) || !stable(level, recipient)) return "Finish ongoing battles and mandatory retreats before negotiating.";
        long own = armies.getTotalSoldiersForState(donor), enemy = armies.getTotalSoldiersForState(recipient);
        boolean baseLost = states.settlementsFor(from).isEmpty();
        boolean stock = armies.getStrategicForcesForState(donor).stream().anyMatch(f -> f.supply() > 0)
                || armies.getLegionsForState(donor).stream().flatMap(l -> l.allCohorts().stream()).anyMatch(c -> !c.isSupplyExhausted());
        boolean willing = baseLost || own <= Legion.AUTO_DISBAND_STRENGTH || from.getStrategy() == StateData.Strategy.SURRENDER
                || from.getLastCapitalLossTime() >= 0 && own < enemy / 2
                || enemy > own * 3 && (!stock || from.getUnpaidDays() >= 2 || from.getTreasuryBalance() < ArmyManager.LEGION_RECRUIT_COST);
        return willing ? "" : "This nation believes it can recover and refuses surrender.";
    }

    //method to demand surrender from an NPC state, returns a message indicating the result of the demand
    public String demand(ServerLevel level, UUID recipient, UUID donor) {
        if (!states.knowsNation(recipient, donor)) return "This nation has not been discovered.";
        String error = surrenderProblem(level, donor, recipient); if (!error.isEmpty()) return error;
        if (!states.getAIWars().idle() || busy()) return "Another outcome is being committed. Retry shortly.";
        StateData from = states.getState(donor), to = states.getState(recipient);
        if (from.getTreasuryBalance() > Long.MAX_VALUE - to.getTreasuryBalance()) return "The receiving treasury cannot hold this transfer.";
        ArmyManager armies = ArmyManager.get(level); ClaimManager claims = ClaimManager.get(level);
        CompoundTag snapshot = snapshot(level, donor, recipient);
        if (snapshot.sizeInBytes() > LIMIT - 65536) return "This handover exceeds the supported transaction size.";
        String summary = from.getStateName() + " surrenders to " + to.getStateName() + ": " + claims.getClaimCountForState(donor) + " chunks, " + states.settlementsFor(from).size() + " settlements, "
                + armies.getLegionCountForState(donor) + " legions (" + armies.getTotalSoldiersForState(donor) + " troops), " + from.getTreasuryBalance()
                + " emeralds; all roads, bridges and camp stock. Forces stay in place; donor orders end. Excess assets restrict growth.";
        Terms terms = new Terms(UUID.randomUUID(), donor, recipient, level.getGameTime() + 2400, revision(snapshot), summary);
        offers.put(donor, terms); while (offers.size() > 64) offers.remove(offers.keySet().iterator().next()); states.setDirty();
        return "Surrender terms: " + summary;
    }
    public String accept(ServerLevel level, UUID recipient, UUID donor, UUID token) {
        Terms terms = terms(recipient, donor, level.getGameTime());
        if (terms == null || !terms.token().equals(token)) return "The surrender terms expired. Request current terms.";
        String error = surrenderProblem(level, donor, recipient); if (!error.isEmpty()) return error;
        if (!states.getAIWars().idle() || busy()) return "Another outcome is being committed. Retry shortly.";
        CompoundTag snapshot = snapshot(level, donor, recipient);
        if (!revision(snapshot).equals(terms.revision())) { offers.remove(donor); states.setDirty(); return "Assets changed. Request updated surrender terms before accepting."; }
        long next = Math.incrementExact(sequence);
        snapshot.putInt("Version", 1); snapshot.putLong("Seed", level.getSeed()); snapshot.putLong("Sequence", next); snapshot.putLong("Time", level.getGameTime());
        snapshot.putString("Donor", donor.toString()); snapshot.putString("Recipient", recipient.toString());
        applying = snapshot; phase = "PREPARING"; CompoundTag frozen = snapshot.copy();
        io = CompletableFuture.runAsync(() -> write(file, frozen));
        return "Surrender accepted. Installing the complete handover.";
    }
    private CompoundTag snapshot(ServerLevel level, UUID donor, UUID recipient) {
        Set<UUID> owners = Set.of(donor, recipient); CompoundTag result = new CompoundTag();
        result.put("States", states.interactionSnapshot(owners)); result.put("Claims", ClaimManager.get(level).interactionSnapshot(owners));
        result.put("Armies", ArmyManager.get(level).interactionSnapshot(owners)); result.put("Logistics", LogisticsManager.get(level).interactionSnapshot(owners)); return result;
    }
    private static String revision(CompoundTag tag) {
        // Bind ownership, roster sizes and money. Normal travel, immunity and supply clocks can advance during review.
        CompoundTag material = new CompoundTag();
        CompoundTag nations = tag.getCompound("States").orElseThrow(); ListTag states = new ListTag(), sites = new ListTag();
        for (var value : nations.getListOrEmpty("States")) states.add(project((CompoundTag) value, "StateId", "Control", "Treasury"));
        for (var value : nations.getListOrEmpty("Settlements")) sites.add(project((CompoundTag) value, "SettlementId", "OwningStateId", "Tier", "LocalPopulation", "AltarPos"));
        material.put("States", states); material.put("Sites", sites); material.put("Claims", tag.getCompound("Claims").orElseThrow());
        CompoundTag military = tag.getCompound("Armies").orElseThrow(); ListTag troops = new ListTag();
        for (var value : military.getListOrEmpty("StrategicForces")) troops.add(project((CompoundTag) value, "Id", "Owner", "Infantry", "Cavalry"));
        for (var value : military.getListOrEmpty("Legions")) {
            CompoundTag legion = (CompoundTag) value; CompoundTag entry = project(legion, "LegionId", "OwningStateId"); ListTag cohorts = new ListTag();
            for (String key : List.of("InfantryCohorts", "CavalrySquadrons", "Auxiliaries")) for (var cohort : legion.getListOrEmpty(key)) cohorts.add(project((CompoundTag) cohort, "CohortId", "SoldierCount"));
            entry.put("Cohorts", cohorts); troops.add(entry);
        }
        material.put("Troops", troops); CompoundTag infrastructure = tag.getCompound("Logistics").orElseThrow();
        for (String key : List.of("Roads", "Bridges", "Camps")) { ListTag items = new ListTag(); for (var value : infrastructure.getListOrEmpty(key)) items.add(project((CompoundTag) value, "Id", "Owner")); material.put(key, items); }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(material).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static CompoundTag project(CompoundTag tag, String... fields) {
        CompoundTag result = new CompoundTag(); for (String key : fields) if (tag.contains(key)) result.put(key, tag.get(key).copy()); return result;
    }
    private static String canonical(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            StringBuilder text = new StringBuilder("{"); compound.keySet().stream().sorted().forEach(k -> text.append(k).append(':').append(canonical(compound.get(k))).append(';')); return text.append('}').toString();
        }
        if (tag instanceof ListTag list) { List<String> items = new ArrayList<>(); list.forEach(v -> items.add(canonical(v))); return items.toString(); }
        return tag.toString();
    }
    public void tick(ServerLevel level) {
        if (phase.equals("CLOSED") || !failure.isEmpty() || lastTick == level.getGameTime()) return;
        lastTick = level.getGameTime();
        try {
            if (phase.equals("NEW")) {
                file = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/minecraftempires_ai/player-handover.dat"); phase = "OPENING";
                long[] receipts = {states.getInteractionSequence(), ClaimManager.get(level).getInteractionSequence(), ArmyManager.get(level).getInteractionSequence(), LogisticsManager.get(level).getInteractionSequence()};
                long seed = level.getSeed(); io = CompletableFuture.supplyAsync(() -> open(file, seed, receipts)); return;
            }
            if (io != null && io.isDone()) {
                Object result = io.join(); io = null;
                switch (phase) {
                    case "OPENING" -> { CompoundTag journal = (CompoundTag) result; sequence = journal.getLong("Sequence").orElse(0L);
                        if (journal.contains("Donor")) { applying = journal; install(level); } else phase = "IDLE"; }
                    case "PREPARING" -> install(level);
                    case "SAVING" -> {
                        phase = "VERIFYING"; CompoundTag plan = applying.copy();
                        Path data = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data");
                        io = CompletableFuture.runAsync(() -> {
                            for (var type : List.of(StateManager.TYPE, ClaimManager.TYPE, ArmyManager.TYPE, LogisticsManager.TYPE)) {
                                try { CompoundTag saved = NbtIo.readCompressed(type.id().withSuffix(".dat").resolveAgainst(data), NbtAccounter.create(64 * 1024 * 1024)).getCompound("data").orElseThrow();
                                    if (saved.getLong("InteractionSequence").orElse(0L) != plan.getLong("Sequence").orElseThrow()) throw new IllegalStateException("Handover receipt is not durable");
                                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                            }
                            CompoundTag complete = new CompoundTag(); complete.putInt("Version", 1); complete.putLong("Seed", plan.getLong("Seed").orElseThrow()); complete.putLong("Sequence", plan.getLong("Sequence").orElseThrow()); write(file, complete);
                        });
                    }
                    case "VERIFYING" -> {
                        sequence = applying.getLong("Sequence").orElseThrow(); UUID recipient = UUID.fromString(applying.getString("Recipient").orElseThrow());
                        applying = null; phase = "IDLE"; states.refreshProgression(level, states.getState(recipient));
                    }
                    default -> throw new IllegalStateException("Invalid handover phase");
                }
            }
            if (phase.equals("IDLE") && level.getGameTime() % 20 == 0) {
                if (offers.values().removeIf(t -> level.getGameTime() > t.expires() || states.getState(t.donor()) == null)) states.setDirty();
                contact(level);
            }
        } catch (RuntimeException error) { failure = error.toString(); phase = "BLOCKED"; System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR, "Player handover stopped safely: " + failure); }
    }
    private void install(ServerLevel level) {
        apply(applying, states, ClaimManager.get(level), ArmyManager.get(level), LogisticsManager.get(level));
        phase = "SAVING"; io = level.getDataStorage().scheduleSave();
    }
    /** Validate every domain before mutating any; each receipt then makes all 16 split-store cases replayable. */
    //this method applies the handover of assets from the donor state to the recipient state, ensuring that all interactions are stable and consistent
    public static void apply(CompoundTag plan, StateManager states, ClaimManager claims, ArmyManager armies, LogisticsManager logistics) {
        if (plan.getInt("Version").orElse(0) != 1 || plan.sizeInBytes() > LIMIT) throw new IllegalArgumentException("Invalid handover record");
        long seq = plan.getLong("Sequence").orElseThrow(), time = plan.getLong("Time").orElseThrow();
        UUID donor = UUID.fromString(plan.getString("Donor").orElseThrow()), recipient = UUID.fromString(plan.getString("Recipient").orElseThrow());
        Set<UUID> owners = Set.of(donor, recipient);
        for (long receipt : new long[]{states.getInteractionSequence(), claims.getInteractionSequence(), armies.getInteractionSequence(), logistics.getInteractionSequence()})
            if (seq < 1 || time < 0 || receipt > seq || receipt < seq - 1) throw new IllegalStateException("Conflicting handover sequence");
        CompoundTag state = plan.getCompound("States").orElseThrow(), military = plan.getCompound("Armies").orElseThrow(), territory = plan.getCompound("Claims").orElseThrow(), infrastructure = plan.getCompound("Logistics").orElseThrow();
        if (states.getInteractionSequence() < seq) {
            StateData from = states.getState(donor), to = states.getState(recipient);
            if (from == null || !from.isAI() || to == null || !armies.interactionStable(donor) || !armies.interactionStable(recipient)) throw new IllegalStateException("Conflicting surrender identity/battle");
            if (state.getListOrEmpty("States").size() != 2) throw new IllegalStateException("Invalid participant snapshot");
            Set<UUID> identities = new HashSet<>(), sites = new HashSet<>(); long money = 0;
            for (var value : state.getListOrEmpty("States")) {
                StateData saved = StateData.fromNBT((CompoundTag) value), current = states.getState(saved.getStateId());
                if (!owners.contains(saved.getStateId()) || !identities.add(saved.getStateId()) || current == null || saved.isAI() != current.isAI()
                        || !Objects.equals(saved.getLeaderId(), current.getLeaderId())) throw new IllegalStateException("Participant control conflict");
                money = Math.addExact(money, saved.getTreasuryBalance());
            }
            for (var value : state.getListOrEmpty("Settlements")) {
                SettlementData saved = SettlementData.fromNBT((CompoundTag) value), current = states.getSettlement(saved.getSettlementId());
                if (!owners.contains(saved.getOwningStateId()) || !sites.add(saved.getSettlementId()) || current != null && !owners.contains(current.getOwningStateId())) throw new IllegalStateException("Settlement ownership conflict");
            }
        }
        if (armies.getInteractionSequence() < seq) {
            Set<UUID> forceIds = new HashSet<>(), cohortIds = new HashSet<>();
            for (String key : List.of("Legions", "Armies", "StrategicForces")) for (var value : military.getListOrEmpty(key)) {
                CompoundTag tag = (CompoundTag) value; String idKey = key.equals("Legions") ? "LegionId" : key.equals("Armies") ? "ArmyId" : "Id";
                UUID id = UUID.fromString(tag.getString(idKey).orElseThrow()); UUID owner = UUID.fromString(tag.getString(key.equals("StrategicForces") ? "Owner" : "OwningStateId").orElseThrow());
                if (!owners.contains(owner)) throw new IllegalStateException("Foreign military snapshot");
                if (!forceIds.add(id)) throw new IllegalStateException("Duplicate military authority");
                Legion existing = armies.getLegion(id); Army existingArmy = armies.getArmy(id);
                if (existing != null && !owners.contains(existing.getOwningStateId()) || existingArmy != null && !owners.contains(existingArmy.getOwningStateId())
                        || armies.getStrategicForces().stream().anyMatch(f -> f.id().equals(id) && !owners.contains(f.owner()))) throw new IllegalStateException("Military identity conflict");
                if (key.equals("Legions")) for (String list : List.of("InfantryCohorts", "CavalrySquadrons", "Auxiliaries")) for (var c : tag.getListOrEmpty(list)) {
                    Cohort cohort = Cohort.fromNBT((CompoundTag) c); Legion parent = armies.getParentLegion(cohort.getCohortId());
                    if (!cohortIds.add(cohort.getCohortId()) || parent != null && !owners.contains(parent.getOwningStateId())) throw new IllegalStateException("Cohort identity conflict");
                }
            }
        }
        if (claims.getInteractionSequence() < seq) {
            for (var value : territory.getListOrEmpty("ClaimsList")) {
                CompoundTag tag = (CompoundTag) value; UUID current = claims.getStrategicOwner(ChunkPos.unpack(tag.getLong("ChunkPosLong").orElseThrow()));
                if (current != null && !owners.contains(current)) throw new IllegalStateException("Concrete ownership conflict");
            }
            for (var value : territory.getListOrEmpty("StrategicRegions")) {
                var region = ClaimManager.StrategicRegion.fromNBT((CompoundTag) value);
                var current = claims.getStrategicRegions().get(region.key());
                if (!owners.contains(region.owner()) || current != null && (!Objects.equals(current.owner(), region.owner()) || current.revision() != region.revision())) throw new IllegalStateException("Regional ownership conflict");
            }
        }
        if (logistics.getInteractionSequence() < seq) for (String key : List.of("Roads", "Camps", "Bridges")) for (var value : infrastructure.getListOrEmpty(key)) {
            CompoundTag tag = (CompoundTag) value; UUID id = UUID.fromString(tag.getString("Id").orElseThrow());
            if (!owners.contains(UUID.fromString(tag.getString("Owner").orElseThrow()))
                    || logistics.getRoads().stream().anyMatch(r -> r.id().equals(id) && !owners.contains(r.ownerId()))
                    || logistics.getCamps().stream().anyMatch(c -> c.id().equals(id) && !owners.contains(c.ownerId()))
                    || logistics.getBridges().stream().anyMatch(b -> b.id().equals(id) && !owners.contains(b.ownerId()))) throw new IllegalStateException("Infrastructure identity conflict");
        }
        armies.applySurrender(seq, donor, recipient, military); claims.applySurrender(seq, donor, recipient, territory);
        logistics.applySurrender(seq, donor, recipient, infrastructure); states.applySurrender(seq, time, donor, recipient, state);
        for (var region : claims.regionsFor(recipient)) states.learnRegion(recipient, new StateManager.KnownRegion(region.key(), recipient, region.landMask(), time, StateManager.KnowledgeSource.OWNERSHIP));
    }
    private static CompoundTag open(Path file, long seed, long[] receipts) {
        try {
            if (!Files.exists(file)) { if (Arrays.stream(receipts).anyMatch(v -> v != 0)) throw new IllegalStateException("Missing handover journal"); return new CompoundTag(); }
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.create(LIMIT));
            long seq = tag.getLong("Sequence").orElseThrow();
            if (tag.getInt("Version").orElse(0) != 1 || tag.getLong("Seed").orElseThrow() != seed || seq < 1
                    || Arrays.stream(receipts).anyMatch(v -> v > seq || !tag.contains("Donor") && v != seq)) throw new IllegalStateException("Conflicting handover receipts");
            return tag;
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    private static void write(Path file, CompoundTag tag) {
        try {
            AIJournal.write(file, tag);
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    public void close() {
        if (io != null) try { io.join(); } catch (RuntimeException ignored) { /* Retain a prepared journal for recovery; shutdown still reconciles battles. */ }
        centerChecks.clear(); phase = "CLOSED";
    }

    public void assessDetailed(ServerLevel level, StateData nation) {
        if (!nation.isAI() || nation.getMajorDefeatTime() < 0 || !stable(level, nation.getStateId())) return;
        ArmyManager armies = ArmyManager.get(level); long troops = armies.getTotalSoldiersForState(nation.getStateId());
        boolean military = false, pending = false;
        for (var force : armies.getStrategicForcesForState(nation.getStateId())) if (AIWarManager.capable(force)) {
            SettlementData home = states.getSettlement(force.home());
            boolean source = home != null && nation.getStateId().equals(home.getOwningStateId()) && home.isSupplyReady();
            military |= force.supply() > 0 || source;
            pending |= !source && force.supply() == 0 && home != null && nation.getStateId().equals(home.getOwningStateId()) && !home.isCenterVerified();
        }
        for (Legion legion : armies.getLegionsForState(nation.getStateId())) if (legion.getTotalSoldiers() > Legion.AUTO_DISBAND_STRENGTH) {
            for (Cohort cohort : legion.allCohorts()) if (cohort.isAlive()) {
                var supply = LogisticsManager.get(level).describe(nation.getStateId(), cohort, level);
                military |= !cohort.isSupplyExhausted() || supply.connected(); pending |= supply.recalculating();
            }
        }
        StateData opponent = states.getState(nation.getDefeatOpponent());
        long enemies = opponent == null ? 0 : armies.getTotalSoldiersForState(opponent.getStateId());
        var result = AIWarManager.assess(true, !pending || military, states.getTownCount(nation) + states.getCityCount(nation), military,
                nation.getLastCapitalLossTime() >= nation.getMajorDefeatTime(), troops, enemies, nation.getUnpaidDays(), nation.getSurrenderDeadline(), level.getGameTime());
        if (result == AIWarManager.Defeat.SURRENDER && nation.getSurrenderDeadline() < 0) { nation.offerSurrender(level.getGameTime()); states.setDirty(); }
        if (result == AIWarManager.Defeat.RECOVER) states.recordAIRecovery(nation, level.getGameTime());
        // A terminal detailed state remains in a bounded negotiation window, then uses ordinary cleanup.
        if (result == AIWarManager.Defeat.DISSOLVE && !busy() && states.getAIWars().idle()) {
            armies.endDonorOrders(nation.getStateId()); states.disbandState(nation.getStateId(), level);
        }
    }
    /** Uses the controller's existing daily visit; proximity alone never declares an unverified attack. */
    //this method checks if an AI state should consider attacking a human opponent based on various conditions such as the AI's strategy, unpaid days, and the presence of enemies. 
    // If the conditions are met, it promotes a strategic force and declares war on the human opponent.
    public void considerHumanOpponent(ServerLevel level, StateData ai) {
        if (!states.getAIWars().idle() || !ai.isAI() || ai.getStrategy() == StateData.Strategy.SURRENDER || ai.getUnpaidDays() > 0
                || !states.enemiesFor(ai.getStateId()).isEmpty() || (RegionInfo.mix(ai.getGenerationSeed() ^ level.getGameTime() / StateBalance.TICKS_PER_DAY) & 7) != 0) return;
        ArmyManager armies = ArmyManager.get(level);
        var force = armies.getStrategicForcesForState(ai.getStateId()).stream().filter(ArmyManager.StrategicForce::ready).findFirst().orElse(null);
        if (force == null) return;
        StateData target = states.getHumanStates().stream().filter(h -> h.getGraceTicksRemaining() == 0 && h.getSiegeImmunityTicks() == 0
                && states.mayInitiateHostility(level, ai.getStateId(), h.getStateId()) && !states.hasPassage(ai.getStateId(), h.getStateId()) && !states.hasPassage(h.getStateId(), ai.getStateId())
                && armies.getTotalSoldiersForState(ai.getStateId()) > armies.getTotalSoldiersForState(h.getStateId())
                && states.settlementsFor(h).stream().anyMatch(s -> ArmyManager.near(force.position(), s.getCenterAltarPos(), 2048)))
                .sorted(Comparator.comparing(StateData::getStateId)).findFirst().orElse(null);
        if (target != null && armies.promoteForce(level, force.id())) states.declareWar(level, ai.getStateId(), target.getStateId());
    }

    /** Four compact candidates per second; terrain queries never create chunks. Map visibility is irrelevant. */
    //this method handles the contact between player and AI states, including observing politics, promoting strategic forces, verifying settlements, and managing surrender demands.
    private void contact(ServerLevel level) {
        centerChecks.keySet().removeIf(id -> states.getSettlement(id) == null || states.getSettlement(id).isCenterVerified());
        for (var player : level.getServer().getPlayerList().getPlayers()) {
            StateData nation = states.getStateByPlayer(player.getUUID());
            if (nation != null && player.level() == level) states.observePolitics(level, nation.getStateId(), player.blockPosition());
        }
        ArmyManager armies = ArmyManager.get(level);
        List<Observation> observations = states.getHumanStates().stream().flatMap(n -> armies.getLegionsForState(n.getStateId()).stream()
                .flatMap(l -> l.allCohorts().stream()).filter(Cohort::isAlive).map(c -> new Observation(n.getStateId(), c.getStoredPosition()))).distinct().toList();
        for (int i = 0; i < Math.min(4, observations.size()); i++) {
            Observation observation = observations.get(Math.floorMod(observationCursor++, observations.size())); states.observePolitics(level, observation.owner(), observation.position());
        }
        List<ArmyManager.StrategicForce> forces = new ArrayList<>(armies.getStrategicForces());
        for (int i = 0; i < Math.min(4, forces.size()); i++) {
            var force = forces.get(Math.floorMod(promotionCursor++, forces.size())); StateData owner = states.getState(force.owner());
            if (owner == null || states.getAIWars().blocks(force.owner())) continue;
            boolean relevant = !owner.isAI() || states.enemiesFor(force.owner()).stream().anyMatch(s -> !s.isAI());
            if (!relevant) continue;
            // Pending promotion keeps the macro position/roster and cannot capture player stakes.
            force.deferMilitaryTime(level.getGameTime()); armies.setDirty(); armies.promoteForce(level, force.id());
        }
        List<SettlementData> inheritedSites = states.getAllSettlements().stream().filter(s -> s.isAbstractSettlement() && !s.isCenterVerified()
                && states.getState(s.getOwningStateId()) != null && !states.getState(s.getOwningStateId()).isAI()).toList();
        for (int i = 0; i < Math.min(4, inheritedSites.size()); i++) verifySite(level, inheritedSites.get(Math.floorMod(siteCursor++, inheritedSites.size())));
        List<StateData> contacts = states.getAllStates().stream().filter(s -> s.isAI() && !states.getAIWars().blocks(s.getStateId())
                && states.enemiesFor(s.getStateId()).stream().anyMatch(h -> !h.isAI())).sorted(Comparator.comparing(StateData::getStateId)).toList();
        for (int i = 0; i < Math.min(4, contacts.size()); i++) {
            StateData enemy = contacts.get(Math.floorMod(contactCursor++, contacts.size()));
            StateData human = states.enemiesFor(enemy.getStateId()).stream().filter(h -> !h.isAI()).sorted(Comparator.comparing(StateData::getStateId)).findFirst().orElseThrow();
            for (SettlementData site : states.settlementsFor(enemy)) if (site.isAbstractSettlement() && !site.isCenterVerified()
                    && armies.getLegionsForState(human.getStateId()).stream().flatMap(l -> l.allCohorts().stream()).anyMatch(c -> ArmyManager.near(c.getStoredPosition(), site.getCenterAltarPos(), 256))) verifySite(level, site);
            detailedOrders(level, enemy, human);
            if (enemy.getStrategy() == StateData.Strategy.SURRENDER && terms(human.getStateId(), enemy.getStateId(), level.getGameTime()) == null) demand(level, human.getStateId(), enemy.getStateId());
        }
        List<Legion> detailed = armies.getAllLegions().stream().filter(l -> armies.isPromoted(l.getLegionId())).toList();
        for (int i = 0; i < Math.min(4, detailed.size()); i++) {
            Legion legion = detailed.get(Math.floorMod(demotionCursor++, detailed.size()));
            StateData owner = states.getState(legion.getOwningStateId());
            if (owner != null && owner.isAI() && armies.isPromoted(legion.getLegionId()) && states.enemiesFor(owner.getStateId()).stream().allMatch(StateData::isAI)
                    && !states.getAIWars().blocks(owner.getStateId()) && stable(level, owner.getStateId())
                    && states.getHumanStates().stream().noneMatch(h -> armies.getLegionsForState(h.getStateId()).stream().flatMap(l -> l.allCohorts().stream()).anyMatch(c -> ArmyManager.near(c.getStoredPosition(), legion.getStoredPosition(), 256)))) {
                armies.endDonorOrders(owner.getStateId());
                for (Army army : armies.getArmiesForState(owner.getStateId())) armies.disbandArmy(army.getArmyId(), false);
                armies.releaseForDemotion(legion.getLegionId()); armies.demoteForce(legion.getLegionId(), level.getGameTime());
            }
        }
    }

    //this method verifies the center of a settlement site for an AI state. 
    //It checks if the site is an abstract settlement, if it has already been verified, and if the owning state is blocked from AI wars. 
    //If the site is eligible for verification, it checks various offsets around the center altar position to find a suitable candidate position.
    // It then checks the deployment formation status and surface conditions at that position. 
    // If the conditions are met, it marks the site as having a verified center and updates the state accordingly.
    private void verifySite(ServerLevel level, SettlementData site) {
        if (!site.isAbstractSettlement() || site.isCenterVerified() || states.getAIWars().blocks(site.getOwningStateId())) return;
        BlockPos at = site.getCenterAltarPos();
        int[][] offsets = {{0,0},{16,0},{0,16},{-16,0},{0,-16},{16,16},{-16,16},{-16,-16},{16,-16}};
        int slot = centerChecks.getOrDefault(site.getSettlementId(), 0); BlockPos candidate = at.offset(offsets[slot][0], 0, offsets[slot][1]);
        var status = MapDataService.deploymentFormationStatus(level, candidate.getX() + .5, candidate.getZ() + .5);
        if (status == MapDataService.Verification.PENDING) return;
        UUID ground = ClaimManager.get(level).getStrategicOwner(ChunkPos.containing(candidate));
        if (status != MapDataService.Verification.VERIFIED || ground != null && !site.getOwningStateId().equals(ground)) {
            centerChecks.put(site.getSettlementId(), (slot + 1) % offsets.length); return;
        }
        var surface = MapDataService.surface(level, candidate);
        if (!surface.dry()) { centerChecks.put(site.getSettlementId(), (slot + 1) % offsets.length); return; }
        site.acceptVerifiedCenter(new BlockPos(candidate.getX(), (int) Math.floor(surface.height()), candidate.getZ())); centerChecks.remove(site.getSettlementId());
        states.setDirty(); states.markLogisticsChanged();
    }

    /*
     *this method handles the detailed orders for a given AI state and a human state.
     * It manages the armies and legions of the AI state, creating campaigns and ordering armies based on the current situation.
     */
    private void detailedOrders(ServerLevel level, StateData ai, StateData human) {
        ArmyManager armies = ArmyManager.get(level);
        for (Legion legion : armies.getLegionsForState(ai.getStateId())) {
            Cohort body = legion.getFieldCohort(); if (body == null || body.isRetreating()) continue;
            Optional<Army> raised = armies.raiseArmy(ai.getStateId(), body.getStoredPosition(), legion.getFieldCohorts().stream().map(Cohort::getCohortId).toList());
            raised.ifPresent(a -> armies.createCampaign(ai.getStateId(), a.getArmyId()));
        }
        for (Army army : armies.getArmiesForState(ai.getStateId())) {
            if (ai.getStrategy() == StateData.Strategy.SURRENDER && !army.isEngaged() && !army.isRetreating()
                    && army.getDeployedCohortIds().stream().map(armies::resolveCohort).filter(Objects::nonNull).noneMatch(c -> c.getMarchState().contains("CrossingStart"))) {
                army.clearWaypoints(); army.getDeployedCohortIds().stream().map(armies::resolveCohort).filter(Objects::nonNull).forEach(c -> { if (!c.isRetreating()) c.clearWaypoints(); });
            }
            if (army.isEngaged() || army.isRetreating() || !army.getWaypoints().isEmpty()) continue;
            // Offline invaders on AI ground remain legitimate defensive targets.
            BlockPos target = armies.getLegionsForState(human.getStateId()).stream().flatMap(l -> l.allCohorts().stream()).filter(Cohort::isAlive)
                    .map(Cohort::getStoredPosition).filter(p -> ai.getStrategy() != StateData.Strategy.SURRENDER && states.hasOnlineMembers(level, human.getStateId()) || ai.getStateId().equals(ClaimManager.get(level).getStrategicOwner(ChunkPos.containing(p))))
                    .min(Comparator.comparingDouble(p -> p.distSqr(army.getStoredPosition()))).orElse(null);
            if (target == null && states.mayInitiateHostility(level, ai.getStateId(), human.getStateId()) && ai.getStrategy() != StateData.Strategy.SURRENDER)
                target = states.settlementsFor(human).stream().map(SettlementData::getCenterAltarPos).min(Comparator.comparingDouble(p -> p.distSqr(army.getStoredPosition()))).orElse(null);
            if (target != null) armies.orderArmy(level, ai.getStateId(), army.getArmyId(), target);
        }
    }
}
