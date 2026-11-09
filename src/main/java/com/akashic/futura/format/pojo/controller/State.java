/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/controller/State.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.controller;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.annotations.SerializedName;

public class State {

    public List<AnimationEntry> animations = new ArrayList<>();
    public List<Transition> transitions = new ArrayList<>();

    @SerializedName("sound_effects")
    public List<SoundEffect> soundEffects = new ArrayList<>();

    @SerializedName("on_entry")
    public List<String> onEntry = new ArrayList<>();

    @SerializedName("on_exit")
    public List<String> onExit = new ArrayList<>();

    @SerializedName("blend_transition")
    public BlendTransition blendTransition = new BlendTransition();

    @SerializedName("blend_via_shortest_path")
    public boolean blendViaShortestPath = false;
}
