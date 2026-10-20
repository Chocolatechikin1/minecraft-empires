package com.devc.minecraftempires.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import java.util.*;

/** Bounded presentation history, saved with StateManager's mutations rather than a second world registry. */
/*
News method
This class manages the world news events in the game. 
It maintains a bounded history of events, allowing for the publication of new events and the retrieval of event pages for display to players. 
The class supports filtering events by type and ensures that only relevant information is presented to players based on their involvement and knowledge of the events.
*/
public final class WorldNews {
    public static final int MAX_EVENTS = 256, MAX_PAGE = 24;
    public enum Kind { WAR, PEACE, CAPTURE, SURRENDER, DEFEAT, GOVERNMENT, BATTLE, RECOVERY }
    public enum Filter {
        ALL, DIPLOMACY, TERRITORY, NATIONS, BATTLES;
        public boolean includes(Kind kind) {
            return switch (this) {
                case ALL -> true;
                case DIPLOMACY -> kind == Kind.WAR || kind == Kind.PEACE || kind == Kind.SURRENDER;
                case TERRITORY -> kind == Kind.CAPTURE;
                case NATIONS -> kind == Kind.DEFEAT || kind == Kind.GOVERNMENT || kind == Kind.RECOVERY;
                case BATTLES -> kind == Kind.BATTLE;
            };
        }
    }
    public record Event(UUID id, long sequence, long time, Kind kind, UUID actor, String actorName,
                        UUID target, String targetName, UUID settlement, String settlementName, int regions) {}
    /** Reports omit hidden participant IDs/coordinates; the client receives only entitled presentation. */
    public record Report(UUID id, long sequence, long time, Kind kind, String headline, UUID settlement) {}
    public record Page(List<Report> reports, long through, long nextBefore, boolean hasOlder, boolean trimmed) {
        public Page { reports = List.copyOf(reports); }
    }
    private final Deque<Event> events = new ArrayDeque<>();
    private final Set<UUID> retainedIds = new HashSet<>();
    private final Runnable changed;
    private long nextSequence = 1;
    private long prunedThrough;

    public WorldNews(Runnable changed) { this.changed = Objects.requireNonNull(changed); }
    public int size() { return events.size(); }

    /** Call only after committing a domain result. Permanent operation receipts belong to its owner. */
    public boolean publish(UUID id, long time, Kind kind, UUID actor, String actorName, UUID target,
                           String targetName, UUID settlement, String settlementName, int regions) {
        if (id == null || kind == null || actor == null || time < 0 || regions < 0 || retainedIds.contains(id)
                || nextSequence == Long.MAX_VALUE) return false;
        Event event = new Event(id, nextSequence++, time, kind, actor, StateManager.cleanName(actorName), target,
                StateManager.cleanName(targetName), settlement, StateManager.cleanName(settlementName), regions);
        events.addFirst(event); retainedIds.add(id);
        trim(); changed.run(); return true;
    }

    /** Cursor is exclusive. Discovery/eligibility is checked server-side on every requested page. */
    public List<Report> page(UUID viewer, StateManager states, long before, int limit) {
        return browse(viewer, states, before, 0, limit, Filter.ALL, false).reports();
    }

    /** Freeze the upper sequence while browsing older pages; new events cannot shift the current page. */
    public Page browse(UUID viewer, StateManager states, long before, long through, int limit, Filter filter, boolean ownOnly) {
        StateData own = states.getState(viewer);
        if (own == null || own.isAI() || before < 0 || through < 0 || filter == null || limit < 1 || limit > MAX_PAGE)
            return new Page(List.of(), 0, 0, false, false);
        long upper = through == 0 ? nextSequence - 1 : Math.min(through, nextSequence - 1);
        List<Report> result = new ArrayList<>();
        Map<UUID, String> names = new HashMap<>();
        for (var known : states.getKnownNations(viewer)) names.put(known.id(), known.name());
        boolean older = false;
        for (Event event : events) {
            if (event.sequence() > upper || before != 0 && event.sequence() >= before || !filter.includes(event.kind())) continue;
            boolean involved = viewer.equals(event.actor()) || viewer.equals(event.target());
            if (ownOnly && !involved) continue;
            if (!involved && (!states.knowsNation(viewer, event.actor())
                    || event.target() != null && !states.knowsNation(viewer, event.target()))) continue;
            if (result.size() == limit) { older = true; break; }
            var known = states.getKnownSettlement(viewer, event.settlement());
            boolean knownPlace = known != null;
            String place = knownPlace ? known.name() : "a settlement";
            // Public summaries do not silently reveal an unseen rename. Direct participants know their event.
            String actor = involved ? event.actorName() : names.getOrDefault(event.actor(), event.actorName());
            String target = involved ? event.targetName() : names.getOrDefault(event.target(), event.targetName());
            String headline = switch (event.kind()) {
                case WAR -> actor + " declared war on " + target + ".";
                case PEACE -> actor + " and " + target + " agreed to peace.";
                case CAPTURE -> event.settlement() == null && event.regions() > 0
                        ? actor + " gained " + event.regions() + " strategic region(s) from " + target + "."
                        : actor + " captured " + place + " from " + target
                        + (event.regions() > 0 ? " and gained " + event.regions() + " strategic region(s)." : ".");
                case SURRENDER -> actor + " surrendered to " + target + ".";
                case DEFEAT -> actor + " ceased to exist following defeat.";
                case BATTLE -> actor + " and " + target + " fought a battle.";
                case GOVERNMENT -> actor + " changed its government.";
                case RECOVERY -> actor + " recovered following a major defeat.";
            };
            result.add(new Report(event.id(), event.sequence(), event.time(), event.kind(), headline,
                    knownPlace ? event.settlement() : null));
        }
        return new Page(result, upper, result.isEmpty() ? 0 : result.getLast().sequence(), older, prunedThrough > 0);
    }

    private void trim() {
        while (events.size() > MAX_EVENTS) {
            Event removed = events.removeLast(); retainedIds.remove(removed.id()); prunedThrough = Math.max(prunedThrough, removed.sequence());
        }
    }

    public CompoundTag toNBT() {
        CompoundTag root = new CompoundTag(); root.putLong("NextSequence", nextSequence);
        root.putLong("PrunedThrough", prunedThrough);
        ListTag list = new ListTag();
        for (Event event : events) {
            CompoundTag tag = new CompoundTag(); tag.putString("Id", event.id().toString());
            tag.putLong("Sequence", event.sequence()); tag.putLong("Time", event.time()); tag.putString("Kind", event.kind().name());
            tag.putString("Actor", event.actor().toString()); tag.putString("ActorName", event.actorName());
            if (event.target() != null) tag.putString("Target", event.target().toString());
            tag.putString("TargetName", event.targetName()); tag.putString("SettlementName", event.settlementName());
            if (event.settlement() != null) tag.putString("Settlement", event.settlement().toString());
            tag.putInt("Regions", event.regions()); list.add(tag);
        }
        root.put("Events", list); return root;
    }

    /** Rebuild newest-first from validated records; damaged presentation cannot corrupt domain ownership. */
    public void loadNBT(CompoundTag root) {
        events.clear(); retainedIds.clear(); nextSequence = 1; prunedThrough = Math.max(0, root.getLong("PrunedThrough").orElse(0L));
        List<Event> valid = new ArrayList<>(); Set<Long> sequences = new HashSet<>();
        for (var value : root.getListOrEmpty("Events")) if (value instanceof CompoundTag tag) {
            try {
                long sequence = tag.getLong("Sequence").orElseThrow(), time = tag.getLong("Time").orElseThrow();
                UUID id = UUID.fromString(tag.getString("Id").orElseThrow());
                if (sequence < 1 || sequence == Long.MAX_VALUE || time < 0 || sequences.contains(sequence) || retainedIds.contains(id)) continue;
                Event event = new Event(id, sequence, time, Kind.valueOf(tag.getString("Kind").orElseThrow()),
                        UUID.fromString(tag.getString("Actor").orElseThrow()), StateManager.cleanName(tag.getString("ActorName").orElse("")),
                        tag.getString("Target").map(UUID::fromString).orElse(null), StateManager.cleanName(tag.getString("TargetName").orElse("")),
                        tag.getString("Settlement").map(UUID::fromString).orElse(null), StateManager.cleanName(tag.getString("SettlementName").orElse("")),
                        Math.max(0, tag.getInt("Regions").orElse(0)));
                retainedIds.add(id); sequences.add(sequence); valid.add(event); nextSequence = Math.max(nextSequence, sequence + 1);
            } catch (RuntimeException malformed) { /* Skip only the invalid news item, not the saved nation. */ }
        }
        valid.sort(Comparator.comparingLong(Event::sequence).reversed()); events.addAll(valid); trim();
        // Earlier saves already trimmed their history but did not carry an explicit presentation notice.
        if (!events.isEmpty()) prunedThrough = Math.max(prunedThrough, events.getLast().sequence() - 1);
        long savedNext = root.getLong("NextSequence").orElse(1L);
        if (savedNext > 0 && savedNext < Long.MAX_VALUE) nextSequence = Math.max(nextSequence, savedNext);
    }
}
