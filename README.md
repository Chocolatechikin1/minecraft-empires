# Minecraft Empires
What is it?
> A strategy and RPG-based mod with state development and combat, but with first-person survival and combat, all within Minecraft.

The official development repository for **Minecraft Empires**!

---

## Overview & Architecture

Minecraft Empires utilizes a Two-Layer Architecture designed to keep server performance smooth and lag-free, bypassing traditional entity limits:

* The Abstract Data Layer (Backend): Manages state finances, territory claims, public order, and army movements entirely in memory (RAM) as lightweight Java objects without spawning physical entities.
* The Immersive Render Layer (Frontend): When a player approaches an abstracted object, entity, or building, the mod dynamically converts abstract data into physical world elements. Cities would generate as players approach, and battlefield soldiers load in based on performance metrics of the user.

---

## Repository & Development Workflow

1. **GitHub Wiki:** All detailed information, including breakdowns, math scaling, and architectural diagrams can be found in the repository Wiki.
2. **Projects Board:** Sprint issues and task cards are managed under the repository **Projects** tab. (maybe)
3. **Pull Requests:** All development code undergoes review in separate branches before merging into `main`. (maybe)

--- 

## Tech Stack & Prerequisites
(Subject to change)
* **Language:** Java (JDK 25)
* **Target Platform:** Java Edition - NeoForge
* **Build System:** Gradle
* **IDE:** VS Code
* **Development Target Version:** 26.2

---

## Planned Sprints

Below is the active development schedule for the project. (Note: timeline may take longer than 20 weeks, and these sprints were made by AI so some may take longer for me than others)

---

### Phase 1: Core Territory Tracking & Claims (The Abstract Foundation)
* **Goal:** Build the backend system that registers chunk ownership in memory without relying on heavy physical blocks.
* **Key Mechanics:**
  * Define settlement tier logic (base settlement radius scaled to 100 blocks / ~156 chunks).
  * Build boundary restriction logic: enemy armies crossing borders flip un-garrisoned land until reaching a settlement’s protective radius.
* **Technical Implementation:** Using **Forge Capabilities** to track X/Z coordinate maps and serialize state ownership directly into world save data.

---

### Phase 2: State Progression & Treasury Systems
* **Goal:** Create the macro-economic engine and tier classification hierarchy.
* **Key Mechanics:**
  * Dual-key state progression matrix: state tiers scale dynamically based on both claimed territory footprints and total population milestones (from *Outpost* up to *Empire*).
  * Daily world-tick financial processing for tax yields and maintenance fees.
  * Integration of the "Siege Lock" timer and post-siege border immunity window (24,000 ticks / 1 full Minecraft day).
* **Technical Implementation:** Set up a lightweight server tick handler running every 24,000 world ticks to update treasury balances in memory.

---

### Phase 3: Interactive Map Dashboard UI
* **Goal:** Build the primary command center interface for all functions that need a UI.
* **Key Mechanics:**
  * Dynamic 2D top-down strategy view with live territory color overlays, provincial labels, and an interactive "Claim Core" button to purchase radius expansions via treasury emeralds. (can be split into multiple UIs, TBD)
  * **Obfuscated Fog of War:** Unexplored/unscouted territories hide precise enemy unit coordinates; breached provinces display a generalized warning overlay on the UI map.
  * Real-time chat alerts when provincial boundaries are breached.
* **Technical Implementation:** Construct a custom `Screen` / `ScreenHandler` UI layer fed directly by Phase 1 backend data.

---

### Phase 4: Abstract Army Data Structures & Morale Engine
* **Goal:** Program the "brains" and stats of military cohorts without spawning physical entities.
* **Key Mechanics:**
  * Define `Cohort` and `Legion` object structures (health, speed, endurance, loyalty, and **Morale** scored from 1–100).
  * **Morale & Routing:** At 0 morale, cohorts break and route. High-tier cohorts routing inflicts a chain-panic penalty on adjacent friendly units.
  * Daily emerald maintenance costs for active standing armies.
* **Technical Implementation:** Pure object-oriented Java classes holding army arrays and state variables in RAM.

---

### Phase 5: Army Management & Waypoint Dispatch System
* **Goal:** Connect army data to the Interactive Map UI for strategic maneuvering.
* **Key Mechanics:**
  * Sub-menus to raise, disband, or inspect armies using treasury funds.
  * **Real-Time Click Interaction & Waypoints:** Issue movement commands by clicking a unit box and right-clicking a target. Holding `Shift` + Right-Click queues multiple sequential path waypoints (e.g., for flanking actions).
  * **Auto-Engagement Rule:** Units following a path queue that physically collide with an enemy box drop remaining waypoints and lock into combat.
* **Technical Implementation:** Store movement order queues using a First-In, First-Out (`FIFO`) `Queue<BlockPos>` on the cohort object.

---

### Phase 6: Tactical Battle Map (Macro Combat Engine)
* **Goal:** Implement the primary bird's-eye battle map mode for resolving clashes.
* **Key Mechanics:**
  * Triggering battles when opposing forces close within 100 blocks.
  * Deployment phase featuring pre-set formation templates (e.g., *Roman Triplex Acies*, *Defensive Phalanx*).
  * Autoresolve calculation engine for battles occurring while the player is absent (factoring in garrisons, fortifications, and troop stats).
* **Technical Implementation:** Turn-based/tick-updated vector math updating unit grid coordinates without heavy mouse-dragging packets.

---

### Phase 7: Logistics, Infrastructure & Supply Lines
* **Goal:** Add historical depth to state building and military campaigns.
* **Key Mechanics:**
  * **Supply Lines:** Armies operating in unorganized/enemy lands require a continuous link to friendly settlements or camps via roads/adjacent territory; severed supply lines cause daily endurance and morale decay.
  * **Public Order & Stability:** Province unrest scales with high taxation or lack of garrisons; 0 public order triggers local rebellions.
  * Modular road construction granting abstract speed buffs across connected coordinate paths.
* **Technical Implementation:** Implement a graph/pathfinding check (like $A^*$) across owned chunks to evaluate supply connectivity.

---

### Phase 8: AI State Decision Engine (The Macro Brain)
* **Goal:** Bring the world to life with competing computer-controlled nations.
* **Key Mechanics:**
  * Background AI states managing finances, upgrading settlement tiers, declaring wars, and deploying "Ghost Legions".
  * Unified `EmpireState` class treating player nations and AI nations using identical backend data structures.
* **Technical Implementation:** Finite State Machines (FSM) or Utility AI algorithms executing during daily economic ticks to make macro decisions with minimal CPU overhead.

---

### Phase 9: World Generation & Procedural Infrastructure
* **Goal:** Alter the physical Minecraft world to reflect macro state data.
* **Key Mechanics:**
  * Structure generation for outposts, forts, and city centers.
  * **"Quantum" Cities & Structures:** Towns and walls exist purely as database flags until a player gets close, at which point the structure template system dynamically generates physical blocks.
* **Technical Implementation:** Custom structure processors and feature definitions leveraging Minecraft’s built-in structure template pools.

---

### Phase 10: "Hop In" Mode & Battlefield Entity Abstraction
* **Goal:** Bridge macro grand strategy with first-person, physical action.
* **Key Mechanics:**
  * **"Hop In" First-Person Deployment:** Equipping a **Command Banner** in the off-hand allows the player to physically spawn and tie a personal guard cohort to their player coordinates.
  * **The Emperor's Presence:** Personal guards gain max morale, and the wider army receives a flat morale boost. Player death triggers a catastrophic army-wide morale crash.
  * **Active UI Vulnerability:** Opening the strategy map mid-battle leaves the player's physical avatar stationary and exposed on the field.
  * **Render Wave Abstraction:** Battlefield entity caps (e.g., 100 vs. 100 active physical mobs) with background reinforcement waves continually pulling from remaining abstract army pools until reserves hit zero.
* **Technical Implementation:** Event listeners handling off-hand banner checks, entity attribute modifiers for morale buffs, and a dynamic entity spawning/despawning queue attached to player render distance.

---
