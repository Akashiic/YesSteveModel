/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/gui/AnimationRouletteScreen.java (the page stack, paging and what a click on an
 * entry does) and src/main/java/com/elfmcys/ysm/client/input/ExtraAnimationKey.java (what an extra key does), Apache
 * License 2.0.
 * Changes: the state lives in an object per model instead of static fields; no Minecraft classes; the config forms of
 * extra_animation_buttons are only recognised, not edited.
 */
package com.akashic.futura.emote;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.akashic.futura.format.pojo.manifest.settings.ExtraAnimationButton;
import com.akashic.futura.format.pojo.manifest.settings.ExtraAnimationClassify;
import com.akashic.futura.format.pojo.manifest.settings.ModelProperties;

/**
 * What the extra-animation roulette shows and does, for one model: the {@code extra_animation} entries (key = the
 * animation to play, value = the label), sub-pages from {@code extra_animation_classify} (an entry whose key is
 * {@code #id}), the {@code #return} entry, eight entries per page, and the path of open sub-pages (at most
 * {@link #MAX_DEPTH} deep). A value {@code #button} names an {@code extra_animation_buttons} entry: its name is the
 * label
 * and it has config forms.
 * <p>
 * Keep one per model: the open page and path are remembered between openings, as upstream does.
 */
public final class RouletteMenu {

    public static final int PER_PAGE = 8;
    /** Deepest path: upstream refuses to open a sub-page when the path already has more than 5 levels. */
    public static final int MAX_DEPTH = 5;
    public static final String SPEC_PREFIX = "#";
    public static final String SPEC_RETURN = "#return";

    /** One roulette slot. */
    public static final class Entry {

        /** The animation name, {@code #id} of a sub-page, or {@code #return}. */
        public final String key;
        /** The raw value from the model ({@code #button} for config buttons). */
        public final String value;
        /** What to draw: the value, the config button's name, or the index when blank. */
        public final String label;
        /** Index in the level's entries. */
        public final int index;
        /** The {@code extra_animation_buttons} entry of a {@code #button} value, or null. */
        public final ExtraAnimationButton config;

        Entry(String key, String value, int index, ExtraAnimationButton config) {
            this.key = key;
            this.value = value;
            this.index = index;
            this.config = config;
            String shown = config != null ? config.name : value;
            this.label = shown == null || shown.trim()
                .isEmpty() ? String.valueOf(index) : shown;
        }

        public boolean isClassify() {
            return key.startsWith(SPEC_PREFIX) && !SPEC_RETURN.equals(key);
        }

        public boolean isReturn() {
            return SPEC_RETURN.equals(key);
        }

        public boolean hasConfig() {
            return config != null;
        }
    }

    /** The result of a click or a key. */
    public static final class Action {

        public enum Type {
            /** nothing to do */
            NONE,
            /** play {@link #name} */
            PLAY,
            /** stop the extra animation ({@code #return} on an extra key) */
            STOP,
            /** the page changed: redraw */
            REFRESH,
            /** close the roulette */
            CLOSE,
            /** open the config forms of button {@link #name} */
            CONFIG,
            /** the path is too deep to open another sub-page */
            TOO_DEEP
        }

        public final Type type;
        public final String name;

        private Action(Type type, String name) {
            this.type = type;
            this.name = name;
        }

        static Action of(Type type) {
            return new Action(type, null);
        }

        static Action of(Type type, String name) {
            return new Action(type, name);
        }

        @Override
        public String toString() {
            return name == null ? type.name() : type + " " + name;
        }
    }

    private static final class Level {

        final String id;
        int page;

        Level(String id) {
            this.id = id;
        }
    }

    private final List<Entry> root;
    private final Map<String, List<Entry>> classify = new HashMap<>();
    private final Deque<Level> path = new ArrayDeque<>();

    public RouletteMenu(ModelProperties properties) {
        Map<String, ExtraAnimationButton> buttons = new HashMap<>();
        if (properties.extraAnimationButtons != null) {
            for (ExtraAnimationButton button : properties.extraAnimationButtons) {
                if (button != null && button.id != null) {
                    buttons.put(button.id, button);
                }
            }
        }
        this.root = entries(properties.extraAnimation, buttons);
        if (properties.extraAnimationClassify != null) {
            for (ExtraAnimationClassify c : properties.extraAnimationClassify) {
                if (c != null && c.id != null) {
                    classify.put(c.id, entries(c.extraAnimation, buttons));
                }
            }
        }
        path.add(new Level(""));
    }

    private static List<Entry> entries(LinkedHashMap<String, String> map, Map<String, ExtraAnimationButton> buttons) {
        List<Entry> list = new ArrayList<>();
        if (map != null) {
            for (Map.Entry<String, String> e : map.entrySet()) {
                String value = e.getValue() == null ? "" : e.getValue();
                ExtraAnimationButton config = null;
                if (value.startsWith(SPEC_PREFIX)) {
                    config = buttons.get(value.substring(SPEC_PREFIX.length()));
                }
                list.add(new Entry(e.getKey(), value, list.size(), config));
            }
        }
        return Collections.unmodifiableList(list);
    }

    /** true if the model has any extra animation entry (upstream opens the roulette only then). */
    public boolean isEmpty() {
        return root.isEmpty();
    }

    /** The root entries (what the extra keys 0-7 address). */
    public List<Entry> rootEntries() {
        return root;
    }

    /** The entries of the open level (root or sub-page). */
    public List<Entry> entries() {
        List<Entry> e = classify.get(path.peekLast().id);
        return e != null ? e : root;
    }

    /** The open page's entries (at most {@link #PER_PAGE}). */
    public List<Entry> pageEntries() {
        List<Entry> all = entries();
        int from = page() * PER_PAGE;
        if (from >= all.size()) {
            return Collections.emptyList();
        }
        return all.subList(from, Math.min(all.size(), from + PER_PAGE));
    }

    /** The open page, 0-based (back to 0 if the level no longer has it, as upstream's {@code init}). */
    public int page() {
        Level level = path.peekLast();
        if (entries().size() < level.page * PER_PAGE + 1) {
            level.page = 0;
        }
        return level.page;
    }

    public int pageCount() {
        return (entries().size() - 1) / PER_PAGE + 1;
    }

    public void pageUp() {
        Level level = path.peekLast();
        level.page = Math.max(0, level.page - 1);
    }

    public void pageDown() {
        Level level = path.peekLast();
        if (entries().size() > (level.page + 1) * PER_PAGE) {
            level.page++;
        }
    }

    /** The open path, e.g. {@code [, dance]}: the root is the empty id. */
    public List<String> path() {
        List<String> ids = new ArrayList<>();
        for (Level level : path) {
            ids.add(level.id);
        }
        return ids;
    }

    /**
     * The label of the {@code #id} entry that opens a sub-page (the first one found, root first), or the id itself
     * when no entry names it with a label.
     */
    public String classifyLabel(String id) {
        String key = SPEC_PREFIX + id;
        String found = label(root, key);
        if (found == null) {
            for (List<Entry> level : classify.values()) {
                found = label(level, key);
                if (found != null) {
                    break;
                }
            }
        }
        return found != null ? found : id;
    }

    private static String label(List<Entry> level, String key) {
        for (Entry e : level) {
            if (e.key.equals(key) && e.value != null
                && !e.value.trim()
                    .isEmpty()) {
                return e.label;
            }
        }
        return null;
    }

    /** true on the first page of the root: only there the extra keys are shown (they address root entries). */
    public boolean showsKeys() {
        return path.size() == 1 && page() == 0;
    }

    /**
     * A click on slot {@code slot} (0-7) of the open page: plays the animation, opens a sub-page, or goes back for
     * {@code #return}.
     */
    public Action click(int slot) {
        List<Entry> page = pageEntries();
        if (slot < 0 || slot >= page.size()) {
            return Action.of(Action.Type.NONE);
        }
        Entry entry = page.get(slot);
        if (entry.isReturn()) {
            return back();
        }
        if (entry.key.startsWith(SPEC_PREFIX)) {
            return open(entry.key.substring(SPEC_PREFIX.length()));
        }
        return Action.of(Action.Type.PLAY, entry.key);
    }

    /** A click on the inner config part of slot {@code slot}: only entries with a config button react. */
    public Action clickConfig(int slot) {
        List<Entry> page = pageEntries();
        if (slot < 0 || slot >= page.size()
            || !page.get(slot)
                .hasConfig()) {
            return Action.of(Action.Type.NONE);
        }
        return Action.of(Action.Type.CONFIG, page.get(slot).config.id);
    }

    private Action open(String id) {
        if (path.size() > MAX_DEPTH) {
            return Action.of(Action.Type.TOO_DEEP);
        }
        if (!classify.containsKey(id)) {
            return Action.of(Action.Type.NONE);
        }
        path.addLast(new Level(id));
        return Action.of(Action.Type.REFRESH);
    }

    /** The return button: one level up, or close at the root. */
    public Action back() {
        if (path.size() > 1) {
            path.removeLast();
            return Action.of(Action.Type.REFRESH);
        }
        return Action.of(Action.Type.CLOSE);
    }

    /**
     * Extra key {@code index} (0-7) pressed in game ({@code ExtraAnimationKey}): addresses the root entries.
     * {@code #id}
     * of a sub-page opens the roulette at that sub-page ({@link Action.Type#REFRESH}: show the roulette),
     * {@code #return} stops the extra animation, anything else plays.
     */
    public Action key(int index) {
        if (index < 0 || index >= root.size()) {
            return Action.of(Action.Type.NONE);
        }
        String key = root.get(index).key;
        if (SPEC_RETURN.equals(key)) {
            return Action.of(Action.Type.STOP);
        }
        if (key.startsWith(SPEC_PREFIX) && classify.containsKey(key.substring(SPEC_PREFIX.length()))) {
            // upstream addRootClassify: the path becomes root > id
            path.clear();
            path.add(new Level(""));
            path.add(new Level(key.substring(SPEC_PREFIX.length())));
            return Action.of(Action.Type.REFRESH);
        }
        return Action.of(Action.Type.PLAY, key);
    }

    /** Back to the first page of the root. */
    public void reset() {
        path.clear();
        path.add(new Level(""));
    }
}
