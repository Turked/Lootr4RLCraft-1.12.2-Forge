package com.mechgaming.lootr4rlcraft.container;

import com.mechgaming.lootr4rlcraft.Lootr4RLCraft;
import com.mechgaming.lootr4rlcraft.adapter.ContainerAdapters;
import com.mechgaming.lootr4rlcraft.adapter.InventoryAdapter;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Normal interaction remains under the installed lock/key handlers before a private menu opens. */
public final class ContainerEvents {
    private final Map<Event, ContainerLoot.Interaction> interactions = new IdentityHashMap<>();

    @SubscribeEvent
    public void attach(AttachCapabilitiesEvent<Chunk> event) {
        Chunk chunk = event.getObject();
        if (!chunk.getWorld().isRemote) {
            event.addCapability(new ResourceLocation(Lootr4RLCraft.ID, "inventories"), ContainerChunkData.attach(chunk));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntityPlayer() instanceof EntityPlayerMP) || !managed(event.getWorld(), event.getPos())) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.getEntityPlayer();
        if (Loader.isModLoaded("carryon") && player.getHeldItemMainhand().isEmpty()
                && player.getHeldItemOffhand().isEmpty() && player.getEntityData().getBoolean("carryOnKeyPressed")) {
            event.setCanceled(true);
            event.setCancellationResult(EnumActionResult.FAIL);
            return;
        }
        if (!ContainerLoot.canOpen(player, event.getPos())) {
            event.setUseBlock(Event.Result.DENY);
        }
        TileEntity tile = event.getWorld().getTileEntity(event.getPos());
        if (!ContainerAdapters.opensFrom(tile, event.getWorld().getBlockState(event.getPos()), event.getFace(), player)) {
            event.setUseBlock(Event.Result.DENY);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void prepareClick(PlayerInteractEvent.RightClickBlock event) {
        if (!event.isCanceled() && event.getUseBlock() != Event.Result.DENY
                && event.getEntityPlayer() instanceof EntityPlayerMP && managed(event.getWorld(), event.getPos())) {
            ContainerLoot.Interaction interaction = ContainerLoot.prepareInteraction(
                    (EntityPlayerMP) event.getEntityPlayer(), event.getPos());
            if (interaction != null) {
                interactions.put(event, interaction);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void afterClick(PlayerInteractEvent.RightClickBlock event) {
        finish(event, event.isCanceled() || event.getUseBlock() == Event.Result.DENY);
        if (event.isCanceled() || event.getUseBlock() == Event.Result.DENY
                || !(event.getEntityPlayer() instanceof EntityPlayerMP) || !managed(event.getWorld(), event.getPos())) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.getEntityPlayer();
        if (player.isSneaking() && (!player.getHeldItemMainhand().isEmpty() || !player.getHeldItemOffhand().isEmpty())) {
            event.setUseBlock(Event.Result.DENY);
            return;
        }
        event.setCancellationResult(ContainerLoot.open(player, event.getPos()) ? EnumActionResult.SUCCESS : EnumActionResult.FAIL);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeBreak(BlockEvent.BreakEvent event) {
        if (!managed(event.getWorld(), event.getPos())) {
            return;
        }
        if (!(event.getPlayer() instanceof EntityPlayerMP)
                || !ContainerLoot.canBreak((EntityPlayerMP) event.getPlayer(), event.getPos())) {
            event.setCanceled(true);
            return;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void prepareBreak(BlockEvent.BreakEvent event) {
        if (!event.isCanceled() && event.getPlayer() instanceof EntityPlayerMP && managed(event.getWorld(), event.getPos())) {
            ContainerLoot.Interaction interaction = ContainerLoot.prepareInteraction((EntityPlayerMP) event.getPlayer(), event.getPos());
            if (interaction != null) {
                interactions.put(event, interaction);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void afterBreak(BlockEvent.BreakEvent event) {
        finish(event, event.isCanceled());
        if (managed(event.getWorld(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    private void finish(Event event, boolean canceled) {
        ContainerLoot.Interaction interaction = interactions.remove(event);
        if (interaction != null) {
            interaction.finish(canceled);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void endTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !interactions.isEmpty()) {
            java.util.List<ContainerLoot.Interaction> unfinished = new java.util.ArrayList<>(interactions.values());
            interactions.clear();
            for (ContainerLoot.Interaction interaction : unfinished) {
                interaction.finish(true);
            }
        }
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote) {
            interactions.entrySet().removeIf(entry -> {
                Event pending = entry.getKey();
                World world = pending instanceof PlayerInteractEvent
                        ? ((PlayerInteractEvent) pending).getWorld() : ((BlockEvent) pending).getWorld();
                if (world == event.getWorld()) {
                    entry.getValue().finish(true);
                    return true;
                }
                return false;
            });
        }
    }

    @SubscribeEvent
    public void place(BlockEvent.PlaceEvent event) {
        if (!event.getWorld().isRemote) {
            TileEntity tile = event.getWorld().getTileEntity(event.getPos());
            InventoryAdapter adapter = ContainerAdapters.forEnrollment(tile);
            if (adapter == null) {
                return;
            }
            try {
                if (adapter.parts((net.minecraft.world.WorldServer) event.getWorld(), event.getPos()).stream()
                        .anyMatch(ContainerLoot::isManaged)) {
                    event.setCanceled(true);
                }
            } catch (IllegalArgumentException unavailable) {
                // The original block retains its placement validation for unsupported arrangements.
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void explosion(ExplosionEvent.Detonate event) {
        if (!event.getWorld().isRemote) {
            event.getAffectedBlocks().removeIf(pos -> managed(event.getWorld(), pos));
        }
    }

    private static boolean managed(World world, BlockPos pos) {
        return !world.isRemote && world.isBlockLoaded(pos) && ContainerLoot.isManaged(world.getTileEntity(pos));
    }
}
