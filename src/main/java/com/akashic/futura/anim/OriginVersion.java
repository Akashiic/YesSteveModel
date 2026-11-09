/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/schema/model/ModelOriginVersion.java, Apache License 2.0.
 * Changes: Maven's ComparableVersion is replaced by a small comparator that orders the listed releases the same way
 * (numbers, then a release above its snapshots, snapshots by number).
 */
package com.akashic.futura.anim;

import java.util.Arrays;
import java.util.List;

/**
 * Which YSM release a model was made for ({@code properties.origin_ver}), as a behaviour level. Only the hand loop
 * rule depends on it here: before {@link #FILE_HAND_LOOP}, hold and use animations are forced to loop and swing to play
 * once; from it on, the animation file's own loop type is used.
 */
public final class OriginVersion {

    private static final List<String> RELEASES = Arrays.asList(
        "0.0.0",
        "1.2.0",
        "2.2.1-snapshot",
        "2.2.1-snapshot-3",
        "2.2.1",
        "2.2.2-snapshot-3",
        "2.2.2-snapshot-11",
        "2.2.2-snapshot-14",
        "2.2.2-snapshot-19",
        "2.2.2",
        "2.2.3-snapshot",
        "2.2.3-snapshot-6",
        "2.2.3-snapshot-8",
        "2.2.3-snapshot-11",
        "2.2.3-snapshot-28",
        "2.3.0",
        "2.4.2-snapshot-1",
        "2.4.2-snapshot-5",
        "2.4.2-snapshot-21",
        "2.4.2-snapshot-32",
        "2.4.2-snapshot-35",
        "2.4.2-snapshot-37",
        "2.4.2-snapshot-39",
        "2.4.2-snapshot-40",
        "2.4.2-snapshot-41",
        "2.4.2-snapshot-45",
        "2.5.0",
        "2.5.2-snapshot-2",
        "2.5.2",
        "2.5.4-snapshot-2",
        "2.5.4-snapshot-3",
        "2.6.0",
        "2.7.0",
        "3.0.0");

    public static final int LEGACY = 0;
    public static final int CURRENT = RELEASES.size() - 1;
    /** {@code 2.4.2-snapshot-32}. */
    public static final int FILE_HAND_LOOP = 19;

    private OriginVersion() {}

    /** Hand animations use the file's loop type from this level on, or always when borrowed from the default model. */
    public static boolean usesFileHandLoop(int originVer, boolean copiedFromDefault) {
        return originVer >= FILE_HAND_LOOP || copiedFromDefault;
    }

    /** @param version {@code properties.origin_ver}; blank or null means the current release */
    public static int fromModVersion(String version) {
        if (version == null || version.trim()
            .isEmpty()) {
            return CURRENT;
        }
        int exact = RELEASES.indexOf(version);
        if (exact >= 0) {
            return exact;
        }
        String v = version.split("\\+", 2)[0];
        // the greatest release not above the version
        int found = -1;
        for (int i = 0; i < RELEASES.size(); i++) {
            int cmp = compare(RELEASES.get(i), v);
            if (cmp == 0) {
                return i;
            }
            if (cmp < 0) {
                found = i;
            }
        }
        return Math.max(LEGACY, found);
    }

    /** Orders {@code a.b.c[-qualifier[-n]]}: numbers first, a release above its qualified builds. */
    static int compare(String a, String b) {
        String[] pa = splitQualifier(a);
        String[] pb = splitQualifier(b);
        int c = compareNumbers(pa[0], pb[0]);
        if (c != 0) {
            return c;
        }
        boolean qa = !pa[1].isEmpty();
        boolean qb = !pb[1].isEmpty();
        if (qa != qb) {
            return qa ? -1 : 1;
        }
        if (!qa) {
            return 0;
        }
        String[] sa = pa[1].split("-");
        String[] sb = pb[1].split("-");
        c = sa[0].compareToIgnoreCase(sb[0]);
        if (c != 0) {
            return c;
        }
        return Integer.compare(number(sa.length > 1 ? sa[1] : "0"), number(sb.length > 1 ? sb[1] : "0"));
    }

    private static String[] splitQualifier(String v) {
        int dash = v.indexOf('-');
        return dash < 0 ? new String[] { v, "" } : new String[] { v.substring(0, dash), v.substring(dash + 1) };
    }

    private static int compareNumbers(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(number(i < x.length ? x[i] : "0"), number(i < y.length ? y[i] : "0"));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
