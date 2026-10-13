package com.devc.minecraftempires.combat;

import com.devc.minecraftempires.army.Cohort;
import net.minecraft.nbt.CompoundTag;
import org.joml.Vector2d;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

//data for a single cohort in a battle
/** A battle snapshot; health is surviving soldiers / original formation strength. */
public class CohortData {
    private final UUID cohortId;
    private final String type;
    private final int maxHealth;
    private int currentHealth;
    private int morale;
    private final double speed;
    private final int strength;
    private final int endurance;
    private boolean routing;
    private boolean routAnnounced;
    private boolean engaged;
    private boolean withdrawing;
    private boolean escaped;
    private int withdrawalSteps;
    private final Vector2d position;
    private final Vector2d previousPosition;
    private final Queue<Vector2d> movementWaypoints = new ArrayDeque<>();

    public CohortData(UUID id, String type, int maxHealth, int morale, double speed,
                      int strength, int endurance, double x, double z) {
        this.cohortId = id;
        this.type = type;
        this.maxHealth = Math.max(1, maxHealth);
        this.currentHealth = this.maxHealth;
        this.morale = Math.clamp(morale, 0, 100);
        this.speed = Math.max(0.1, speed);
        this.strength = strength;
        this.endurance = endurance;
        position = new Vector2d(x, z);
        previousPosition = new Vector2d(x, z);
    }

    public static CohortData fromCohort(Cohort cohort, double x, double z) {
        CohortData data = new CohortData(cohort.getCohortId(), cohort.getType().name(),
                cohort.getMaxSoldiers(), cohort.getMorale(), cohort.getSpeed() / 50.0,
                cohort.getStrength(), cohort.getEndurance(), x, z);
        data.currentHealth = cohort.getSoldierCount();
        data.routing = cohort.isRouting() || cohort.getMorale() == 0;
        return data;
    }

    public void tickMovement() {
        previousPosition.set(position);
        if (!isAlive() || escaped) return;
        if (withdrawalSteps > 0) { withdrawalSteps--; return; }
        if (movementWaypoints.isEmpty() || (engaged && !routing && !withdrawing)) return;
        Vector2d target = movementWaypoints.peek();
        double step = speed * (routing ? 1.25 : 1.0);
        if (position.distance(target) <= step) {
            position.set(target);
            movementWaypoints.poll();
            if ((routing || withdrawing) && movementWaypoints.isEmpty()) escaped = true;
        } else {
            position.add(new Vector2d(target).sub(position).normalize().mul(step));
        }
    }

    public void applyDamage(int damage, int moraleShock) {
        currentHealth = Math.max(0, currentHealth - Math.max(0, damage));
        applyMoraleShock(moraleShock);
    }

    public void applyMoraleShock(int shock) {
        morale = Math.clamp(morale - Math.max(0, shock), 0, 100);
        if (morale == 0) routing = true;
    }

    public boolean announceRout() {
        if (!routing || routAnnounced) return false;
        routAnnounced = true;
        return true;
    }

    public boolean isAlive() { return currentHealth > 0; }
    public boolean canFight() { return isAlive() && !routing && !escaped; }
    public boolean canTakeOrders() { return canFight() && !withdrawing; }
    public void setEngaged(boolean value) {
        if (value && !engaged && !routing && !withdrawing) clearOrders();
        engaged = value;
    }
    public void queueWaypoint(double x, double z) {
        if (movementWaypoints.size() < 64) movementWaypoints.add(new Vector2d(x, z));
    }
    public void setRetreatWaypoint(double x, double z) {
        if (escaped) return;
        movementWaypoints.clear();
        queueWaypoint(x, z);
    }
    public void beginWithdrawal(double x, double z) {
        if (!canTakeOrders()) return;
        withdrawing = true;
        withdrawalSteps = engaged ? 12 : 0; // three seconds at four simulation steps/second
        setRetreatWaypoint(x, z);
    }
    public void place(double x, double z) {
        position.set(x, z);
        previousPosition.set(position);
        clearOrders();
    }
    public void clearOrders() { movementWaypoints.clear(); }
    public UUID getCohortId() { return cohortId; }
    public String getType() { return type; }
    public int getCurrentHealth() { return currentHealth; }
    public int getMaxHealth() { return maxHealth; }
    public int getMorale() { return morale; }
    public double getSpeed() { return speed; }
    public int getStrength() { return strength; }
    public int getEndurance() { return endurance; }
    public Vector2d getPosition() { return position; }
    public Vector2d getPreviousPosition() { return previousPosition; }
    public boolean isRouting() { return routing; }
    public void setRouting(boolean value) { routing = value; }
    public boolean isEngaged() { return engaged; }
    public boolean isWithdrawing() { return withdrawing; }
    public boolean hasEscaped() { return escaped; }
    public Queue<Vector2d> getMovementWaypoints() { return movementWaypoints; }

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", cohortId.toString());
        tag.putString("Type", type);
        tag.putInt("Max", maxHealth);
        tag.putInt("Current", currentHealth);
        tag.putInt("Morale", morale);
        tag.putDouble("Speed", speed);
        tag.putInt("Strength", strength);
        tag.putInt("Endurance", endurance);
        tag.putDouble("X", position.x);
        tag.putDouble("Z", position.y);
        tag.putBoolean("Routing", routing);
        tag.putBoolean("Escaped", escaped);
        tag.putBoolean("Withdrawing", withdrawing);
        return tag;
    }
    public static CohortData fromNBT(CompoundTag tag) {
        CohortData data = new CohortData(UUID.fromString(tag.getString("Id").orElseThrow()),
                tag.getString("Type").orElse("INFANTRY"), tag.getInt("Max").orElse(50),
                tag.getInt("Morale").orElse(50), tag.getDouble("Speed").orElse(1.0),
                tag.getInt("Strength").orElse(50), tag.getInt("Endurance").orElse(50),
                tag.getDouble("X").orElse(0.0), tag.getDouble("Z").orElse(0.0));
        data.currentHealth = Math.clamp(tag.getInt("Current").orElse(data.maxHealth), 0, data.maxHealth);
        data.routing = tag.getBoolean("Routing").orElse(false);
        data.escaped = tag.getBoolean("Escaped").orElse(false);
        data.withdrawing = tag.getBoolean("Withdrawing").orElse(false);
        return data;
    }
}
