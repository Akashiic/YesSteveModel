/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/model/resource/client/render/ModelRenderTargetLoader.java (commonAssets: callable
 * names and event handlers from {@code <name>@<event>} files) and
 * src/main/java/com/elfmcys/ysm/expression/inventory/MolangEventCatalog.java (which events exist), Apache License 2.0.
 * Changes: compiled for the tree interpreter; the default model's functions are not merged (it has none).
 */
package com.akashic.futura.anim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.akashic.futura.molang.Molang;
import com.akashic.futura.molang.MolangExpression;

/**
 * A model's user functions ({@code functions/<name>@<event>.molang}): {@code fn.<name>(...)} calls and event
 * handlers. A file {@code name.molang} is only callable, {@code @event.molang} only a handler,
 * {@code name@event.molang}
 * both. Events, as upstream's catalog: {@code player_init}, {@code player_update}, {@code sync}, {@code defer} and
 * {@code <category>_ctrl_<name>} (the override of a coded controller, e.g. {@code player_ctrl_main}); other suffixes
 * are ignored. Immutable.
 */
public final class ModelFunctions {

    public static final String PLAYER_INIT = "player_init";
    public static final String PLAYER_UPDATE = "player_update";
    public static final String SYNC = "sync";
    public static final String DEFER = "defer";

    /** At most this many arguments reach {@code @sync} handlers (upstream {@code SYNC_MAX_ARGUMENTS}). */
    public static final int SYNC_MAX_ARGUMENTS = 16;
    /** At most this many reach {@code @defer} handlers, the delay included (upstream {@code DEFER_MAX_ARGUMENTS}). */
    public static final int DEFER_MAX_ARGUMENTS = 255;

    public static final ModelFunctions EMPTY = new ModelFunctions(
        Collections.<String, MolangExpression>emptyMap(),
        Collections.<String, List<MolangExpression>>emptyMap());

    private static final Pattern CODED_CONTROLLER = Pattern.compile("[a-z0-9_]+_ctrl_[a-z0-9_]+");

    /** Callable functions by lower-case name. */
    private final Map<String, MolangExpression> callable;
    /** Event handlers by lower-case event, in file order. */
    private final Map<String, List<MolangExpression>> events;

    private ModelFunctions(Map<String, MolangExpression> callable, Map<String, List<MolangExpression>> events) {
        this.callable = callable;
        this.events = events;
    }

    /** Compiles a model's functions ({@code LoadedModel.functions}) with {@link Molang#parse}. */
    public static ModelFunctions compile(Map<String, String> sources) {
        return compile(sources, Molang::parse);
    }

    /** @param parser compiles Molang (tests stub it) */
    public static ModelFunctions compile(Map<String, String> sources, Function<String, MolangExpression> parser) {
        if (sources == null || sources.isEmpty()) {
            return EMPTY;
        }
        Map<String, MolangExpression> callable = new HashMap<>();
        Map<String, List<MolangExpression>> events = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            String name = e.getKey();
            MolangExpression body = parser.apply(e.getValue() == null ? "" : e.getValue());
            int at = name.indexOf('@');
            if (at != 0) {
                String fn = at < 0 ? name : name.substring(0, at);
                callable.put(fn.toLowerCase(Locale.ROOT), body);
            }
            if (at >= 0 && at + 1 < name.length()) {
                String event = name.substring(at + 1)
                    .toLowerCase(Locale.ROOT);
                if (isEvent(event)) {
                    events.computeIfAbsent(event, k -> new ArrayList<>())
                        .add(body);
                }
            }
        }
        for (Map.Entry<String, List<MolangExpression>> e : events.entrySet()) {
            e.setValue(Collections.unmodifiableList(e.getValue()));
        }
        return new ModelFunctions(Collections.unmodifiableMap(callable), Collections.unmodifiableMap(events));
    }

    /** Upstream {@code MolangEventCatalog.classify}: the five declared routes. */
    public static boolean isEvent(String event) {
        return PLAYER_INIT.equals(event) || PLAYER_UPDATE.equals(event)
            || SYNC.equals(event)
            || DEFER.equals(event)
            || CODED_CONTROLLER.matcher(event)
                .matches();
    }

    /** The coded-controller override event of a controller: {@code player.main} is {@code player_ctrl_main}. */
    public static String overrideEvent(String controller) {
        return controller.replace(".", "_ctrl_");
    }

    /** @param name lower-case, as Molang hands it over */
    public MolangExpression function(String name) {
        return callable.get(name);
    }

    /** The handlers of an event, in file order (empty when none). */
    public List<MolangExpression> handlers(String event) {
        List<MolangExpression> list = events.get(event);
        return list == null ? Collections.<MolangExpression>emptyList() : list;
    }

    /** The first handler of an event, or null (a coded controller override uses only the first). */
    public MolangExpression first(String event) {
        List<MolangExpression> list = events.get(event);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /** Every event that has handlers. */
    public java.util.Set<String> events() {
        return events.keySet();
    }

    /** Only the handlers the first-person arms run (upstream {@code FP_ARM} keeps defer and coded overrides). */
    public ModelFunctions forFirstPerson() {
        if (events.isEmpty()) {
            return this;
        }
        Map<String, List<MolangExpression>> kept = new LinkedHashMap<>();
        for (Map.Entry<String, List<MolangExpression>> e : events.entrySet()) {
            String event = e.getKey();
            if (!PLAYER_INIT.equals(event) && !PLAYER_UPDATE.equals(event) && !SYNC.equals(event)) {
                kept.put(event, e.getValue());
            }
        }
        return new ModelFunctions(callable, Collections.unmodifiableMap(kept));
    }
}
