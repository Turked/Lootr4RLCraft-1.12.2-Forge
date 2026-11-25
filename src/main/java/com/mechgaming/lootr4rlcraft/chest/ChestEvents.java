package com.mechgaming.lootr4rlcraft.chest;

import com.mechgaming.lootr4rlcraft.Settings;
import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import com.mechgaming.lootr4rlcraft.generation.GenerationDiscovery;
import com.mechgaming.lootr4rlcraft.network.ChestViews;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;
import javax.annotation.Nullable;
import net.minecraft.block.BlockChest;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.IWorldGenerator;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class ChestEvents implements IWorldGenerator {
    private final Map<Event, InstancedChestTile> pendingMimic = new IdentityHashMap<>();
    private final DeferredChestRegistration deferred = new DeferredChestRegistration();
    private final GenerationDiscovery discovery = new GenerationDiscovery(deferred::add);

    public void registerDiscovery() {
        discovery.register();
    }

    public boolean generatedEntity(WorldServer world, Entity entity) {
        return discovery.generatedEntity(world, entity);
    }

    public void beginAdditionalGenerators(WorldServer world, int chunkX, int chunkZ, IChunkGenerator generator) {
        if (Settings.registerNewChests && LockAccess.available()) {
            discovery.ensurePopulation(world, chunkX, chunkZ, generator);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforePopulation(PopulateChunkEvent.Pre event) {
        if (!Settings.registerNewChests || !(event.getWorld() instanceof WorldServer) || !LockAccess.available()) {
            return;
        }
        WorldServer world = (WorldServer) event.getWorld();
        discovery.beginPopulation(world, event.getChunkX(), event.getChunkZ(), event.getGenerator());
    }

    @Override
    public void generate(Random random, int chunkX, int chunkZ, World world, IChunkGenerator generator,
            IChunkProvider chunkProvider) {
        if (world instanceof WorldServer) {
            discovery.finishPopulation((WorldServer) world, chunkX, chunkZ);
        }
    }

    @SubscribeEvent
    public void watch(ChunkWatchEvent.Watch event) {
        Chunk chunk = event.getChunkInstance();
        if (chunk == null) {
            return;
        }
        EntityPlayerMP viewer = event.getPlayer();
        if (viewer.world != chunk.getWorld()) {
            return;
        }
        deferred.wake(viewer.getServerWorld());
        sendViews(viewer, chunk, false);
    }

    public static void sendViews(EntityPlayerMP viewer, Chunk chunk, boolean includeEmpty) {
        Map<BlockPos, Boolean> views = new HashMap<>();
        Map<BlockPos, Boolean> neighboringViews = new HashMap<>();
        for (TileEntity tile : chunk.getTileEntityMap().values()) {
            if (tile instanceof InstancedChestTile && ((InstancedChestTile) tile).canDisplay()) {
                InstancedChestTile managed = (InstancedChestTile) tile;
                boolean opened = managed.hasOpened(viewer);
                views.put(tile.getPos(), opened);
                BlockPos partner = managed.partnerPosition();
                if (partner != null && (partner.getX() >> 4 != chunk.x || partner.getZ() >> 4 != chunk.z)) {
                    PlayerChunkMapEntry watched = viewer.getServerWorld().getPlayerChunkMap()
                            .getEntry(partner.getX() >> 4, partner.getZ() >> 4);
                    if (watched != null && watched.getWatchingPlayers().contains(viewer)) {
                        neighboringViews.put(partner, opened);
                    }
                }
            }
        }
        ContainerLoot.collectViews(viewer, chunk, views, neighboringViews);
        if (!views.isEmpty() || includeEmpty) {
            ChestViews.sendChunk(viewer, chunk.getPos(), views);
        }
        neighboringViews.forEach((pos, opened) -> ChestViews.send(viewer, pos, opened));
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (event.getWorld() instanceof WorldServer) {
            deferred.unload((WorldServer) event.getWorld());
            discovery.unload((WorldServer) event.getWorld());
        }
        pendingMimic.entrySet().removeIf(entry -> entry.getValue().getWorld() == event.getWorld());
    }

    @SubscribeEvent
    public void loadChunk(ChunkEvent.Load event) {
        if (event.getWorld() instanceof WorldServer) {
            deferred.load(event.getChunk());
        }
    }

    @SubscribeEvent
    public void unloadChunk(ChunkEvent.Unload event) {
        deferred.unload(event.getChunk());
        discovery.unload(event.getChunk());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void worldTick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.world instanceof WorldServer) {
            WorldServer world = (WorldServer) event.world;
            discovery.endTick(world);
            if (Settings.registerNewChests) {
                deferred.tick(world);
            } else {
                deferred.unload(world);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeClick(PlayerInteractEvent.RightClickBlock event) {
        if (Settings.registerNewChests && event.getWorld() instanceof WorldServer
                && deferred.preparing((WorldServer) event.getWorld(), event.getPos())) {
            event.setUseBlock(Event.Result.DENY);
            event.getEntityPlayer().sendStatusMessage(new TextComponentString("Lootr chest is waiting for Locks to finish generating."), true);
            return;
        }
        InstancedChestTile tile = managed(event.getWorld(), event.getPos());
        if (tile == null) {
            return;
        }
        EntityPlayer player = event.getEntityPlayer();
        if (!tile.canOpen(player)) {
            event.setUseBlock(Event.Result.DENY);
        }
        if (Loader.isModLoaded("carryon") && player.getHeldItemMainhand().isEmpty()
                && player.getHeldItemOffhand().isEmpty() && player.getEntityData().getBoolean("carryOnKeyPressed")) {
            event.setCancellationResult(EnumActionResult.FAIL);
            event.setCanceled(true);
            return;
        }
        captureMimic(event, tile);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void afterClick(PlayerInteractEvent.RightClickBlock event) {
        finishMimic(event, event.getEntityPlayer());
        if (event.isCanceled() || event.getUseBlock() == Event.Result.DENY || event.getWorld().isRemote
                || !(event.getEntityPlayer() instanceof EntityPlayerMP)) {
            return;
        }
        InstancedChestTile tile = managed(event.getWorld(), event.getPos());
        if (tile == null || !tile.isPaired()) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.getEntityPlayer();
        if (player.isSneaking() && (!player.getHeldItemMainhand().isEmpty() || !player.getHeldItemOffhand().isEmpty())) {
            event.setUseBlock(Event.Result.DENY);
            return;
        }
        event.setCancellationResult(tile.openDouble(player) ? EnumActionResult.SUCCESS : EnumActionResult.FAIL);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeBreak(BlockEvent.BreakEvent event) {
        if (Settings.registerNewChests && event.getWorld() instanceof WorldServer
                && deferred.preparing((WorldServer) event.getWorld(), event.getPos())) {
            event.setCanceled(true);
            return;
        }
        InstancedChestTile tile = managed(event.getWorld(), event.getPos());
        if (tile != null) {
            if (event.getPlayer() instanceof FakePlayer || !tile.authorized(event.getPlayer())) {
                event.setCanceled(true);
            } else {
                captureMimic(event, tile);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void afterBreak(BlockEvent.BreakEvent event) {
        finishMimic(event, event.getPlayer());
        // Locks and RLArtifacts run before this handler. A winning Mimic may already replace the chest.
        if (managed(event.getWorld(), event.getPos()) != null) {
            event.setCanceled(true);
        }
    }

    private void captureMimic(Event event, InstancedChestTile tile) {
        if (Loader.isModLoaded("artifacts") && tile.getLootTable() != null) {
            pendingMimic.put(event, tile);
        }
    }

    private void finishMimic(Event event, EntityPlayer player) {
        InstancedChestTile captured = pendingMimic.remove(event);
        if (captured != null && event.isCanceled() && captured.getLootTable() == null && player instanceof EntityPlayerMP) {
            captured.finishMimicReplacement((EntityPlayerMP) player);
        }
    }

    @SubscribeEvent
    public void preventJoining(BlockEvent.PlaceEvent event) {
        if (!(event.getPlacedBlock().getBlock() instanceof BlockChest)) {
            return;
        }
        for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) {
            BlockPos neighbor = event.getPos().offset(side);
            if (managed(event.getWorld(), neighbor) != null
                    && event.getWorld().getBlockState(neighbor).getBlock() == event.getPlacedBlock().getBlock()) {
                event.setCanceled(true);
                return;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void protectFromExplosions(ExplosionEvent.Detonate event) {
        if (!event.getWorld().isRemote) {
            event.getAffectedBlocks().removeIf(pos -> managed(event.getWorld(), pos) != null);
        }
    }

    @Nullable
    private InstancedChestTile managed(World world, BlockPos pos) {
        if (!world.isRemote && world.isBlockLoaded(pos)) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof InstancedChestTile) {
                return (InstancedChestTile) tile;
            }
        }
        return null;
    }
}
