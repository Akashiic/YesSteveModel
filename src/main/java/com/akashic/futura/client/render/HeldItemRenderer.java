package com.akashic.futura.client.render;

import java.nio.FloatBuffer;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.tileentity.TileEntitySkullRenderer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.util.StringUtils;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.MinecraftForgeClient;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.akashic.futura.client.PlayerModels;
import com.akashic.futura.geo.LocatorTransforms;
import com.akashic.futura.geo.Mat4;
import com.mojang.authlib.GameProfile;

/**
 * Draws held and helmet-slot items at locator frames with 1.7.10's own item rendering: the per-item transforms of
 * {@code RenderPlayer.renderEquippedItems} (blocks, bows, full-3D tools, flat items, the fishing rod drawn as a stick
 * while cast, {@code IItemRenderer} {@code EQUIPPED} blocks, multi-pass items with their colour) after
 * {@link LocatorTransforms#handToLegacyArm} instead of the biped arm.
 */
public final class HeldItemRenderer {

    private final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
    private final float[] scratch = new float[16];

    /** Multiplies the current GL matrix by a locator frame (model space) followed by a transform. */
    private void apply(float[] locator, int offset, boolean hand) {
        Mat4.copy(locator, offset, scratch, 0);
        if (hand) {
            LocatorTransforms.handToLegacyArm(scratch, 0);
        } else {
            LocatorTransforms.headToLegacyHead(scratch, 0);
        }
        buffer.clear();
        buffer.put(scratch, 0, 16);
        buffer.flip();
        GL11.glMultMatrix(buffer);
    }

    /**
     * Draws the item a player holds at a hand locator.
     *
     * @param locator the locator frame in the current GL space (from {@code PoseMatrices.locator})
     */
    public void renderHeld(EntityPlayer player, ItemStack stack, float[] locator, int offset) {
        if (stack == null || stack.getItem() == null) {
            return;
        }
        GL11.glPushMatrix();
        apply(locator, offset, true);
        if (player.fishEntity != null && !PlayerModels.keepsItemWhileFishing(player)) {
            stack = new ItemStack(Items.stick);
        }
        Item item = stack.getItem();
        EnumAction action = player.getItemInUseCount() > 0 ? stack.getItemUseAction() : null;
        IItemRenderer custom = MinecraftForgeClient.getItemRenderer(stack, IItemRenderer.ItemRenderType.EQUIPPED);
        boolean block3d = custom != null && custom.shouldUseRenderHelper(
            IItemRenderer.ItemRenderType.EQUIPPED,
            stack,
            IItemRenderer.ItemRendererHelper.BLOCK_3D);
        float f;
        if (block3d || item instanceof ItemBlock && RenderBlocks.renderItemIn3d(
            Block.getBlockFromItem(item)
                .getRenderType())) {
            f = 0.5f * 0.75f;
            GL11.glTranslatef(0, 0.1875f, -0.3125f);
            GL11.glRotatef(20, 1, 0, 0);
            GL11.glRotatef(45, 0, 1, 0);
            GL11.glScalef(-f, -f, f);
        } else if (item == Items.bow) {
            f = 0.625f;
            GL11.glTranslatef(0, 0.125f, 0.3125f);
            GL11.glRotatef(-20, 0, 1, 0);
            GL11.glScalef(f, -f, f);
            GL11.glRotatef(-100, 1, 0, 0);
            GL11.glRotatef(45, 0, 1, 0);
        } else if (item.isFull3D()) {
            f = 0.625f;
            if (item.shouldRotateAroundWhenRendering()) {
                GL11.glRotatef(180, 0, 0, 1);
                GL11.glTranslatef(0, -0.125f, 0);
            }
            if (action == EnumAction.block) {
                GL11.glTranslatef(0.05f, 0, -0.1f);
                GL11.glRotatef(-50, 0, 1, 0);
                GL11.glRotatef(-10, 1, 0, 0);
                GL11.glRotatef(-60, 0, 0, 1);
            }
            GL11.glTranslatef(0, 0.1875f, 0);
            GL11.glScalef(f, -f, f);
            GL11.glRotatef(-100, 1, 0, 0);
            GL11.glRotatef(45, 0, 1, 0);
        } else {
            f = 0.375f;
            GL11.glTranslatef(0.25f, 0.1875f, -0.1875f);
            GL11.glScalef(f, f, f);
            GL11.glRotatef(60, 0, 0, 1);
            GL11.glRotatef(-90, 1, 0, 0);
            GL11.glRotatef(20, 0, 0, 1);
        }
        renderPasses(player, stack);
        GL11.glPopMatrix();
    }

    private static void renderPasses(EntityLivingBase entity, ItemStack stack) {
        Item item = stack.getItem();
        int passes = item.requiresMultipleRenderPasses() ? item.getRenderPasses(stack.getItemDamage()) : 1;
        for (int pass = 0; pass < passes; pass++) {
            int color = item.getColorFromItemStack(stack, pass);
            GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, 1);
            RenderManager.instance.itemRenderer.renderItem(entity, stack, pass);
        }
    }

    /** Whether vanilla draws this helmet-slot item at the head (blocks and skulls; armour is the model's business). */
    public static boolean drawsOnHead(ItemStack stack) {
        return stack != null && stack.getItem() != null
            && !(stack.getItem() instanceof ItemArmor)
            && (stack.getItem() instanceof ItemBlock || stack.getItem() == Items.skull);
    }

    /** Draws a helmet-slot block or skull at the {@code Head} locator, as {@code RenderPlayer} does at the head. */
    public void renderHead(EntityPlayer player, ItemStack stack, float[] locator, int offset) {
        if (!drawsOnHead(stack)) {
            return;
        }
        GL11.glPushMatrix();
        apply(locator, offset, false);
        Item item = stack.getItem();
        if (item instanceof ItemBlock) {
            IItemRenderer custom = MinecraftForgeClient.getItemRenderer(stack, IItemRenderer.ItemRenderType.EQUIPPED);
            boolean block3d = custom != null && custom.shouldUseRenderHelper(
                IItemRenderer.ItemRenderType.EQUIPPED,
                stack,
                IItemRenderer.ItemRendererHelper.BLOCK_3D);
            if (block3d || RenderBlocks.renderItemIn3d(
                Block.getBlockFromItem(item)
                    .getRenderType())) {
                float f = 0.625f;
                GL11.glTranslatef(0, -0.25f, 0);
                GL11.glRotatef(90, 0, 1, 0);
                GL11.glScalef(f, -f, -f);
            }
            GL11.glColor4f(1, 1, 1, 1);
            RenderManager.instance.itemRenderer.renderItem(player, stack, 0);
        } else {
            float f = 1.0625f;
            GL11.glScalef(f, -f, -f);
            GameProfile profile = null;
            if (stack.hasTagCompound()) {
                NBTTagCompound tag = stack.getTagCompound();
                if (tag.hasKey("SkullOwner", 10)) {
                    profile = NBTUtil.func_152459_a(tag.getCompoundTag("SkullOwner"));
                } else if (tag.hasKey("SkullOwner", 8) && !StringUtils.isNullOrEmpty(tag.getString("SkullOwner"))) {
                    profile = new GameProfile((UUID) null, tag.getString("SkullOwner"));
                }
            }
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            TileEntitySkullRenderer.field_147536_b
                .func_152674_a(-0.5f, 0, -0.5f, 1, 180, stack.getItemDamage(), profile);
        }
        GL11.glPopMatrix();
    }
}
