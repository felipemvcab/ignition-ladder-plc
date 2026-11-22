package com.antigravity.ladder.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a single Rung inside a Ladder Routine.
 */
public class LadderRung {

    public int number;
    public String comment;
    public String mnemonic;
    public transient RungNode ast;
    public volatile boolean powerOut;

    public LadderRung() {
    }

    public LadderRung(int number, String comment, String mnemonic) {
        this.number = number;
        this.comment = comment != null ? comment : "";
        this.mnemonic = mnemonic != null ? mnemonic : "";
        recompile();
    }

    public void recompile() {
        this.ast = MnemonicCompiler.compile(this.mnemonic);
    }

    public Map<String, Object> toMap(TagImageManager tagImage) {
        if (ast == null) {
            recompile();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("number", number);
        map.put("comment", comment);
        map.put("mnemonic", mnemonic);
        map.put("powerOut", powerOut);
        map.put("ast", ast != null ? ast.toMap(tagImage) : null);
        return map;
    }
}
