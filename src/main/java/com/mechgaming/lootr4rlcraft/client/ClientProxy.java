package com.mechgaming.lootr4rlcraft.client;

import com.mechgaming.lootr4rlcraft.CommonProxy;
import com.mechgaming.lootr4rlcraft.Settings;
import com.mechgaming.lootr4rlcraft.network.ChestViews;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecartChest;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.lwjgl.opengl.GL11;

public final class ClientProxy extends CommonProxy {
    private final Map<Long, Map<BlockPos, Indicator>> chunks = new HashMap<>();
    private final Set<Long> awaitingSnapshot = new HashSet<>();
    private final Map<Integer, CartIndicator> carts = new HashMap<>();
    private WorldClient trackedWorld;

    @Override
    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void acceptView(ChestViews.View message, MessageContext context) {
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.addScheduledTask(() -> {
            WorldClient world = minecraft.world;
            if (world == null || minecraft.getConnection() != context.netHandler
                    || world.provider.getDimension() != message.getDimension()) {
                return;
            }
            track(world);
            applyView(world, message);
        });
    }

    private void applyView(WorldClient world, ChestViews.View message) {
        long key = ChunkPos.asLong(message.getChunkX(), message.getChunkZ());
        if (message.isSnapshot()) {
            chunks.remove(key);
            awaitingSnapshot.remove(key);
        }
        Chunk chunk = world.getChunkProvider().getLoadedChunk(message.getChunkX(), message.getChunkZ());
        for (Map.Entry<BlockPos, Boolean> entry : message.getStates().entrySet()) {
            BlockPos pos = entry.getKey();
            TileEntity tile = chunk == null ? null : world.getTileEntity(pos);
            chunks.computeIfAbsent(key, ignored -> new HashMap<>()).put(pos, new Indicator(tile, entry.getValue()));
        }
    }

    private void track(WorldClient world) {
        if (trackedWorld != world) {
            chunks.clear();
            awaitingSnapshot.clear();
            carts.clear();
            trackedWorld = world;
        }
    }

    @Override
    public void updateCart(int dimension, int entityId, UUID uuid, boolean managed, boolean opened,
            MessageContext context) {
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.addScheduledTask(() -> {
            WorldClient world = minecraft.world;
            if (world == null || world.provider.getDimension() != dimension || minecraft.getConnection() != context.netHandler) {
                return;
            }
            track(world);
            if (managed) {
                carts.put(entityId, new CartIndicator(uuid, opened));
            } else {
                carts.computeIfPresent(entityId, (id, state) -> state.uuid.equals(uuid) ? null : state);
            }
        });
    }

    @SubscribeEvent
    public void unloadChunk(ChunkEvent.Unload event) {
        if (event.getWorld() == trackedWorld) {
            Chunk chunk = event.getChunk();
            long key = ChunkPos.asLong(chunk.x, chunk.z);
            chunks.remove(key);
            awaitingSnapshot.remove(key);
        }
    }

    @SubscribeEvent
    public void unloadWorld(WorldEvent.Unload event) {
        if (event.getWorld() == trackedWorld) {
            track(null);
        }
    }

    @SubscribeEvent
    public void identifyTarget(RenderGameOverlayEvent.Text event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        RayTraceResult hit = minecraft.objectMouseOver;
        if (!Settings.indicators || minecraft.world == null || minecraft.world != trackedWorld || hit == null) {
            return;
        }
        Boolean opened = null;
        if (hit.typeOfHit == RayTraceResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            Map<BlockPos, Indicator> entries = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            Indicator indicator = entries == null ? null : entries.get(pos);
            if (indicator != null && liveTile(minecraft.world, pos, indicator) != null) {
                opened = indicator.opened;
            }
        } else if (hit.entityHit instanceof EntityMinecartChest) {
            CartIndicator cart = carts.get(hit.entityHit.getEntityId());
            if (cart != null && cart.uuid.equals(hit.entityHit.getUniqueID())) {
                opened = cart.opened;
            }
        }
        if (opened != null) {
            event.getLeft().add(I18n.format(opened ? "hud.lootr4rlcraft.opened" : "hud.lootr4rlcraft.unopened"));
        }
    }

    private TileEntity liveTile(WorldClient world, BlockPos pos, Indicator indicator) {
        Chunk chunk = world.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
        TileEntity tile = chunk == null ? null : chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);
        if (tile == null || tile.isInvalid()) {
            return null;
        }
        if (tile != indicator.tile) {
            // A chunk packet can precede a tile's creation, and mods may replace a client-side tile.
            // Confirm its ownership before displaying anything on a possibly ordinary replacement.
            if (awaitingSnapshot.add(ChunkPos.asLong(chunk.x, chunk.z))) {
                ChestViews.refresh(world.provider.getDimension(), chunk.x, chunk.z);
            }
            return null;
        }
        return tile;
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        WorldClient world = minecraft.world;
        Entity camera = minecraft.getRenderViewEntity();
        if (!Settings.indicators || world == null || world != trackedWorld || camera == null
                || (chunks.isEmpty() && carts.isEmpty())) {
            return;
        }
        float tick = event.getPartialTicks();
        double x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * tick;
        double y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * tick;
        double z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * tick;
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            int radius = (minecraft.gameSettings.renderDistanceChunks + 1) * 16;
            for (Map<BlockPos, Indicator> indicators : chunks.values()) {
                drawChunk(world, buffer, indicators, x, y, z, tick, radius);
            }
            carts.forEach((id, state) -> {
                Entity cart = world.getEntityByID(id);
                if (cart instanceof EntityMinecartChest && state.uuid.equals(cart.getUniqueID())) {
                    double cx = cart.lastTickPosX + (cart.posX - cart.lastTickPosX) * tick - x;
                    double cy = cart.lastTickPosY + (cart.posY - cart.lastTickPosY) * tick - y;
                    double cz = cart.lastTickPosZ + (cart.posZ - cart.lastTickPosZ) * tick - z;
                    // A free-standing seal follows the cart without replacing its model or rail transforms.
                    drawSeal(buffer, cx, cy + 1.08, cz, state.opened);
                }
            });
            tessellator.draw();
        } finally {
            GL11.glPopAttrib();
        }
    }

    private void drawChunk(WorldClient world, BufferBuilder buffer, Map<BlockPos, Indicator> indicators,
            double x, double y, double z, float tick, int radius) {
        for (Map.Entry<BlockPos, Indicator> entry : indicators.entrySet()) {
            BlockPos pos = entry.getKey();
            Indicator indicator = entry.getValue();
            if (Math.abs(pos.getX() + 0.5 - x) <= radius && Math.abs(pos.getZ() + 0.5 - z) <= radius
                    && liveTile(world, pos, indicator) != null) {
                int color = indicator.opened ? Settings.openedColor : Settings.unopenedColor;
                Chunk chunk = world.getChunkFromBlockCoords(pos);
                if (indicator.tile instanceof TileEntityChest && chunk.getBlockState(pos).getBlock() instanceof BlockChest) {
                    TileEntityChest chest = (TileEntityChest) indicator.tile;
                    chest.checkForAdjacentChests();
                    drawBands(buffer, pos.getX() - x, pos.getY() - y, pos.getZ() - z, color,
                            hasPartner(world, chest.adjacentChestXNeg), hasPartner(world, chest.adjacentChestXPos),
                            hasPartner(world, chest.adjacentChestZNeg), hasPartner(world, chest.adjacentChestZPos));
                    float lid = chest.prevLidAngle + (chest.lidAngle - chest.prevLidAngle) * tick;
                    drawLidEmblem(buffer, pos.getX() - x, pos.getY() - y, pos.getZ() - z,
                            chunk.getBlockState(pos).getValue(BlockChest.FACING), lid, color);
                } else {
                    AxisAlignedBB bounds = chunk.getBlockState(pos).getBoundingBox(world, pos);
                    drawContainerBadge(buffer, bounds.offset(pos).offset(-x, -y, -z), color);
                }
            }
        }
    }

    private boolean hasPartner(WorldClient world, TileEntityChest adjacent) {
        if (adjacent == null) {
            return false;
        }
        BlockPos neighbor = adjacent.getPos();
        Map<BlockPos, Indicator> indicators = chunks.get(ChunkPos.asLong(neighbor.getX() >> 4, neighbor.getZ() >> 4));
        if (indicators == null) {
            return false;
        }
        Indicator partner = indicators.get(neighbor);
        return partner != null && liveTile(world, neighbor, partner) == adjacent;
    }

    private static void drawSeal(BufferBuilder buffer, double x, double y, double z, boolean opened) {
        int color = opened ? Settings.openedColor : Settings.unopenedColor;
        double radius = 0.17;
        // Each facet is emitted as a quad with the pole repeated, keeping all indicators in one draw.
        for (int side = 0; side < 4; side++) {
            double a = side * Math.PI / 2;
            double b = (side + 1) * Math.PI / 2;
            double x1 = x + Math.cos(a) * radius, z1 = z + Math.sin(a) * radius;
            double x2 = x + Math.cos(b) * radius, z2 = z + Math.sin(b) * radius;
            for (int sign = -1; sign <= 1; sign += 2) {
                float shade = sign > 0 ? 1.0f : 0.70f;
                vertex(buffer, x1, y, z1, color, shade);
                vertex(buffer, x2, y, z2, color, shade);
                vertex(buffer, x, y + sign * 0.16, z, color, shade);
                vertex(buffer, x, y + sign * 0.16, z, color, shade);
            }
        }
    }

    private static void drawContainerBadge(BufferBuilder buffer, AxisAlignedBB bounds, int color) {
        double centerX = (bounds.minX + bounds.maxX) * 0.5;
        double centerZ = (bounds.minZ + bounds.maxZ) * 0.5;
        double top = bounds.maxY + 0.003;
        double radius = Math.min(0.17, Math.min(bounds.maxX - bounds.minX, bounds.maxZ - bounds.minZ) * 0.25);
        vertex(buffer, centerX - radius, top, centerZ, color, 1.0f);
        vertex(buffer, centerX, top, centerZ - radius, color, 1.0f);
        vertex(buffer, centerX + radius, top, centerZ, color, 1.0f);
        vertex(buffer, centerX, top, centerZ + radius, color, 1.0f);
        double middleY = bounds.minY + (bounds.maxY - bounds.minY) * 0.65;
        drawLootMark(buffer, centerX, middleY, bounds.minZ - 0.003, false, color);
        drawLootMark(buffer, centerX, middleY, bounds.maxZ + 0.003, false, color);
        drawLootMark(buffer, centerZ, middleY, bounds.minX - 0.003, true, color);
        drawLootMark(buffer, centerZ, middleY, bounds.maxX + 0.003, true, color);
    }

    private static void drawBands(BufferBuilder buffer, double x, double y, double z, int color,
            boolean west, boolean east, boolean north, boolean south) {
        // Native chest bodies span 1/16..15/16 and stop at y=10/16. The trim stays on
        // that fixed body so it cannot distort the lid, its animation, or attached locks.
        double minX = x + (west ? 0.0 : 0.0615);
        double maxX = x + (east ? 1.0 : 0.9385);
        double minZ = z + (north ? 0.0 : 0.0615);
        double maxZ = z + (south ? 1.0 : 0.9385);
        double bottom = y + 0.08;
        double top = y + 0.61;
        if (!north) {
            drawFaceBands(buffer, minX, maxX, minZ, bottom, top, color, false, 0.90f);
        }
        if (!south) {
            drawFaceBands(buffer, minX, maxX, maxZ, bottom, top, color, false, 0.90f);
        }
        if (!west) {
            drawFaceBands(buffer, minZ, maxZ, minX, bottom, top, color, true, 0.75f);
        }
        if (!east) {
            drawFaceBands(buffer, minZ, maxZ, maxX, bottom, top, color, true, 0.75f);
        }
    }

    private static void drawFaceBands(BufferBuilder buffer, double left, double right, double depth,
            double bottom, double top, int color, boolean swapAxes, float shade) {
        face(buffer, left, right, depth, bottom, bottom + 0.09, color, swapAxes, shade);
        face(buffer, left, right, depth, top - 0.09, top, color, swapAxes, shade);
        // Offset the seal from the central latch and Locks' attachment.
        drawLootMark(buffer, left + (right - left) * 0.29, (bottom + top) / 2, depth, swapAxes, color);
    }

    /** Two stacked chevrons form the same original mark on flat and animated containers. */
    private static void drawLootMark(BufferBuilder buffer, double center, double y, double depth,
            boolean swapAxes, int color) {
        for (int row = 0; row < 2; row++) {
            double base = y - 0.11 + row * 0.13;
            for (int sign = -1; sign <= 1; sign += 2) {
                double edge = center + 0.13 * sign;
                float shade = sign > 0 ? 0.84f : 1f;
                markVertex(buffer, center, base, depth, swapAxes, color, shade);
                markVertex(buffer, edge, base + 0.065, depth, swapAxes, color, shade);
                markVertex(buffer, edge, base + 0.123, depth, swapAxes, color, shade);
                markVertex(buffer, center, base + 0.058, depth, swapAxes, color, shade);
            }
        }
    }

    private static void markVertex(BufferBuilder buffer, double horizontal, double vertical, double depth,
            boolean swapAxes, int color, float shade) {
        vertex(buffer, swapAxes ? depth : horizontal, vertical, swapAxes ? horizontal : depth, color, shade);
    }

    private static void face(BufferBuilder buffer, double left, double right, double depth,
            double bottom, double top, int color, boolean swapAxes, float shade) {
        if (swapAxes) {
            vertex(buffer, depth, bottom, left, color, shade);
            vertex(buffer, depth, top, left, color, shade);
            vertex(buffer, depth, top, right, color, shade);
            vertex(buffer, depth, bottom, right, color, shade);
        } else {
            vertex(buffer, left, bottom, depth, color, shade);
            vertex(buffer, right, bottom, depth, color, shade);
            vertex(buffer, right, top, depth, color, shade);
            vertex(buffer, left, top, depth, color, shade);
        }
    }

    private static void drawLidEmblem(BufferBuilder buffer, double x, double y, double z,
            EnumFacing facing, float lid, int color) {
        double progress = 1.0 - Math.pow(1.0 - lid, 3.0);
        double angle = progress * Math.PI * 0.5;
        double sine = Math.sin(angle);
        double cosine = Math.cos(angle);
        lidVertex(buffer, x, y, z, facing, 0.5, 0.16, sine, cosine, color);
        lidVertex(buffer, x, y, z, facing, 0.77, 0.4375, sine, cosine, color);
        lidVertex(buffer, x, y, z, facing, 0.5, 0.715, sine, cosine, color);
        lidVertex(buffer, x, y, z, facing, 0.23, 0.4375, sine, cosine, color);
    }

    private static void lidVertex(BufferBuilder buffer, double x, double y, double z, EnumFacing facing,
            double across, double depth, double sine, double cosine, int color) {
        double fromHinge = depth * cosine - 0.3145 * sine;
        double height = y + 0.5625 + depth * sine + 0.3145 * cosine;
        double along = 0.0625 + fromHinge;
        switch (facing) {
            case NORTH:
                vertex(buffer, x + across, height, z + 1.0 - along, color, 1.0f);
                break;
            case SOUTH:
                vertex(buffer, x + across, height, z + along, color, 1.0f);
                break;
            case WEST:
                vertex(buffer, x + 1.0 - along, height, z + across, color, 1.0f);
                break;
            case EAST:
                vertex(buffer, x + along, height, z + across, color, 1.0f);
                break;
            default:
                throw new IllegalArgumentException("A chest must face horizontally");
        }
    }

    private static void vertex(BufferBuilder buffer, double x, double y, double z, int color, float light) {
        buffer.pos(x, y, z).color((int) (((color >> 16) & 255) * light),
                (int) (((color >> 8) & 255) * light), (int) ((color & 255) * light), 255).endVertex();
    }

    private static final class Indicator {
        private final TileEntity tile;
        private final boolean opened;

        private Indicator(TileEntity tile, boolean opened) {
            this.tile = tile;
            this.opened = opened;
        }
    }

    private static final class CartIndicator {
        private final UUID uuid;
        private final boolean opened;

        private CartIndicator(UUID uuid, boolean opened) {
            this.uuid = uuid;
            this.opened = opened;
        }
    }
}
