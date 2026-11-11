#!/usr/bin/env python3
"""
Writes the default model's fishing set (src/main/resources/assets/futura3d/builtin/default/animations/
fishing.animation.json): the clips the player.fishing controller plays (anim/FishingStates). Every model falls back to
them, so they only touch the bones every YSM model has (UpBody, Head, the arms and forearms, the hand locators, the
legs and AllBody).

    python3 tools/fishing_animations.py            # writes the file
    python3 tools/fishing_animations.py --check    # fails if the file is not what this script writes

Conventions of the YSM bones (read from the default model's own clips): rotations in degrees, added to the bind pose.
Arms: x -90 holds the arm straight forward, -180 straight up; y and z bring the hands toward the middle. Forearms:
negative x bends the elbow. UpBody and Head: positive x leans forward / looks down, positive y turns the right
shoulder back. RightHandLocator turns the rod in the hand: positive x lowers the tip toward the water, negative raises
it. Positions in pixels; AllBody y up, z back.

The rod's elevation above the horizon (degrees, seen from the side with the arm straight ahead), measured in the
render harness: about 0.45 * -(RightArm.x + RightForeArm.x) - RightHandLocator.x + 55. The waiting pose
(-50, -22, 42.5) points it 45 degrees up toward the bobber; 90 is straight up, past 120 it hides behind the head.

Molang the clips read (not upstream, see FishingStates): ctrl.fishing_tension (0..1, the fight's tension, eased) and
ctrl.fishing_heave (0..1, the minigame key: sneak pulls the rod up and back, eased).

Made for 3D Futura Engine; CC0, like the rest of the default model.
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/assets/futura3d/builtin/default/animations/fishing.animation.json"

T = "ctrl.fishing_tension"
H = "ctrl.fishing_heave"


def k(value, lerp="catmullrom"):
    return {"post": value, "lerp_mode": lerp}


def track(*frames, lerp="catmullrom"):
    """(time, value) pairs; a value may be a dict with pre/post for a jump."""
    out = {}
    for time, value in frames:
        key = f"{time:.4f}".rstrip("0").rstrip(".")
        if "." not in key:
            key += ".0"
        if isinstance(value, dict):
            entry = dict(value)
            entry.setdefault("lerp_mode", lerp)
            out[key] = entry
        else:
            out[key] = k(value, lerp)
    return out


ZERO = [0, 0, 0]

# The waiting pose: both hands on the rod, held low in front, the tip toward the bobber (the old
# hold_mainhand:fishing, a little looser).
WAIT_POSE = {
    "RightArm": [-50, -14, -14],
    "RightForeArm": [-22, 0, 0],
    "LeftArm": [-52, 22, 12],
    "LeftForeArm": [-20, 0, 0],
    "RightHandLocator": [42.5, 0, 0],
}

# The fight's pose at no tension: leaning back, the rod up, both fists high. The locator leaves the rod a little short
# of upright: AkashicFishing's 3D rods lean back another 11 to 18 degrees in their own briga.
FIGHT_POSE = {
    "UpBody": [-8, 0, 0],
    "Head": [-4, 0, 0],
    "RightArm": [-88, -12, -14],
    "RightForeArm": [-30, 0, 0],
    "LeftArm": [-84, 22, 12],
    "LeftForeArm": [-30, 0, 0],
    "RightHandLocator": [48, 0, 0],
}


def wait():
    """Loop, 4 s. The torso and the head stay the main controller's (breathing, sneaking, walking); the rod's tip bobs
    lazily, the arms drift with it, and once in a while (life time, so never in step with the loop) the wrist turns
    as if checking the line."""
    w = WAIT_POSE
    return {
        "loop": True,
        "animation_length": 4,
        "bones": {
            "RightArm": {"rotation": [
                f"{w['RightArm'][0]} + math.sin(q.anim_time * 90 + 40) * 1.5",
                f"{w['RightArm'][1]} + math.sin(q.anim_time * 90) * 1",
                w["RightArm"][2]]},
            "RightForeArm": {"rotation": [f"{w['RightForeArm'][0]} + math.sin(q.anim_time * 180) * 1.5", 0, 0]},
            "LeftArm": {"rotation": [
                f"{w['LeftArm'][0]} + math.sin(q.anim_time * 90 + 60) * 1.5",
                f"{w['LeftArm'][1]} - math.sin(q.anim_time * 90) * 1",
                w["LeftArm"][2]]},
            "LeftForeArm": {"rotation": [f"{w['LeftForeArm'][0]} + math.sin(q.anim_time * 180 + 30) * 1.5", 0, 0]},
            "RightHandLocator": {"rotation": [
                f"{w['RightHandLocator'][0]} + math.sin(q.anim_time * 180) * 2.5 + math.sin(q.anim_time * 540) * 0.6"
                " + math.max(0, math.sin(q.life_time * 23) - 0.92) * 60",
                "math.sin(q.anim_time * 90) * 3",
                0]},
        },
    }


def cast():
    """Once, 0.8 s. A quick wind-up over the right shoulder (the whole cast is two-handed), the whip forward at 0.14 s,
    when the bobber leaves (the click and the bobber are a round trip apart; the old swing:fishing_rod threw at
    0.37 s, long after the bobber had gone), the follow-through pointing the rod at the water, then the waiting
    pose."""
    w = WAIT_POSE
    return {
        "animation_length": 0.8,
        "bones": {
            "UpBody": {"rotation": track(
                (0, ZERO), (0.07, [-14, 20, -4]), (0.14, [14, -20, 2]), (0.22, [18, -26, 3]), (0.42, [10, -12, 2]),
                (0.8, ZERO))},
            "Head": {"rotation": track(
                (0, ZERO), (0.07, [12, -14, 0]), (0.14, [-6, 16, 0]), (0.22, [-8, 20, 0]), (0.42, [-4, 10, 0]),
                (0.8, ZERO))},
            "RightArm": {"rotation": track(
                (0, [-60, -10, -10]), (0.07, [-170, 8, -12]), (0.14, [-105, 18, -20]), (0.22, [-62, 22, -8]),
                (0.42, [-55, 4, -12]), (0.8, w["RightArm"]))},
            "RightForeArm": {"rotation": track(
                (0, [-25, 0, 0]), (0.07, [-70, 0, 0]), (0.14, [-5, 0, 0]), (0.22, [-12, 0, 0]), (0.42, [-18, 0, 0]),
                (0.8, w["RightForeArm"]))},
            "LeftArm": {"rotation": track(
                (0, [-55, 20, 12]), (0.07, [-150, -10, 20]), (0.14, [-95, 30, 18]), (0.22, [-70, 38, 10]),
                (0.42, [-56, 26, 12]), (0.8, w["LeftArm"]))},
            "LeftForeArm": {"rotation": track(
                (0, [-20, 0, 0]), (0.07, [-55, 0, 0]), (0.14, [-10, 0, 0]), (0.22, [-12, 0, 0]), (0.42, [-18, 0, 0]),
                (0.8, w["LeftForeArm"]))},
            "RightHandLocator": {"rotation": track(
                (0, [30, 0, 0]), (0.07, [22, 0, 0]), (0.14, [34, 0, 0]), (0.22, [70, 0, 0]), (0.42, [57, 0, 0]),
                (0.8, w["RightHandLocator"]))},
            # weight back on the right foot for the wind-up, forward onto the left for the throw
            "LeftLeg": {"rotation": track((0, ZERO), (0.07, [-6, 0, 0]), (0.14, [-10, 0, 0]), (0.3, [-8, 0, 0]),
                                          (0.8, ZERO))},
            "RightLeg": {"rotation": track((0, ZERO), (0.07, [6, 0, 0]), (0.14, [8, 0, 0]), (0.3, [6, 0, 0]),
                                           (0.8, ZERO))},
        },
    }


def bite():
    """Once, 0.7 s. The rod is dragged down and the body with it, the eyebrows jump; an instinctive yank back; the
    fists come up into the fight's pose."""
    f = FIGHT_POSE
    w = WAIT_POSE
    return {
        "animation_length": 0.7,
        "bones": {
            "UpBody": {"rotation": track(
                (0, ZERO), (0.05, [16, 4, 0]), (0.12, [-12, -4, 0]), (0.25, [-6, 0, 0]), (0.45, [-7, 2, 0]),
                (0.7, f["UpBody"]))},
            "Head": {"rotation": track(
                (0, ZERO), (0.05, [14, 0, 0]), (0.12, [-6, 0, 0]), (0.25, [-2, 0, 0]), (0.7, f["Head"]))},
            "AllBody": {"position": track(
                (0, ZERO), (0.05, [0, -0.5, -0.6]), (0.12, [0, 0.3, 0.4]), (0.25, ZERO), (0.7, ZERO))},
            "RightArm": {"rotation": track(
                (0, w["RightArm"]), (0.05, [-38, -10, -12]), (0.12, [-95, -12, -16]), (0.25, [-85, -12, -14]),
                (0.7, f["RightArm"]))},
            "RightForeArm": {"rotation": track(
                (0, w["RightForeArm"]), (0.05, [-10, 0, 0]), (0.12, [-35, 0, 0]), (0.25, [-30, 0, 0]),
                (0.7, f["RightForeArm"]))},
            "LeftArm": {"rotation": track(
                (0, w["LeftArm"]), (0.05, [-40, 18, 10]), (0.12, [-92, 24, 14]), (0.25, [-82, 22, 12]),
                (0.7, f["LeftArm"]))},
            "LeftForeArm": {"rotation": track(
                (0, w["LeftForeArm"]), (0.05, [-10, 0, 0]), (0.12, [-35, 0, 0]), (0.25, [-30, 0, 0]),
                (0.7, f["LeftForeArm"]))},
            "RightHandLocator": {"rotation": track(
                (0, w["RightHandLocator"]), (0.05, [80, 0, 0]), (0.12, [22, 0, 0]), (0.25, [32, 0, 0]),
                (0.45, [36, 0, 0]), (0.7, f["RightHandLocator"]))},
            "RightEyebrow": {"position": track((0, ZERO), (0.05, [0, 0.6, 0]), (0.4, [0, 0.5, 0]), (0.7, ZERO))},
            "LeftEyebrow": {"position": track((0, ZERO), (0.05, [0, 0.6, 0]), (0.4, [0, 0.5, 0]), (0.7, ZERO))},
        },
    }


def fight():
    """Loop, 1.6 s. Leaning back with the rod high; the fish runs left and right (the torso and the rod follow), the
    tug comes twice per loop. Tension leans the body further back, raises the fists and the tip and widens every
    movement; near the limit the arms tremble. Each press of the minigame key (sneak) heaves the rod up and back; the
    body stays standing (the main controller does not crouch during the fight, see FishingStates.braced)."""
    f = FIGHT_POSE
    s1 = "math.sin(q.anim_time * 225)"  # once per loop: the fish runs from side to side
    s2 = "math.sin(q.anim_time * 450)"  # twice per loop: the tug
    tremble = f"math.sin(q.life_time * 2900) * math.max(0, {T} - 0.6) * 4"
    return {
        "loop": True,
        "animation_length": 1.6,
        "bones": {
            "UpBody": {"rotation": [
                f"{f['UpBody'][0]} - 10 * {T} - 9 * {H} + {s2} * (1.5 + 2.5 * {T}) + {tremble}",
                f"{s1} * (6 + 8 * {T})",
                f"math.sin(q.anim_time * 450 + 50) * (1.5 + 2 * {T})"]},
            "Head": {"rotation": [
                f"{f['Head'][0]} + {s2} * 1.5 + 4 * {H}",
                f"{s1} * -4",
                0]},
            "RightArm": {"rotation": [
                f"{f['RightArm'][0]} - 10 * {T} - 18 * {H} + {s2} * 4 * (0.5 + {T}) + {tremble}",
                f"{f['RightArm'][1]} + {s1} * 4",
                f["RightArm"][2]]},
            "RightForeArm": {"rotation": [f"{f['RightForeArm'][0]} - 10 * {T} - 10 * {H} + {s2} * 5", 0, 0]},
            "LeftArm": {"rotation": [
                f"{f['LeftArm'][0]} - 10 * {T} - 18 * {H} + math.sin(q.anim_time * 450 + 30) * 4 * (0.5 + {T})"
                f" - {tremble}",
                f"{f['LeftArm'][1]} + {s1} * 4",
                f["LeftArm"][2]]},
            "LeftForeArm": {"rotation": [
                f"{f['LeftForeArm'][0]} - 10 * {T} - 10 * {H} + math.sin(q.anim_time * 450 + 30) * 5", 0, 0]},
            "RightHandLocator": {"rotation": [
                f"{f['RightHandLocator'][0]} - 10 * {T} - 8 * {H} + {s2} * (4 + 6 * {T})",
                0,
                f"{s1} * 4"]},
        },
    }


def reel():
    """Loop, 0.6 s. The rod held up in the right hand while the left cranks the reel in circles; the tip nods with
    every turn and dips under tension."""
    turn_s = "math.sin(q.anim_time * 600)"
    turn_c = "math.cos(q.anim_time * 600)"
    return {
        "loop": True,
        "animation_length": 0.6,
        "bones": {
            "UpBody": {"rotation": [f"-4 + {turn_s} * 1 - 6 * {H}", f"{turn_c} * 1.5", 0]},
            "Head": {"rotation": [3, 0, 0]},
            "RightArm": {"rotation": [f"-80 - 8 * {H}", -12, -14]},
            "RightForeArm": {"rotation": [-28, 0, 0]},
            "LeftArm": {"rotation": [f"-70 + {turn_s} * 12", f"25 + {turn_c} * 10", 12]},
            "LeftForeArm": {"rotation": [f"-35 + {turn_c} * 12", 0, 0]},
            "RightHandLocator": {"rotation": [f"40 + 12 * {T} + {turn_s} * 2", 0, 0]},
        },
    }


def catch():
    """Once, 1.5 s. A dip to load, then the big yank up and over the head with a hop while the fish flies out (the
    head follows it up); landing, the left fist pumps twice, the rod resting low to the side."""
    f = FIGHT_POSE
    return {
        "animation_length": 1.5,
        "bones": {
            "UpBody": {"rotation": track(
                (0, f["UpBody"]), (0.08, [6, 0, 0]), (0.22, [-22, 8, 0]), (0.4, [-18, 5, 0]), (0.55, [4, 0, 0]),
                (0.75, [-6, -10, 0]), (1.1, [-6, -8, 0]), (1.5, ZERO))},
            "Head": {"rotation": track(
                (0, f["Head"]), (0.08, [4, 0, 0]), (0.22, [-18, 0, 0]), (0.4, [-20, 0, 0]), (0.55, [-4, 0, 0]),
                (0.75, [-12, -15, 0]), (0.95, [-8, -12, 0]), (1.1, [-12, -14, 0]), (1.5, ZERO))},
            "AllBody": {"position": track(
                (0, ZERO), (0.08, [0, -0.8, 0]), (0.22, [0, 1.6, 0]), (0.4, [0, 2.2, 0]), (0.55, [0, -0.6, 0]),
                (0.7, ZERO), (1.5, ZERO))},
            "RightArm": {"rotation": track(
                (0, f["RightArm"]), (0.08, [-60, -12, -14]), (0.22, [-175, -5, -10]), (0.4, [-165, -5, -10]),
                (0.55, [-120, -10, -12]), (0.75, [-30, 0, -25]), (1.1, [-28, 0, -24]), (1.5, ZERO))},
            "RightForeArm": {"rotation": track(
                (0, f["RightForeArm"]), (0.08, [-15, 0, 0]), (0.22, [-40, 0, 0]), (0.55, [-30, 0, 0]),
                (0.75, [-15, 0, 0]), (1.5, ZERO))},
            "LeftArm": {"rotation": track(
                (0, f["LeftArm"]), (0.08, [-58, 22, 12]), (0.22, [-165, 15, 12]), (0.4, [-160, 12, 14]),
                (0.55, [-150, 10, 25]), (0.75, [-175, -10, -10]), (0.95, [-150, -10, -10]), (1.1, [-178, -10, -10]),
                (1.5, ZERO))},
            "LeftForeArm": {"rotation": track(
                (0, f["LeftForeArm"]), (0.08, [-15, 0, 0]), (0.22, [-40, 0, 0]), (0.55, [-45, 0, 0]),
                (0.75, [-10, 0, 0]), (0.95, [-40, 0, 0]), (1.1, [-5, 0, 0]), (1.5, ZERO))},
            "RightHandLocator": {"rotation": track(
                (0, f["RightHandLocator"]), (0.08, [55, 0, 0]), (0.22, [40, 0, 0]), (0.4, [37, 0, 0]),
                (0.55, [42, 0, 0]), (0.75, [70, 0, 0]), (1.1, [72, 0, 0]), (1.5, ZERO))},
            "LeftLeg": {"rotation": track(
                (0, ZERO), (0.08, [-10, 0, 0]), (0.22, ZERO), (0.4, [-12, 0, 0]), (0.55, [-14, 0, 0]),
                (0.75, ZERO), (1.5, ZERO))},
            "RightLeg": {"rotation": track(
                (0, ZERO), (0.08, [-10, 0, 0]), (0.22, ZERO), (0.4, [8, 0, 0]), (0.55, [-14, 0, 0]), (0.75, ZERO),
                (1.5, ZERO))},
            "LeftLowerLeg": {"rotation": track(
                (0, ZERO), (0.08, [15, 0, 0]), (0.22, ZERO), (0.4, [20, 0, 0]), (0.55, [22, 0, 0]), (0.75, ZERO),
                (1.5, ZERO))},
            "RightLowerLeg": {"rotation": track(
                (0, ZERO), (0.08, [15, 0, 0]), (0.22, ZERO), (0.4, [6, 0, 0]), (0.55, [22, 0, 0]), (0.75, ZERO),
                (1.5, ZERO))},
        },
    }


def escape():
    """Once, 1.4 s. The line goes slack and the body stumbles back with the rod flying up; balance caught, the
    shoulders slump, the rod hangs, the head shakes."""
    f = FIGHT_POSE
    slump_head = [26, 0, 0]
    return {
        "animation_length": 1.4,
        "bones": {
            "UpBody": {"rotation": track(
                (0, f["UpBody"]), (0.06, [-20, 0, 0]), (0.25, [-8, 0, 0]), (0.5, [18, 0, 0]), (1.1, [16, 0, 0]),
                (1.4, ZERO))},
            "Head": {"rotation": track(
                (0, f["Head"]), (0.06, [-15, 0, 0]), (0.25, [-4, 0, 0]), (0.5, slump_head), (0.7, [26, -16, 0]),
                (0.85, [26, 16, 0]), (1.0, [26, -10, 0]), (1.1, [24, 0, 0]), (1.4, ZERO))},
            "AllBody": {"position": track(
                (0, ZERO), (0.06, [0, 0, 1.2]), (0.25, [0, 0, 0.8]), (0.5, [0, -0.4, 0.3]), (1.1, [0, -0.3, 0.2]),
                (1.4, ZERO))},
            "RightArm": {"rotation": track(
                (0, f["RightArm"]), (0.06, [-120, -12, -14]), (0.25, [-80, -12, -14]), (0.5, [-15, -5, -8]),
                (1.1, [-12, -5, -8]), (1.4, ZERO))},
            "RightForeArm": {"rotation": track(
                (0, f["RightForeArm"]), (0.06, [-40, 0, 0]), (0.25, [-25, 0, 0]), (0.5, [-8, 0, 0]), (1.4, ZERO))},
            "LeftArm": {"rotation": track(
                (0, f["LeftArm"]), (0.06, [-110, 22, 12]), (0.25, [-70, 20, 12]), (0.5, [-5, 5, 5]), (1.4, ZERO))},
            "LeftForeArm": {"rotation": track(
                (0, f["LeftForeArm"]), (0.06, [-35, 0, 0]), (0.25, [-20, 0, 0]), (0.5, [-5, 0, 0]), (1.4, ZERO))},
            "RightHandLocator": {"rotation": track(
                (0, f["RightHandLocator"]), (0.06, [-10, 0, 0]), (0.25, [20, 0, 0]), (0.5, [55, 0, 0]),
                (1.1, [58, 0, 0]), (1.4, ZERO))},
            "RightLeg": {"rotation": track((0, ZERO), (0.06, [10, 0, 0]), (0.25, [6, 0, 0]), (0.5, ZERO),
                                           (1.4, ZERO))},
            "LeftLeg": {"rotation": track((0, ZERO), (0.06, [-4, 0, 0]), (0.25, ZERO), (1.4, ZERO))},
        },
    }


def retrieve():
    """Once, 0.45 s. The line comes in without a fish: a flick of the wrist that snaps the tip up, then the rod
    lowered."""
    w = WAIT_POSE
    return {
        "animation_length": 0.45,
        "bones": {
            "UpBody": {"rotation": track((0, ZERO), (0.06, [-6, 4, 0]), (0.14, [-5, 3, 0]), (0.45, ZERO))},
            "Head": {"rotation": track((0, ZERO), (0.06, [-4, 0, 0]), (0.14, [-8, 0, 0]), (0.45, ZERO))},
            "RightArm": {"rotation": track(
                (0, w["RightArm"]), (0.06, [-100, -12, -16]), (0.14, [-110, -8, -14]), (0.45, ZERO))},
            "RightForeArm": {"rotation": track(
                (0, w["RightForeArm"]), (0.06, [-45, 0, 0]), (0.14, [-40, 0, 0]), (0.45, ZERO))},
            "LeftArm": {"rotation": track(
                (0, w["LeftArm"]), (0.06, [-70, 22, 12]), (0.14, [-50, 18, 10]), (0.45, ZERO))},
            "LeftForeArm": {"rotation": track(
                (0, w["LeftForeArm"]), (0.06, [-25, 0, 0]), (0.14, [-15, 0, 0]), (0.45, ZERO))},
            "RightHandLocator": {"rotation": track(
                (0, w["RightHandLocator"]), (0.06, [25, 0, 0]), (0.14, [20, 0, 0]), (0.45, ZERO))},
        },
    }


def draw():
    """Once, 0.9 s, as long as the 3D rods' own sacar. The rod comes up from the side into view and is shown, the head
    following it, then settles. It does not spin the rod: AkashicFishing's 3D rods twirl in their own sacar, and a
    spin of the hand on top of it was a double one."""
    return {
        "animation_length": 0.9,
        "bones": {
            "UpBody": {"rotation": track((0, ZERO), (0.3, [0, -8, 0]), (0.6, [0, -4, 0]), (0.9, ZERO))},
            "Head": {"rotation": track((0, ZERO), (0.2, [8, -14, 0]), (0.5, [4, -8, 0]), (0.9, ZERO))},
            "RightArm": {"rotation": track(
                (0, ZERO), (0.15, [-35, 0, -20]), (0.45, [-55, -8, -12]), (0.7, [-40, -4, -8]), (0.9, ZERO))},
            "RightForeArm": {"rotation": track(
                (0, ZERO), (0.15, [-35, 0, 0]), (0.45, [-30, 0, 0]), (0.7, [-18, 0, 0]), (0.9, ZERO))},
            "RightHandLocator": {"rotation": track((0, ZERO), (0.45, [25, 0, 0]), (0.9, ZERO))},
        },
    }


def pet():
    """Once, 2.0 s (the rods' carinho: Shift and a left click). The rod is brought up close before the chest and the
    free hand strokes it three times, the head tilted toward it."""
    hold_arm = [-40, -20, -10]
    hold_fore = [-75, 0, 0]
    stroke_up = [-112, 24, 16]
    stroke_down = [-96, 24, 16]
    return {
        "animation_length": 2.0,
        "bones": {
            "UpBody": {"rotation": track((0, ZERO), (0.25, [4, -6, 0]), (1.0, [4, -3, 0]), (1.75, [4, -6, 0]),
                                         (2.0, ZERO))},
            "Head": {"rotation": track((0, ZERO), (0.25, [14, -10, 8]), (1.0, [12, -8, 10]), (1.75, [14, -10, 8]),
                                       (2.0, ZERO))},
            "RightArm": {"rotation": track((0, ZERO), (0.25, hold_arm), (1.75, hold_arm), (2.0, ZERO))},
            "RightForeArm": {"rotation": track((0, ZERO), (0.25, hold_fore), (1.75, hold_fore), (2.0, ZERO))},
            "RightHandLocator": {"rotation": track((0, ZERO), (0.25, [17, 0, 0]), (1.75, [17, 0, 0]), (2.0, ZERO))},
            "LeftArm": {"rotation": track(
                (0, ZERO), (0.3, stroke_down), (0.55, stroke_up), (0.8, stroke_down), (1.05, stroke_up),
                (1.3, stroke_down), (1.55, stroke_up), (1.75, stroke_down), (2.0, ZERO))},
            "LeftForeArm": {"rotation": track(
                (0, ZERO), (0.3, [-35, 0, 0]), (0.55, [-50, 0, 0]), (0.8, [-35, 0, 0]), (1.05, [-50, 0, 0]),
                (1.3, [-35, 0, 0]), (1.55, [-50, 0, 0]), (1.75, [-35, 0, 0]), (2.0, ZERO))},
        },
    }


def inspect():
    """Once, 2.4 s (the rods' inspecionar: two of the gesture). The rod is raised upright before the face while it
    turns in the hand on its own; the eyes travel from the tip down to the grip, the free hand under it."""
    arm = [-70, -15, -10]
    fore = [-60, 0, 0]
    return {
        "animation_length": 2.4,
        "bones": {
            "UpBody": {"rotation": track((0, ZERO), (0.3, [-4, -8, 0]), (2.0, [-3, -6, 0]), (2.4, ZERO))},
            "Head": {"rotation": track((0, ZERO), (0.3, [-12, -10, 0]), (0.6, [-20, -10, 0]), (1.6, [12, -6, 0]),
                                       (2.0, [6, -6, 0]), (2.4, ZERO))},
            "RightArm": {"rotation": track((0, ZERO), (0.3, arm), (2.0, arm), (2.4, ZERO))},
            "RightForeArm": {"rotation": track((0, ZERO), (0.3, fore), (2.0, fore), (2.4, ZERO))},
            "RightHandLocator": {"rotation": track((0, ZERO), (0.3, [18, 0, 0]), (2.0, [18, 0, 0]), (2.4, ZERO))},
            "LeftArm": {"rotation": track((0, ZERO), (0.35, [-60, 30, 15]), (2.0, [-60, 30, 15]), (2.4, ZERO))},
            "LeftForeArm": {"rotation": track((0, ZERO), (0.35, [-45, 0, 0]), (2.0, [-45, 0, 0]), (2.4, ZERO))},
        },
    }


def lazy():
    """Loop, 6 s (the rods' ociosa: 30 s standing still). The rod rests on the right shoulder; the head nods off once,
    then a yawn with the free hand before the mouth. The torso and the legs stay the main controller's."""
    rest = [0, 0, 0]
    return {
        "loop": True,
        "animation_length": 6,
        "bones": {
            "RightArm": {"rotation": [-25, 10, -10]},
            "RightForeArm": {"rotation": [-110, 0, 0]},
            "RightHandLocator": {"rotation": [-25, 0, 0]},
            "LeftArm": {"rotation": track(
                (0, rest), (3.2, rest), (3.5, [-130, -35, -20]), (4.3, [-130, -35, -20]), (4.7, rest), (6.0, rest))},
            "LeftForeArm": {"rotation": track(
                (0, rest), (3.2, rest), (3.5, [-70, 0, 0]), (4.3, [-70, 0, 0]), (4.7, rest), (6.0, rest))},
            "Head": {"rotation": track(
                (0, rest), (1.5, [4, 0, 0]), (2.0, [12, 0, 2]), (2.4, [2, 0, 0]), (3.4, [-8, 0, 0]),
                (3.6, [-22, 0, 4]), (4.3, [-20, 0, 4]), (4.8, [3, 6, 0]), (6.0, rest))},
        },
    }


def greet():
    """Once, 1.6 s (the rods' encontro: another of them nearby). A wave with the free hand, the rod raised a little in
    salute, the head tilted kindly."""
    up = [-165, -10, -10]
    return {
        "animation_length": 1.6,
        "bones": {
            "UpBody": {"rotation": track((0, ZERO), (0.2, [0, 8, 0]), (1.2, [0, 8, 0]), (1.6, ZERO))},
            "Head": {"rotation": track((0, ZERO), (0.2, [-4, 6, -8]), (1.2, [-4, 6, -8]), (1.6, ZERO))},
            "LeftArm": {"rotation": track(
                (0, ZERO), (0.2, up), (0.4, [-165, -10, 15]), (0.6, [-165, -10, -15]), (0.8, [-165, -10, 15]),
                (1.0, [-165, -10, -10]), (1.6, ZERO))},
            "LeftForeArm": {"rotation": track((0, ZERO), (0.2, [-20, 0, 0]), (1.0, [-20, 0, 0]), (1.6, ZERO))},
            "RightArm": {"rotation": track((0, ZERO), (0.25, [-60, -10, -10]), (1.2, [-60, -10, -10]), (1.6, ZERO))},
            "RightForeArm": {"rotation": track((0, ZERO), (0.25, [-40, 0, 0]), (1.2, [-40, 0, 0]), (1.6, ZERO))},
            "RightHandLocator": {"rotation": track((0, ZERO), (0.25, [10, 0, 0]), (1.2, [10, 0, 0]), (1.6, ZERO))},
        },
    }


def rival():
    """Once, 1.8 s (the rods' rivalidade: Kitty and Kuromi nearby). Leaning in with a glare, the rod pointed ahead like
    a sword, the free hand on the hip; two huffs."""
    lean = [12, 0, 0]
    huff = [16, 0, 0]
    arm = [-85, -5, -10]
    return {
        "animation_length": 1.8,
        "bones": {
            "UpBody": {"rotation": track((0, ZERO), (0.2, lean), (0.6, huff), (0.8, lean), (1.2, huff), (1.4, lean),
                                         (1.8, ZERO))},
            "Head": {"rotation": track((0, ZERO), (0.2, [-12, 0, 0]), (1.5, [-12, 0, 0]), (1.8, ZERO))},
            "RightArm": {"rotation": track((0, ZERO), (0.2, arm), (0.6, [-90, -5, -10]), (0.8, arm),
                                           (1.2, [-90, -5, -10]), (1.5, arm), (1.8, ZERO))},
            "RightForeArm": {"rotation": track((0, ZERO), (0.2, [-10, 0, 0]), (1.5, [-10, 0, 0]), (1.8, ZERO))},
            "RightHandLocator": {"rotation": track((0, ZERO), (0.2, [85, 0, 0]), (1.5, [85, 0, 0]), (1.8, ZERO))},
            "LeftArm": {"rotation": track((0, ZERO), (0.25, [15, 0, -40]), (1.5, [15, 0, -40]), (1.8, ZERO))},
            "LeftForeArm": {"rotation": track((0, ZERO), (0.25, [-60, 0, 0]), (1.5, [-60, 0, 0]), (1.8, ZERO))},
        },
    }


def celebrate():
    """Once, 2.0 s (the rods' comemoracao: two fights won together). Two hops with both arms up and the rod high, then
    the free fist pumps."""
    up_r = [-170, 0, 10]
    up_l = [-170, 0, -10]
    return {
        "animation_length": 2.0,
        "bones": {
            "AllBody": {"position": track(
                (0, ZERO), (0.1, [0, -0.6, 0]), (0.3, [0, 2.0, 0]), (0.5, ZERO), (0.6, [0, -0.5, 0]),
                (0.8, [0, 2.0, 0]), (1.0, ZERO), (2.0, ZERO))},
            "Head": {"rotation": track((0, ZERO), (0.2, [-15, 0, 0]), (1.0, [-12, 0, 0]), (1.5, [-10, 0, 0]),
                                       (2.0, ZERO))},
            "RightArm": {"rotation": track((0, ZERO), (0.2, up_r), (0.3, [-170, 0, 18]), (0.55, up_r),
                                           (0.8, [-170, 0, 18]), (1.1, up_r), (1.7, [-150, 0, 10]), (2.0, ZERO))},
            "RightForeArm": {"rotation": track((0, ZERO), (0.2, [-10, 0, 0]), (1.7, [-10, 0, 0]), (2.0, ZERO))},
            "RightHandLocator": {"rotation": track((0, ZERO), (0.2, [40, 0, 0]), (1.7, [40, 0, 0]), (2.0, ZERO))},
            "LeftArm": {"rotation": track((0, ZERO), (0.2, up_l), (0.3, [-170, 0, -18]), (0.55, up_l),
                                          (0.8, [-170, 0, -18]), (1.1, up_l), (1.25, [-150, 0, -10]),
                                          (1.4, [-178, 0, -10]), (1.55, [-150, 0, -10]), (1.7, [-178, 0, -10]),
                                          (2.0, ZERO))},
            "LeftForeArm": {"rotation": track((0, ZERO), (0.2, [-10, 0, 0]), (1.25, [-40, 0, 0]), (1.4, [-5, 0, 0]),
                                              (1.55, [-40, 0, 0]), (1.7, [-5, 0, 0]), (2.0, ZERO))},
            "LeftLeg": {"rotation": track((0, ZERO), (0.1, [-10, 0, 0]), (0.3, ZERO), (0.5, [-8, 0, 0]),
                                          (0.6, [-10, 0, 0]), (0.8, ZERO), (1.0, [-8, 0, 0]), (1.2, ZERO), (2.0, ZERO))},
            "RightLeg": {"rotation": track((0, ZERO), (0.1, [-10, 0, 0]), (0.3, ZERO), (0.5, [-8, 0, 0]),
                                           (0.6, [-10, 0, 0]), (0.8, ZERO), (1.0, [-8, 0, 0]), (1.2, ZERO),
                                           (2.0, ZERO))},
            "LeftLowerLeg": {"rotation": track((0, ZERO), (0.1, [15, 0, 0]), (0.3, ZERO), (0.5, [12, 0, 0]),
                                               (0.6, [15, 0, 0]), (0.8, ZERO), (1.0, [12, 0, 0]), (1.2, ZERO),
                                               (2.0, ZERO))},
            "RightLowerLeg": {"rotation": track((0, ZERO), (0.1, [15, 0, 0]), (0.3, ZERO), (0.5, [12, 0, 0]),
                                                (0.6, [15, 0, 0]), (0.8, ZERO), (1.0, [12, 0, 0]), (1.2, ZERO),
                                                (2.0, ZERO))},
        },
    }


def animations():
    return {
        "fishing:draw": draw(),
        "fishing:cast": cast(),
        "fishing:wait": wait(),
        "fishing:bite": bite(),
        "fishing:fight": fight(),
        "fishing:reel": reel(),
        "fishing:catch": catch(),
        "fishing:escape": escape(),
        "fishing:retrieve": retrieve(),
        "fishing:pet": pet(),
        "fishing:inspect": inspect(),
        "fishing:lazy": lazy(),
        "fishing:greet": greet(),
        "fishing:rival": rival(),
        "fishing:celebrate": celebrate(),
    }


def render():
    doc = {"format_version": "1.8.0", "animations": animations()}
    return json.dumps(doc, ensure_ascii=False, indent=2) + "\n"


def main():
    text = render()
    if "--check" in sys.argv[1:]:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT} is out of date: run python3 tools/fishing_animations.py")
            return 1
        return 0
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
