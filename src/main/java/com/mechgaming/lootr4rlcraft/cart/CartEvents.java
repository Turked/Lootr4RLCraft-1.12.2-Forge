package com.mechgaming.lootr4rlcraft.cart;

import com.mechgaming.lootr4rlcraft.Settings;
import com.mechgaming.lootr4rlcraft.loot.ChestLootData;
import java.util.function.BiPredicate;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecartChest;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class CartEvents {
    private static final String MARKER = "lootr4rlcraft:cart";
    private final BiPredicate<WorldServer, Entity> generated;

    public CartEvents(BiPredicate<WorldServer, Entity> generated) { this.generated = generated; }

    private static boolean managed(Entity entity) {
        return entity instanceof EntityMinecartChest && entity.getEntityData().getBoolean(MARKER);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void join(EntityJoinWorldEvent event) {
        if (!(event.getWorld() instanceof WorldServer) || event.getEntity().getClass() != EntityMinecartChest.class) return;
        WorldServer world = (WorldServer) event.getWorld();
        EntityMinecartChest cart = (EntityMinecartChest) event.getEntity();
        if (CartRecords.get(world).contains(cart.getUniqueID())) {
            // An entity restored from an older chunk save must not replay an already enrolled table.
            cart.setLootTable(null, 0L);
            protect(cart);
        } else if (!managed(cart) && Settings.registerNewChests && Settings.blockAllowed("minecraft:chest_minecart", "cart")
                && generated.test(world, cart) && (cart.getLootTable() != null || Settings.generatedFixedChests && !cart.isEmpty())) {
            convert(world, cart, cart.getLootTable() == null);
        }
    }

    private static void protect(EntityMinecartChest cart) {
        cart.getEntityData().setBoolean(MARKER, true);
        cart.setEntityInvulnerable(true);
    }

    public static String convert(WorldServer world, EntityMinecartChest cart, boolean fixed) {
        if (cart.getClass() != EntityMinecartChest.class || managed(cart) || cart.isDead || cart.world != world
                || !Settings.blockAllowed("minecraft:chest_minecart", "cart")) {
            throw new IllegalArgumentException("Minecart is already enrolled, unavailable, or excluded.");
        }
        if (fixed != (cart.getLootTable() == null)) throw new IllegalArgumentException("Use convert for retained tables and convert-fixed for physical slots.");
        CartRecords.get(world).enroll(cart);
        protect(cart);
        Settings.debug("Registered chest minecart {} in dimension {}", cart.getUniqueID(), cart.dimension);
        for (net.minecraft.entity.player.EntityPlayer watcher : world.getEntityTracker().getTrackingPlayers(cart)) {
            if (watcher instanceof EntityPlayerMP) sync((EntityPlayerMP) watcher, cart);
        }
        return "Registered minecart " + cart.getUniqueID() + " with 27 private slots.";
    }

    public static String inspect(WorldServer world, EntityMinecartChest cart) {
        return "Minecart " + cart.getUniqueID() + ": " + (managed(cart)
                ? (CartRecords.get(world).contains(cart.getUniqueID()) ? "managed, 27 private slots." : "missing saved record; access suspended.")
                : (cart.getLootTable() != null ? "native table " + cart.getLootTable() : "physical inventory"));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void carry(PlayerInteractEvent.EntityInteract event) {
        if (!event.getWorld().isRemote && managed(event.getTarget()) && event.getEntityPlayer().getEntityData().getBoolean("carryOnKeyPressed")
                && event.getEntityPlayer().getHeldItemMainhand().isEmpty() && event.getEntityPlayer().getHeldItemOffhand().isEmpty()) {
            event.setCanceled(true);
            event.setCancellationResult(EnumActionResult.FAIL);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void open(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntityPlayer() instanceof EntityPlayerMP) || !managed(event.getTarget())) return;
        EntityPlayerMP player = (EntityPlayerMP) event.getEntityPlayer();
        EntityMinecartChest cart = (EntityMinecartChest) event.getTarget();
        event.setCanceled(true);
        event.setCancellationResult(EnumActionResult.FAIL);
        CartRecords records = CartRecords.get(player.getServerWorld());
        if (!Settings.privateLootEnabled || player.isSpectator() || player instanceof FakePlayer || event.getHand() != EnumHand.MAIN_HAND
                || !cart.isUsableByPlayer(player) || !records.contains(cart.getUniqueID())) return;
        player.displayGUIChest(records.open(cart, player));
        sync(player, cart);
        event.setCancellationResult(EnumActionResult.SUCCESS);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void attack(AttackEntityEvent event) {
        if (!event.getEntityPlayer().world.isRemote && managed(event.getTarget())) event.setCanceled(true);
    }

    private static void sync(EntityPlayerMP player, EntityMinecartChest cart) {
        ChestLootData loot = CartRecords.get((WorldServer) cart.world).find(cart);
        CartViews.send(player, cart, loot != null, loot != null && loot.hasOpened(player));
    }

    @SubscribeEvent
    public void start(PlayerEvent.StartTracking event) {
        if (event.getEntityPlayer() instanceof EntityPlayerMP && managed(event.getTarget()))
            sync((EntityPlayerMP) event.getEntityPlayer(), (EntityMinecartChest) event.getTarget());
    }

    @SubscribeEvent
    public void stop(PlayerEvent.StopTracking event) {
        if (event.getEntityPlayer() instanceof EntityPlayerMP && managed(event.getTarget()))
            CartViews.send((EntityPlayerMP) event.getEntityPlayer(), (EntityMinecartChest) event.getTarget(), false, false);
    }
}
