package com.felipe.ladder.build;

import com.felipe.ladder.engine.AoiDefinition;
import com.felipe.ladder.engine.CyclicScanEngine;
import com.felipe.ladder.engine.LadderProgram;
import com.felipe.ladder.engine.LadderRoutine;
import com.felipe.ladder.engine.LadderRung;
import com.felipe.ladder.engine.TagImageManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Randomized end-to-end validation suite exercising EVERY instruction/function
 * supported by Ladder Logic Studio using dynamically generated random tags and rungs.
 */
public class InstructionValidationSuite {

    private final Random rng;
    private final List<String> passed = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();

    public InstructionValidationSuite(long seed) {
        this.rng = new Random(seed);
    }

    private String rndTag(String prefix) {
        return "[LadderPLC]Validation/" + prefix + "_" + (1000 + rng.nextInt(9000));
    }

    private double rndVal(int min, int max) {
        return min + rng.nextInt(max - min + 1);
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private void check(String inst, String detail, boolean condition) {
        if (condition) {
            passed.add(inst + " :: " + detail);
        } else {
            failed.add(inst + " :: " + detail);
        }
    }

    private CyclicScanEngine createFreshEngine(TagImageManager tagImage, LadderProgram prog) {
        CyclicScanEngine engine = new CyclicScanEngine(tagImage, null);
        engine.loadEmptyProject();
        // Replace MainProgram with our validation program
        engine.createProgram(prog.name, prog.description, true);
        engine.setActiveProgram(prog.name);
        engine.setProgramEnabled("MainProgram", false);
        for (LadderRoutine r : prog.routines.values()) {
            List<Map<String, Object>> rawRungs = new ArrayList<>();
            for (LadderRung rung : r.rungs) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("comment", rung.comment);
                m.put("mnemonic", rung.mnemonic);
                rawRungs.add(m);
            }
            engine.upsertRoutine(prog.name, r.name, r.type, r.description, rawRungs);
        }
        return engine;
    }

    public boolean runAll() throws Exception {
        testBitAndBranch();
        testFirstScanBit();
        testOneShots();
        testCompareInstructions();
        testMoveClearMath();
        testTimers();
        testCountersAndRes();
        testSubroutinesJsrSbrRet();
        testJmpLbl();
        testAoi();
        testPid();

        System.out.println("\n==================================================================");
        System.out.println(" INSTRUCTION VALIDATION SUMMARY");
        System.out.println("==================================================================");
        for (String p : passed) {
            System.out.println("  [PASS] " + p);
        }
        for (String f : failed) {
            System.out.println("  [FAIL] " + f);
        }
        System.out.printf("TOTAL: %d passed, %d failed%n", passed.size(), failed.size());
        return failed.isEmpty();
    }

    private void testBitAndBranch() {
        TagImageManager ti = new TagImageManager(null, null);
        String inA = rndTag("InA"), inB = rndTag("InB"), inC = rndTag("InC");
        String outXic = rndTag("OutXic"), outXio = rndTag("OutXio");
        String latchBit = rndTag("LatchBit"), latchCmd = rndTag("LatchCmd"), unlatchCmd = rndTag("UnlatchCmd");
        String outBranch = rndTag("OutBranch");
        String outAfi = rndTag("OutAfi"), outNop = rndTag("OutNop");

        ti.registerTag(inA, false, "BOOL", "");
        ti.registerTag(inB, false, "BOOL", "");
        ti.registerTag(inC, false, "BOOL", "");
        ti.registerTag(outXic, false, "BOOL", "");
        ti.registerTag(outXio, false, "BOOL", "");
        ti.registerTag(latchBit, false, "BOOL", "");
        ti.registerTag(latchCmd, false, "BOOL", "");
        ti.registerTag(unlatchCmd, false, "BOOL", "");
        ti.registerTag(outBranch, false, "BOOL", "");
        ti.registerTag(outAfi, true, "BOOL", "");
        ti.registerTag(outNop, false, "BOOL", "");

        LadderProgram prog = new LadderProgram("Val_BitBranch", "Bit & Branch Validation", true);
        LadderRoutine main = new LadderRoutine("MainRoutine", "MAIN", "");
        main.rungs.add(new LadderRung(0, "XIC + OTE", "XIC " + inA + " OTE " + outXic));
        main.rungs.add(new LadderRung(1, "XIO + OTE", "XIO " + inA + " OTE " + outXio));
        main.rungs.add(new LadderRung(2, "OTL", "XIC " + latchCmd + " OTL " + latchBit));
        main.rungs.add(new LadderRung(3, "OTU", "XIC " + unlatchCmd + " OTU " + latchBit));
        main.rungs.add(new LadderRung(4, "BST/NXB/BND 3-branch",
                "BST XIC " + inA + " NXB XIC " + inB + " NXB XIC " + inC + " BND OTE " + outBranch));
        main.rungs.add(new LadderRung(5, "AFI blocks rung", "AFI OTE " + outAfi));
        main.rungs.add(new LadderRung(6, "NOP passes power", "NOP OTE " + outNop));
        prog.routines.put(main.name, main);

        CyclicScanEngine eng = createFreshEngine(ti, prog);

        // Scan 1: all false
        eng.executeSingleScanSafely();
        check("AFI", "always blocks power -> output false", !ti.readBoolean(outAfi));
        check("NOP", "passes power through -> output true", ti.readBoolean(outNop));
        check("XIC/OTE", "false input -> output false", !ti.readBoolean(outXic));
        check("XIO/OTE", "false input -> output true", ti.readBoolean(outXio));
        check("BST/NXB/BND", "all branches false -> output false", !ti.readBoolean(outBranch));

        // Scan 2: inA true, latchCmd true
        ti.writeFromExternalUI(inA, true);
        ti.writeFromExternalUI(latchCmd, true);
        eng.executeSingleScanSafely();
        check("XIC/OTE", "true input -> output true", ti.readBoolean(outXic));
        check("XIO/OTE", "true input -> output false", !ti.readBoolean(outXio));
        check("OTL", "latches true when rung true", ti.readBoolean(latchBit));
        check("BST/NXB/BND", "branch 1 true -> output true", ti.readBoolean(outBranch));

        // Scan 3: inA false, inB true, latchCmd false (latch must persist!)
        ti.writeFromExternalUI(inA, false);
        ti.writeFromExternalUI(inB, true);
        ti.writeFromExternalUI(latchCmd, false);
        eng.executeSingleScanSafely();
        check("OTE", "de-energizes when rung returns false", !ti.readBoolean(outXic));
        check("OTL", "retains true after rung returns false", ti.readBoolean(latchBit));
        check("BST/NXB/BND", "middle branch true -> output true", ti.readBoolean(outBranch));

        // Scan 4: unlatchCmd true
        ti.writeFromExternalUI(inB, false);
        ti.writeFromExternalUI(unlatchCmd, true);
        eng.executeSingleScanSafely();
        check("OTU", "unlatches to false when rung true", !ti.readBoolean(latchBit));
    }

    private void testFirstScanBit() {
        TagImageManager ti = new TagImageManager(null, null);
        String fsCount = rndTag("FSCount");
        String fsLatched = rndTag("FSLatched");
        ti.registerTag(fsCount, 0L, "DINT", "");
        ti.registerTag(fsLatched, false, "BOOL", "");

        LadderProgram prog = new LadderProgram("Val_SFS", "S:FS Validation", true);
        LadderRoutine main = new LadderRoutine("MainRoutine", "MAIN", "");
        main.rungs.add(new LadderRung(0, "S:FS counter", "XIC S:FS ADD " + fsCount + " 1 " + fsCount));
        main.rungs.add(new LadderRung(1, "S:FS latch", "XIC S:FS OTL " + fsLatched));
        prog.routines.put(main.name, main);

        CyclicScanEngine eng = createFreshEngine(ti, prog);
        eng.executeSingleScanSafely();
        eng.executeSingleScanSafely();
        eng.executeSingleScanSafely();

        check("S:FS", "fires on first scan and only once (count=" + (long) ti.readDouble(fsCount) + ")",
                ti.readBoolean(fsLatched) && Math.abs(ti.readDouble(fsCount) - 1.0) < 1e-6);
    }

    private void testOneShots() {
        TagImageManager ti = new TagImageManager(null, null);
        String trig = rndTag("Trig");
        String onsBit = rndTag("OnsBit"), onsCnt = rndTag("OnsCnt");
        String osrStor = rndTag("OsrStor"), osrOut = rndTag("OsrOut"), osrCnt = rndTag("OsrCnt");
        String osfStor = rndTag("OsfStor"), osfOut = rndTag("OsfOut"), osfCnt = rndTag("OsfCnt");

        for (String b : new String[]{trig, onsBit, osrStor, osrOut, osfStor, osfOut}) {
            ti.registerTag(b, false, "BOOL", "");
        }
        for (String c : new String[]{onsCnt, osrCnt, osfCnt}) {
            ti.registerTag(c, 0L, "DINT", "");
        }

        LadderProgram prog = new LadderProgram("Val_OneShots", "ONS/OSR/OSF Validation", true);
        LadderRoutine main = new LadderRoutine("MainRoutine", "MAIN", "");
        main.rungs.add(new LadderRung(0, "ONS", "XIC " + trig + " ONS " + onsBit + " ADD " + onsCnt + " 1 " + onsCnt));
        main.rungs.add(new LadderRung(1, "OSR", "XIC " + trig + " OSR " + osrStor + " " + osrOut + " ADD " + osrCnt + " 1 " + osrCnt));
        main.rungs.add(new LadderRung(2, "OSF", "XIC " + trig + " OSF " + osfStor + " " + osfOut + " ADD " + osfCnt + " 1 " + osfCnt));
        prog.routines.put(main.name, main);

        CyclicScanEngine eng = createFreshEngine(ti, prog);

        // Scan 1: trig=false
        eng.executeSingleScanSafely();
        // Scan 2..4: trig=true (rising edge on scan 2 only)
        ti.writeFromExternalUI(trig, true);
        eng.executeSingleScanSafely();
        boolean osrOutOnRise = ti.readBoolean(osrOut);
        eng.executeSingleScanSafely();
        boolean osrOutAfterHold = ti.readBoolean(osrOut);
        eng.executeSingleScanSafely();

        check("ONS", "fires exactly once on rising edge (cnt=" + (long) ti.readDouble(onsCnt) + ")",
                Math.abs(ti.readDouble(onsCnt) - 1.0) < 1e-6);
        check("OSR", "output bit pulses 1 scan and count=1 (rise=" + osrOutOnRise + ", hold=" + osrOutAfterHold + ", cnt=" + (long) ti.readDouble(osrCnt) + ")",
                osrOutOnRise && !osrOutAfterHold && Math.abs(ti.readDouble(osrCnt) - 1.0) < 1e-6);
        check("OSF", "does not fire on rising edge (cnt=" + (long) ti.readDouble(osfCnt) + ")",
                Math.abs(ti.readDouble(osfCnt) - 0.0) < 1e-6);

        // Scan 5..6: trig=false (falling edge on scan 5 only)
        ti.writeFromExternalUI(trig, false);
        eng.executeSingleScanSafely();
        boolean osfOutOnFall = ti.readBoolean(osfOut);
        eng.executeSingleScanSafely();
        boolean osfOutAfterFall = ti.readBoolean(osfOut);

        check("OSF", "output bit pulses 1 scan on falling edge and count=1 (fall=" + osfOutOnFall + ", after=" + osfOutAfterFall + ", cnt=" + (long) ti.readDouble(osfCnt) + ")",
                osfOutOnFall && !osfOutAfterFall && Math.abs(ti.readDouble(osfCnt) - 1.0) < 1e-6);
    }

    private void testCompareInstructions() {
        TagImageManager ti = new TagImageManager(null, null);
        double a = rndVal(10, 50);
        double b = a + rndVal(5, 25); // strictly b > a
        double mid = a + 2.0;

        String tA = rndTag("ValA"), tB = rndTag("ValB"), tMid = rndTag("ValMid");
        String oEquT = rndTag("EquT"), oEquF = rndTag("EquF");
        String oNeqT = rndTag("NeqT"), oNeqF = rndTag("NeqF");
        String oGrtT = rndTag("GrtT"), oGrtF = rndTag("GrtF");
        String oGeqT = rndTag("GeqT"), oGeqF = rndTag("GeqF");
        String oLesT = rndTag("LesT"), oLesF = rndTag("LesF");
        String oLeqT = rndTag("LeqT"), oLeqF = rndTag("LeqF");
        String oLimNormT = rndTag("LimNormT"), oLimNormF = rndTag("LimNormF");
        String oLimWrapT = rndTag("LimWrapT"), oLimWrapF = rndTag("LimWrapF");

        ti.registerTag(tA, a, "REAL", "");
        ti.registerTag(tB, b, "REAL", "");
        ti.registerTag(tMid, mid, "REAL", "");
        for (String o : new String[]{oEquT, oEquF, oNeqT, oNeqF, oGrtT, oGrtF, oGeqT, oGeqF, oLesT, oLesF, oLeqT, oLeqF, oLimNormT, oLimNormF, oLimWrapT, oLimWrapF}) {
            ti.registerTag(o, false, "BOOL", "");
        }

        LadderProgram prog = new LadderProgram("Val_Compare", "Compare Validation", true);
        LadderRoutine main = new LadderRoutine("MainRoutine", "MAIN", "");
        main.rungs.add(new LadderRung(0, "EQU true", "EQU " + tA + " " + a + " OTE " + oEquT));
        main.rungs.add(new LadderRung(1, "EQU false", "EQU " + tA + " " + tB + " OTE " + oEquF));
        main.rungs.add(new LadderRung(2, "NEQ true", "NEQ " + tA + " " + tB + " OTE " + oNeqT));
        main.rungs.add(new LadderRung(3, "NEQ false", "NEQ " + tA + " " + a + " OTE " + oNeqF));
        main.rungs.add(new LadderRung(4, "GRT true", "GRT " + tB + " " + tA + " OTE " + oGrtT));
        main.rungs.add(new LadderRung(5, "GRT false", "GRT " + tA + " " + tA + " OTE " + oGrtF));
        main.rungs.add(new LadderRung(6, "GEQ true (eq)", "GEQ " + tA + " " + a + " OTE " + oGeqT));
        main.rungs.add(new LadderRung(7, "GEQ false", "GEQ " + tA + " " + tB + " OTE " + oGeqF));
        main.rungs.add(new LadderRung(8, "LES true", "LES " + tA + " " + tB + " OTE " + oLesT));
        main.rungs.add(new LadderRung(9, "LES false", "LES " + tB + " " + tB + " OTE " + oLesF));
        main.rungs.add(new LadderRung(10, "LEQ true (eq)", "LEQ " + tB + " " + b + " OTE " + oLeqT));
        main.rungs.add(new LadderRung(11, "LEQ false", "LEQ " + tB + " " + tA + " OTE " + oLeqF));
        main.rungs.add(new LadderRung(12, "LIM normal true", "LIM " + tA + " " + tMid + " " + tB + " OTE " + oLimNormT));
        main.rungs.add(new LadderRung(13, "LIM normal false", "LIM " + tMid + " " + tA + " " + tB + " OTE " + oLimNormF));
        main.rungs.add(new LadderRung(14, "LIM wraparound true (low>high)", "LIM " + tB + " " + tA + " " + tMid + " OTE " + oLimWrapT));
        main.rungs.add(new LadderRung(15, "LIM wraparound false (low>high)", "LIM " + tB + " " + tMid + " " + tA + " OTE " + oLimWrapF));
        prog.routines.put(main.name, main);

        CyclicScanEngine eng = createFreshEngine(ti, prog);
        eng.executeSingleScanSafely();

        check("EQU", "a==a true & a==b false", ti.readBoolean(oEquT) && !ti.readBoolean(oEquF));
        check("NEQ", "a!=b true & a!=a false", ti.readBoolean(oNeqT) && !ti.readBoolean(oNeqF));
        check("GRT", "b>a true & a>a false", ti.readBoolean(oGrtT) && !ti.readBoolean(oGrtF));
        check("GEQ", "a>=a true & a>=b false", ti.readBoolean(oGeqT) && !ti.readBoolean(oGeqF));
        check("LES", "a<b true & b<b false", ti.readBoolean(oLesT) && !ti.readBoolean(oLesF));
        check("LEQ", "b<=b true & b<=a false", ti.readBoolean(oLeqT) && !ti.readBoolean(oLeqF));
        check("LIM", "normal and wrap-around limits",
                ti.readBoolean(oLimNormT) && !ti.readBoolean(oLimNormF)
                        && ti.readBoolean(oLimWrapT) && !ti.readBoolean(oLimWrapF));
    }

    private void testMoveClearMath() {
        TagImageManager ti = new TagImageManager(null, null);
        double x = rndVal(15, 95);
        double y = rndVal(3, 19);

        String en = rndTag("MathEn");
        String tX = rndTag("X"), tY = rndTag("Y");
        String dMov = rndTag("MovDst"), dClrNum = rndTag("ClrNum"), dClrBool = rndTag("ClrBool");
        String dAdd = rndTag("AddDst"), dSub = rndTag("SubDst"), dMul = rndTag("MulDst"), dDiv = rndTag("DivDst"), dDiv0 = rndTag("Div0Dst");

        ti.registerTag(en, true, "BOOL", "");
        ti.registerTag(tX, x, "REAL", "");
        ti.registerTag(tY, y, "REAL", "");
        ti.registerTag(dMov, 0.0, "REAL", "");
        ti.registerTag(dClrNum, 777L, "DINT", "");
        ti.registerTag(dClrBool, true, "BOOL", "");
        ti.registerTag(dAdd, 0.0, "REAL", "");
        ti.registerTag(dSub, 0.0, "REAL", "");
        ti.registerTag(dMul, 0.0, "REAL", "");
        ti.registerTag(dDiv, 0.0, "REAL", "");
        ti.registerTag(dDiv0, 42.0, "REAL", "");

        LadderProgram prog = new LadderProgram("Val_Math", "MOV/CLR/Math Validation", true);
        LadderRoutine main = new LadderRoutine("MainRoutine", "MAIN", "");
        main.rungs.add(new LadderRung(0, "MOV", "XIC " + en + " MOV " + tX + " " + dMov));
        main.rungs.add(new LadderRung(1, "CLR num", "XIC " + en + " CLR " + dClrNum));
        main.rungs.add(new LadderRung(2, "CLR bool", "XIC " + en + " CLR " + dClrBool));
        main.rungs.add(new LadderRung(3, "ADD", "XIC " + en + " ADD " + tX + " " + tY + " " + dAdd));
        main.rungs.add(new LadderRung(4, "SUB", "XIC " + en + " SUB " + tX + " " + tY + " " + dSub));
        main.rungs.add(new LadderRung(5, "MUL", "XIC " + en + " MUL " + tX + " " + tY + " " + dMul));
        main.rungs.add(new LadderRung(6, "DIV", "XIC " + en + " DIV " + tX + " " + tY + " " + dDiv));
        main.rungs.add(new LadderRung(7, "DIV by zero guard", "XIC " + en + " DIV " + tX + " 0 " + dDiv0));
        prog.routines.put(main.name, main);

        CyclicScanEngine eng = createFreshEngine(ti, prog);
        eng.executeSingleScanSafely();

        check("MOV", x + " -> " + ti.readDouble(dMov), Math.abs(ti.readDouble(dMov) - x) < 1e-6);
        check("CLR", "clears numeric to 0 and bool to false",
                Math.abs(ti.readDouble(dClrNum)) < 1e-6 && !ti.readBoolean(dClrBool));
        check("ADD", x + " + " + y + " = " + ti.readDouble(dAdd), Math.abs(ti.readDouble(dAdd) - round4(x + y)) < 1e-4);
        check("SUB", x + " - " + y + " = " + ti.readDouble(dSub), Math.abs(ti.readDouble(dSub) - round4(x - y)) < 1e-4);
        check("MUL", x + " * " + y + " = " + ti.readDouble(dMul), Math.abs(ti.readDouble(dMul) - round4(x * y)) < 1e-4);
        check("DIV", x + " / " + y + " = " + ti.readDouble(dDiv) + " & div-by-0 safe (" + ti.readDouble(dDiv0) + ")",
                Math.abs(ti.readDouble(dDiv) - round4(x / y)) < 1e-4 && Math.abs(ti.readDouble(dDiv0) - 42.0) < 1e-6);
    }

    private void testTimers() throws Exception {
        TagImageManager ti = new TagImageManager(null, null);
        String tonEn = rndTag("TonEn"), tofEn = rndTag("TofEn"), rtoEn = rndTag("RtoEn"), rtoRes = rndTag("RtoRes");
        String tonBase = rndTag("T_TON"), tofBase = rndTag("T_TOF"), rtoBase = rndTag("T_RTO");

        ti.registerTag(tonEn, false, "BOOL", "");
        ti.registerTag(tofEn, false, "BOOL", "");
        ti.registerTag(rtoEn, false, "BOOL", "");
        ti.registerTag(rtoRes, false, "BOOL", "");

        LadderProgram prog = new LadderProgram("Val_Timers", "TON/TOF/RTO Validation", true);
        LadderRoutine main = new LadderRoutine("MainRoutine", "MAIN", "");
        main.rungs.add(new LadderRung(0, "TON 120ms", "XIC " + tonEn + " TON " + tonBase + " 120 0"));
        main.rungs.add(new LadderRung(1, "TOF 120ms", "XIC " + tofEn + " TOF " + tofBase + " 120 0"));
        main.rungs.add(new LadderRung(2, "RTO 120ms", "XIC " + rtoEn + " RTO " + rtoBase + " 120 0"));
        main.rungs.add(new LadderRung(3, "RES RTO", "XIC " + rtoRes + " RES " + rtoBase));
        prog.routines.put(main.name, main);

        CyclicScanEngine eng = createFreshEngine(ti, prog);
        eng.registerTimerTags(tonBase, 120L, "");
        eng.registerTimerTags(tofBase, 120L, "");
        eng.registerTimerTags(rtoBase, 120L, "");

        // Cold scan (all FALSE)
        eng.executeSingleScanSafely();
        check("TON", "cold false -> EN=0 TT=0 DN=0 ACC=0",
                !ti.readBoolean(tonBase + ".EN") && !ti.readBoolean(tonBase + ".TT")
                        && !ti.readBoolean(tonBase + ".DN") && ti.readDouble(tonBase + ".ACC") == 0.0);
        check("TOF", "cold false (never energized) -> EN=0 TT=0 DN=0",
                !ti.readBoolean(tofBase + ".EN") && !ti.readBoolean(tofBase + ".TT") && !ti.readBoolean(tofBase + ".DN"));

        // Energize TON, TOF, RTO
        ti.writeFromExternalUI(tonEn, true);
        ti.writeFromExternalUI(tofEn, true);
        ti.writeFromExternalUI(rtoEn, true);
        eng.executeSingleScanSafely();
        Thread.sleep(60);
        eng.executeSingleScanSafely();

        long tonMidAcc = (long) ti.readDouble(tonBase + ".ACC");
        long rtoMidAcc = (long) ti.readDouble(rtoBase + ".ACC");
        check("TON", "mid-timing EN=1 TT=1 DN=0 ACC>0 (acc=" + tonMidAcc + ")",
                ti.readBoolean(tonBase + ".EN") && ti.readBoolean(tonBase + ".TT")
                        && !ti.readBoolean(tonBase + ".DN") && tonMidAcc > 0 && tonMidAcc < 120);
        check("TOF", "energized -> EN=1 TT=0 DN=1 ACC=0",
                ti.readBoolean(tofBase + ".EN") && !ti.readBoolean(tofBase + ".TT")
                        && ti.readBoolean(tofBase + ".DN") && ti.readDouble(tofBase + ".ACC") == 0.0);

        // Pause RTO mid-way -> must retain ACC! Also de-energize TOF -> starts off-delay timing!
        ti.writeFromExternalUI(rtoEn, false);
        ti.writeFromExternalUI(tofEn, false);
        Thread.sleep(40);
        eng.executeSingleScanSafely();
        long rtoHeldAcc = (long) ti.readDouble(rtoBase + ".ACC");
        check("RTO", "retains ACC when rung goes false (mid=" + rtoMidAcc + ", held=" + rtoHeldAcc + ")",
                !ti.readBoolean(rtoBase + ".EN") && !ti.readBoolean(rtoBase + ".TT") && rtoHeldAcc == rtoMidAcc && rtoHeldAcc > 0);
        check("TOF", "off-delay timing -> EN=0 TT=1 DN=1",
                !ti.readBoolean(tofBase + ".EN") && ti.readBoolean(tofBase + ".TT") && ti.readBoolean(tofBase + ".DN"));

        // Re-enable RTO and wait past preset for TON, TOF, RTO
        ti.writeFromExternalUI(rtoEn, true);
        Thread.sleep(150);
        eng.executeSingleScanSafely();
        check("TON", "done after preset -> EN=1 TT=0 DN=1 ACC=120",
                ti.readBoolean(tonBase + ".EN") && !ti.readBoolean(tonBase + ".TT")
                        && ti.readBoolean(tonBase + ".DN") && ti.readDouble(tonBase + ".ACC") == 120.0);
        check("TOF", "expired after preset -> EN=0 TT=0 DN=0 ACC=120",
                !ti.readBoolean(tofBase + ".EN") && !ti.readBoolean(tofBase + ".TT")
                        && !ti.readBoolean(tofBase + ".DN") && ti.readDouble(tofBase + ".ACC") == 120.0);
        check("RTO", "resumed and reached DN=1 ACC=120",
                ti.readBoolean(rtoBase + ".DN") && ti.readDouble(rtoBase + ".ACC") == 120.0);

        // Reset TON via false rung; Reset RTO via RES instruction
        ti.writeFromExternalUI(tonEn, false);
        ti.writeFromExternalUI(rtoEn, false);
        ti.writeFromExternalUI(rtoRes, true);
        eng.executeSingleScanSafely();
        check("TON", "resets ACC=0 DN=0 when rung goes false",
                !ti.readBoolean(tonBase + ".DN") && ti.readDouble(tonBase + ".ACC") == 0.0);
        check("RES", "resets RTO ACC=0 DN=0",
                !ti.readBoolean(rtoBase + ".DN") && ti.readDouble(rtoBase + ".ACC") == 0.0);
    }

    private void testCountersAndRes() {
        TagImageManager ti = new TagImageManager(null, null);
        String cuTrig = rndTag("CuTrig"), cdTrig = rndTag("CdTrig"), resTrig = rndTag("CntRes");
        String cntBase = rndTag("C_Test");

        ti.registerTag(cuTrig, false, "BOOL", "");
        ti.registerTag(cdTrig, false, "BOOL", "");
        ti.registerTag(resTrig, false, "BOOL", "");

        LadderProgram prog = new LadderProgram("Val_Counters", "CTU/CTD/RES Validation", true);
        LadderRoutine main = new LadderRoutine("MainRoutine", "MAIN", "");
        main.rungs.add(new LadderRung(0, "CTU", "XIC " + cuTrig + " CTU " + cntBase + " 3 0"));
        main.rungs.add(new LadderRung(1, "CTD", "XIC " + cdTrig + " CTD " + cntBase + " 3 0"));
        main.rungs.add(new LadderRung(2, "RES", "XIC " + resTrig + " RES " + cntBase));
        prog.routines.put(main.name, main);

        CyclicScanEngine eng = createFreshEngine(ti, prog);
        eng.registerCounterTags(cntBase, 3L, "");

        // 3 rising edges on CTU (each held for 2 scans to confirm edge-triggering!)
        for (int i = 0; i < 3; i++) {
            ti.writeFromExternalUI(cuTrig, true);
            eng.executeSingleScanSafely();
            eng.executeSingleScanSafely(); // must NOT double-count
            ti.writeFromExternalUI(cuTrig, false);
            eng.executeSingleScanSafely();
        }
        check("CTU", "3 rising edges -> ACC=3, DN=true (acc=" + (long) ti.readDouble(cntBase + ".ACC") + ")",
                ti.readDouble(cntBase + ".ACC") == 3.0 && ti.readBoolean(cntBase + ".DN"));

        // 1 rising edge on CTD (held 2 scans) -> ACC=2, DN=false
        ti.writeFromExternalUI(cdTrig, true);
        eng.executeSingleScanSafely();
        eng.executeSingleScanSafely();
        ti.writeFromExternalUI(cdTrig, false);
        eng.executeSingleScanSafely();
        check("CTD", "1 rising edge -> ACC=2, DN=false (acc=" + (long) ti.readDouble(cntBase + ".ACC") + ")",
                ti.readDouble(cntBase + ".ACC") == 2.0 && !ti.readBoolean(cntBase + ".DN"));

        // RES counter via RES instruction
        ti.writeFromExternalUI(resTrig, true);
        eng.executeSingleScanSafely();
        check("RES", "resets counter ACC=0 DN=0",
                ti.readDouble(cntBase + ".ACC") == 0.0 && !ti.readBoolean(cntBase + ".DN"));

        // Count up again, then reset via counter's .RES bit directly
        ti.writeFromExternalUI(resTrig, false);
        for (int i = 0; i < 3; i++) {
            ti.writeFromExternalUI(cuTrig, true);
            eng.executeSingleScanSafely();
            ti.writeFromExternalUI(cuTrig, false);
            eng.executeSingleScanSafely();
        }
        ti.writeFromExternalUI(cntBase + ".RES", true);
        eng.executeSingleScanSafely();
        check("CTU_RES", ".RES bit resets counter ACC=0 DN=0",
                ti.readDouble(cntBase + ".ACC") == 0.0 && !ti.readBoolean(cntBase + ".DN"));

        // Bumpless Assemble & Save test: hold rungIn=true, recompile routine via upsertRoutine, verify ACC does NOT spurious-increment!
        ti.writeFromExternalUI(cntBase + ".RES", false);
        ti.writeFromExternalUI(cuTrig, true);
        eng.executeSingleScanSafely();
        long accBeforeAssemble = (long) ti.readDouble(cntBase + ".ACC");
        List<Map<String, Object>> recompiledRungs = new ArrayList<>();
        recompiledRungs.add(Map.of("comment", "CTU", "mnemonic", "XIC " + cuTrig + " CTU " + cntBase + " 3 0"));
        eng.upsertRoutine("MainRoutine", "MAIN", "", recompiledRungs);
        eng.executeSingleScanSafely();
        long accAfterAssemble = (long) ti.readDouble(cntBase + ".ACC");
        check("CTU_BUMPLESS", "Assemble & Save while rungIn=TRUE does not increment ACC (before=" + accBeforeAssemble + ", after=" + accAfterAssemble + ")",
                accBeforeAssemble == 1L && accAfterAssemble == 1L);
    }

    private void testSubroutinesJsrSbrRet() {
        TagImageManager ti = new TagImageManager(null, null);
        double paramVal = rndVal(10, 90);
        String callEn = rndTag("CallEn"), earlyRetEn = rndTag("EarlyRetEn");
        String srcParam = rndTag("SrcParam"), rxParam = rndTag("RxParam");
        String afterRetFlag = rndTag("AfterRetFlag");

        ti.registerTag(callEn, true, "BOOL", "");
        ti.registerTag(earlyRetEn, true, "BOOL", "");
        ti.registerTag(srcParam, paramVal, "REAL", "");
        ti.registerTag(rxParam, 0.0, "REAL", "");
        ti.registerTag(afterRetFlag, false, "BOOL", "");

        LadderProgram prog = new LadderProgram("Val_Sub", "JSR/SBR/RET Validation", true);
        LadderRoutine main = new LadderRoutine("MainRoutine", "MAIN", "");
        main.rungs.add(new LadderRung(0, "JSR with param", "XIC " + callEn + " JSR(Sub_Worker," + srcParam + ")"));
        prog.routines.put(main.name, main);

        LadderRoutine sub = new LadderRoutine("Sub_Worker", "SUBROUTINE", "");
        sub.rungs.add(new LadderRung(0, "SBR receive param", "SBR(" + rxParam + ")"));
        sub.rungs.add(new LadderRung(1, "Conditional early RET", "XIC " + earlyRetEn + " RET"));
        sub.rungs.add(new LadderRung(2, "After RET rung", "OTL " + afterRetFlag));
        prog.routines.put(sub.name, sub);

        CyclicScanEngine eng = createFreshEngine(ti, prog);
        eng.executeSingleScanSafely();

        check("JSR/SBR", "passes parameter " + paramVal + " -> received " + ti.readDouble(rxParam),
                Math.abs(ti.readDouble(rxParam) - paramVal) < 1e-6);
        check("RET", "early conditional RET skips trailing rung", !ti.readBoolean(afterRetFlag));

        // Disable early RET -> trailing rung must execute!
        ti.writeFromExternalUI(earlyRetEn, false);
        eng.executeSingleScanSafely();
        check("RET", "false RET allows trailing rung to execute", ti.readBoolean(afterRetFlag));
    }

    private void testJmpLbl() {
        TagImageManager ti = new TagImageManager(null, null);
        String doJump = rndTag("DoJump");
        String skippedFlag = rndTag("SkippedFlag"), afterLblFlag = rndTag("AfterLblFlag");

        ti.registerTag(doJump, true, "BOOL", "");
        ti.registerTag(skippedFlag, false, "BOOL", "");
        ti.registerTag(afterLblFlag, false, "BOOL", "");

        LadderProgram prog = new LadderProgram("Val_JmpLbl", "JMP/LBL Validation", true);
        LadderRoutine main = new LadderRoutine("MainRoutine", "MAIN", "");
        main.rungs.add(new LadderRung(0, "JMP", "XIC " + doJump + " JMP SkipZone"));
        main.rungs.add(new LadderRung(1, "Middle rung", "OTL " + skippedFlag));
        main.rungs.add(new LadderRung(2, "LBL + after", "LBL SkipZone OTL " + afterLblFlag));
        prog.routines.put(main.name, main);

        CyclicScanEngine eng = createFreshEngine(ti, prog);
        eng.executeSingleScanSafely();
        check("JMP/LBL", "skips middle rung and lands on LBL",
                !ti.readBoolean(skippedFlag) && ti.readBoolean(afterLblFlag));

        ti.writeFromExternalUI(doJump, false);
        eng.executeSingleScanSafely();
        check("JMP/LBL", "executes middle rung when JMP condition is false", ti.readBoolean(skippedFlag));
    }

    private void testAoi() {
        TagImageManager ti = new TagImageManager(null, null);
        String aoiEn = rndTag("AoiEn");
        String instBase = rndTag("MotorInst");

        ti.registerTag(aoiEn, true, "BOOL", "");
        ti.registerTag(instBase + ".AutoCmd", true, "BOOL", "");
        ti.registerTag(instBase + ".Permissive", true, "BOOL", "");
        ti.registerTag(instBase + ".Faulted", false, "BOOL", "");
        ti.registerTag(instBase + ".MotorOut", false, "BOOL", "");
        ti.registerTag(instBase + ".EnableIn", false, "BOOL", "");
        ti.registerTag(instBase + ".EnableOut", false, "BOOL", "");

        LadderProgram prog = new LadderProgram("Val_AOI", "AOI Validation", true);
        LadderRoutine main = new LadderRoutine("MainRoutine", "MAIN", "");
        main.rungs.add(new LadderRung(0, "Invoke Motor_HOA AOI", "XIC " + aoiEn + " AOI Motor_HOA " + instBase));
        prog.routines.put(main.name, main);

        CyclicScanEngine eng = createFreshEngine(ti, prog);
        eng.executeSingleScanSafely();
        check("AOI", "Motor_HOA energizes MotorOut & EnableOut when inputs valid",
                ti.readBoolean(instBase + ".EnableIn") && ti.readBoolean(instBase + ".EnableOut")
                        && ti.readBoolean(instBase + ".MotorOut"));

        // Inject Faulted = true -> MotorOut must turn FALSE
        ti.writeFromExternalUI(instBase + ".Faulted", true);
        eng.executeSingleScanSafely();
        check("AOI", "Motor_HOA de-energizes MotorOut when Faulted=true", !ti.readBoolean(instBase + ".MotorOut"));
    }

    private void testPid() throws Exception {
        TagImageManager ti = new TagImageManager(null, null);
        String en = rndTag("PidEn"), pv = rndTag("PidPV"), sp = rndTag("PidSP"), cv = rndTag("PidCV");
        ti.registerTag(en, true, "BOOL", "");
        ti.registerTag(pv, 40.0, "REAL", "");
        ti.registerTag(sp, 60.0, "REAL", "");
        ti.registerTag(cv, 0.0, "REAL", "");

        LadderProgram prog = new LadderProgram("Val_PID", "PID Validation", true);
        LadderRoutine main = new LadderRoutine("MainRoutine", "MAIN", "");
        // Error = 20, Kp = 2.0 -> P = 40.0; Ki = 1.0 accumulates over time; Kd = 0.0
        main.rungs.add(new LadderRung(0, "PID Loop", "XIC " + en + " PID " + pv + " " + sp + " " + cv + " 2.0 1.0 0.0"));
        prog.routines.put(main.name, main);

        CyclicScanEngine eng = createFreshEngine(ti, prog);
        eng.executeSingleScanSafely();
        double cv1 = ti.readDouble(cv);
        check("PID", "computes P+I output in (0..100] when SP>PV (cv1=" + cv1 + ")", cv1 >= 40.0 && cv1 <= 100.0);

        Thread.sleep(60);
        eng.executeSingleScanSafely();
        double cv2 = ti.readDouble(cv);
        check("PID", "integral term increases CV over time (cv1=" + cv1 + ", cv2=" + cv2 + ")", cv2 > cv1 && cv2 <= 100.0);

        // Raise PV above SP -> CV must immediately decrease!
        ti.writeFromExternalUI(pv, 70.0);
        eng.executeSingleScanSafely();
        double cv3 = ti.readDouble(cv);
        check("PID", "CV drops immediately when PV exceeds SP (cv2=" + cv2 + ", cv3=" + cv3 + ")", cv3 < cv2);

        // Disable rung -> CV resets to 0.0
        ti.writeFromExternalUI(en, false);
        eng.executeSingleScanSafely();
        check("PID", "resets CV=0 when rung goes false (cv=" + ti.readDouble(cv) + ")", Math.abs(ti.readDouble(cv)) < 1e-6);

        // Also test UDT-backed 4-operand PID: PID [LadderPLC]LadderLogic/PIDs/<name> PV SP CV
        String pidUdt = "[LadderPLC]LadderLogic/PIDs/ValLoop_" + (1000 + rng.nextInt(9000));
        String cvUdtOut = rndTag("PidUdtCV");
        ti.registerTag(cvUdtOut, 0.0, "REAL", "");
        ti.writeFromExternalUI(en, true);
        ti.writeFromExternalUI(pv, 30.0);
        ti.writeFromExternalUI(sp, 50.0);

        LadderProgram prog2 = new LadderProgram("Val_PID_UDT", "PID UDT Validation", true);
        LadderRoutine main2 = new LadderRoutine("MainRoutine", "MAIN", "");
        main2.rungs.add(new LadderRung(0, "UDT PID", "XIC " + en + " PID " + pidUdt + " " + pv + " " + sp + " " + cvUdtOut));
        prog2.routines.put(main2.name, main2);

        CyclicScanEngine eng2 = createFreshEngine(ti, prog2);
        eng2.registerPidTags(pidUdt, "Validation PID UDT");
        ti.writeFromExternalUI(pidUdt + "/KP", 2.0);
        ti.writeFromExternalUI(pidUdt + "/KI", 0.0);
        eng2.executeSingleScanSafely();
        double udtCv = ti.readDouble(cvUdtOut);
        double udtMemberCv = ti.readDouble(pidUdt + "/CV");
        check("PID_UDT", "4-op UDT PID computes CV=40.0 and updates UDT members (out=" + udtCv + ", udt=" + udtMemberCv + ")",
                Math.abs(udtCv - 40.0) < 1e-4 && Math.abs(udtMemberCv - 40.0) < 1e-4 && ti.readBoolean(pidUdt + "/EN"));

        // Test Digital/Relay PID (PIDD) with anti-chatter minimum switch delay
        String piddUdt = "[LadderPLC]LadderLogic/PIDs/ValDig_" + (1000 + rng.nextInt(9000));
        String relayOut = rndTag("PiddRelayOut");
        ti.registerTag(relayOut, false, "BOOL", "");
        ti.writeFromExternalUI(en, true);
        ti.writeFromExternalUI(pv, 30.0);
        ti.writeFromExternalUI(sp, 80.0);

        LadderProgram prog3 = new LadderProgram("Val_PIDD", "Digital PID Validation", true);
        LadderRoutine main3 = new LadderRoutine("MainRoutine", "MAIN", "");
        main3.rungs.add(new LadderRung(0, "Digital PID", "XIC " + en + " PIDD " + piddUdt + " " + pv + " " + sp + " " + relayOut));
        prog3.routines.put(main3.name, main3);

        CyclicScanEngine eng3 = createFreshEngine(ti, prog3);
        eng3.registerPidTags(piddUdt, "Validation Digital PID");
        ti.writeFromExternalUI(piddUdt + "/KP", 2.0);
        ti.writeFromExternalUI(piddUdt + "/KI", 0.0);
        ti.writeFromExternalUI(piddUdt + "/CYCLE_MS", 500L);
        ti.writeFromExternalUI(piddUdt + "/MIN_SW_MS", 120L);
        eng3.executeSingleScanSafely();
        check("PIDD", "energizes digital relay ON when SP>PV (out=" + ti.readBoolean(relayOut) + ")",
                ti.readBoolean(relayOut) && ti.readBoolean(piddUdt + "/OUT"));

        // Reverse error immediately -> anti-chatter dwell must keep relay ON until MIN_SW_MS elapses
        ti.writeFromExternalUI(pv, 90.0);
        Thread.sleep(30);
        eng3.executeSingleScanSafely();
        check("PIDD", "anti-chatter delay holds relay ON before MIN_SW_MS elapses",
                ti.readBoolean(relayOut));

        // After MIN_SW_MS passes -> relay turns OFF
        Thread.sleep(130);
        eng3.executeSingleScanSafely();
        check("PIDD", "turns relay OFF once MIN_SW_MS delay elapses",
                !ti.readBoolean(relayOut) && !ti.readBoolean(piddUdt + "/OUT"));

        // Verify Timer .DN energizes right side of rung (trailing OTE)
        String tDnEn = rndTag("TDnEn"), tDnBase = rndTag("T_DN_OUT"), tDnCoil = rndTag("TDnCoil");
        ti.registerTag(tDnEn, true, "BOOL", "");
        ti.registerTag(tDnCoil, false, "BOOL", "");
        LadderProgram prog4 = new LadderProgram("Val_TimerPowerOut", "Timer DN PowerOut", true);
        LadderRoutine main4 = new LadderRoutine("MainRoutine", "MAIN", "");
        main4.rungs.add(new LadderRung(0, "TON -> OTE", "XIC " + tDnEn + " TON " + tDnBase + " 80 0 OTE " + tDnCoil));
        prog4.routines.put(main4.name, main4);
        CyclicScanEngine eng4 = createFreshEngine(ti, prog4);
        eng4.registerTimerTags(tDnBase, 80L, "");
        eng4.executeSingleScanSafely();
        boolean beforeDn = ti.readBoolean(tDnCoil);
        Thread.sleep(100);
        eng4.executeSingleScanSafely();
        boolean afterDn = ti.readBoolean(tDnCoil);
        check("TON_DN", "right-side coil energizes only when TON.DN turns true (before=" + beforeDn + ", after=" + afterDn + ")",
                !beforeDn && afterDn);
    }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : System.currentTimeMillis();
        System.out.println("Running Randomized All-Instructions Validation Suite (seed=" + seed + ")...");
        InstructionValidationSuite suite = new InstructionValidationSuite(seed);
        boolean ok = suite.runAll();
        if (!ok) {
            System.exit(1);
        }
    }
}
