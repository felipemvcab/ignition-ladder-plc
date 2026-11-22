package com.felipe.ladder.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Represents a node in the pre-compiled Ladder Logic Rung Abstract Syntax Tree (AST).
 * Designed for zero-allocation cyclic scan evaluation.
 */
public class RungNode {

    public enum NodeType {
        SERIES,     // Sequence of instructions/branches in series
        PARALLEL,   // Parallel branch group (BST ... NXB ... BND)
        INSTRUCTION // Leaf instruction (XIC, OTE, TON, JSR, ADD, etc.)
    }

    public NodeType type;
    public String instruction; // e.g. "XIC", "XIO", "OTE", "OTL", "OTU", "ONS", "TON", "TOF", "CTU", "RES", "EQU", "NEQ", "GRT", "GEQ", "LES", "LEQ", "LIM", "MOV", "ADD", "SUB", "MUL", "DIV", "JSR", "SBR", "RET", "JMP", "LBL"
    public List<String> operands = new ArrayList<>();

    // For SERIES: ordered child nodes
    public List<RungNode> children = new ArrayList<>();

    // For PARALLEL: list of parallel branch paths (each branch path is a SERIES node)
    public List<RungNode> branches = new ArrayList<>();

    // Runtime state updated in-place during scan (zero-allocation)
    public volatile boolean powerIn;
    public volatile boolean powerOut;
    public volatile boolean energized;

    // Internal state for ONS (One-Shot), edge-triggered instructions (CTU/CTD), timers, and PID
    public transient boolean lastScanRungIn = false;
    public transient long lastUpdateNanos = 0L;
    public transient double pidIntegral = 0.0;
    public transient double pidPrevPv = 0.0;
    public transient boolean pidInitialized = false;
    public transient double pidCycleElapsedMs = 0.0;
    public transient double pidSinceSwitchMs = 0.0;
    public transient boolean pidDigitalOut = false;

    public RungNode() {
    }

    public static RungNode series() {
        RungNode n = new RungNode();
        n.type = NodeType.SERIES;
        return n;
    }

    public static RungNode parallel() {
        RungNode n = new RungNode();
        n.type = NodeType.PARALLEL;
        return n;
    }

    public static RungNode instruction(String inst, List<String> ops) {
        RungNode n = new RungNode();
        n.type = NodeType.INSTRUCTION;
        n.instruction = inst.toUpperCase();
        if (ops != null) {
            n.operands.addAll(ops);
        }
        return n;
    }

    public static int getOperandCount(String inst) {
        if (inst == null) return 0;
        switch (inst.toUpperCase()) {
            case "RET":
            case "NOP":
            case "AFI":
                return 0;
            case "XIC":
            case "XIO":
            case "OTE":
            case "OTL":
            case "OTU":
            case "ONS":
            case "CLR":
            case "RES":
            case "JMP":
            case "LBL":
            case "JSR":
            case "SBR":
                return 1;
            case "OSR":
            case "OSF":
            case "AOI":
            case "EQU":
            case "NEQ":
            case "GRT":
            case "GEQ":
            case "LES":
            case "LEQ":
            case "MOV":
            case "CPT":
                return 2;
            case "TON":
            case "TOF":
            case "RTO":
            case "CTU":
            case "CTD":
            case "ADD":
            case "SUB":
            case "MUL":
            case "DIV":
            case "LIM":
                return 3;
            case "PID":
            case "PIDD":
                return 4;
            default:
                return 1;
        }
    }

    public Map<String, Object> toMap(TagImageManager tagImage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type.name());
        map.put("powerIn", powerIn);
        map.put("powerOut", powerOut);
        map.put("energized", energized);

        if (type == NodeType.INSTRUCTION) {
            map.put("instruction", instruction);
            map.put("operands", new ArrayList<>(operands));
            Map<String, Object> liveValues = new LinkedHashMap<>();
            Map<String, Boolean> forcedFlags = new LinkedHashMap<>();
            for (int opIdx = 0; opIdx < operands.size(); opIdx++) {
                String op = operands.get(opIdx);
                if (op != null && !op.isEmpty() && !isNumericLiteral(op)) {
                    Object val = tagImage != null ? tagImage.readValue(op) : null;
                    if (val != null) {
                        liveValues.put(op, val);
                    }
                    if (tagImage != null && tagImage.isForced(op)) {
                        forcedFlags.put(op, true);
                    }
                    // Also include timer/counter sub-elements if applicable
                    if (tagImage != null && isTimerOrCounter(instruction)) {
                        putIfPresent(liveValues, tagImage, TagImageManager.memberPath(op, "PRE"));
                        putIfPresent(liveValues, tagImage, TagImageManager.memberPath(op, "ACC"));
                        putIfPresent(liveValues, tagImage, TagImageManager.memberPath(op, "EN"));
                        putIfPresent(liveValues, tagImage, TagImageManager.memberPath(op, "TT"));
                        putIfPresent(liveValues, tagImage, TagImageManager.memberPath(op, "DN"));
                        putIfPresent(liveValues, tagImage, TagImageManager.memberPath(op, "CU"));
                        putIfPresent(liveValues, tagImage, TagImageManager.memberPath(op, "CD"));
                        putIfPresent(liveValues, tagImage, TagImageManager.memberPath(op, "RES"));
                    } else if (tagImage != null && ("PID".equalsIgnoreCase(instruction) || "PIDD".equalsIgnoreCase(instruction)) && operands.size() <= 4 && opIdx == 0) {
                        for (String m : UdtTimerCounterManager.PID_MEMBERS) {
                            putIfPresent(liveValues, tagImage, TagImageManager.memberPath(op, m));
                        }
                    }
                }
            }
            map.put("liveValues", liveValues);
            map.put("forced", forcedFlags);
        } else if (type == NodeType.SERIES) {
            List<Map<String, Object>> childList = new ArrayList<>(children.size());
            for (RungNode c : children) {
                childList.add(c.toMap(tagImage));
            }
            map.put("children", childList);
        } else if (type == NodeType.PARALLEL) {
            List<Map<String, Object>> branchList = new ArrayList<>(branches.size());
            for (RungNode b : branches) {
                branchList.add(b.toMap(tagImage));
            }
            map.put("branches", branchList);
        }
        return map;
    }

    private static void putIfPresent(Map<String, Object> target, TagImageManager tagImage, String path) {
        Object v = tagImage.readValue(path);
        if (v != null) {
            target.put(path, v);
        }
    }

    public static boolean isTimerOrCounter(String inst) {
        if (inst == null) return false;
        switch (inst.toUpperCase()) {
            case "TON":
            case "TOF":
            case "RTO":
            case "CTU":
            case "CTD":
            case "RES":
                return true;
            default:
                return false;
        }
    }

    public static boolean isNumericLiteral(String s) {
        if (s == null || s.isEmpty()) return false;
        char c0 = s.charAt(0);
        if ((c0 >= '0' && c0 <= '9') || c0 == '-' || c0 == '+') {
            try {
                Double.parseDouble(s);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }
}
