package com.antigravity.ladder.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Studio 5000 / Logix Designer compatible ASCII Mnemonic Parser & Decompiler.
 *
 * Supports both classic space-delimited syntax:
 *   BST XIC Start_PB NXB XIC Motor_Run BND XIO Stop_PB OTE Motor_Run
 * and parenthesized syntax:
 *   BST XIC(Start_PB) NXB XIC(Motor_Run) BND XIO(Stop_PB) OTE(Motor_Run)
 */
public class MnemonicCompiler {

    public static RungNode compile(String mnemonic) {
        RungNode root = RungNode.series();
        if (mnemonic == null || mnemonic.trim().isEmpty()) {
            return root;
        }

        List<String> tokens = tokenize(mnemonic.trim());
        Deque<RungNode> seriesStack = new ArrayDeque<>();
        Deque<RungNode> parallelStack = new ArrayDeque<>();
        seriesStack.push(root);

        int i = 0;
        while (i < tokens.size()) {
            String rawToken = tokens.get(i++);
            if (rawToken.isEmpty() || rawToken.equals(";")) {
                continue;
            }
            String upper = rawToken.toUpperCase();

            if (upper.equals("BST") || upper.equals("[")) {
                RungNode par = RungNode.parallel();
                RungNode firstBranch = RungNode.series();
                par.branches.add(firstBranch);
                seriesStack.peek().children.add(par);
                parallelStack.push(par);
                seriesStack.push(firstBranch);
            } else if (upper.equals("NXB") || upper.equals(",")) {
                if (!parallelStack.isEmpty()) {
                    seriesStack.pop();
                    RungNode nextBranch = RungNode.series();
                    parallelStack.peek().branches.add(nextBranch);
                    seriesStack.push(nextBranch);
                }
            } else if (upper.equals("BND") || upper.equals("]")) {
                if (!parallelStack.isEmpty()) {
                    seriesStack.pop();
                    parallelStack.pop();
                }
            } else {
                // Check if token is parenthesized e.g. XIC(TagA) or TON(Timer1,5000,0)
                int parenIdx = rawToken.indexOf('(');
                if (parenIdx > 0 && rawToken.endsWith(")")) {
                    String inst = rawToken.substring(0, parenIdx).trim().toUpperCase();
                    String inside = rawToken.substring(parenIdx + 1, rawToken.length() - 1).trim();
                    List<String> ops = new ArrayList<>();
                    if (!inside.isEmpty()) {
                        for (String part : inside.split(",")) {
                            ops.add(part.trim());
                        }
                    }
                    seriesStack.peek().children.add(RungNode.instruction(inst, ops));
                } else {
                    String inst = upper;
                    int opCount = RungNode.getOperandCount(inst);
                    // Backwards-compatible support for legacy 6-operand PID (PV SP CV Kp Ki Kd) alongside 4-operand UDT PID (PidTag PV SP CV)
                    if ("PID".equals(inst) && i + 5 < tokens.size()
                            && RungNode.isNumericLiteral(tokens.get(i + 3))
                            && RungNode.isNumericLiteral(tokens.get(i + 4))
                            && RungNode.isNumericLiteral(tokens.get(i + 5))) {
                        opCount = 6;
                    }
                    List<String> ops = new ArrayList<>(opCount);
                    for (int k = 0; k < opCount && i < tokens.size(); k++) {
                        String nextTok = tokens.get(i);
                        String nextUpper = nextTok.toUpperCase();
                        if (nextUpper.equals("BST") || nextUpper.equals("NXB") || nextUpper.equals("BND")) {
                            break;
                        }
                        ops.add(tokens.get(i++));
                    }
                    seriesStack.peek().children.add(RungNode.instruction(inst, ops));
                }
            }
        }
        return root;
    }

    public static String toMnemonic(RungNode node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        appendMnemonic(node, sb);
        return sb.toString().trim();
    }

    private static void appendMnemonic(RungNode node, StringBuilder sb) {
        if (node == null) return;
        if (node.type == RungNode.NodeType.SERIES) {
            for (RungNode child : node.children) {
                appendMnemonic(child, sb);
            }
        } else if (node.type == RungNode.NodeType.PARALLEL) {
            sb.append("BST ");
            for (int i = 0; i < node.branches.size(); i++) {
                if (i > 0) sb.append("NXB ");
                appendMnemonic(node.branches.get(i), sb);
            }
            sb.append("BND ");
        } else if (node.type == RungNode.NodeType.INSTRUCTION) {
            sb.append(node.instruction).append(" ");
            for (String op : node.operands) {
                sb.append(op).append(" ");
            }
        }
    }

    public static Set<String> extractTagPaths(RungNode node) {
        Set<String> tags = new LinkedHashSet<>();
        collectTags(node, tags);
        return tags;
    }

    private static void collectTags(RungNode node, Set<String> tags) {
        if (node == null) return;
        if (node.type == RungNode.NodeType.INSTRUCTION) {
            String inst = node.instruction != null ? node.instruction.toUpperCase() : "";
            if (inst.equals("JSR") || inst.equals("JMP") || inst.equals("LBL") || inst.equals("SBR") || inst.equals("RET") || inst.equals("AOI")) {
                // First operand of JSR/JMP/LBL/AOI is routine/label/AOI name, any additional operands are tags/instances
                for (int i = 1; i < node.operands.size(); i++) {
                    String op = node.operands.get(i);
                    if (!RungNode.isNumericLiteral(op)) {
                        tags.add(op);
                    }
                }
                return;
            }
            for (int i = 0; i < node.operands.size(); i++) {
                String op = node.operands.get(i);
                if (op != null && !op.isEmpty() && !op.equals("?") && !RungNode.isNumericLiteral(op)) {
                    tags.add(op);
                }
            }
        } else if (node.type == RungNode.NodeType.SERIES) {
            for (RungNode c : node.children) {
                collectTags(c, tags);
            }
        } else if (node.type == RungNode.NodeType.PARALLEL) {
            for (RungNode b : node.branches) {
                collectTags(b, tags);
            }
        }
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenDepth = 0;
        int bracketDepth = 0;
        boolean inQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (!inQuotes) {
                if (c == '(') {
                    parenDepth++;
                    current.append(c);
                } else if (c == ')') {
                    if (parenDepth > 0) parenDepth--;
                    current.append(c);
                    // Flush completed parenthesized instruction e.g. XIC(A)OTE(B)
                    if (parenDepth == 0 && bracketDepth == 0 && current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                } else if (c == '[' && parenDepth == 0) {
                    if (isStudio5000BranchOpen(input, i, current.length())) {
                        if (current.length() > 0) {
                            tokens.add(current.toString());
                            current.setLength(0);
                        }
                        tokens.add("[");
                    } else {
                        bracketDepth++;
                        current.append(c);
                    }
                } else if (c == ']' && parenDepth == 0) {
                    if (bracketDepth > 0) {
                        bracketDepth--;
                        current.append(c);
                    } else {
                        if (current.length() > 0) {
                            tokens.add(current.toString());
                            current.setLength(0);
                        }
                        tokens.add("]");
                    }
                } else if (c == ',' && parenDepth == 0 && bracketDepth == 0) {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                    tokens.add(",");
                } else if (c == ';' && parenDepth == 0 && bracketDepth == 0) {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                } else if (Character.isWhitespace(c) && parenDepth == 0 && bracketDepth == 0) {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * Distinguishes Studio 5000 parallel branch '[' (e.g. "[XIC(A) ,XIC(B) ]")
     * from Ignition tag provider prefix "[LadderPLC]Path" or array index "Arr[0]".
     */
    private static boolean isStudio5000BranchOpen(String input, int openIdx, int currentLen) {
        if (currentLen > 0) {
            // Preceded by identifier without space -> array index like Tag[0]
            return false;
        }
        // Scan forward to matching ']' at depth 0
        int closeIdx = -1;
        boolean hasBranchSyntax = false;
        for (int j = openIdx + 1; j < input.length(); j++) {
            char ch = input.charAt(j);
            if (ch == '(' || ch == '[' || ch == ',' || Character.isWhitespace(ch)) {
                hasBranchSyntax = true;
                break;
            }
            if (ch == ']') {
                closeIdx = j;
                break;
            }
        }
        if (hasBranchSyntax) {
            return true;
        }
        // If [Provider]TagPath, ']' is immediately followed by a tag path character
        if (closeIdx > openIdx && closeIdx + 1 < input.length()) {
            char after = input.charAt(closeIdx + 1);
            if (Character.isLetterOrDigit(after) || after == '_' || after == '/') {
                return false;
            }
        }
        return true;
    }
}
