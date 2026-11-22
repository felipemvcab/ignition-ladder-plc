package com.antigravity.ladder.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Studio 5000 style Add-On Instruction (AOI) Definition backed by an Ignition UDT.
 */
public class AoiDefinition {

    public static class AoiParam {
        public String name;
        public String dataType; // BOOL, DINT, REAL
        public String usage;    // INPUT, OUTPUT, INOUT

        public AoiParam() {
        }

        public AoiParam(String name, String dataType, String usage) {
            this.name = name;
            this.dataType = dataType != null ? dataType.toUpperCase() : "BOOL";
            this.usage = usage != null ? usage.toUpperCase() : "INOUT";
        }
    }

    public String name;
    public String description = "";
    public List<AoiParam> params = new ArrayList<>();
    public List<LadderRung> rungs = new ArrayList<>();

    public AoiDefinition() {
    }

    public AoiDefinition(String name, String description) {
        this.name = name;
        this.description = description != null ? description : "";
    }

    public void reindexAndCompile() {
        for (int i = 0; i < rungs.size(); i++) {
            LadderRung r = rungs.get(i);
            r.number = i;
            r.recompile();
        }
    }
}
