package com.devc.minecraftempires.commands;

import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateManager;
import com.devc.minecraftempires.state.StateTier;
import com.devc.minecraftempires.territory.SettlementData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

//current commands: 
// /state create <name> - creates a new state with the given name, with the player as the leader   
// /state info - displays information about the state the player leads (if any)
// /state ai - operator-only abstract generation/initialization diagnostics
// /state construction [retry <settlement id>] - checks build status of settlements the player leads, or requests a recheck of a specific settlement (if the player is the leader or an operator)
// /state locate [owned|pending|all|village|town|city|outpost|independent] - operator lookup of generated sites
// /state disband - disbanded the state the player leads (if any)
public class StateCommand {
    private static final List<String> SITE_FILTERS=List.of("owned","pending","all","village","town","city","outpost","independent");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("state")
            .then(Commands.literal("create")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(StateCommand::createState)
                )
            )
            .then(Commands.literal("info")
                .executes(StateCommand::stateInfo)
            )
            .then(Commands.literal("locate")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> locateSites(context,"owned"))
                .then(Commands.argument("filter",StringArgumentType.word())
                    .suggests((context,builder) -> {for(String filter:SITE_FILTERS)builder.suggest(filter);return builder.buildFuture();})
                    .executes(context -> locateSites(context,StringArgumentType.getString(context,"filter").toLowerCase(Locale.ROOT)))))
            .then(Commands.literal("construction")
                .executes(StateCommand::constructionInfo)
                .then(Commands.literal("retry")
                    .then(Commands.argument("settlement", StringArgumentType.word())
                        .suggests((context,builder) -> {
                            var source=context.getSource();var manager=StateManager.get(source.getLevel());
                            if(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) {
                                manager.getAllSettlements().stream().filter(site->site.isAbstractSettlement()&&!site.isIndependent()).limit(128)
                                        .forEach(site->builder.suggest(site.getSettlementId().toString(),Component.literal(site.getSettlementName())));
                                return builder.buildFuture();
                            }
                            var player=source.getPlayer();if(player!=null){var nation=manager.getStateByPlayer(player.getUUID());
                                if(nation!=null)for(var site:manager.settlementsFor(nation))builder.suggest(site.getSettlementId().toString(),Component.literal(site.getSettlementName()));}
                            return builder.buildFuture();
                        })
                        .executes(StateCommand::constructionRetry))))
            .then(Commands.literal("ai")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> {
                    StateManager manager = StateManager.get(context.getSource().getLevel());
                    context.getSource().sendSuccess(() -> Component.literal("AI generation "
                            + (com.devc.minecraftempires.Config.AI_GENERATION_ENABLED.get() ? "enabled: " : "disabled: ")
                            + manager.getRegionInfo().status() + "; macro wars " + manager.getAIWars().status() + "; player handover " + manager.getPlayerConflicts().status()
                            + "; construction " + manager.getConstruction().status() + "; settlement registration " + manager.getDevelopment().recoveryStatus()), false);
                    return 1;
                })
            )
            .then(Commands.literal("disband")
                .executes(StateCommand::disbandState)
            )
            .then(Commands.literal("leave")
                .executes(StateCommand::leaveState)
            )
        );
    }

    private static int locateSites(CommandContext<CommandSourceStack> context,String filter) {
        var source=context.getSource();var level=source.getLevel();
        if(level!=level.getServer().overworld()){source.sendFailure(Component.literal("Use the settlement locator in the Overworld."));return 0;}
        if(!SITE_FILTERS.contains(filter)){source.sendFailure(Component.literal("Choose a site filter: "+String.join(", ",SITE_FILTERS)));return 0;}
        var manager=StateManager.get(level);var construction=manager.getConstruction();BlockPos origin=BlockPos.containing(source.getPosition());
        Comparator<SettlementData> nearest=Comparator.comparingDouble((SettlementData site)->siteDistanceSquared(site,origin)).thenComparing(SettlementData::getSettlementId);
        PriorityQueue<SettlementData> sites=new PriorityQueue<>(6,nearest.reversed());int matches=0;
        for(var site:manager.getAllSettlements()) {
            if(!site.isAbstractSettlement())continue;
            boolean accepted=switch(filter) {
                case "all" -> true;
                case "independent" -> site.isIndependent();
                case "owned","pending" -> !site.isIndependent();
                default -> !site.isIndependent()&&site.getKind().name().equalsIgnoreCase(filter);
            };
            if(!accepted)continue;
            if(filter.equals("pending")) {
                String phase=construction.inspect(level,site,origin).phase();if(phase.equals("REJECTED")||phase.equals("COMPLETE"))continue;
            }
            matches++;sites.add(site);if(sites.size()>5)sites.poll();
        }
        String header="Settlement plans: "+matches+" match "+filter+"; showing nearest "+sites.size()+". NPC generation "+(com.devc.minecraftempires.Config.AI_GENERATION_ENABLED.get()?"enabled":"disabled")
                +"; construction "+(com.devc.minecraftempires.Config.PHYSICAL_CONSTRUCTION_ENABLED.get()?"enabled":"disabled")+"; range "+com.devc.minecraftempires.Config.CONSTRUCTION_RANGE.get()+" blocks; service "+construction.status()+".";
        source.sendSuccess(()->Component.literal(header),false);
        if(matches==0){
            if(manager.getAllSettlements().stream().noneMatch(SettlementData::isAbstractSettlement)) {
                source.sendSuccess(()->Component.literal("No settlement plans have been committed yet. NPC initialization: "+manager.getRegionInfo().status()),false);
                source.sendSuccess(()->Component.literal("Initialization advances while the world is unpaused. Check /state ai for progress; terrain and nation planning finish before sites appear here."),false);
                if(source.getPlayer()!=null&&source.getPlayer().isSpectator())source.sendSuccess(()->Component.literal("Spectators do not trigger NPC generation or construction. Use Creative or Survival for exploration testing."),false);
            } else source.sendSuccess(()->Component.literal("No matching generated sites are registered. Try /state locate all or check /state ai. This lookup does not generate new plans or terrain."),false);
            return 0;
        }
        if(source.getPlayer()!=null&&source.getPlayer().isSpectator())source.sendSuccess(()->Component.literal("Spectators can locate plans, but do not trigger their construction."),false);
        for(var site:sites.stream().sorted(nearest).toList()) {
            var pos=site.getCenterAltarPos();var nation=manager.getState(site.getOwningStateId());var progress=construction.inspect(level,site,origin);
            String owner=site.isIndependent()?"Independent":nation==null?"Missing state":nation.getStateName();long distance=Math.round(Math.sqrt(siteDistanceSquared(site,origin)));
            var color=progress.phase().equals("REJECTED")||progress.phase().equals("PAUSED")||progress.phase().equals("BLOCKED")?ChatFormatting.RED:progress.phase().equals("COMPLETE")?ChatFormatting.GREEN:ChatFormatting.YELLOW;
            String teleport="/tp @s "+(pos.getX()+.5)+" "+Math.min(level.getMaxY()-2,Math.max(120,pos.getY()+24))+" "+(pos.getZ()+.5);
            var entry=Component.literal(site.getSettlementName()+" ["+site.getKind()+"; "+owner+"] "+pos.toShortString()+" · "+distance+" blocks · stage "+(progress.stage()+1)+" ")
                    .append(Component.literal(progress.phase()).withStyle(color)).append(" ")
                    .append(Component.literal("[TP]").withStyle(style->style.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.SuggestCommand(teleport)).withHoverEvent(new HoverEvent.ShowText(Component.literal("Fill teleport command above the planned center (Creative recommended)")))));
            source.sendSuccess(()->entry,false);
            if(!progress.detail().isBlank())source.sendSuccess(()->Component.literal("  "+progress.detail()),false);
        }
        return sites.size();
    }
    private static double siteDistanceSquared(SettlementData site,BlockPos origin) {
        double dx=(double)site.getCenterAltarPos().getX()-origin.getX(),dz=(double)site.getCenterAltarPos().getZ()-origin.getZ();return dx*dx+dz*dz;
    }

    private static int constructionInfo(CommandContext<CommandSourceStack> context) {
        var source=context.getSource();var player=source.getPlayer();if(player==null){source.sendFailure(Component.literal("Use this command as a state member."));return 0;}
        var manager=StateManager.get(player.level());var nation=manager.getStateByPlayer(player.getUUID());
        if(nation==null){source.sendFailure(Component.literal("Join or found a state to inspect its construction."));return 0;}
        var progress=manager.getConstruction().forState(nation.getStateId());
        source.sendSuccess(()->Component.literal("Construction: "+(com.devc.minecraftempires.Config.PHYSICAL_CONSTRUCTION_ENABLED.get()?"enabled":"disabled")+"; "+progress.size()+" saved stages. Nearby new terrain is checked automatically."),false);
        for(var entry:progress.stream().limit(16).toList()){
            var site=manager.getSettlement(entry.settlement());String detail=entry.detail().isBlank()?"":": "+entry.detail();
            source.sendSuccess(()->Component.literal(site.getSettlementName()+" · stage "+(entry.stage()+1)+" · "+entry.phase()+" "+entry.placed()+"/"+entry.total()+detail),false);
        }
        return 1;
    }
    private static int constructionRetry(CommandContext<CommandSourceStack> context) {
        var source=context.getSource();var player=source.getPlayer();boolean operator=Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
        if(source.getLevel()!=source.getServer().overworld()){source.sendFailure(Component.literal("Request construction rechecks in the Overworld."));return 0;}
        var manager=StateManager.get(source.getLevel());var nation=player==null?null:manager.getStateByPlayer(player.getUUID());
        if(!operator&&(nation==null || !nation.getLeaderId().equals(player.getUUID()))){source.sendFailure(Component.literal("Only the state leader or an operator can request a construction recheck."));return 0;}
        UUID site;try{site=UUID.fromString(StringArgumentType.getString(context,"settlement"));}catch(IllegalArgumentException invalid){source.sendFailure(Component.literal("Choose a valid settlement ID."));return 0;}
        UUID owner;
        if(operator) {
            var settlement=manager.getSettlement(site);
            if(settlement==null||!settlement.isAbstractSettlement()){source.sendFailure(Component.literal("Choose an existing generated settlement."));return 0;}
            if(settlement.isIndependent()){source.sendFailure(Component.literal("Independent physical settlements are not implemented yet; a recheck cannot build them."));return 0;}
            owner=settlement.getOwningStateId();
        } else owner=nation.getStateId();
        String result=manager.getConstruction().retry(owner,site);source.sendSuccess(()->Component.literal(result),false);return 1;
    }

    private static int createState(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        if (source.getPlayer() != null) {
            UUID leaderId = source.getPlayer().getUUID();
            StateManager manager = StateManager.get((ServerLevel) source.getLevel());
            
            //bug/exploit fix: prevent infinite state creation
            StateData existingState = manager.getStateByPlayer(leaderId);
            if (existingState != null) {
                source.sendFailure(Component.literal("§c[Minecraft Empires] You are already part of a state! You must leave or /state disband it first."));
                return 0; //Cancel the command execution
            }
            // Create a base Tier 1 (County) State for testing and save the returned data object
            StateData newState = manager.createState(name, leaderId, StateTier.COUNTY);
            
            // NEW MAPPING FIX: Explicitly add player to the map so CityAltarBlock can validate them
            manager.addPlayerToState(leaderId, newState.getStateId());
            
            source.sendSuccess(() -> Component.literal("§a[Minecraft Empires] Successfully founded the Realm of " + name + "!"), false);
            return 1;
        }
        return 0;
    }

    private static int stateInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (source.getPlayer() != null) {
            UUID playerId = source.getPlayer().getUUID();
            StateManager manager = StateManager.get((ServerLevel) source.getLevel());
            
            // Find the state this player leads
            StateData playerState = null;
            for (StateData state : manager.getAllStates()) {
                if (state.getLeaderId() != null && state.getLeaderId().equals(playerId)) {
                    playerState = state;
                    break;
                }
            }
            
            if (playerState != null) {
                final StateData foundState = playerState; //assign to final variable for lambda (preventing "variable used in lambda should be final or effectively final" error)

                source.sendSuccess(() -> Component.literal("§6--- State of " + foundState.getStateName() + " ---"), false);
                source.sendSuccess(() -> Component.literal("§eTier: §f" + foundState.getCurrentTier().name()), false);
                source.sendSuccess(() -> Component.literal("§eTreasury: §f" + foundState.getTreasuryBalance() + " emeralds"), false);
                source.sendSuccess(() -> Component.literal("§ePopulation: §f" + foundState.getTotalPopulation()), false);
                //dont think we need a siege tick display but if we do uncomment this line
                //source.sendSuccess(() -> Component.literal("§eSiege Lock Ticks: §f" + foundState.getSiegeImmunityTicks()), false);
            } else {
                source.sendFailure(Component.literal("Place a City Altar to found your state."));
            }
            return 1;
        }
        return 0;
    }

    private static int disbandState(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (source.getPlayer() != null) {
            UUID playerId = source.getPlayer().getUUID();
            ServerLevel level = (ServerLevel) source.getLevel();
            StateManager manager = StateManager.get(level);

            StateData existingState = manager.getStateByPlayer(playerId);
            if (existingState == null) {
                source.sendFailure(Component.literal("§c[Minecraft Empires] No state to disband."));
                return 0;
            }

            // Ensure only the leader can destroy the state
            if (!playerId.equals(existingState.getLeaderId())) {
                source.sendFailure(Component.literal("§c[Minecraft Empires] Only current leader can disband the state."));
                return 0;
            }

            String stateName = existingState.getStateName();
            if (!manager.disbandState(existingState.getStateId(), level)) {
                source.sendFailure(Component.literal("End all campaigns and battles before disbanding your state."));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("§a[Minecraft Empires] " + stateName + " successfully disbanded."), true);
            
            return 1;
        }
        return 0;
    }

    private static int leaveState(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (source.getPlayer() != null) {
            UUID playerId = source.getPlayer().getUUID();
            StateManager manager = StateManager.get((ServerLevel) source.getLevel());

            StateData existingState = manager.getStateByPlayer(playerId);
            if (existingState == null) {
                source.sendFailure(Component.literal("§c[Minecraft Empires] You are not currently part of any state."));
                return 0;
            }

            if (playerId.equals(existingState.getLeaderId())) {
                source.sendFailure(Component.literal("§c[Minecraft Empires] You are the leader of this state! You must transfer leadership or use /state disband."));
                return 0;
            }

            manager.leaveState(playerId);
            source.sendSuccess(() -> Component.literal("§a[Minecraft Empires] You have resigned as a citizen of " + existingState.getStateName() + "."), true);
            return 1;
        }
        return 0;
    }
}
