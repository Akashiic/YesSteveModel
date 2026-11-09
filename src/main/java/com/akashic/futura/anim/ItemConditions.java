/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/animation/condition/ (ConditionManager, ConditionalHold, ConditionalSwing,
 * ConditionalUse, ConditionArmor, ConditionalVehicle, ConditionalPassenger, InnerClassify), Apache License 2.0.
 * Changes: items and entities arrive as plain strings (ids, tags, class); tag names that are not valid resource
 * locations (OreDictionary names such as "ingotIron") are kept as written instead of being dropped; names are scanned
 * in sorted order so the tag order is deterministic.
 */
package com.akashic.futura.anim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Picks item- and entity-specific animation names from the names a model (and the default model) defines:
 * {@code hold_mainhand$<id>}, {@code hold_mainhand#<tag>}, {@code hold_mainhand:<class or use action>}, the same for
 * {@code hold_offhand}, {@code swing}, {@code swing_offhand}, {@code use_mainhand}, {@code use_offhand};
 * {@code <slot>$<id>} / {@code <slot>#<tag>} for armour; {@code vehicle$<entity>}, {@code passenger$<entity>} and
 * their {@code #tag} forms. Precedence: id, then tag, then class, then use action.
 */
public final class ItemConditions {

    /** Armour and hand slots as upstream names them ({@code EquipmentSlot.getName}). */
    public enum Slot {

        MAINHAND("mainhand"),
        OFFHAND("offhand"),
        FEET("feet"),
        LEGS("legs"),
        CHEST("chest"),
        HEAD("head");

        public final String id;

        Slot(String id) {
            this.id = id;
        }

        public boolean isArmor() {
            return this != MAINHAND && this != OFFHAND;
        }

        /** Case-insensitive, as the {@code ctrl.hold/swing/use/armor} functions read it. */
        public static Slot byId(String id) {
            return id == null ? null : byIdExact(id.toLowerCase(java.util.Locale.ROOT));
        }

        /** Exact name, as animation names are matched. */
        public static Slot byIdExact(String id) {
            for (Slot slot : values()) {
                if (slot.id.equals(id)) {
                    return slot;
                }
            }
            return null;
        }
    }

    /** One hand condition (upstream ConditionalHold / ConditionalSwing / ConditionalUse). */
    public static final class Hand {

        private final String idPre;
        private final String tagPre;
        private final String extraPre;
        private final String emptyName;
        private final Set<String> ids = new HashSet<>();
        private final List<String> tags = new ArrayList<>();
        private final Set<ItemInfo.UseAction> extra = EnumSet.noneOf(ItemInfo.UseAction.class);
        private final Set<String> inner = new HashSet<>();

        Hand(String base, String emptyName) {
            this.idPre = base + "$";
            this.tagPre = base + "#";
            this.extraPre = base + ":";
            this.emptyName = emptyName;
        }

        void addTest(String name) {
            int preSize = idPre.length();
            if (name.length() <= preSize) {
                return;
            }
            String rest = name.substring(preSize);
            if (name.startsWith(idPre)) {
                String id = normalizeId(rest, false);
                if (id != null) {
                    ids.add(id);
                }
            }
            if (name.startsWith(tagPre)) {
                String tag = normalizeId(rest, true);
                if (tag != null && !tags.contains(tag)) {
                    tags.add(tag);
                }
            }
            if (name.startsWith(extraPre)) {
                if (rest.equals("none")) {
                    return;
                }
                ItemInfo.UseAction action = ItemInfo.UseAction.byId(rest);
                if (action != null) {
                    extra.add(action);
                }
                inner.add(name);
            }
        }

        private ItemInfo lastItem;
        private String lastResult;

        /** @return the animation name for this item, or "" (remembers the last item: ItemInfo is immutable) */
        public String test(ItemInfo item) {
            if (item == null || item.isEmpty()) {
                return emptyName;
            }
            if (item == lastItem) {
                return lastResult;
            }
            String result = match(item);
            lastItem = item;
            lastResult = result;
            return result;
        }

        private String match(ItemInfo item) {
            if (!ids.isEmpty()) {
                String id = item.normalizedId;
                if (id != null && ids.contains(id)) {
                    return idPre + id;
                }
            }
            for (int i = 0; i < tags.size(); i++) {
                if (item.tags.contains(tags.get(i))) {
                    return tagPre + tags.get(i);
                }
            }
            if (extra.isEmpty() && inner.isEmpty()) {
                return "";
            }
            if (!item.classify.isEmpty()) {
                String innerName = extraPre + item.classify;
                if (inner.contains(innerName)) {
                    return innerName;
                }
            }
            if (extra.contains(item.useAction)) {
                return extraPre + item.useAction.id();
            }
            return "";
        }
    }

    /** Vehicle or passenger condition (upstream ConditionalVehicle / ConditionalPassenger). */
    public static final class Entity {

        private final String idPre;
        private final String tagPre;
        private final Set<String> ids = new HashSet<>();
        private final List<String> tags = new ArrayList<>();

        Entity(String base) {
            this.idPre = base + "$";
            this.tagPre = base + "#";
        }

        void addTest(String name) {
            int preSize = idPre.length();
            if (name.length() <= preSize) {
                return;
            }
            String rest = name.substring(preSize);
            if (name.startsWith(idPre)) {
                String id = normalizeId(rest, false);
                if (id != null) {
                    ids.add(id);
                }
            }
            if (name.startsWith(tagPre)) {
                String tag = normalizeId(rest, true);
                if (tag != null && !tags.contains(tag)) {
                    tags.add(tag);
                }
            }
        }

        /**
         * @param entityId   e.g. {@code minecraft:pig}, or null for none
         * @param entityTags normalised tags of the entity type, may be null
         * @return the animation name, or ""
         */
        public String test(String entityId, Collection<String> entityTags) {
            if (entityId == null) {
                return "";
            }
            if (!ids.isEmpty()) {
                String id = normalizeId(entityId, false);
                if (id != null && ids.contains(id)) {
                    return idPre + id;
                }
            }
            if (entityTags != null && !tags.isEmpty()) {
                for (int i = 0; i < tags.size(); i++) {
                    String tag = tags.get(i);
                    for (String t : entityTags) {
                        if (tag.equals(normalizeId(t, true))) {
                            return tagPre + tag;
                        }
                    }
                }
            }
            return "";
        }
    }

    /** Armour condition (upstream ConditionArmor): {@code <slot>$<id>} and {@code <slot>#<tag>}. */
    public static final class Armor {

        private final List<Set<String>> ids = new ArrayList<>();
        private final List<List<String>> tags = new ArrayList<>();

        Armor() {
            for (int i = 0; i < Slot.values().length; i++) {
                ids.add(null);
                tags.add(null);
            }
        }

        void addTest(String name) {
            int dollar = name.indexOf('$');
            if (dollar > 0) {
                Slot slot = Slot.byIdExact(name.substring(0, dollar));
                if (slot != null) {
                    String id = normalizeId(name.substring(dollar + 1), false);
                    if (id != null) {
                        if (ids.get(slot.ordinal()) == null) {
                            ids.set(slot.ordinal(), new HashSet<String>());
                        }
                        ids.get(slot.ordinal())
                            .add(id);
                    }
                }
            }
            int hash = name.indexOf('#');
            if (hash > 0) {
                Slot slot = Slot.byIdExact(name.substring(0, hash));
                if (slot != null) {
                    String tag = normalizeId(name.substring(hash + 1), true);
                    if (tag != null) {
                        if (tags.get(slot.ordinal()) == null) {
                            tags.set(slot.ordinal(), new ArrayList<String>());
                        }
                        if (!tags.get(slot.ordinal())
                            .contains(tag)) {
                            tags.get(slot.ordinal())
                                .add(tag);
                        }
                    }
                }
            }
        }

        public boolean hasTest(Slot slot) {
            return ids.get(slot.ordinal()) != null || tags.get(slot.ordinal()) != null;
        }

        /** @return {@code <slot>$<id>} or {@code <slot>#<tag>}, or "" */
        public String test(Slot slot, ItemInfo item) {
            if (item == null || item.isEmpty()) {
                return "";
            }
            Set<String> idSet = ids.get(slot.ordinal());
            if (idSet != null && !idSet.isEmpty()) {
                String id = item.normalizedId;
                if (id != null && idSet.contains(id)) {
                    return slot.id + "$" + id;
                }
            }
            List<String> tagList = tags.get(slot.ordinal());
            if (tagList != null) {
                for (String tag : tagList) {
                    if (item.tags.contains(tag)) {
                        return slot.id + "#" + tag;
                    }
                }
            }
            return "";
        }
    }

    public final Hand holdMainhand = new Hand("hold_mainhand", "hold_mainhand:empty");
    public final Hand holdOffhand = new Hand("hold_offhand", "hold_offhand:empty");
    public final Hand swingMainhand = new Hand("swing", "");
    public final Hand swingOffhand = new Hand("swing_offhand", "");
    public final Hand useMainhand = new Hand("use_mainhand", "");
    public final Hand useOffhand = new Hand("use_offhand", "");
    public final Armor armor = new Armor();
    public final Entity vehicle = new Entity("vehicle");
    public final Entity passenger = new Entity("passenger");

    /** Builds the conditions from every animation name (own and fallback), scanned in sorted order. */
    public ItemConditions(Collection<String> animationNames) {
        for (String name : new TreeSet<>(animationNames)) {
            holdMainhand.addTest(name);
            holdOffhand.addTest(name);
            swingMainhand.addTest(name);
            swingOffhand.addTest(name);
            useMainhand.addTest(name);
            useOffhand.addTest(name);
            armor.addTest(name);
            vehicle.addTest(name);
            passenger.addTest(name);
        }
    }

    /**
     * Normalises a resource location as Minecraft parses it: {@code path} becomes {@code minecraft:path}.
     *
     * @param lenient keep a string that is not a valid resource location as written (for OreDictionary names)
     * @return the normalised id, or null if invalid and not lenient
     */
    public static String normalizeId(String s, boolean lenient) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        int colon = s.indexOf(':');
        String namespace = colon >= 1 ? s.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? s.substring(colon + 1) : s;
        if (validNamespace(namespace) && validPath(path)) {
            return namespace + ":" + path;
        }
        return lenient ? s : null;
    }

    private static boolean validNamespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c == '_' || c == '-' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    private static boolean validPath(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c == '_' || c == '-' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '/' || c == '.')) {
                return false;
            }
        }
        return true;
    }
}
