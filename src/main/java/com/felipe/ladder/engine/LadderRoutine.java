package com.felipe.ladder.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a Routine (MainRoutine or Subroutine called via JSR) inside a Program.
 */
public class LadderRoutine {

    public String name;
    public String type; // "MAIN", "SUBROUTINE", "FAULT"
    public String description;
    public List<LadderRung> rungs = new ArrayList<>();
    public volatile long executionCount = 0L;
    public volatile double lastExecutionUs = 0.0;
    public volatile boolean scannedLastCycle = false;

    public LadderRoutine() {
    }

    public LadderRoutine(String name, String type, String description) {
        this.name = name;
        this.type = type != null ? type : "SUBROUTINE";
        this.description = description != null ? description : "";
    }

    public void reindexAndCompile() {
        for (int i = 0; i < rungs.size(); i++) {
            LadderRung r = rungs.get(i);
            r.number = i;
            if (r.ast == null) {
                r.recompile();
            }
        }
    }

    public Map<String, Object> toMap(TagImageManager tagImage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("type", type);
        map.put("description", description);
        map.put("executionCount", executionCount);
        map.put("lastExecutionUs", lastExecutionUs);
        map.put("scannedLastCycle", scannedLastCycle);

        List<Map<String, Object>> rungMaps = new ArrayList<>(rungs.size());
        for (LadderRung r : rungs) {
            rungMaps.add(r.toMap(tagImage));
        }
        map.put("rungs", rungMaps);
        return map;
    }
}
