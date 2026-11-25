package com.mechgaming.lootr4rlcraft.serverprobe;

import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import java.lang.reflect.Field;
import java.util.UUID;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityHopper;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** World-level probes use only disposable space beneath the Ruins sample. */
final class PhysicalProbe {
    private static final Logger LOG = LogManager.getLogger("lootr-server-probe");

    static void run(WorldServer world, EntityPlayerMP[] actors, BlockPos base) throws Exception {
        BlockPos p = clearVolume(world, base);
        world.setBlockState(p, Blocks.CHEST.getDefaultState());
        TileEntityChest chest = (TileEntityChest) world.getTileEntity(p);
        chest.setInventorySlotContents(0, new ItemStack(Items.BOOK, 7));
        ContainerLoot.convert(world, p, true);
        UUID id = chest.getTileData().getCompoundTag(ContainerLoot.MARKER).getUniqueId("Id");
        hopper(world, p, chest);
        // A hopper above the lid obstructs native opening. Remove only our disposable fixture.
        world.setBlockToAir(p.up());
        click(world, actors[0], p);
        require(count(privateMenu(actors[0], 27), Items.BOOK) == 7, "Hoppers altered private template");
        actors[0].closeScreen();
        LOG.info("PHYSICAL PASS real upper and lower hopper ticks moved 3 physical ingots and no private books");

        BlockPos control = p.east(6);
        world.setBlockState(control, Blocks.CHEST.getDefaultState());
        EntityPlayerMP actor = actors[1];
        actor.setSneaking(true);
        actor.getEntityData().setBoolean("carryOnKeyPressed", true);
        try {
            click(world, actor, p);
            require(actor.openContainer == actor.inventoryContainer && actor.getHeldItemMainhand().isEmpty()
                    && world.getTileEntity(p) == chest, "Carry On picked up or opened managed chest");
            click(world, actor, control);
            require(world.isAirBlock(control) && !actor.getHeldItemMainhand().isEmpty()
                    && "carryon".equals(actor.getHeldItemMainhand().getItem().getRegistryName().getResourceDomain()),
                    "Carry On native unmanaged positive control did not pick up the chest");
        } finally {
            actor.getEntityData().removeTag("carryOnKeyPressed");
            actor.setSneaking(false);
            actor.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
            if (actor.openContainer != actor.inventoryContainer) actor.closeScreen();
        }
        LOG.info("PHYSICAL PASS native Carry On key gesture collected unmanaged chest and left managed chest intact");

        piston(world, p, false);
        world.setBlockState(control, Blocks.WOOL.getDefaultState());
        piston(world, control, true);
        require(world.getTileEntity(p) == chest && !actors[0].interactionManager.tryHarvestBlock(p),
                "Managed chest was moved or harvested");
        world.setBlockState(p.north(), Blocks.WOOL.getDefaultState());
        world.newExplosion(null, p.getX() + .5, p.getY() + .5, p.getZ() - .2, 3F, false, true);
        require(world.isAirBlock(p.north()) && world.getTileEntity(p) == chest
                && id.equals(chest.getTileData().getCompoundTag(ContainerLoot.MARKER).getUniqueId("Id")),
                "Explosion failed its positive control or altered managed identity");
        click(world, actors[2], p);
        require(count(privateMenu(actors[2], 27), Items.BOOK) == 7, "Explosion or piston changed surviving contents");
        actors[2].closeScreen();
        LOG.info("PHYSICAL PASS powered pistons, native harvest, real explosion and reopening with preserved identity");
        pairedMimic(world, actors, p.up(8));
    }

    private static BlockPos clearVolume(WorldServer world, BlockPos base) {
        for (int y = base.getY(); y >= 95; y -= 13) {
            BlockPos p = new BlockPos(base.getX(), y, base.getZ());
            boolean clear = true;
            for (BlockPos test : BlockPos.getAllInBox(p.add(-4, -4, -4), p.add(8, 10, 4))) {
                if (!world.isAirBlock(test)) { clear = false; break; }
            }
            if (clear) return p;
        }
        throw new IllegalStateException("No unoccupied fixture volume beneath the grid");
    }

    private static void hopper(WorldServer world, BlockPos p, TileEntityChest chest) {
        world.setBlockState(p.up(), Blocks.HOPPER.getDefaultState());
        world.setBlockState(p.down(), Blocks.HOPPER.getDefaultState());
        TileEntityHopper input = (TileEntityHopper) world.getTileEntity(p.up());
        TileEntityHopper output = (TileEntityHopper) world.getTileEntity(p.down());
        for (int i = 0; i < 10; i++) output.update();
        require(output.isEmpty(), "Native hopper extracted private books");
        input.setInventorySlotContents(0, new ItemStack(Items.GOLD_INGOT, 3));
        for (int i = 0; i < 40; i++) input.update();
        require(input.isEmpty() && count(chest, Items.GOLD_INGOT) == 3, "Input hopper failed to fill physical buffer");
        for (int i = 0; i < 40; i++) output.update();
        require(chest.isEmpty() && count(output, Items.GOLD_INGOT) == 3 && count(output, Items.BOOK) == 0,
                "Output hopper extracted private items or lost physical ingots");
    }

    private static void piston(WorldServer world, BlockPos target, boolean moves) {
        BlockPos piston = target.west();
        world.setBlockState(piston, Blocks.PISTON.getDefaultState().withProperty(BlockPistonBase.FACING, EnumFacing.EAST));
        world.setBlockState(piston.west(), Blocks.REDSTONE_BLOCK.getDefaultState());
        // Deliver the same extension block event the redstone update schedules.
        boolean extended = Blocks.PISTON.eventReceived(world.getBlockState(piston), world, piston, 0, EnumFacing.EAST.getIndex());
        require(extended == moves && (world.getBlockState(target.east()).getBlock() == Blocks.PISTON_EXTENSION) == moves,
                "Unexpected powered piston result for " + target);
    }

    private static void pairedMimic(WorldServer world, EntityPlayerMP[] actors, BlockPos p) throws Exception {
        BlockPos other = p.east();
        for (BlockPos pos : new BlockPos[]{p, other}) {
            world.setBlockState(pos, Blocks.CHEST.getDefaultState());
            ((TileEntityChest) world.getTileEntity(pos)).setLootTable(new ResourceLocation("minecraft:chests/simple_dungeon"), 123L);
        }
        ContainerLoot.convert(world, p, false);
        UUID id = world.getTileEntity(p).getTileData().getCompoundTag(ContainerLoot.MARKER).getUniqueId("Id");
        BlockPos anchor = BlockPos.fromLong(world.getTileEntity(p).getTileData().getCompoundTag(ContainerLoot.MARKER).getLong("Anchor"));
        BlockPos survivor = p.equals(anchor) ? other : p;
        Object settings = Class.forName("artifacts.common.ModConfig").getField("general").get(null);
        Field chance = settings.getClass().getField("unlootedChestMimicRatio");
        double previous = chance.getDouble(settings);
        try {
            chance.setDouble(settings, 1.0);
            int before = mimics(world, anchor);
            click(world, actors[0], anchor);
            require(world.isAirBlock(anchor) && mimics(world, anchor) == before + 1
                    && actors[0].openContainer == actors[0].inventoryContainer, "First paired Mimic failed");
            require(ContainerLoot.isManaged(world.getTileEntity(survivor))
                    && id.equals(world.getTileEntity(survivor).getTileData().getCompoundTag(ContainerLoot.MARKER).getUniqueId("Id")),
                    "Surviving half lost group identity");
            for (EntityPlayerMP actor : actors) {
                click(world, actor, survivor);
                privateMenu(actor, 27);
                actor.closeScreen();
            }
            require(mimics(world, anchor) == before + 1, "Remaining half repeated consumed Mimic roll");
            LOG.info("PHYSICAL PASS actual RLArtifacts removed paired anchor; same identity on surviving single, three private reopenings");
        } finally {
            chance.setDouble(settings, previous);
        }
    }

    private static int mimics(WorldServer world, BlockPos pos) {
        return world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(pos).grow(3),
                e -> e != null && "artifacts.common.entity.EntityMimic".equals(e.getClass().getName())).size();
    }

    private static IInventory privateMenu(EntityPlayerMP actor, int size) {
        require(actor.openContainer instanceof ContainerChest, "Missing chest menu");
        IInventory inv = ((ContainerChest) actor.openContainer).getLowerChestInventory();
        require(inv.getSizeInventory() == size && "lootr".equals(inv.getName()), "Wrong private menu");
        return inv;
    }

    private static int count(IInventory inventory, Item item) {
        int total = 0;
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    private static void click(WorldServer world, EntityPlayerMP actor, BlockPos p) {
        actor.setPosition(p.getX() + .5, p.getY(), p.getZ() + 1.05);
        actor.interactionManager.processRightClickBlock(actor, world, actor.getHeldItemMainhand(), EnumHand.MAIN_HAND,
                p, EnumFacing.SOUTH, .5F, .5F, 1F);
    }

    private static void require(boolean result, String why) {
        if (!result) throw new IllegalStateException(why);
    }
}
