package com.akashic.futura.anim;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the animation runtime needs to know about an item stack, without Minecraft classes. Immutable; build one when
 * the stack changes and reuse it.
 */
public final class ItemInfo {

    /** Use action of an item (1.20 {@code UseAnim}; 1.7.10 {@code EnumAction} covers NONE to BOW). */
    public enum UseAction {

        NONE,
        EAT,
        DRINK,
        BLOCK,
        BOW,
        SPEAR,
        CROSSBOW,
        SPYGLASS,
        TOOT_HORN,
        BRUSH;

        /** Lower-case name, as used in animation names ({@code use_mainhand:eat}). */
        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** @return the action for a lower-case name, or null */
        public static UseAction byId(String id) {
            for (UseAction action : values()) {
                if (action.id()
                    .equals(id)) {
                    return action;
                }
            }
            return null;
        }
    }

    public static final ItemInfo EMPTY = new ItemInfo("", Collections.<String>emptySet(), "", UseAction.NONE, 0, 0);

    /**
     * Registry id with namespace, e.g. {@code minecraft:diamond_sword}. On 1.7.10:
     * {@code GameData.getItemRegistry().getNameForObject(item)} (already {@code modid:name}). Empty for no item.
     */
    public final String id;
    /** {@link #id} as a resource location ({@code stick} becomes {@code minecraft:stick}), or null if invalid. */
    final String normalizedId;
    /**
     * Tags for {@code #tag} names, compared after the same normalisation as ids (a name without {@code :} gets
     * {@code minecraft:}). On 1.7.10: the item's OreDictionary names ({@code OreDictionary.getOreIDs}), and any
     * modern-style tags the client wants to emulate (e.g. {@code forge:tools/swords}).
     */
    public final Set<String> tags;
    /**
     * Built-in class for {@code :classify} names: {@code slashblade}, {@code sword}, {@code axe}, {@code pickaxe},
     * {@code shovel}, {@code hoe}, {@code shield}, {@code crossbow}, {@code bow}, {@code fishing_rod}, {@code spear}
     * (trident), {@code throwable_potion}, or empty. On 1.7.10 from the item class: {@code ItemSword}, {@code ItemAxe},
     * {@code ItemPickaxe}, {@code ItemSpade}, {@code ItemHoe}, {@code ItemBow}, {@code ItemFishingRod}, splash
     * {@code ItemPotion} ({@code ItemPotion.isSplash(meta)}); SlashBlade's {@code ItemSlashBlade} gives
     * {@code slashblade}. Checked in that order upstream (slashblade first).
     */
    public final String classify;
    /** {@code item.getItemUseAction(stack)} on 1.7.10. */
    public final UseAction useAction;
    /** {@code item.getMaxItemUseDuration(stack)} on 1.7.10, ticks. */
    public final int maxUseDuration;
    /**
     * Changes when the stack changes in a way that should restart the hold animation (upstream compares the whole
     * stack, or only the item if it is damaged): e.g. a hash of id, metadata, count and NBT, or of id alone for
     * damaged items.
     */
    public final int stackHash;

    public ItemInfo(String id, Set<String> tags, String classify, UseAction useAction, int maxUseDuration,
        int stackHash) {
        this.id = id == null ? "" : id;
        this.normalizedId = ItemConditions.normalizeId(this.id, false);
        Set<String> normalised = new LinkedHashSet<>();
        if (tags != null) {
            for (String tag : tags) {
                String n = ItemConditions.normalizeId(tag, true);
                if (n != null) {
                    normalised.add(n);
                }
            }
        }
        this.tags = Collections.unmodifiableSet(normalised);
        this.classify = classify == null ? "" : classify;
        this.useAction = useAction == null ? UseAction.NONE : useAction;
        this.maxUseDuration = maxUseDuration;
        this.stackHash = stackHash;
    }

    /** A simple item: id, class, use action. */
    public static ItemInfo of(String id, String classify, UseAction useAction) {
        return new ItemInfo(id, null, classify, useAction, 0, id == null ? 0 : id.hashCode());
    }

    public boolean isEmpty() {
        return id.isEmpty();
    }

    /** Same item for the hold controller's restart check. */
    public boolean sameStack(ItemInfo other) {
        return other != null && id.equals(other.id) && stackHash == other.stackHash;
    }
}
