package com.antigravity.ladder.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents an independent PLC Program on the Ignition Gateway.
 * Each Program can be Enabled/Disabled, Renamed, or Deleted, and contains its own
 * MainRoutine and Subroutines (called via JSR).
 */
public class LadderProgram {

    public String name;
    public String description;
    public volatile boolean enabled = true;
    public volatile long scanRateMs = 50L;
    public String mainRoutineName = "MainRoutine";
    public Map<String, LadderRoutine> routines = new ConcurrentHashMap<>();

    // Per-program runtime telemetry
    public volatile long scanCount = 0L;
    public volatile double lastScanMs = 0.0;
    public volatile double maxScanMs = 0.0;

    public LadderProgram() {
    }

    public LadderProgram(String name, String description, boolean enabled) {
        this.name = name;
        this.description = description != null ? description : "";
        this.enabled = enabled;
        LadderRoutine main = new LadderRoutine("MainRoutine", "MAIN", "Primary dispatcher routine for " + name);
        main.rungs.add(new LadderRung(0, "Rung 0: Default Rung", "XIC ? OTE ?"));
        main.reindexAndCompile();
        this.routines.put("MainRoutine", main);
    }

    public void ensureCompiled() {
        if (!routines.containsKey(mainRoutineName)) {
            LadderRoutine main = new LadderRoutine(mainRoutineName, "MAIN", "Primary dispatcher routine for " + name);
            main.rungs.add(new LadderRung(0, "Rung 0", "XIC ? OTE ?"));
            routines.put(mainRoutineName, main);
        }
        for (LadderRoutine r : routines.values()) {
            r.reindexAndCompile();
        }
    }

    public int getTotalRungCount() {
        int total = 0;
        for (LadderRoutine r : routines.values()) {
            if (r.rungs != null) total += r.rungs.size();
        }
        return total;
    }

    public int getTotalAstNodeCount() {
        int count = 0;
        for (LadderRoutine r : routines.values()) {
            if (r.rungs == null) continue;
            for (LadderRung rung : r.rungs) {
                if (rung.ast == null) rung.recompile();
                count += countNodes(rung.ast);
            }
        }
        return count;
    }

    private int countNodes(RungNode node) {
        if (node == null) return 0;
        int c = 1;
        if (node.children != null) {
            for (RungNode ch : node.children) c += countNodes(ch);
        }
        if (node.branches != null) {
            for (RungNode br : node.branches) c += countNodes(br);
        }
        return c;
    }

    public Map<String, Object> toSummaryMap() {
        int astNodes = getTotalAstNodeCount();
        int rungs = getTotalRungCount();
        double ramKb = Math.round(((routines.size() * 256.0 + rungs * 192.0 + astNodes * 128.0) / 1024.0) * 100.0) / 100.0;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("description", description);
        map.put("enabled", enabled);
        map.put("scanRateMs", scanRateMs);
        map.put("mainRoutine", mainRoutineName);
        map.put("routineCount", routines.size());
        map.put("totalRungs", rungs);
        map.put("astNodeCount", astNodes);
        map.put("estimatedRamKb", ramKb);
        map.put("scanCount", scanCount);
        map.put("lastScanMs", lastScanMs);
        map.put("maxScanMs", maxScanMs);
        return map;
    }

    public Map<String, Object> toFullMap(TagImageManager tagImage) {
        Map<String, Object> map = toSummaryMap();
        List<String> rNames = new ArrayList<>(routines.keySet());
        rNames.sort((a, b) -> {
            if (a.equals(mainRoutineName)) return -1;
            if (b.equals(mainRoutineName)) return 1;
            return a.compareToIgnoreCase(b);
        });
        Map<String, Object> routineMap = new LinkedHashMap<>();
        for (String rn : rNames) {
            routineMap.put(rn, routines.get(rn).toMap(tagImage));
        }
        map.put("routines", routineMap);
        return map;
    }
}
