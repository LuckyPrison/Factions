package com.massivecraft.factions.listeners;

import java.util.EnumSet;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import com.massivecraft.factions.Board;
import com.massivecraft.factions.Conf;
import com.massivecraft.factions.FLocation;
import com.massivecraft.factions.FPlayer;
import com.massivecraft.factions.FPlayers;
import com.massivecraft.factions.Faction;
import com.massivecraft.factions.P;
import com.massivecraft.factions.integration.Worldguard;
import com.massivecraft.factions.struct.Permission;
import com.massivecraft.factions.struct.Relation;
import com.ulfric.lib.api.inventory.ItemUtils;


public class FactionsBlockListener implements Listener {

    public P p;

    public FactionsBlockListener(P p) {
        this.p = p;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!event.canBuild()) {
            return;
        }

        // special case for flint&steel, which should only be prevented by DenyUsage list
        if (event.getBlockPlaced().getType() == Material.FIRE) {
            return;
        }

        if (!playerCanBuildDestroyBlock(event.getPlayer(), event.getBlock().getLocation(), "build", false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        if (!Conf.handleExploitLiquidFlow) {
            return;
        }
        if (event.getBlock().isLiquid()) {
            if (event.getToBlock().isEmpty()) {
                Faction from = Board.getInstance().getFactionAt(new FLocation(event.getBlock()));
                Faction to = Board.getInstance().getFactionAt(new FLocation(event.getToBlock()));
                if (from == to) {
                    // not concerned with inter-faction events
                    return;
                }
                // from faction != to faction
                if (to.isNormal()) {
                    if (from.isNormal() && from.getRelationTo(to).isAlly()) {
                        return;
                    }
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!playerCanBuildDestroyBlock(event.getPlayer(), event.getBlock().getLocation(), "destroy", false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (event.getInstaBreak() && !playerCanBuildDestroyBlock(event.getPlayer(), event.getBlock().getLocation(), "destroy", false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockPistonExtend(BlockPistonExtendEvent event) {
        if (!Conf.pistonProtectionThroughDenyBuild) {
            return;
        }

        Faction pistonFaction = Board.getInstance().getFactionAt(new FLocation(event.getBlock()));

        // target end-of-the-line empty (air) block which is being pushed into, including if piston itself would extend into air
        Block targetBlock = event.getBlock().getRelative(event.getDirection(), event.getLength() + 1);

        // if potentially pushing into air/water/lava in another territory, we need to check it out
        if ((targetBlock.isEmpty() || targetBlock.isLiquid()) && !canPistonMoveBlock(pistonFaction, targetBlock.getLocation())) {
            event.setCancelled(true);
            return;
        }

		/*
         * note that I originally was testing the territory of each affected block, but since I found that pistons can only push
		 * up to 12 blocks and the width of any territory is 16 blocks, it should be safe (and much more lightweight) to test
		 * only the final target block as done above
		 */
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockPistonRetract(BlockPistonRetractEvent event) {
        // if not a sticky piston, retraction should be fine
        if (!event.isSticky() || !Conf.pistonProtectionThroughDenyBuild) {
            return;
        }

        Location targetLoc = event.getRetractLocation();
        Faction otherFaction = Board.getInstance().getFactionAt(new FLocation(targetLoc));

        // Check if the piston is moving in a faction's territory. This disables pistons entirely in faction territory.
        if (otherFaction.isNormal() && P.p.getConfig().getBoolean("disable-pistons-in-territory", false)) {
            event.setCancelled(true);
            return;
        }

        // if potentially retracted block is just air/water/lava, no worries
        if (targetLoc.getBlock().isEmpty() || targetLoc.getBlock().isLiquid()) {
            return;
        }

        Faction pistonFaction = Board.getInstance().getFactionAt(new FLocation(event.getBlock()));

        if (!canPistonMoveBlock(pistonFaction, targetLoc)) {
            event.setCancelled(true);
            return;
        }
    }

    private boolean canPistonMoveBlock(Faction pistonFaction, Location target) {

        Faction otherFaction = Board.getInstance().getFactionAt(new FLocation(target));

        if (pistonFaction == otherFaction) {
            return true;
        }

        if (otherFaction.isWilderness()) {
            if (!Conf.wildernessDenyBuild || Conf.worldsNoWildernessProtection.contains(target.getWorld().getName())) {
                return true;
            }

            return false;
        } else if (otherFaction.isSafeZone()) {
            if (!Conf.safeZoneDenyBuild) {
                return true;
            }

            return false;
        } else if (otherFaction.isWarZone()) {
            if (!Conf.warZoneDenyBuild) {
                return true;
            }

            return false;
        }

        Relation rel = pistonFaction.getRelationTo(otherFaction);

        if (rel.confDenyBuild(otherFaction.hasPlayersOnline())) {
            return false;
        }

        return true;
    }

    public static boolean playerCanBuildDestroyBlock(Player player, Location location, String action, boolean justCheck) {
        String name = player.getName();
        if (Conf.playersWhoBypassAllProtection.contains(name)) {
            return true;
        }

        FPlayer me = FPlayers.getInstance().getById(player.getUniqueId().toString());
        if (me.isAdminBypassing()) {
            return true;
        }

        FLocation loc = new FLocation(location);
        Faction otherFaction = Board.getInstance().getFactionAt(loc);

        if (otherFaction.isWilderness()) {
            if (Conf.worldGuardBuildPriority && Worldguard.playerCanBuild(player, location)) {
                return true;
            }

            if (!Conf.wildernessDenyBuild || Conf.worldsNoWildernessProtection.contains(location.getWorld().getName())) {
                return true; // This is not faction territory. Use whatever you like here.
            }

            if (!justCheck) {
                me.msg("<b>You can't " + action + " in the wilderness.");
            }

            return false;
        } else if (otherFaction.isSafeZone()) {
            if (Conf.worldGuardBuildPriority && Worldguard.playerCanBuild(player, location)) {
                return true;
            }

            if (!Conf.safeZoneDenyBuild || Permission.MANAGE_SAFE_ZONE.has(player)) {
                return true;
            }

            if (!justCheck) {
                me.msg("<b>You can't " + action + " in a safe zone.");
            }

            return false;
        } else if (otherFaction.isWarZone()) {
            if (Conf.worldGuardBuildPriority && Worldguard.playerCanBuild(player, location)) {
                return true;
            }

            if (!Conf.warZoneDenyBuild || Permission.MANAGE_WAR_ZONE.has(player)) {
                return true;
            }

            if (!justCheck) {
                me.msg("<b>You can't " + action + " in a war zone.");
            }

            return false;
        }
        if (P.p.getConfig().getBoolean("hcf.raidable", false) && otherFaction.getLandRounded() >= otherFaction.getPowerRounded()) {
            return true;
        }

        Faction myFaction = me.getFaction();
        Relation rel = myFaction.getRelationTo(otherFaction);
        boolean online = otherFaction.hasPlayersOnline();
        boolean pain = !justCheck && rel.confPainBuild(online);
        boolean deny = rel.confDenyBuild(online);

        // hurt the player for building/destroying in other territory?
        if (pain) {
            player.damage(Conf.actionDeniedPainAmount);

            if (!deny) {
                me.msg("<b>It is painful to try to " + action + " in the territory of " + otherFaction.getTag(myFaction));
            }
        }

        // cancel building/destroying in other territory?
        if (deny) {
        	// Allow farm raiding
        	ItemStack item = player.getItemInHand();

        	if (item != null && FactionsBlockListener.HOES.contains(item.getType()))
        	{
        		Block block = location.getBlock();

        		if (block != null && FactionsBlockListener.CROPS.contains(block.getType()))
        		{
        			ItemUtils.decrementHand(player);

        			return true;
        		}
        	}

            if (!justCheck) {
                me.msg("<b>You can't " + action + " in the territory of " + otherFaction.getTag(myFaction));
            }

            return false;
        }

        // Also cancel and/or cause pain if player doesn't have ownership rights for this claim
        if (Conf.ownedAreasEnabled && (Conf.ownedAreaDenyBuild || Conf.ownedAreaPainBuild) && !otherFaction.playerHasOwnershipRights(me, loc)) {
            if (!pain && Conf.ownedAreaPainBuild && !justCheck) {
                player.damage(Conf.actionDeniedPainAmount);

                if (!Conf.ownedAreaDenyBuild) {
                    me.msg("<b>It is painful to try to " + action + " in this territory, it is owned by: " + otherFaction.getOwnerListString(loc));
                }
            }
            if (Conf.ownedAreaDenyBuild) {
                if (!justCheck) {
                    me.msg("<b>You can't " + action + " in this territory, it is owned by: " + otherFaction.getOwnerListString(loc));
                }

                return false;
            }
        }

        return true;
    }

    private static final Set<Material> HOES;
    private static final Set<Material> CROPS = EnumSet.of(Material.SUGAR_CANE_BLOCK, Material.CROPS, Material.NETHER_WARTS, Material.CARROT, Material.CACTUS, Material.POTATO, Material.PUMPKIN, Material.MELON_BLOCK, Material.MELON_STEM, Material.PUMPKIN_STEM, Material.SAPLING, Material.WATER_LILY);
    static
    {
    	HOES = EnumSet.noneOf(Material.class);

    	for (Material material : Material.values())
    	{
    		if (material.isBlock()) continue;

    		if (!material.name().endsWith("_HOE")) continue;

    		FactionsBlockListener.HOES.add(material);
    	}
    }

}
