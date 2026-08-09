package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import com.devc.minecraftempires.combat.BattleSession;
import com.devc.minecraftempires.combat.CohortData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

//position, morale and health tracking for all cohorts in a battle. server to client
public record BattleSyncPayload(UUID battleId, List<CohortSnapshot> attackerCohorts, List<CohortSnapshot> defenderCohorts, String battlePhase, int deploymentTicksRemaining) implements CustomPacketPayload {
    public static final Type<BattleSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "battle_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BattleSyncPayload> STREAM_CODEC = StreamCodec.ofMember(BattleSyncPayload::write, BattleSyncPayload::new);

    public BattleSyncPayload(RegistryFriendlyByteBuf buf) {
        this(
            buf.readUUID(),
            readSnapshots(buf),
            readSnapshots(buf),
            buf.readUtf(),
            buf.readVarInt()
        );
    }

    //encoder
    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(battleId);
        writeSnapshots(buf, attackerCohorts);
        writeSnapshots(buf, defenderCohorts);
        buf.writeUtf(battlePhase);
        buf.writeVarInt(deploymentTicksRemaining);
    }

    //helper methods for encoding/decoding lists of CohortSnapshot objects
    private static void writeSnapshots(RegistryFriendlyByteBuf buf, List<CohortSnapshot> list) {
        buf.writeVarInt(list.size());
        for (CohortSnapshot s : list) s.write(buf);
    }

    //decoder
    private static List<CohortSnapshot> readSnapshots(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<CohortSnapshot> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(new CohortSnapshot(buf)); //for loop gives O(n) complexity, but the list is small enough that it doesn't matter
        return list;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    //server side: builds a snapshot of the current battle state for transmission to the client
    public static BattleSyncPayload fromSession(BattleSession session) {
        return new BattleSyncPayload(
                session.getBattleId(),
                snapshotList(session.getAttackerCohorts()),
                snapshotList(session.getDefenderCohorts()),
                session.getPhase().name(),
                session.getDeploymentTicksRemaining()
        );
    }

    //helper method to convert a list of CohortData objects into a list of CohortSnapshot objects for transmission
    private static List<CohortSnapshot> snapshotList(List<CohortData> cohorts) {
        List<CohortSnapshot> list = new ArrayList<>(cohorts.size());
        for (CohortData c : cohorts) {
            list.add(new CohortSnapshot(
                    c.getCohortId(),
                    c.getPosition().x,
                    c.getPosition().y,
                    c.getCurrentHealth(),
                    c.getMaxHealth(),
                    c.getMorale(),
                    c.getType(),
                    c.isRouting()
            ));
        }
        return list;
    }

    //snapshot of a single cohort's state for transmission to the client, including position, health, morale, type, and routing status
    public record CohortSnapshot( UUID cohortId, double x, double z, int currentHealth, int maxHealth, int morale, String type,boolean isRouting) {
        CohortSnapshot(RegistryFriendlyByteBuf buf) { //decode the data from the buffer when received
            this(
                buf.readUUID(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readUtf(),
                buf.readBoolean()
            );
        }

        void write(RegistryFriendlyByteBuf buf) { //encode the data into the buffer for transmission
            buf.writeUUID(cohortId);
            buf.writeDouble(x);
            buf.writeDouble(z);
            buf.writeVarInt(currentHealth);
            buf.writeVarInt(maxHealth);
            buf.writeVarInt(morale);
            buf.writeUtf(type);
            buf.writeBoolean(isRouting);
        }
    }
}
