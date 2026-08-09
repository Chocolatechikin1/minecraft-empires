package com.devc.minecraftempires.combat;

import com.devc.minecraftempires.army.Cohort;
import org.joml.Vector2d;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

//data for a single cohort in a battle
public class CohortData {
    private final UUID cohortId;   //matches the source Cohort's cohortId for result write-back
    private final String type;     //"INFANTRY", "CAVALRY", "AUXILIARY"
    private int currentHealth;  //current soldier count alive
    private final int maxHealth;      //soldier count at battle start
    private int morale;         //0–100; hitting 0 triggers routing
    private final double speed;         //battle-units per tick
    private final int strength;      //raw combat output; affects damage dealt

    private final int endurance;     //fatigue resistance (reserved for future attrition calc)
    private boolean isRouting = false;

    //position variables
    private Vector2d position;
    private Vector2d previousPosition; //for client-side lerp (linear interpolation)
    private final Queue<Vector2d> movementWaypoints;

    public CohortData(UUID cohortId, String type, int maxHealth, int morale, double speed, int strength, int endurance, double startX, double startZ) {
        this.cohortId          = cohortId;
        this.type              = type;
        this.maxHealth         = maxHealth;
        this.currentHealth     = maxHealth;
        this.morale            = morale;
        this.speed             = speed;
        this.strength          = strength;
        this.endurance         = endurance;
        this.position          = new Vector2d(startX, startZ);
        this.previousPosition  = new Vector2d(startX, startZ);
        this.movementWaypoints = new LinkedList<>();
    }

    //pulls cohort data from a Cohort object
    public static CohortData fromCohort(Cohort cohort, double startX, double startZ) {
        // speed: Cohort.speed 0–100 → battle speed 0–2.0 (average stat = 50 → 1.0 unit/tick)
        double battleSpeed = cohort.getSpeed() / 50.0;
        return new CohortData(
                cohort.getCohortId(),
                cohort.getType().name(),
                cohort.getSoldierCount(),
                cohort.getMorale(),
                battleSpeed,
                cohort.getStrength(),
                cohort.getEndurance(),
                startX,
                startZ
        );
    }

    //cohort speed variable
    public void tickMovement() {
        if (movementWaypoints.isEmpty() || morale <= 0) return;

        Vector2d target   = movementWaypoints.peek();
        double   distance = position.distance(target);

        previousPosition.set(position);

        if (distance <= speed) {
            position.set(target);
            movementWaypoints.poll();
        } else {
            Vector2d direction = new Vector2d(target).sub(position).normalize();
            position.add(direction.mul(speed));
        }
    }

    //combat tracking
    
    //damage tracker: damage affects health, morale for morale
    public void applyDamage(int damage, int moraleShock) {
        this.currentHealth = Math.max(0, this.currentHealth - damage);
        this.morale        = Math.max(0, this.morale - moraleShock);
        if (this.morale == 0) {
            this.isRouting = true;
        }
    }

    //hits a cohort if a neighoring cohort routs
    public void applyMoraleShock(int shock) {
        this.morale = Math.max(0, this.morale - shock);
        if (this.morale == 0) {
            this.isRouting = true;
        }
    }

    //gets cohort troop count
    public boolean isAlive() {
        return currentHealth > 0;
    }

    //waypoint queuer
    public void queueWaypoint(double x, double z) {
        movementWaypoints.add(new Vector2d(x, z));
    }

    //if unit retreats, wipes existing waypoints and sets a retreat waypoint
    public void setRetreatWaypoint(double x, double z) {
        movementWaypoints.clear();
        movementWaypoints.add(new Vector2d(x, z));
    }

    public void clearOrders() {
        movementWaypoints.clear();
    }

    //getters and setters
    public UUID     getCohortId()           { return cohortId; }
    public String   getType()               { return type; }
    public int      getCurrentHealth()      { return currentHealth; }
    public int      getMaxHealth()          { return maxHealth; }
    public int      getMorale()             { return morale; }
    public double   getSpeed()              { return speed; }
    public int      getStrength()           { return strength; }
    public int      getEndurance()          { return endurance; }
    public Vector2d getPosition()           { return position; }
    public Vector2d getPreviousPosition()   { return previousPosition; }
    public boolean  isRouting()             { return isRouting; }
    public void     setRouting(boolean v)   { this.isRouting = v; }
    public Queue<Vector2d> getMovementWaypoints() { return movementWaypoints; }
}