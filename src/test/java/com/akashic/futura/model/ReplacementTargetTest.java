package com.akashic.futura.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Projectile and vehicle replacements read from {@code ysm.json} ({@code files.projectiles/vehicles/arrow}). */
class ReplacementTargetTest {

    static final String GEO = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":"
        + "{\"identifier\":\"geometry.x\",\"texture_width\":16,\"texture_height\":16},\"bones\":["
        + "{\"name\":\"root\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]},"
        + "{\"name\":\"tip\",\"parent\":\"root\",\"pivot\":[0,2,0],\"cubes\":[{\"origin\":[0,2,0],\"size\":[1,1,1],"
        + "\"uv\":[0,0]}]}]}]}";

    static final String ANIMATION = "{\"format_version\":\"1.8.0\",\"animations\":{"
        + "\"air\":{\"loop\":true,\"bones\":{\"tip\":{\"rotation\":[0,90,0]}}},"
        + "\"ground\":{\"loop\":true,\"bones\":{\"tip\":{\"rotation\":[45,0,0]}}},"
        + "\"water\":{\"loop\":true,\"bones\":{\"tip\":{\"scale\":0}}},"
        + "\"parallel0\":{\"loop\":true,\"bones\":{\"root\":{\"rotation\":[0,0,10]}}}}}";

    static final String CONTROLLER = "{\"format_version\":\"1.19.0\",\"animation_controllers\":{"
        + "\"vehicle.main\":{\"initial_state\":\"default\",\"states\":{\"default\":{\"animations\":[\"air\"]}}}}}";

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** A model with a player part and the given {@code files} extras (JSON members after {@code player}). */
    static MemoryModelSource model(String filesExtra, Map<String, byte[]> extraFiles) throws IOException {
        String manifest = "{\"spec\":2,\"metadata\":{\"name\":\"t\"},\"properties\":{},\"files\":{\"player\":{"
            + "\"model\":{\"main\":\"models/main.json\",\"arm\":\"models/arm.json\"},"
            + "\"texture\":[\"textures/skin.png\"]}"
            + (filesExtra.isEmpty() ? "" : "," + filesExtra)
            + "}}";
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("ysm.json", utf8(manifest));
        files.put("models/main.json", utf8(GEO));
        files.put("models/arm.json", utf8(GEO));
        files.put("models/arrow.json", utf8(GEO));
        files.put("models/boat.json", utf8(GEO));
        files.put("animations/arrow.animation.json", utf8(ANIMATION));
        files.put("controller/boat.json", utf8(CONTROLLER));
        files.putAll(extraFiles);
        return new MemoryModelSource("t", files);
    }

    @Test
    void readsProjectilesVehiclesAndTheDeprecatedArrow() throws IOException {
        LoadedModel m = ModelLoader.load(
            model(
                "\"projectiles\":[{\"match\":[\"minecraft:arrow\",\"snowball\"],\"model\":\"models/arrow.json\","
                    + "\"animation\":\"animations/arrow.animation.json\",\"texture\":\"textures/arrow.png\"},"
                    + "{\"model\":\"models/arrow.json\",\"texture\":\"textures/none.png\"}],"
                    + "\"arrow\":{\"model\":\"models/arrow.json\",\"texture\":{\"uv\":\"textures/old.png\"}},"
                    + "\"vehicles\":{\"minecraft:boat\":{\"model\":\"models/boat.json\","
                    + "\"controller\":\"controller/boat.json\",\"texture\":\"textures/boat.png\"},"
                    + "\"minecraft:minecart\":{\"model\":\"models/boat.json\"}}",
                new LinkedHashMap<>()));
        assertTrue(m.replacementErrors.isEmpty(), m.replacementErrors.toString());
        // the entry without match is skipped; the arrow comes after the projectiles, index after the skipped one
        assertEquals(2, m.projectiles.size());
        ReplacementTarget arrow = m.projectiles.get(0);
        assertEquals("projectile/0000", arrow.id());
        assertEquals(Arrays.asList("minecraft:arrow", "snowball"), arrow.match);
        assertEquals(Arrays.asList("minecraft:arrow", "minecraft:snowball"), arrow.entityIds);
        assertEquals(2, arrow.geometry.bones.length);
        assertEquals(4, arrow.animation.animations.size());
        assertEquals("textures/arrow.png", arrow.texture);
        ReplacementTarget legacy = m.projectiles.get(1);
        assertEquals("projectile/0002", legacy.id());
        assertEquals(Arrays.asList(ReplacementTarget.ARROW), legacy.match);
        assertNull(legacy.animation);
        assertEquals("textures/old.png", legacy.texture);
        // the first usable target that matches wins
        assertSame(arrow, m.projectile("minecraft:arrow"));
        assertSame(arrow, m.projectile("minecraft:snowball"));
        assertNull(m.projectile("minecraft:egg"));

        assertEquals(2, m.vehicles.size());
        ReplacementTarget boat = m.vehicles.get(0);
        assertEquals("vehicle/0000", boat.id());
        assertEquals(Arrays.asList("minecraft:boat"), boat.match);
        assertTrue(boat.controllers.containsKey("vehicle.main"));
        assertSame(boat, m.vehicle("minecraft:boat"));
        // a target without texture is read but never used (the host draws)
        ReplacementTarget cart = m.vehicles.get(1);
        assertFalse(cart.usable());
        assertNull(m.vehicle("minecraft:minecart"));
        assertEquals(
            4,
            m.replacements()
                .size());
    }

    @Test
    void aBrokenTargetIsLeftOutAndTheModelStillLoads() throws IOException {
        LoadedModel m = ModelLoader.load(
            model(
                "\"projectiles\":[{\"match\":[\"arrow\"],\"model\":\"models/missing.json\",\"texture\":\"a.png\"},"
                    + "{\"match\":[\"arrow\"],\"model\":\"models/arrow.json\",\"texture\":\"b.png\","
                    + "\"animation\":\"animations/bad.json\"},"
                    + "{\"match\":[\"arrow\"],\"model\":\"models/arrow.json\",\"texture\":\"c.png\"}]",
                files("animations/bad.json", "{ not json")));
        assertEquals(2, m.replacementErrors.size(), m.replacementErrors.toString());
        assertEquals(1, m.projectiles.size());
        assertEquals(
            "projectile/0002",
            m.projectiles.get(0)
                .id());
        assertEquals("c.png", m.projectile("minecraft:arrow").texture);
        assertNotNull(m.main);
    }

    @Test
    void aModelWithoutReplacementsHasNone() throws IOException {
        LoadedModel m = ModelLoader.load(model("", new LinkedHashMap<>()));
        assertTrue(m.projectiles.isEmpty());
        assertTrue(m.vehicles.isEmpty());
        assertTrue(m.replacementErrors.isEmpty());
        assertNull(m.projectile("minecraft:arrow"));
    }

    @Test
    void entityIdsAsUpstreamParsesThem() {
        assertEquals("minecraft:arrow", ReplacementTarget.entityId("arrow"));
        assertEquals("minecraft:arrow", ReplacementTarget.entityId(" minecraft:arrow "));
        assertEquals("minecraft:arrow", ReplacementTarget.entityId(":arrow"));
        assertEquals("tacz:bullet", ReplacementTarget.entityId("tacz:bullet"));
        assertEquals("a:b/c.d-e", ReplacementTarget.entityId("a:b/c.d-e"));
        assertNull(ReplacementTarget.entityId("#minecraft:arrows"));
        assertNull(ReplacementTarget.entityId("Minecraft:Arrow"));
        assertNull(ReplacementTarget.entityId("a b"));
        assertNull(ReplacementTarget.entityId("minecraft:"));
        assertNull(ReplacementTarget.entityId(""));
        assertNull(ReplacementTarget.entityId(null));
    }

    @Test
    void theDomainIsTheSortedMatchList() {
        ReplacementTarget a = new ReplacementTarget(
            ReplacementTarget.Kind.PROJECTILE,
            0,
            Arrays.asList("minecraft:b", "minecraft:a"),
            null,
            null,
            null,
            "t.png");
        ReplacementTarget b = new ReplacementTarget(
            ReplacementTarget.Kind.PROJECTILE,
            3,
            Arrays.asList("minecraft:a", "minecraft:b"),
            null,
            null,
            null,
            null);
        ReplacementTarget c = new ReplacementTarget(
            ReplacementTarget.Kind.VEHICLE,
            0,
            Arrays.asList("minecraft:a", "minecraft:b"),
            null,
            null,
            null,
            null);
        assertEquals(a.domain(), b.domain());
        assertFalse(
            a.domain()
                .equals(c.domain()));
    }

    static Map<String, byte[]> files(String path, String content) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(path, utf8(content));
        return files;
    }

    // ---- built-in models ----

    static final Path BUILTIN = Paths.get("src/main/resources/assets/futura3d/builtin");

    @TestFactory
    Stream<DynamicTest> everyBuiltinReplacementLoads() throws IOException {
        List<Path> manifests;
        try (Stream<Path> walk = Files.walk(BUILTIN)) {
            manifests = walk.filter(
                p -> p.getFileName()
                    .toString()
                    .equals("ysm.json"))
                .sorted()
                .collect(Collectors.toList());
        }
        return manifests.stream()
            .map(p -> {
                String rel = BUILTIN.relativize(p.getParent())
                    .toString()
                    .replace('\\', '/');
                return DynamicTest.dynamicTest(rel, () -> {
                    LoadedModel m = ModelLoader.load(BuiltinIndex.source(rel));
                    assertTrue(m.replacementErrors.isEmpty(), m.replacementErrors.toString());
                    for (ReplacementTarget t : m.replacements()) {
                        assertTrue(t.usable(), t.toString());
                        assertTrue(t.geometry.bones.length > 0, t.toString());
                        assertFalse(t.entityIds.isEmpty(), t.toString());
                    }
                });
            });
    }

    @Test
    void builtinTargets() throws IOException {
        LoadedModel def = ModelLoader.load(BuiltinIndex.source("default"));
        assertEquals(4, def.projectiles.size());
        assertEquals(3, def.vehicles.size());
        assertNotNull(def.projectile("minecraft:arrow"));
        assertNotNull(def.projectile("minecraft:fishing_bobber"));
        assertNotNull(def.vehicle("minecraft:boat"));
        assertNotNull(def.vehicle("minecraft:minecart"));
        assertNull(def.vehicle("minecraft:horse"));
        assertEquals("textures/boat.png", def.vehicle("minecraft:boat").texture);

        // the deprecated files.arrow of the older models replaces the arrow
        LoadedModel newYear = ModelLoader.load(BuiltinIndex.source("wine_fox/02_new_year"));
        assertEquals(1, newYear.projectiles.size());
        assertNotNull(newYear.projectile("minecraft:arrow"));
        assertNotNull(newYear.projectile("minecraft:arrow").animation);

        // the object form: the key is the match
        LoadedModel maid = ModelLoader.load(BuiltinIndex.source("wine_fox/01_taisho_maid"));
        assertNotNull(maid.vehicle("minecraft:horse"));
        assertNotNull(maid.vehicle("minecraft:mule"));
        assertNotNull(maid.vehicle("minecraft:boat"));
        // same match list as the default model's arrow: it borrows the default arrow's animations
        assertEquals(
            def.projectile("minecraft:arrow")
                .domain(),
            maid.projectile("minecraft:arrow")
                .domain());
    }

    @Test
    void legacyEntityNamesAsModernIds() {
        assertEquals("minecraft:arrow", LegacyEntityIds.of("Arrow", 0));
        assertEquals("minecraft:boat", LegacyEntityIds.of("Boat", 0));
        assertEquals("minecraft:minecart", LegacyEntityIds.of("MinecartRideable", 0));
        assertEquals("minecraft:chest_minecart", LegacyEntityIds.of("MinecartChest", 0));
        assertEquals("minecraft:pig", LegacyEntityIds.of("Pig", 0));
        assertEquals("minecraft:horse", LegacyEntityIds.of("EntityHorse", 0));
        assertEquals("minecraft:donkey", LegacyEntityIds.of("EntityHorse", 1));
        assertEquals("minecraft:mule", LegacyEntityIds.of("EntityHorse", 2));
        assertEquals("minecraft:zombie_horse", LegacyEntityIds.of("EntityHorse", 3));
        assertEquals("minecraft:skeleton_horse", LegacyEntityIds.of("EntityHorse", 4));
        assertEquals("minecraft:horse", LegacyEntityIds.of("EntityHorse", 9));
        assertEquals("minecraft:ender_pearl", LegacyEntityIds.of("ThrownEnderpearl", 0));
        assertEquals("minecraft:zombified_piglin", LegacyEntityIds.of("PigZombie", 0));
        assertEquals("mymod:flying_carpet", LegacyEntityIds.of("MyMod.FlyingCarpet", 0));
        assertEquals("minecraft:some_thing", LegacyEntityIds.of("SomeThing", 0));
        assertNull(LegacyEntityIds.of(null, 0));
        assertNull(LegacyEntityIds.of("", 0));
    }
}
