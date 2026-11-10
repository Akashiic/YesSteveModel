/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/geo/GeoProjectilesRenderer.java and GeoEntityRenderer.java
 * (renderReplacement: placement, scale, invisible entities), src/main/java/com/elfmcys/ysm/client/renderer/replace/
 * EntityRendererReplace.java (getYaw: living and minecart yaw), ProjectileRendererReplace.java and
 * FishingHookRendererReplace.java, src/main/java/com/elfmcys/ysm/util/RenderUtil.java (adjustPassengerPosition),
 * src/main/java/com/elfmcys/ysm/event/CapabilityEvent.java (onProjectileSetOwner / onVehicleSetModel: whose model a
 * projectile or vehicle is drawn with) and src/main/java/com/elfmcys/ysm/client/entity/CustomProjectileEntity.java /
 * CustomVehicleEntity.java (0.7 scale), Apache License 2.0.
 * Changes: the owner is found on the client (the arrow's shooter, the hook's angler, the vehicle's player rider)
 * instead of a model id the server attaches to the entity; PoseStack math turned into 1.7.10 GL calls, with the 1.7.10
 * conventions of each entity (bounding box offset, boat yaw).
 */
package com.akashic.futura.client;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityEgg;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.akashic.futura.Futura3D;
import com.akashic.futura.anim.EntityAnimator;
import com.akashic.futura.anim.EntitySnapshot;
import com.akashic.futura.anim.PlayerAnimator;
import com.akashic.futura.geo.PoseMatrices;
import com.akashic.futura.mixin.mixins.EntityArrowAccessor;
import com.akashic.futura.model.LegacyEntityIds;

/**
 * Draws projectiles and vehicles with the replacement models of their owner's model ({@code files.projectiles},
 * {@code files.vehicles}): an arrow with its shooter's, a fishing hook with its angler's, a boat, minecart, pig or
 * horse with its player rider's (and, as upstream keeps the last rider's model on the entity, with the last player
 * rider's after he gets off). Everything else, and any failure, falls back to the vanilla renderer.
 */
public final class EntityReplacements {

    public static final EntityReplacements INSTANCE = new EntityReplacements();

    /** Upstream {@code CustomProjectileEntity/CustomVehicleEntity.getWidthScale/getHeightScale}. */
    static final float SCALE = 0.7f;
    /** Upstream {@code RenderUtil.adjustPassengerPosition}: a player sits half a block lower in a replaced vehicle. */
    static final double PLAYER_SEAT_OFFSET = -0.5;

    /** What one entity is drawn with. */
    private static final class State {

        /** The owner was looked for (a projectile's owner is fixed at the first draw). */
        boolean resolved;
        /** The model key of the owner (fixed for a projectile, the last player rider's for a vehicle), or null. */
        String modelKey;
        /**
         * The player whose roaming variables a vehicle takes while he rides it. Weak: the states are weakly keyed by
         * the vehicle, and a rider points back to it ({@code ridingEntity}).
         */
        java.lang.ref.WeakReference<EntityPlayer> riderRef;
        String shootItemId = "";
        ClientModel model;
        ClientReplacements.Target target;
        EntityAnimator animator;
        PoseMatrices pose;
        final EntitySnapshot snapshot = new EntitySnapshot();

        EntityPlayer rider() {
            return riderRef == null ? null : riderRef.get();
        }

        void setRider(EntityPlayer player) {
            riderRef = player == null ? null : new java.lang.ref.WeakReference<>(player);
        }
    }

    private final Map<Entity, State> states = new WeakHashMap<>();
    private boolean enabled = true;

    private EntityReplacements() {}

    /** Turns the replacements off (vanilla projectiles and vehicles) or back on. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clear();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Throwers the server named ({@code S_PROJECTILE_OWNER}, protocol 3) for projectiles the client cannot attribute
     * itself, by entity id. Entity ids are reused, so an entry lives only until the world changes or the entity is
     * drawn.
     */
    private final Map<Integer, java.util.UUID> throwers = new java.util.HashMap<>();

    /** The server says who threw the projectile with this entity id. */
    public void setThrower(int entityId, java.util.UUID thrower) {
        if (throwers.size() > 4096) {
            throwers.clear();
        }
        throwers.put(entityId, thrower);
    }

    /** Forgets every entity (models were freed, or the world changed). */
    /** Entities with a replacement state (leak checks of the harness). */
    public int stateCount() {
        return states.size();
    }

    public void clear() {
        throwers.clear();
        for (State state : states.values()) {
            if (state.animator != null) {
                state.animator.stopSounds();
            }
        }
        states.clear();
    }

    /** The 1.13+ id a model's {@code match} names this entity by. */
    static String entityId(Entity entity) {
        if (entity instanceof EntityFishHook) {
            return LegacyEntityIds.FISHING_BOBBER;
        }
        if (entity instanceof EntityEgg) {
            return LegacyEntityIds.EGG;
        }
        int horse = entity instanceof EntityHorse ? ((EntityHorse) entity).getHorseType() : 0;
        return LegacyEntityIds.of(EntityList.getEntityString(entity), horse);
    }

    // ---- projectiles ----

    /**
     * The player who shot an arrow or cast a hook, as the client knows it, or who threw any other projectile, as the
     * server said; null if unknown.
     */
    EntityPlayer projectileOwner(Entity entity) {
        Entity owner = entity instanceof EntityArrow ? ((EntityArrow) entity).shootingEntity
            : entity instanceof EntityFishHook ? ((EntityFishHook) entity).field_146042_b : null;
        if (owner == null) {
            java.util.UUID thrower = throwers.remove(entity.getEntityId());
            if (thrower != null && entity.worldObj != null) {
                owner = entity.worldObj.func_152378_a(thrower);
            }
        }
        return owner instanceof EntityPlayer ? (EntityPlayer) owner : null;
    }

    /**
     * Draws a projectile with its owner's replacement, where its vanilla renderer would draw it.
     *
     * @param x  render position, as {@code Render.doRender} gets it
     * @param pt partial tick
     * @return false to let the vanilla renderer draw it
     */
    public boolean renderProjectile(Entity entity, double x, double y, double z, float pt) {
        if (!enabled || !PlayerModels.INSTANCE.active()) {
            return false;
        }
        State state = state(entity);
        if (!state.resolved) {
            // upstream fixes the model when the owner is set (the shot): here, when the client first draws it. A thrown
            // projectile's owner comes in a message right after its spawn packet: wait a few ticks for it.
            EntityPlayer owner = projectileOwner(entity);
            state.resolved = owner != null || entity instanceof EntityArrow
                || entity instanceof EntityFishHook
                || entity.ticksExisted > 5;
            if (owner != null) {
                state.modelKey = PlayerModels.INSTANCE.keyOf(owner);
                state.shootItemId = itemId(owner.getHeldItem());
                state.setRider(owner);
            }
        }
        if (!prepare(state, entity, true)) {
            return false;
        }
        EntityPlayer shooter = state.rider();
        if (shooter != null) {
            // the shooter's roaming variables at the shot (upstream initRoamingVars once)
            takeRoaming(state, shooter);
            state.setRider(null);
        }
        float yaw = entity.prevRotationYaw + (entity.rotationYaw - entity.prevRotationYaw) * pt;
        float pitch = entity instanceof EntityFishHook ? 0 // upstream: the bobber's pitch is forced to 0
            : entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * pt;
        return draw(state, entity, x, y, z, pt, yaw - 90, pitch);
    }

    // ---- vehicles ----

    /**
     * Draws a vehicle with its player rider's replacement (or its last player rider's), where its vanilla renderer
     * would draw it.
     *
     * @param yawIn the yaw the vanilla renderer gets
     * @return false to let the vanilla renderer draw it
     */
    public boolean renderVehicle(Entity entity, double x, double y, double z, float yawIn, float pt) {
        if (!enabled || !PlayerModels.INSTANCE.active() || entity instanceof EntityPlayer) {
            return false;
        }
        State state = states.get(entity);
        if (!(entity.riddenByEntity instanceof EntityPlayer) && (state == null || state.modelKey == null)) {
            return false;
        }
        if (state == null) {
            state = state(entity);
        }
        updateRider(state, entity);
        if (!prepare(state, entity, false)) {
            return false;
        }
        EntityPlayer riding = state.rider();
        if (riding != null && riding == entity.riddenByEntity) {
            takeRoaming(state, riding);
        }
        return draw(state, entity, x, y, z, pt, 180 - vehicleYaw(entity, yawIn, pt), 0);
    }

    /**
     * Whether a vehicle is drawn with a replacement (it was in this frame or will be): its player passenger sits
     * {@link #PLAYER_SEAT_OFFSET} lower, at the vehicle's base, as upstream {@code adjustPassengerPosition} places him.
     */
    public boolean replacesVehicle(Entity vehicle) {
        if (!enabled || vehicle == null || !PlayerModels.INSTANCE.active() || vehicle instanceof EntityPlayer) {
            return false;
        }
        State state = states.get(vehicle);
        String key = vehicle.riddenByEntity instanceof EntityPlayer
            ? PlayerModels.INSTANCE.keyOf((EntityPlayer) vehicle.riddenByEntity)
            : state == null ? null : state.modelKey;
        if (key == null) {
            return false;
        }
        ClientModel model = ModelRegistry.INSTANCE.get(key);
        return model != null && !model.released() && model.replacements.vehicle(entityId(vehicle)) != null;
    }

    /**
     * The render y of a player riding a replaced vehicle: the vehicle's base, half a block lower (upstream
     * {@code adjustPassengerPosition}); {@code y} when the vehicle is not replaced.
     *
     * @param y the player's render y (feet)
     */
    public double passengerY(EntityPlayer player, double y, float pt) {
        Entity vehicle = player.ridingEntity;
        if (vehicle == null || !replacesVehicle(vehicle)) {
            return y;
        }
        double playerY = player.lastTickPosY + (player.posY - player.lastTickPosY) * pt - player.yOffset;
        double vehicleY = vehicle.lastTickPosY + (vehicle.posY - vehicle.lastTickPosY) * pt - vehicle.yOffset;
        return y + (vehicleY - playerY) + PLAYER_SEAT_OFFSET;
    }

    /** While a player rides it, the vehicle takes his model; after he gets off it keeps it (upstream keeps it too). */
    private static void updateRider(State state, Entity vehicle) {
        if (vehicle.riddenByEntity instanceof EntityPlayer) {
            EntityPlayer rider = (EntityPlayer) vehicle.riddenByEntity;
            state.setRider(rider);
            state.modelKey = PlayerModels.INSTANCE.keyOf(rider);
        } else {
            state.setRider(null);
        }
        state.resolved = true;
    }

    /**
     * Upstream {@code EntityRendererReplace.getYaw} in 1.13+ yaw: a living vehicle's body yaw, a minecart's along its
     * rail; a boat's 1.7.10 yaw points backwards along its x axis, a quarter turn from the usual entity yaw.
     */
    static float vehicleYaw(Entity entity, float yawIn, float pt) {
        if (entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) entity;
            float yaw = lerpAngle(living.prevRenderYawOffset, living.renderYawOffset, pt);
            if (living.ridingEntity instanceof EntityLivingBase && living.ridingEntity.shouldRiderSit()) {
                EntityLivingBase vehicle = (EntityLivingBase) living.ridingEntity;
                float head = lerpAngle(living.prevRotationYawHead, living.rotationYawHead, pt);
                yaw = lerpAngle(vehicle.prevRenderYawOffset, vehicle.renderYawOffset, pt);
                float diff = MathHelper.clamp_float(MathHelper.wrapAngleTo180_float(head - yaw), -85, 85);
                yaw = head - diff;
                if (diff * diff > 2500) {
                    yaw += diff * 0.2f;
                }
            }
            return yaw;
        }
        if (entity instanceof EntityMinecart) {
            EntityMinecart cart = (EntityMinecart) entity;
            double x = cart.lastTickPosX + (cart.posX - cart.lastTickPosX) * pt;
            double y = cart.lastTickPosY + (cart.posY - cart.lastTickPosY) * pt;
            double z = cart.lastTickPosZ + (cart.posZ - cart.lastTickPosZ) * pt;
            Vec3 base = cart.func_70489_a(x, y, z);
            if (base != null) {
                Vec3 front = cart.func_70495_a(x, y, z, 0.3);
                Vec3 back = cart.func_70495_a(x, y, z, -0.3);
                if (front == null) {
                    front = base;
                }
                if (back == null) {
                    back = base;
                }
                double dx = back.xCoord - front.xCoord, dy = back.yCoord - front.yCoord,
                    dz = back.zCoord - front.zCoord;
                if (dx * dx + dy * dy + dz * dz != 0) {
                    return (float) (Math.atan2(dz, dx) * 180 / Math.PI);
                }
            }
            return yawIn;
        }
        if (entity instanceof EntityBoat) {
            return yawIn + 90;
        }
        return yawIn;
    }

    // ---- shared ----

    private State state(Entity entity) {
        State state = states.get(entity);
        if (state == null) {
            state = new State();
            states.put(entity, state);
        }
        return state;
    }

    /** Finds the target and its animator for the owner's model; false when there is nothing to draw with. */
    private boolean prepare(State state, Entity entity, boolean projectile) {
        if (state.modelKey == null) {
            return false;
        }
        ClientModel model = ModelRegistry.INSTANCE.get(state.modelKey);
        if (model == null || model.released()) {
            // still loading (or gone): the host draws, as upstream without an installed model
            return false;
        }
        String id = entityId(entity);
        ClientReplacements.Target target = projectile ? model.replacements.projectile(id)
            : model.replacements.vehicle(id);
        if (target == null || target.animations == null) {
            return false;
        }
        if (state.model != model || state.target != target || state.animator == null) {
            if (state.animator != null) {
                state.animator.stopSounds();
            }
            state.model = model;
            state.target = target;
            state.animator = new EntityAnimator(target.animations);
            state.animator.setSoundBackend(ModelSounds.INSTANCE.backend(entity, model));
            state.pose = new PoseMatrices(target.target.geometry);
        }
        return true;
    }

    private static void takeRoaming(State state, EntityPlayer owner) {
        if (PlayerModels.INSTANCE.modelOf(owner) != state.model
            || !(owner instanceof net.minecraft.client.entity.AbstractClientPlayer)) {
            return;
        }
        PlayerAnimator body = PlayerModels.INSTANCE.animator((net.minecraft.client.entity.AbstractClientPlayer) owner);
        if (body != null) {
            state.animator.takeRoaming(body.variables());
        }
    }

    /**
     * Animates and draws: upstream {@code T(render position) · R · S(0.7)}, the model's feet at the bottom of the
     * entity's bounding box (1.7.10 keeps {@code posY} {@code yOffset} above it for boats and minecarts).
     *
     * @param yaw   degrees about y (projectiles: entity yaw − 90; vehicles: 180 − yaw)
     * @param pitch degrees about z, after the yaw (projectiles)
     */
    private boolean draw(State state, Entity entity, double x, double y, double z, float pt, float yaw, float pitch) {
        ClientReplacements.Target target = state.target;
        boolean pushed = false;
        try {
            fill(state.snapshot, entity, state.shootItemId, pt);
            state.animator.update(state.snapshot, entity.ticksExisted + pt);
            state.pose.compute(state.animator.pose());
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer != null && entity.isInvisibleToPlayer(mc.thePlayer)) {
                // upstream: consumed, but nothing drawn (no glowing outline on 1.7.10)
                return true;
            }
            GL11.glPushMatrix();
            pushed = true;
            GL11.glTranslated(x, y - entity.yOffset, z);
            GL11.glRotatef(yaw, 0, 1, 0);
            if (pitch != 0) {
                GL11.glRotatef(pitch, 0, 0, 1);
            }
            GL11.glScalef(SCALE, SCALE, SCALE);
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            target.bind();
            target.renderer()
                .draw(state.pose, state.animator.attributes(), 1, 1, 1, 1);
            GL11.glPopAttrib();
            return true;
        } catch (RuntimeException e) {
            // upstream: a failed projectile or vehicle restores the host's rendering
            target.failed = true;
            Futura3D.LOG
                .error("Replacement {} of model {} failed; drawing it as vanilla", target.target, state.model.key, e);
            return false;
        } finally {
            if (pushed) {
                GL11.glPopMatrix();
            }
        }
    }

    private static void fill(EntitySnapshot s, Entity e, String shootItemId, float pt) {
        s.ticksExisted = e.ticksExisted;
        s.partialTicks = pt;
        s.prevX = e.lastTickPosX;
        s.prevY = e.lastTickPosY - e.yOffset;
        s.prevZ = e.lastTickPosZ;
        s.x = e.posX;
        s.y = e.posY - e.yOffset;
        s.z = e.posZ;
        s.motionX = e.motionX;
        s.motionY = e.motionY;
        s.motionZ = e.motionZ;
        s.yaw = e.prevRotationYaw + (e.rotationYaw - e.prevRotationYaw) * pt;
        s.pitch = e.prevRotationPitch + (e.rotationPitch - e.prevRotationPitch) * pt;
        s.yawSpeed = MathHelper.wrapAngleTo180_float(e.rotationYaw - e.prevRotationYaw);
        s.onGround = e.onGround;
        s.inWater = e.isInWater();
        s.onFire = e.isBurning();
        s.inGround = e instanceof EntityArrow && ((EntityArrowAccessor) e).futura3d$inGround();
        s.hasRider = e.riddenByEntity != null;
        s.living = e instanceof EntityLivingBase;
        if (s.living) {
            EntityLivingBase living = (EntityLivingBase) e;
            s.bodyYaw = lerpAngle(living.prevRenderYawOffset, living.renderYawOffset, pt);
            s.health = living.getHealth();
            s.maxHealth = living.getMaxHealth();
            s.moveForward = living.moveForward;
        } else {
            s.bodyYaw = s.yaw;
            s.health = s.maxHealth = s.moveForward = 0;
        }
        s.shootItemId = shootItemId;
        World w = e.worldObj;
        s.weather = w.isThundering() ? 2 : w.isRaining() ? 1 : 0;
        s.worldTime = w.getWorldTime();
        s.openAir = w.canBlockSeeTheSky(
            MathHelper.floor_double(e.posX),
            MathHelper.floor_double(e.boundingBox.minY + 0.1),
            MathHelper.floor_double(e.posZ));
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "";
        }
        String id = Item.itemRegistry.getNameForObject(stack.getItem());
        return id == null ? "" : id;
    }

    private static float lerpAngle(float prev, float now, float pt) {
        return prev + MathHelper.wrapAngleTo180_float(now - prev) * pt;
    }
}
