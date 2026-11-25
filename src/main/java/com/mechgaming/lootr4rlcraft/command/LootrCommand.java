package com.mechgaming.lootr4rlcraft.command;

import com.mechgaming.lootr4rlcraft.Settings;
import com.mechgaming.lootr4rlcraft.cart.CartEvents;
import net.minecraft.entity.item.EntityMinecartChest;
import java.util.UUID;
import com.mechgaming.lootr4rlcraft.chest.ChestRegistration;
import com.mechgaming.lootr4rlcraft.chest.InstancedChestTile;
import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

/** Explicit operator enrollment and diagnostics for individual loaded containers. */
public final class LootrCommand extends CommandBase {
    private static final double REACH = 5.0D;

    @Override
    public String getName() {
        return "lootr4rlcraft";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/lootr4rlcraft <inspect|convert|convert-fixed> [x y z | cart-uuid]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if ((args.length != 1 && args.length != 2 && args.length != 4) || !isAction(args[0])) {
            throw new WrongUsageException(getUsage(sender));
        }
        if (!(sender.getEntityWorld() instanceof WorldServer)) {
            throw new CommandException("Lootr4RLCraft commands require a server world.");
        }
        WorldServer world = (WorldServer) sender.getEntityWorld();
        if ("inspect".equals(args[0]) ? !Settings.allowInspect
                : !Settings.privateLootEnabled || !Settings.allowManualConversion) {
            throw new CommandException("This operation is disabled by the server configuration.");
        }
        if (args.length == 2) {
            try {
                Entity entity = world.getEntityFromUuid(UUID.fromString(args[1]));
                if (!(entity instanceof EntityMinecartChest)) throw new CommandException("No loaded chest minecart with that UUID.");
                EntityMinecartChest cart = (EntityMinecartChest) entity;
                sender.sendMessage(new TextComponentString("inspect".equals(args[0]) ? CartEvents.inspect(world, cart)
                        : CartEvents.convert(world, cart, "convert-fixed".equals(args[0]))));
                return;
            } catch (IllegalArgumentException error) {
                throw new CommandException(error.getMessage());
            }
        }
        BlockPos pos = target(world, sender, args);
        if (!world.isBlockLoaded(pos)) {
            throw new CommandException("That block is in an unloaded chunk.");
        }
        try {
            net.minecraft.tileentity.TileEntity tile = world.getTileEntity(pos);
            boolean legacy = tile instanceof InstancedChestTile;
            String result;
            if ("inspect".equals(args[0])) {
                result = legacy ? ChestRegistration.inspect(world, pos) : ContainerLoot.inspect(world, pos);
            } else {
                result = legacy ? ChestRegistration.convert(world, pos, "convert-fixed".equals(args[0]))
                        : ContainerLoot.convert(world, pos, "convert-fixed".equals(args[0]));
            }
            sender.sendMessage(new TextComponentString(result));
        } catch (IllegalArgumentException error) {
            throw new CommandException(error.getMessage());
        }
    }

    private static boolean isAction(String action) {
        return "inspect".equals(action) || "convert".equals(action) || "convert-fixed".equals(action);
    }

    private static BlockPos target(WorldServer world, ICommandSender sender, String[] args) throws CommandException {
        Entity entity = sender.getCommandSenderEntity();
        if (args.length == 4) {
            return parseBlockPos(sender, args, 1, false);
        }
        if (!(entity instanceof EntityPlayerMP)) {
            throw new CommandException("Supply x y z when using this command from the server console.");
        }
        Vec3d eye = entity.getPositionEyes(1.0F);
        Vec3d look = entity.getLook(1.0F);
        Vec3d end = eye.addVector(look.x * REACH, look.y * REACH, look.z * REACH);
        requireLoadedRay(world, eye, end);
        RayTraceResult hit = world.rayTraceBlocks(eye, end, false, false, false);
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
            throw new CommandException("Look directly at a container within five blocks, or supply x y z.");
        }
        return hit.getBlockPos();
    }

    private static void requireLoadedRay(WorldServer world, Vec3d start, Vec3d end) throws CommandException {
        int firstX = MathHelper.floor(Math.min(start.x, end.x)) >> 4;
        int lastX = MathHelper.floor(Math.max(start.x, end.x)) >> 4;
        int firstZ = MathHelper.floor(Math.min(start.z, end.z)) >> 4;
        int lastZ = MathHelper.floor(Math.max(start.z, end.z)) >> 4;
        for (int x = firstX; x <= lastX; x++) {
            for (int z = firstZ; z <= lastZ; z++) {
                if (world.getChunkProvider().getLoadedChunk(x, z) == null) {
                    throw new CommandException("The target crosses an unloaded chunk. Move closer and try again.");
                }
            }
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
            String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "inspect", "convert", "convert-fixed");
        }
        if (args.length >= 2 && args.length <= 4 && isAction(args[0])) {
            return getTabCompletionCoordinate(args, 1, targetPos);
        }
        return Collections.emptyList();
    }
}
