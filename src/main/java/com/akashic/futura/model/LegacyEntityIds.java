package com.akashic.futura.model;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The entity id a model's {@code match} list names (1.13+ ids such as {@code minecraft:boat}) for a 1.7.10 entity,
 * from its {@code EntityList} name ({@code Boat}, {@code MinecartRideable}, {@code EntityHorse}...). No Minecraft
 * classes.
 */
public final class LegacyEntityIds {

    /** {@code EntityHorse.getHorseType()}: 0 horse, 1 donkey, 2 mule, 3 zombie, 4 skeleton. */
    private static final String[] HORSES = { "minecraft:horse", "minecraft:donkey", "minecraft:mule",
        "minecraft:zombie_horse", "minecraft:skeleton_horse" };

    private static final Map<String, String> VANILLA = new HashMap<>();

    static {
        String[][] table = { { "Arrow", "arrow" }, { "Snowball", "snowball" }, { "Fireball", "fireball" },
            { "SmallFireball", "small_fireball" }, { "ThrownEnderpearl", "ender_pearl" },
            { "EyeOfEnderSignal", "eye_of_ender" }, { "ThrownPotion", "potion" },
            { "ThrownExpBottle", "experience_bottle" }, { "WitherSkull", "wither_skull" },
            { "FireworksRocketEntity", "firework_rocket" }, { "PrimedTnt", "tnt" }, { "FallingSand", "falling_block" },
            { "Item", "item" }, { "XPOrb", "experience_orb" }, { "LeashKnot", "leash_knot" },
            { "Painting", "painting" }, { "ItemFrame", "item_frame" }, { "EnderCrystal", "end_crystal" },
            { "Boat", "boat" }, { "MinecartRideable", "minecart" }, { "MinecartChest", "chest_minecart" },
            { "MinecartFurnace", "furnace_minecart" }, { "MinecartTNT", "tnt_minecart" },
            { "MinecartHopper", "hopper_minecart" }, { "MinecartSpawner", "spawner_minecart" },
            { "MinecartCommandBlock", "command_block_minecart" }, { "Creeper", "creeper" }, { "Skeleton", "skeleton" },
            { "Spider", "spider" }, { "Giant", "giant" }, { "Zombie", "zombie" }, { "Slime", "slime" },
            { "Ghast", "ghast" }, { "PigZombie", "zombified_piglin" }, { "Enderman", "enderman" },
            { "CaveSpider", "cave_spider" }, { "Silverfish", "silverfish" }, { "Blaze", "blaze" },
            { "LavaSlime", "magma_cube" }, { "EnderDragon", "ender_dragon" }, { "WitherBoss", "wither" },
            { "Bat", "bat" }, { "Witch", "witch" }, { "Pig", "pig" }, { "Sheep", "sheep" }, { "Cow", "cow" },
            { "Chicken", "chicken" }, { "Squid", "squid" }, { "Wolf", "wolf" }, { "MushroomCow", "mooshroom" },
            { "SnowMan", "snow_golem" }, { "Ozelot", "ocelot" }, { "VillagerGolem", "iron_golem" },
            { "Villager", "villager" } };
        for (String[] row : table) {
            VANILLA.put(row[0], "minecraft:" + row[1]);
        }
    }

    /** Not in {@code EntityList} on 1.7.10: the client names them by class. */
    public static final String FISHING_BOBBER = "minecraft:fishing_bobber";

    public static final String EGG = "minecraft:egg";

    private LegacyEntityIds() {}

    /**
     * @param name      {@code EntityList.getEntityString(entity)}: a vanilla name, or {@code modid.Name} for a mod's
     *                  entity
     * @param horseType {@code EntityHorse.getHorseType()} when the name is {@code EntityHorse}
     * @return the id ({@code minecraft:boat}; a mod's {@code Mod.EntityName} as {@code mod:entity_name}), or null
     *         without a name
     */
    public static String of(String name, int horseType) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        if ("EntityHorse".equals(name)) {
            return HORSES[horseType >= 0 && horseType < HORSES.length ? horseType : 0];
        }
        String vanilla = VANILLA.get(name);
        if (vanilla != null) {
            return vanilla;
        }
        // as PlayerInputsAdapter names vehicles for the player's vehicle$ conditions: Mod.EntityName is mod:entity_name
        int dot = name.indexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(0, dot)
                .toLowerCase(Locale.ROOT) + ":"
                + snake(name.substring(dot + 1));
        }
        return "minecraft:" + snake(name);
    }

    private static String snake(String name) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && !Character.isUpperCase(name.charAt(i - 1))) {
                b.append('_');
            }
            b.append(c == ' ' ? '_' : Character.toLowerCase(c));
        }
        return b.toString();
    }
}
