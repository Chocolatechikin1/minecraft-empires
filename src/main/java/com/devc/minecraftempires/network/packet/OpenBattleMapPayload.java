package com.devc.minecraftempires.network.packet;

import com.devc.minecraftempires.MinecraftEmpires;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

//battle initiation packet sent from server to client to open the battle map screen for a specific battle
//sent either when player specatates a battle or when a battle starts and the player is involved
public record OpenBattleMapPayload(UUID battleId, UUID attackerArmyId,UUID defenderArmyId) implements CustomPacketPayload {
    public static final Type<OpenBattleMapPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MinecraftEmpires.MODID, "open_battle_map"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenBattleMapPayload> STREAM_CODEC = StreamCodec.ofMember(OpenBattleMapPayload::write, OpenBattleMapPayload::new);

    public OpenBattleMapPayload(RegistryFriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readUUID(), buf.readUUID());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(battleId);
        buf.writeUUID(attackerArmyId);
        buf.writeUUID(defenderArmyId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
