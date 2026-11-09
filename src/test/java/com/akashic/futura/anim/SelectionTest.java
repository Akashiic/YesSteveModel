package com.akashic.futura.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akashic.futura.format.pojo.animation.LoopType;

/** Main-state priority, item and entity name matching, origin versions. */
class SelectionTest {

    private static String main(PlayerSnapshot s) {
        return MainStates.select(s, s.limbSwingAmount, false);
    }

    @Test
    void mainStatesFollowThePriorityList() {
        PlayerSnapshot s = new PlayerSnapshot();
        assertEquals("idle", main(s));
        s.limbSwingAmount = 0.5f;
        assertEquals("walk", main(s));
        s.sprinting = true;
        assertEquals("run", main(s));
        s.sneaking = true;
        assertEquals("sneak", main(s));
        s.limbSwingAmount = 0;
        assertEquals("sneaking", main(s));
        s.onGround = false;
        assertEquals("jump", main(s));
        s.hurtTime = 5;
        assertEquals("attacked", main(s));
        s.inWater = true;
        assertEquals("swim_stand", main(s));
        s.flying = true;
        assertEquals("fly", main(s));
        s.onLadder = true;
        s.y = 10.2;
        s.prevY = 10;
        assertEquals("ladder_up", main(s));
        s.y = 10;
        assertEquals("ladder_stillness", main(s));
        s.y = 9.8;
        assertEquals("ladder_down", main(s));
        s.sleeping = true;
        assertEquals("sleep", main(s));
        s.deadOrDying = true;
        assertEquals("death", main(s));
    }

    @Test
    void walkNeedsForwardSpeedButCtrlUsesTheMagnitude() {
        PlayerSnapshot s = new PlayerSnapshot();
        s.limbSwingAmount = -0.5f;
        assertEquals("idle", MainStates.select(s, s.limbSwingAmount, false));
        assertEquals("walk", MainStates.select(s, s.limbSwingAmount, true));
        s.limbSwingAmount = 0.04f;
        assertEquals("idle", MainStates.select(s, s.limbSwingAmount, false));
    }

    @Test
    void featuresMissingOn1710NeverFire() {
        PlayerSnapshot s = new PlayerSnapshot();
        s.inWater = true;
        // no swim pose on 1.7.10: in water on the ground is just idle
        assertEquals("idle", main(s));
        s.onGround = false;
        assertEquals("swim_stand", main(s));
    }

    @Test
    void onlyDeathAndAttackedPlayOnce() {
        for (String state : MainStates.NAMES) {
            LoopType expected = state.equals("death") || state.equals("attacked") ? LoopType.PLAY_ONCE : LoopType.LOOP;
            assertEquals(expected, MainStates.loop(state), state);
        }
    }

    private static final List<String> NAMES = Arrays.asList(
        "hold_mainhand$minecraft:diamond_sword",
        "hold_mainhand$stick",
        "hold_mainhand#forge:swords",
        "hold_mainhand#ingotIron",
        "hold_mainhand:sword",
        "hold_mainhand:eat",
        "hold_mainhand:none",
        "hold_offhand:shield",
        "swing:sword",
        "swing$minecraft:mace",
        "use_mainhand:bow",
        "use_mainhand:drink",
        "head$minecraft:diamond_helmet",
        "chest#forge:armors",
        "feet:default",
        "vehicle$minecraft:horse",
        "vehicle#forge:mounts",
        "passenger$touhou_little_maid:maid",
        "idle");

    @Test
    void itemNamesPreferIdThenTagThenClassThenUseAction() {
        ItemConditions c = new ItemConditions(NAMES);
        ItemInfo diamondSword = new ItemInfo(
            "minecraft:diamond_sword",
            new HashSet<>(Arrays.asList("forge:swords")),
            "sword",
            ItemInfo.UseAction.BLOCK,
            72000,
            1);
        assertEquals("hold_mainhand$minecraft:diamond_sword", c.holdMainhand.test(diamondSword));
        ItemInfo ironSword = new ItemInfo(
            "minecraft:iron_sword",
            new HashSet<>(Arrays.asList("forge:swords")),
            "sword",
            ItemInfo.UseAction.BLOCK,
            72000,
            2);
        assertEquals("hold_mainhand#forge:swords", c.holdMainhand.test(ironSword));
        ItemInfo woodSword = ItemInfo.of("minecraft:wooden_sword", "sword", ItemInfo.UseAction.BLOCK);
        assertEquals("hold_mainhand:sword", c.holdMainhand.test(woodSword));
        ItemInfo apple = ItemInfo.of("minecraft:apple", "", ItemInfo.UseAction.EAT);
        assertEquals("hold_mainhand:eat", c.holdMainhand.test(apple));
        ItemInfo dirt = ItemInfo.of("minecraft:dirt", "", ItemInfo.UseAction.NONE);
        assertEquals("", c.holdMainhand.test(dirt));
        assertEquals("hold_mainhand:empty", c.holdMainhand.test(ItemInfo.EMPTY));
        assertEquals("hold_offhand:empty", c.holdOffhand.test(ItemInfo.EMPTY));
        // swing/use have no empty name
        assertEquals("", c.swingMainhand.test(ItemInfo.EMPTY));
        assertEquals("swing:sword", c.swingMainhand.test(woodSword));
        assertEquals(
            "use_mainhand:drink",
            c.useMainhand.test(ItemInfo.of("minecraft:potion", "", ItemInfo.UseAction.DRINK)));
        assertEquals(
            "use_mainhand:bow",
            c.useMainhand.test(ItemInfo.of("minecraft:bow", "bow", ItemInfo.UseAction.BOW)));
    }

    @Test
    void idsAreNormalisedAsResourceLocations() {
        ItemConditions c = new ItemConditions(NAMES);
        // "hold_mainhand$stick" matches minecraft:stick and answers with the full id, as upstream
        assertEquals("hold_mainhand$minecraft:stick", c.holdMainhand.test(ItemInfo.of("minecraft:stick", "", null)));
        assertEquals("hold_mainhand$minecraft:stick", c.holdMainhand.test(ItemInfo.of("stick", "", null)));
        // OreDictionary names are kept as written
        ItemInfo ingot = new ItemInfo("minecraft:iron_ingot", Collections.singleton("ingotIron"), "", null, 0, 3);
        assertEquals("hold_mainhand#ingotIron", c.holdMainhand.test(ingot));
        assertEquals("minecraft:stick", ItemConditions.normalizeId("stick", false));
        assertEquals("modid:thing", ItemConditions.normalizeId("modid:thing", false));
        assertEquals(null, ItemConditions.normalizeId("Bad Name", false));
        assertEquals("Bad Name", ItemConditions.normalizeId("Bad Name", true));
    }

    @Test
    void armorAndEntityConditions() {
        ItemConditions c = new ItemConditions(NAMES);
        ItemInfo helmet = ItemInfo.of("minecraft:diamond_helmet", "", null);
        assertEquals("head$minecraft:diamond_helmet", c.armor.test(ItemConditions.Slot.HEAD, helmet));
        assertEquals("", c.armor.test(ItemConditions.Slot.CHEST, helmet));
        ItemInfo plate = new ItemInfo(
            "minecraft:iron_chestplate",
            Collections.singleton("forge:armors"),
            "",
            null,
            0,
            4);
        assertEquals("chest#forge:armors", c.armor.test(ItemConditions.Slot.CHEST, plate));
        assertTrue(c.armor.hasTest(ItemConditions.Slot.HEAD));
        assertTrue(c.armor.hasTest(ItemConditions.Slot.CHEST));
        assertFalse(c.armor.hasTest(ItemConditions.Slot.LEGS));
        assertEquals("vehicle$minecraft:horse", c.vehicle.test("minecraft:horse", null));
        assertEquals("vehicle#forge:mounts", c.vehicle.test("mod:camel", Collections.singleton("forge:mounts")));
        assertEquals("", c.vehicle.test("minecraft:pig", null));
        assertEquals("", c.vehicle.test(null, null));
        assertEquals("passenger$touhou_little_maid:maid", c.passenger.test("touhou_little_maid:maid", null));
    }

    @Test
    void originVersionsOrderLikeTheReleases() {
        assertEquals(OriginVersion.CURRENT, OriginVersion.fromModVersion(null));
        assertEquals(OriginVersion.CURRENT, OriginVersion.fromModVersion(""));
        assertEquals(19, OriginVersion.fromModVersion("2.4.2-snapshot-32"));
        assertEquals(18, OriginVersion.fromModVersion("2.4.2-snapshot-30"));
        assertEquals(4, OriginVersion.fromModVersion("2.2.1"));
        assertEquals(3, OriginVersion.fromModVersion("2.2.1-snapshot-9"));
        assertEquals(OriginVersion.CURRENT, OriginVersion.fromModVersion("9.9.9"));
        assertEquals(OriginVersion.LEGACY, OriginVersion.fromModVersion("0.0.1-alpha"));
        assertEquals(26, OriginVersion.fromModVersion("2.5.0+mc1.20.1"));
        assertTrue(OriginVersion.usesFileHandLoop(19, false));
        assertFalse(OriginVersion.usesFileHandLoop(18, false));
        assertTrue(OriginVersion.usesFileHandLoop(0, true));
    }
}
