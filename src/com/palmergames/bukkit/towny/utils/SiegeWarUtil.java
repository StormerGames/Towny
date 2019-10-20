package com.palmergames.bukkit.towny.utils;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyFormatter;
import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.exceptions.*;
import com.palmergames.bukkit.towny.object.*;
import com.palmergames.bukkit.towny.war.siegewar.Siege;
import com.palmergames.bukkit.towny.war.siegewar.SiegeStatus;
import com.palmergames.bukkit.towny.war.siegewar.SiegeStats;
import com.palmergames.bukkit.util.BukkitTools;
import com.palmergames.bukkit.util.ChatTools;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Created by Anonymoose on 19/05/2019.
 */
public class SiegeWarUtil {

    public final static long ONE_SECOND_IN_MILLIS = 1000;
    public final static long ONE_MINUTE_IN_MILLIS = ONE_SECOND_IN_MILLIS * 60;
    public final static long ONE_HOUR_IN_MILLIS = ONE_MINUTE_IN_MILLIS * 60;
    public final static long ONE_DAY_IN_MILLIS = ONE_HOUR_IN_MILLIS * 24;

    public static void attackTown(Nation attackingNation,
                                    Town defendingTown) throws TownyException {

        Siege siege;
        boolean attackerJoinedSiege;

        if(!defendingTown.hasSiege()) {
            attackerJoinedSiege =false;
            TownyUniverse.getDataSource().newSiege(defendingTown.getName());
            siege = TownyUniverse.getDataSource().getSiege(defendingTown.getName());

            //Setup siege values
            siege.setStatus(SiegeStatus.IN_PROGRESS);
            siege.setTownPlundered(false);
            siege.setTownInvaded(false);
            siege.setAttackerWinner(null);
            siege.setActualStartTime(System.currentTimeMillis());
            siege.setScheduledEndTime((System.currentTimeMillis() +
                    ((long)(TownySettings.getWarSiegeMaxHoldoutTimeHours() * ONE_HOUR_IN_MILLIS))));
            siege.setActualEndTime(0);
            siege.setNextUpkeepTime(System.currentTimeMillis() + ONE_MINUTE_IN_MILLIS);

            siege.setSiegeStatsDefenders(new SiegeStats());
            siege.getSiegeStatsDefenders().setActive(true);

            siege.setSiegeStatsAttackers(new HashMap<Nation, SiegeStats>());
            siege.getSiegeStatsAttackers().put(attackingNation, new SiegeStats());
            siege.getSiegeStatsAttackers().get(attackingNation).setActive(true);

            //Link siege to town
            defendingTown.setSiege(siege);
        } else {
            attackerJoinedSiege = true;
            siege = defendingTown.getSiege();
            if(!(siege.getStatus() == SiegeStatus.IN_PROGRESS))
                throw new TownyException("The town is in a siege cooldown period.");
            //Add new siege attack
            siege.getSiegeStatsAttackers().put(attackingNation,new SiegeStats());
            siege.getSiegeStatsAttackers().get(attackingNation).setActive(true);
        }

        //Link siege to nation
        attackingNation.addSiege(siege);

        //Save siege, nation, and town
        TownyUniverse.getDataSource().saveSiege(siege);
        TownyUniverse.getDataSource().saveNation(attackingNation);
        TownyUniverse.getDataSource().saveTown(defendingTown);

        //Save siege list if required
        if(!attackerJoinedSiege)
            TownyUniverse.getDataSource().saveSiegeList();

        //Send global message;
        if(attackerJoinedSiege) {
            TownyMessaging.sendGlobalMessage(
                    "The nation of " + attackingNation.getName() +
                            " has joined the siege on the town of " + defendingTown.getName());
        } else {
            TownyMessaging.sendGlobalMessage(
                    "The nation of " + attackingNation.getName() +
                            " has initiated a siege on the town of " + defendingTown.getName());
        }


        //BukkitTools.getPluginManager().callEvent(new NewNationEvent(nation));
        //TODO - Do we announce a new siege event like this???
    }

    public static void revolt(Towny plugin, Resident resident, Town town) {
        try {
            Nation nation = town.getNation();
            removeTownFromNation(plugin, town, nation);

            TownyMessaging.sendGlobalMessage(
                    String.format(
                            TownySettings.getLangString("msg_siege_war_revolt"),
                            TownyFormatter.getFormattedTownName(town),
                            TownyFormatter.getFormattedResidentName(resident),
                            TownyFormatter.getFormattedNationName(town.getNation())));

            //Turn OFF siege cooldown
            town.setSiegeCooldownEndTime(0);

            //Tell town that siege cooldown has been reset to off
            TownyMessaging.sendTownMessage(town, TownySettings.getLangString("msg_siege_war_post_revolt_siege_cooldown_reset"));

            //Turn ON revolt cooldown
            long revoltCooldownDurationMillis = (long)(TownySettings.getWarSiegeRevoltCooldownHours() * ONE_HOUR_IN_MILLIS);
            long revoltCooldownEndTime= System.currentTimeMillis() + revoltCooldownDurationMillis;
            town.setRevoltCooldownEndTime(revoltCooldownEndTime);

            if(nation.getTowns().size() == 0) {
                TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                        TownySettings.getLangString("msg_siege_war_nation_defeated"),
                        TownyFormatter.getFormattedNationName(nation)
                )));
            }

        } catch (NotRegisteredException x) {
            //We shouldn't get here as we already checked for nation
        }
    }


    ///////////////////PROCESS ONGOING SIEGE ACTIVITY //////////////////////

    public static boolean isPlayerWithinSiegeZone(Player player,
                                                  Town town) {
        return isWorldCoordWithinSiegeZone(WorldCoord.parseWorldCoord(player), town);
    }


    public static boolean isTownBlockWithinSiegeZone(TownBlock townBlock,
                                                     Town town) {

        return isWorldCoordWithinSiegeZone(townBlock.getWorldCoord(), town);
    }


    public static boolean isWorldCoordWithinSiegeZone(WorldCoord worldCoord,
                                                      Town town)  {
        if(!town.hasHomeBlock())
            return false;

        TownBlock homeBlock = null;
        try {
            homeBlock = town.getHomeBlock();
        } catch (TownyException x) {
            //We won't get here as we returned earlier if there was no homeblock.
        }

        int siegeZoneRadiusInTownBlocks = TownySettings.getWarSiegeZoneDistanceFromTown();

        //Player is too far north
        if (worldCoord.getZ() < homeBlock.getZ() - siegeZoneRadiusInTownBlocks)
            return false;

        //Player is too far south
        if (worldCoord.getZ() > homeBlock.getZ() + siegeZoneRadiusInTownBlocks)
            return false;

        //Player is too far east
        if (worldCoord.getX() > homeBlock.getX() + siegeZoneRadiusInTownBlocks)
            return false;

        //Player is too far west
        if (worldCoord.getX() < homeBlock.getX() - siegeZoneRadiusInTownBlocks)
            return false;

        return true; //Co-ordinate is within the siegezone
    }

    public static boolean isTownBlockOnTheTownBorder(TownBlock townBlock, Town town) {
        WorldCoord worldCoord = townBlock.getWorldCoord();

        int[][] offset = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };
        for (int i = 0; i < 4; i++)
            try {
                TownBlock edgeTownBlock = worldCoord.getTownyWorld().getTownBlock(new Coord(worldCoord.getX() + offset[i][0], worldCoord.getZ() + offset[i][1]));
                boolean sameTown = (edgeTownBlock.getTown() == town);
                if (!sameTown)
                    return true; //If the adjacent plot is in a different town, return true
            } catch (NotRegisteredException e) {
                return true;  //If the adjacent plot is not in a town, return true
            }
        return false;
    }

    public static boolean doesPlayerHaveANonAirBlockAboveThem(Player player) {
        Location loc = player.getEyeLocation().add(0,1,0);

        while(loc.getY() < 256)
        {
            if(loc.getBlock().getType() != Material.AIR)
            {
                return true;   //There is a non-air block above them
            }
            loc.add(0,1,0);
        }
        return false;  //There is nothing but air above them
    }


    public static void captureTown(Towny plugin, Siege siege, Nation attackingNation, Town defendingTown) {
        if(defendingTown.hasNation()) {

            Nation nationOfDefendingTown = null;
            try {
                nationOfDefendingTown = defendingTown.getNation();
            } catch (NotRegisteredException x) {
                //This won't happen because we checked for a nation just above
            }

            //Remove town from nation (and nation itself if empty)
            removeTownFromNation(plugin, defendingTown, nationOfDefendingTown);

            addTownToNation(plugin, defendingTown, attackingNation);

            TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                    TownySettings.getLangString("msg_siege_war_nation_town_captured"),
                    TownyFormatter.getFormattedTownName(defendingTown),
                    TownyFormatter.getFormattedNationName(nationOfDefendingTown),
                    TownyFormatter.getFormattedNationName(attackingNation)
            )));

            if(nationOfDefendingTown.getTowns().size() == 0) {
                TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                        TownySettings.getLangString("msg_siege_war_nation_defeated"),
                        TownyFormatter.getFormattedNationName(nationOfDefendingTown)
                )));
            }
        } else {
            addTownToNation(plugin, defendingTown, attackingNation);

            TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                    TownySettings.getLangString("msg_siege_war_neutral_town_captured"),
                    TownyFormatter.getFormattedTownName(defendingTown),
                    TownyFormatter.getFormattedNationName(attackingNation)
            )));
        }

        siege.setTownInvaded(true);
    }

    public static void removeTownFromNation(Towny plugin, Town town, Nation nation) {
        boolean removeNation = false;

        try {
            nation.removeTown(town);
        } catch(NotRegisteredException x) {
            TownyMessaging.sendErrorMsg("Attempted to remove town from nation but Town was already removed.");
            return;  //Town was already removed
        } catch(EmptyNationException x) {
            removeNation = true;  //Set flag to remove nation at end of this method
        }
        /*
         * Remove all resident titles/nationRanks before saving the town itself.
         */
        List<Resident> titleRemove = new ArrayList<Resident>(town.getResidents());

        for (Resident res : titleRemove) {
            if (res.hasTitle() || res.hasSurname()) {
                res.setTitle("");
                res.setSurname("");
            }
            res.updatePermsForNationRemoval(); // Clears the nationRanks.
            TownyUniverse.getDataSource().saveResident(res);
        }

        if(removeNation) {
            TownyUniverse.getDataSource().removeNation(nation);
            TownyUniverse.getDataSource().saveNationList();
        } else {
            TownyUniverse.getDataSource().saveNation(nation);
            TownyUniverse.getDataSource().saveNationList();
            plugin.resetCache();
        }

        TownyUniverse.getDataSource().saveTown(town);
    }


    private static void addTownToNation(Towny plugin, Town town,Nation nation) {
        try {
            nation.addTown(town);
            TownyUniverse.getDataSource().saveTown(town);
            plugin.resetCache();
            TownyUniverse.getDataSource().saveNation(nation);
        } catch (AlreadyRegisteredException x) {
            return;   //Town already in nation
        }
    }


    public static void plunderTown(Siege siege, Town defendingTown, Nation winnerNation) {
        double plunder =
                TownySettings.getWarSiegeAttackerPlunderAmountPerPlot()
                        * defendingTown.getTownBlocks().size();
        try {
            if (defendingTown.canPayFromHoldings(plunder)) {
                defendingTown.payTo(plunder, winnerNation, "Town was plundered by attacker");
                sendPlunderSuccessMessage(defendingTown, winnerNation);
        } else {
                double actualPlunder = defendingTown.getHoldingBalance();
                defendingTown.payTo(actualPlunder, winnerNation, "Town was plundered by attacker");
                sendPlunderSuccessMessage(defendingTown, winnerNation);
                TownyMessaging.sendGlobalMessage("The town " + defendingTown.getName() + " was destroyed by " +winnerNation.getName());
                TownyUniverse.getDataSource().removeTown(defendingTown);
            }
        } catch (EconomyException x) {
            TownyMessaging.sendErrorMsg(x.getMessage());
        }

        siege.setTownPlundered(true);
    }


    private static void sendPlunderSuccessMessage(Town defendingTown, Nation winnerNation) {
        if(defendingTown.hasNation()) {
            try {
                TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                        TownySettings.getLangString("msg_siege_war_nation_town_plundered"),
                        TownyFormatter.getFormattedTownName(defendingTown),
                        TownyFormatter.getFormattedNationName(defendingTown.getNation()),
                        TownyFormatter.getFormattedNationName(winnerNation)
                )));
            } catch (NotRegisteredException e) {
                e.printStackTrace();
            }
        } else {
            TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                    TownySettings.getLangString("msg_siege_war_neutral_town_plundered"),
                    TownyFormatter.getFormattedTownName(defendingTown),
                    TownyFormatter.getFormattedNationName(winnerNation))));
        }
    }

    public static void applySiegeUpkeepCost(Siege siege) {
        double upkeepCostPerPlot = TownySettings.getWarSiegeAttackerCostPerPlotPerHour();
        long upkeepCost = Math.round(upkeepCostPerPlot * siege.getDefendingTown().getTotalBlocks());

        //Each attacking nation who is involved must pay upkeep
        if(upkeepCost > 1) {
            for (Nation nation : siege.getSiegeStatsAttackers().keySet()) {
                try {
                    if (nation.canPayFromHoldings(upkeepCost))
                        nation.pay(upkeepCost, "Cost of maintaining siege.");
                    else {
                        siege.getSiegeStatsAttackers().get(nation).setActive(false);
                        TownyMessaging.sendGlobalMessage("The nation of " + nation.getName() +
                                " has been forced to abandon the siege on the town of " + siege.getDefendingTown().getName() +
                                ", due to lack of funds.");
                    }
                } catch (EconomyException x) {
                    TownyMessaging.sendErrorMsg(x.getMessage());
                }
            }
        }
    }


    public static void attackerWin(Siege siege, Nation winnerNation) {
        siege.setStatus(SiegeStatus.ATTACKER_WIN);
        siege.setActualEndTime(System.currentTimeMillis());
        siege.setAttackerWinner(winnerNation);
        activateSiegeCooldown(siege);
        activateRevoltCooldown(siege);
        TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                TownySettings.getLangString("msg_siege_war_attacker_win"),
                TownyFormatter.getFormattedNationName(winnerNation),
                TownyFormatter.getFormattedTownName(siege.getDefendingTown()))
        ));
    }

    public static void attackerAbandon(Nation nation, Siege siege) {
        siege.getSiegeStatsAttackers().get(nation).setActive(false);
        TownyMessaging.sendGlobalMessage(nation.getName() + " has abandoned their attack on" + siege.getDefendingTown().getName());

        if (siege.getActiveAttackers().size() == 0) {
            siege.setStatus(SiegeStatus.ATTACKER_ABANDON);
            siege.setActualEndTime(System.currentTimeMillis());
            activateSiegeCooldown(siege);
            TownyMessaging.sendGlobalMessage("The siege on " + siege.getDefendingTown().getName() +" has been abandoned all attackers.");
        }
    }

    public static void defenderWin(Siege siege, Town winnerTown) {
        siege.setStatus(SiegeStatus.DEFENDER_WIN);
        siege.setActualEndTime(System.currentTimeMillis());
        activateSiegeCooldown(siege);
        TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                TownySettings.getLangString("msg_siege_war_defender_win"),
                TownyFormatter.getFormattedTownName(winnerTown)
        )));
    }

    public static void defenderSurrender(Siege siege) throws TownyException {
        siege.setStatus(SiegeStatus.DEFENDER_SURRENDER);
        siege.setActualEndTime(System.currentTimeMillis());
        siege.getSiegeStatsDefenders().setActive(false);
        siege.setAttackerWinner(siege.getActiveAttackers().get(0));
        activateSiegeCooldown(siege);
        activateRevoltCooldown(siege);
        TownyMessaging.sendGlobalMessage("Town has surrendered.");
    }

    private static void activateSiegeCooldown(Siege siege) {
        double siegeDuration = siege.getActualEndTime() - siege.getActualStartTime();
        double cooldownDuration = siegeDuration * TownySettings.getWarSiegeSiegeCooldownModifier();
        siege.getDefendingTown().setSiegeCooldownEndTime(System.currentTimeMillis() + (long)(cooldownDuration + 0.5));
    }

    private static void activateRevoltCooldown(Siege siege) {
        long cooldownDuration = (long)(TownySettings.getWarSiegeRevoltCooldownHours() * ONE_HOUR_IN_MILLIS);
        siege.getDefendingTown().setRevoltCooldownEndTime(System.currentTimeMillis() + cooldownDuration);
    }

    public static TownyObject calculateSiegeWinner(Siege siege) {
        TownyObject winner = siege.getDefendingTown();
        int winningPoints = siege.getSiegeStatsDefenders().getSiegePointsTotal();

        for(Nation attackingNation: siege.getSiegeStatsAttackers().keySet()) {
            SiegeStats stats = siege.getSiegeStatsAttackers().get(attackingNation);
            if(stats.getSiegePointsTotal() > winningPoints) {
                winner = attackingNation;
                winningPoints = stats.getSiegePointsTotal();
            }
        }
        return winner;
    }

    public static List<Town> addAttackerSiegePoints()throws TownyException {
        List<Town> townsWithAttackersInSiegeZone = new ArrayList<>();
        int siegePointsPerAttackingPlayer = TownySettings.getSiegeWarPointsPerAttackingPlayer();

        //1. Cycle through players to find attackers
        for (Player player : BukkitTools.getOnlinePlayers()) {

            Resident resident = TownyUniverse.getDataSource().getResident(player.getName());
            if (!resident.hasNation())
                continue; //Player not in a nation. Cannot attack

            List<Town> townsUnderActiveAttackFromPlayer = resident.getTown().getNation().getTownsUnderActiveSiegeAttack();
            if(townsUnderActiveAttackFromPlayer.size() == 0)
                continue; //Player's nation is not besieging anyone

            TownBlock townBlockWherePlayerIsLocated = TownyUniverse.getTownBlockWherePlayerIsLocated(player);
            if (townBlockWherePlayerIsLocated == null)
                continue; //Player not in a town

            Town town = townBlockWherePlayerIsLocated.getTown();
            if (!town.hasSiege())
                continue;  //Town not under siege

            if (!townsUnderActiveAttackFromPlayer.contains(town))
                continue; //Player's nation is not actively attacking the town

            if (!SiegeWarUtil.isTownBlockOnTheTownBorder(townBlockWherePlayerIsLocated, town))
                continue;  //Player is not on a border block. Cannot score points

            //Score points
            Nation playerNation = resident.getTown().getNation();
            SiegeStats attackerStats = town.getSiege().getSiegeStatsAttackers().get(playerNation);
            attackerStats.addSiegePoints(siegePointsPerAttackingPlayer);

            //Mark this town as having attackersin siege zone
            townsWithAttackersInSiegeZone.add(town);
        }

        return townsWithAttackersInSiegeZone;
    }

    public static void addDefenderSiegePoints(List<Town> townsWithAttackersInSiegeZone) throws TownyException {
        int siegePointsPerDefendingPlayer = TownySettings.getSiegeWarPointsPerDefendingPlayer();

        //1. Cycle through players to find defenders
        for (Player player : BukkitTools.getOnlinePlayers()) {

            Resident resident = TownyUniverse.getDataSource().getResident(player.getName());
            if (!resident.hasTown())
                continue; //Player not in a town. Cannot defend

            Town townOfPlayer = resident.getTown();
            if (!townOfPlayer.hasSiege())
                continue;  //Town not under siege

            if(townOfPlayer.getSiege().getStatus() != SiegeStatus.IN_PROGRESS)
                continue;   //Siege over

            TownBlock townBlockWherePlayerIsLocated = TownyUniverse.getTownBlockWherePlayerIsLocated(player);
            if (townBlockWherePlayerIsLocated == null)
                continue; //Player not in a town

            Town townWherePlayerIsLocated = townBlockWherePlayerIsLocated.getTown();
            if(townOfPlayer.hasNation() && townWherePlayerIsLocated.hasNation()) {
                if(townOfPlayer.getNation() != townWherePlayerIsLocated.getNation())
                    continue;  //Player not in any town belonging to their nation
            } else {
                if(townWherePlayerIsLocated != townOfPlayer)
                    continue;  //Player is not in their own town
            }

            if(townsWithAttackersInSiegeZone.contains(townOfPlayer))
                continue;  //Defender cannot score if there are attackers in the zone

            /* Note on Defence point scoring location:
             * Currently defence points are scored from ANYWHERE in the town
             * If some problem with this becomes apparent during playtesting,
             * this can easily be changed to border-only by adding a check in the code here.
            */

            //Score points
            SiegeStats defenderStats = townOfPlayer.getSiege().getSiegeStatsDefenders();
            defenderStats.addSiegePoints(siegePointsPerDefendingPlayer);
        }
    }

    public static String getFormattedTimeValue(double timeMillis) {

        String timeUnit;
        double timeUtilCompletion;

        if(timeMillis> 0) {

            NumberFormat numberFormat = NumberFormat.getInstance();

            if (timeMillis / ONE_DAY_IN_MILLIS > 1) {
                numberFormat.setMaximumFractionDigits(1);
                timeUnit = TownySettings.getLangString("day_plu");
                timeUtilCompletion = timeMillis / ONE_DAY_IN_MILLIS;

            } else if (timeMillis / ONE_HOUR_IN_MILLIS > 1) {
                numberFormat.setMaximumFractionDigits(1);
                timeUnit = TownySettings.getLangString("hour_plu");
                timeUtilCompletion = timeMillis / ONE_HOUR_IN_MILLIS;

            } else if (timeMillis / ONE_MINUTE_IN_MILLIS > 1) {
                numberFormat.setMaximumFractionDigits(1);
                timeUnit = TownySettings.getLangString("minute_plu");
                timeUtilCompletion = timeMillis / ONE_MINUTE_IN_MILLIS;

            } else {
                numberFormat.setMaximumFractionDigits(0);
                timeUnit = TownySettings.getLangString("second_plu");
                timeUtilCompletion = timeMillis / ONE_SECOND_IN_MILLIS;
            }

            double timeRoundedUp = Math.ceil(timeUtilCompletion * 10) / 10;
            return numberFormat.format(timeRoundedUp) + " " + timeUnit;

        } else {
            return "n/a";
        }

    }
}