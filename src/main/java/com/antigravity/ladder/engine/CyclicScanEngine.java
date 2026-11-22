package com.antigravity.ladder.engine;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic Industrial PLC Cyclic Scan Engine.
 * Supports multiple independent Programs (each with Enable/Disable, Rename, Delete,
 * MainRoutine, and Subroutines called via JSR/SBR/RET) on an isolated thread
 * with hardware-style Watchdog protection and dirty-write output flushing.
 */
public class CyclicScanEngine {

    private static final Logger logger = LoggerFactory.getLogger(CyclicScanEngine.class);

    public enum ControllerMode {
        RUN,
        PROG,
        FAULT
    }

    public static final int MAX_JSR_DEPTH = 16;
    public static final int MAX_JMP_LOOPS = 500;
    public static final long ACC_FLUSH_INTERVAL_MS = 500L;

    private final TagImageManager tagImage;
    private final UdtTimerCounterManager udtManager;
    private final File storageFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, LadderProgram> programs = new ConcurrentHashMap<>();
    private final Map<String, AoiDefinition> aois = new ConcurrentHashMap<>();
    private volatile String activeProgramName = "MainProgram";
    private volatile String mainRoutineName = "MainRoutine";

    private volatile ControllerMode mode = ControllerMode.RUN;
    private volatile boolean firstScanPending = true;
    private volatile long scanRateMs = 50L;
    private volatile long watchdogMs = 500L;
    private volatile String faultMessage = "No Faults";

    // Scan telemetry
    private final AtomicLong scanCount = new AtomicLong(0);
    private volatile double lastScanMs = 0.0;
    private volatile double maxScanMs = 0.0;
    private volatile double avgScanMs = 0.0;

    private final ThreadLocal<Boolean> subroutineReturnedEarly = ThreadLocal.withInitial(() -> false);

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> scanTask;
    private volatile long scanThreadId = -1L;

    public CyclicScanEngine(TagImageManager tagImage, File storageFile) {
        this.tagImage = tagImage;
        this.udtManager = new UdtTimerCounterManager(tagImage.getGatewayTagManager(), tagImage);
        this.storageFile = storageFile;
        initializeSystemTags();
        initializeDefaultAois();
        if (udtManager.isAvailable()) {
            udtManager.ensureUdtDefinitionsAndFolders();
        }
        if (storageFile != null && storageFile.exists()) {
            loadProjectFromDisk();
        } else if (storageFile == null) {
            loadDefaultIndustrialProject();
        } else {
            loadEmptyProject();
            saveProjectToDisk();
        }
    }

    public synchronized void loadEmptyProject() {
        programs.clear();
        LadderProgram mainProg = new LadderProgram("MainProgram", "Primary PLC Program", true);
        LadderRoutine main = new LadderRoutine("MainRoutine", "MAIN", "Main Program Routine");
        main.rungs.add(new LadderRung(0, "Rung 0", ""));
        main.reindexAndCompile();
        mainProg.routines.put(main.name, main);
        programs.put(mainProg.name, mainProg);
        this.activeProgramName = mainProg.name;
    }

    public UdtTimerCounterManager getUdtManager() {
        return udtManager;
    }

    private void initializeSystemTags() {
        tagImage.registerTag("[LadderPLC]System/Mode", "RUN", "STRING", "Controller Operating Mode (RUN, PROG, FAULT)");
        tagImage.registerTag("[LadderPLC]System/ScanCount", 0L, "DINT", "Total Completed PLC Scan Cycles");
        tagImage.registerTag("[LadderPLC]System/LastScanMs", 0.0, "REAL", "Last Scan Execution Time (ms)");
        tagImage.registerTag("[LadderPLC]System/MaxScanMs", 0.0, "REAL", "Maximum Recorded Scan Time (ms)");
        tagImage.registerTag("[LadderPLC]System/AvgScanMs", 0.0, "REAL", "Rolling Average Scan Time (ms)");
        tagImage.registerTag("[LadderPLC]System/ScanRateMs", 50L, "DINT", "Configured Periodic Task Scan Rate (ms)");
        tagImage.registerTag("[LadderPLC]System/WatchdogMs", 500L, "DINT", "Watchdog Fault Threshold (ms)");
        tagImage.registerTag("[LadderPLC]System/FaultMessage", "No Faults", "STRING", "Active Major/Minor Fault Diagnostic");
        tagImage.registerTag("[LadderPLC]System/DirtyWritesTotal", 0L, "DINT", "Cumulative Dirty Tag Writes Flushed");
        tagImage.registerTag("[LadderPLC]System/S_FS", true, "BOOL", "First Scan Bit (S:FS) - TRUE on first scan in RUN");
    }

    private void initializeDefaultAois() {
        AoiDefinition motorHoa = new AoiDefinition("Motor_HOA", "Standard Hand/Off/Auto Motor Starter AOI");
        motorHoa.params.add(new AoiDefinition.AoiParam("AutoCmd", "BOOL", "INPUT"));
        motorHoa.params.add(new AoiDefinition.AoiParam("Permissive", "BOOL", "INPUT"));
        motorHoa.params.add(new AoiDefinition.AoiParam("Faulted", "BOOL", "INPUT"));
        motorHoa.params.add(new AoiDefinition.AoiParam("MotorOut", "BOOL", "OUTPUT"));
        motorHoa.rungs.add(new LadderRung(0, "AOI Motor Interlock Logic", "XIC AutoCmd XIC Permissive XIO Faulted OTE MotorOut"));
        motorHoa.reindexAndCompile();
        aois.put(motorHoa.name, motorHoa);
        if (udtManager != null && udtManager.isAvailable()) {
            udtManager.ensureAoiUdtDefinition(motorHoa);
        }
    }

    public void loadDefaultIndustrialProject() {
        programs.clear();

        // Register default Controller Tags under [LadderPLC]ControllerTags/...
        tagImage.registerTag("[LadderPLC]ControllerTags/Auto_Mode", true, "BOOL", "Master Auto Mode Enable");
        tagImage.registerTag("[LadderPLC]ControllerTags/Start_PB", false, "BOOL", "Pump 1 Momentary Start Pushbutton");
        tagImage.registerTag("[LadderPLC]ControllerTags/Stop_PB", false, "BOOL", "Pump 1 Stop Pushbutton (Normally Open)");
        tagImage.registerTag("[LadderPLC]ControllerTags/Pump1_Run", false, "BOOL", "Pump 1 Contactor Output Coil");
        tagImage.registerTag("[LadderPLC]ControllerTags/Pump1_Ready", true, "BOOL", "Pump 1 VFD Ready Permissive");
        tagImage.registerTag("[LadderPLC]ControllerTags/Drain_Valve", false, "BOOL", "Tank Bottom Drain Solenoid Valve");
        tagImage.registerTag("[LadderPLC]ControllerTags/Tank_Level", 35.0, "REAL", "Storage Tank Level (0.0 to 100.0 %)");
        tagImage.registerTag("[LadderPLC]ControllerTags/High_Level_SP", 85.0, "REAL", "High Level Trip Setpoint (%)");
        tagImage.registerTag("[LadderPLC]ControllerTags/Low_Level_SP", 15.0, "REAL", "Low Level Auto-Start Setpoint (%)");
        tagImage.registerTag("[LadderPLC]ControllerTags/HighLevel_Alarm", false, "BOOL", "Latched High Tank Level Alarm");
        tagImage.registerTag("[LadderPLC]ControllerTags/LowLevel_Alarm", false, "BOOL", "Active Low Tank Level Warning");
        tagImage.registerTag("[LadderPLC]ControllerTags/Alarm_Reset_PB", false, "BOOL", "Operator Alarm Acknowledge & Reset");
        tagImage.registerTag("[LadderPLC]ControllerTags/Pump_Start_Count", 0L, "DINT", "Total Pump Starts Counter");

        // Timer & Counter structured tags (real Ignition UDTs when Gateway TagProvider [default] is available)
        boolean useUdts = udtManager != null && udtManager.isAvailable();
        String pumpRunTimer = useUdts ? UdtTimerCounterManager.TIMERS_FOLDER_PATH + "/Pump_Run_Timer" : "[LadderPLC]ControllerTags/Pump_Run_Timer";
        String heartbeatTimer = useUdts ? UdtTimerCounterManager.TIMERS_FOLDER_PATH + "/Heartbeat_Timer" : "[LadderPLC]ControllerTags/Heartbeat_Timer";
        String pumpStartsCtu = useUdts ? UdtTimerCounterManager.COUNTERS_FOLDER_PATH + "/Pump_Starts_CTU" : "[LadderPLC]ControllerTags/Pump_Starts_CTU";

        registerTimerTags(pumpRunTimer, 5000L, "Pump Warmup On-Delay Timer (ms)");
        registerTimerTags(heartbeatTimer, 1000L, "1Hz System Heartbeat Oscillator Timer");
        registerCounterTags(pumpStartsCtu, 100L, "Pump Maintenance Cycle Counter");
        tagImage.registerTag("[LadderPLC]ControllerTags/Pump_Pressurized", false, "BOOL", "Pump Discharge Pressure Established (.DN)");
        tagImage.registerTag("[LadderPLC]ControllerTags/Heartbeat_Bit", false, "BOOL", "PLC Scan Heartbeat Pulse");

        LadderProgram mainProg = new LadderProgram("MainProgram", "Primary Plant Pump & Tank Control Program", true);
        mainProg.routines.clear();

        // 1. MainRoutine (Dispatcher calling subroutines via JSR)
        LadderRoutine main = new LadderRoutine("MainRoutine", "MAIN", "Primary Periodic Task Dispatcher - Executes every scan and calls plant area subroutines via JSR");
        main.rungs.add(new LadderRung(0,
                "Rung 0: 1Hz System Heartbeat Generator & Scan Pulse",
                "XIO " + TagImageManager.memberPath(heartbeatTimer, "DN") + " TON " + heartbeatTimer + " 1000 0"));
        main.rungs.add(new LadderRung(1,
                "Rung 1: Toggle Heartbeat Bit when Heartbeat Timer reaches 500ms+",
                "GRT " + TagImageManager.memberPath(heartbeatTimer, "ACC") + " 500 OTE [LadderPLC]ControllerTags/Heartbeat_Bit"));
        main.rungs.add(new LadderRung(2,
                "Rung 2: Unconditional Jump to Subroutine -> Sub_PumpControl (Motor Start/Stop & Timers)",
                "JSR Sub_PumpControl"));
        main.rungs.add(new LadderRung(3,
                "Rung 3: Conditional Jump to Subroutine -> Sub_TankSimulation (Only scanned when Auto_Mode is TRUE)",
                "XIC [LadderPLC]ControllerTags/Auto_Mode JSR Sub_TankSimulation"));
        main.rungs.add(new LadderRung(4,
                "Rung 4: Unconditional Jump to Subroutine -> Sub_AlarmManager (Safety & Process Interlocks)",
                "JSR Sub_AlarmManager"));
        main.reindexAndCompile();
        mainProg.routines.put(main.name, main);

        // 2. Sub_PumpControl (Motor Seal-In, TON Timer, CTU Counter)
        LadderRoutine pumpSub = new LadderRoutine("Sub_PumpControl", "SUBROUTINE", "Pump 101 Start/Stop Seal-In Logic, Warmup Timer (TON), and Start Counter (CTU)");
        pumpSub.rungs.add(new LadderRung(0,
                "Rung 0: Pump 1 Start/Stop Seal-In Circuit with High-Level Interlock & Auto Low-Level Start",
                "BST XIC [LadderPLC]ControllerTags/Start_PB NXB XIC [LadderPLC]ControllerTags/Pump1_Run NXB XIC [LadderPLC]ControllerTags/Auto_Mode LES [LadderPLC]ControllerTags/Tank_Level [LadderPLC]ControllerTags/Low_Level_SP BND XIO [LadderPLC]ControllerTags/Stop_PB XIO [LadderPLC]ControllerTags/HighLevel_Alarm XIC [LadderPLC]ControllerTags/Pump1_Ready OTE [LadderPLC]ControllerTags/Pump1_Run"));
        pumpSub.rungs.add(new LadderRung(1,
                "Rung 1: Pump Discharge Pressure Delay Timer (TON - 5.0s Preset)",
                "XIC [LadderPLC]ControllerTags/Pump1_Run TON " + pumpRunTimer + " 5000 0"));
        pumpSub.rungs.add(new LadderRung(2,
                "Rung 2: Energize Pump_Pressurized Status when Pump_Run_Timer.DN is Done",
                "XIC " + TagImageManager.memberPath(pumpRunTimer, "DN") + " OTE [LadderPLC]ControllerTags/Pump_Pressurized"));
        pumpSub.rungs.add(new LadderRung(3,
                "Rung 3: Count Total Pump Starts (CTU) for Preventive Maintenance",
                "XIC [LadderPLC]ControllerTags/Pump1_Run CTU " + pumpStartsCtu + " 100 0"));
        pumpSub.rungs.add(new LadderRung(4,
                "Rung 4: Return from Subroutine back to MainRoutine",
                "RET"));
        pumpSub.reindexAndCompile();
        mainProg.routines.put(pumpSub.name, pumpSub);

        // 3. Sub_TankSimulation (Analog Math ADD/SUB/LIM)
        LadderRoutine tankSub = new LadderRoutine("Sub_TankSimulation", "SUBROUTINE", "Analog Tank Level Process Simulation using ADD, SUB, and Compare instructions");
        tankSub.rungs.add(new LadderRung(0,
                "Rung 0: When Pump 1 is Running and Tank < 100%, Fill Tank (+0.25% per scan)",
                "XIC [LadderPLC]ControllerTags/Pump1_Run LES [LadderPLC]ControllerTags/Tank_Level 100.0 ADD [LadderPLC]ControllerTags/Tank_Level 0.25 [LadderPLC]ControllerTags/Tank_Level"));
        tankSub.rungs.add(new LadderRung(1,
                "Rung 1: Auto-Open Drain Valve when High Level Alarm trips, Close when below Low Level SP",
                "BST XIC [LadderPLC]ControllerTags/HighLevel_Alarm NXB XIC [LadderPLC]ControllerTags/Drain_Valve GRT [LadderPLC]ControllerTags/Tank_Level [LadderPLC]ControllerTags/Low_Level_SP BND OTE [LadderPLC]ControllerTags/Drain_Valve"));
        tankSub.rungs.add(new LadderRung(2,
                "Rung 2: When Drain Valve is Open and Tank > 0%, Drain Tank (-0.30% per scan)",
                "XIC [LadderPLC]ControllerTags/Drain_Valve GRT [LadderPLC]ControllerTags/Tank_Level 0.0 SUB [LadderPLC]ControllerTags/Tank_Level 0.30 [LadderPLC]ControllerTags/Tank_Level"));
        tankSub.rungs.add(new LadderRung(3,
                "Rung 3: Return from Subroutine",
                "RET"));
        tankSub.reindexAndCompile();
        mainProg.routines.put(tankSub.name, tankSub);

        // 4. Sub_AlarmManager (OTL/OTU Latching & Interlocks)
        LadderRoutine alarmSub = new LadderRoutine("Sub_AlarmManager", "SUBROUTINE", "Tank High/Low Level Alarm Detection, Latching (OTL/OTU), and Operator Reset");
        alarmSub.rungs.add(new LadderRung(0,
                "Rung 0: Latch High Level Alarm (OTL) when Tank_Level >= High_Level_SP (85%)",
                "GEQ [LadderPLC]ControllerTags/Tank_Level [LadderPLC]ControllerTags/High_Level_SP OTL [LadderPLC]ControllerTags/HighLevel_Alarm"));
        alarmSub.rungs.add(new LadderRung(1,
                "Rung 1: Unlatch High Level Alarm (OTU) when Operator presses Alarm_Reset_PB or Level drops below 75%",
                "BST XIC [LadderPLC]ControllerTags/Alarm_Reset_PB NXB LES [LadderPLC]ControllerTags/Tank_Level 75.0 BND OTU [LadderPLC]ControllerTags/HighLevel_Alarm"));
        alarmSub.rungs.add(new LadderRung(2,
                "Rung 2: Low Level Warning Output (OTE) when Tank_Level <= Low_Level_SP (15%)",
                "LEQ [LadderPLC]ControllerTags/Tank_Level [LadderPLC]ControllerTags/Low_Level_SP OTE [LadderPLC]ControllerTags/LowLevel_Alarm"));
        alarmSub.rungs.add(new LadderRung(3,
                "Rung 3: Return from Subroutine",
                "RET"));
        alarmSub.reindexAndCompile();
        mainProg.routines.put(alarmSub.name, alarmSub);

        programs.put(mainProg.name, mainProg);
        activeProgramName = mainProg.name;

        refreshAllTagSubscriptions();
    }

    public LadderProgram getActiveProgram() {
        LadderProgram p = programs.get(activeProgramName);
        if (p == null && !programs.isEmpty()) {
            p = programs.values().iterator().next();
            activeProgramName = p.name;
        }
        if (p == null) {
            p = new LadderProgram("MainProgram", "Default Ladder Program", true);
            programs.put(p.name, p);
            activeProgramName = p.name;
        }
        return p;
    }

    public synchronized void setActiveProgram(String name) {
        if (name != null && programs.containsKey(name)) {
            this.activeProgramName = name;
        }
    }

    public synchronized LadderProgram createProgram(String name, String description, boolean enabled) {
        String clean = name != null ? name.trim() : "";
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Program name cannot be empty");
        }
        LadderProgram prog = programs.computeIfAbsent(clean, n -> new LadderProgram(n, description, enabled));
        if (description != null) prog.description = description;
        prog.enabled = enabled;
        this.activeProgramName = clean;
        saveProjectToDisk();
        return prog;
    }

    public synchronized boolean setProgramEnabled(String name, boolean enabled) {
        LadderProgram prog = programs.get(name);
        if (prog == null) return false;
        prog.enabled = enabled;
        if (!enabled) {
            for (LadderRoutine r : prog.routines.values()) {
                r.scannedLastCycle = false;
            }
        }
        saveProjectToDisk();
        return true;
    }

    public synchronized boolean renameProgram(String oldName, String newName, String newDescription) {
        if (oldName == null || newName == null) return false;
        String cleanNew = newName.trim();
        if (cleanNew.isEmpty()) return false;
        LadderProgram prog = programs.get(oldName);
        if (prog == null) return false;
        if (!oldName.equals(cleanNew)) {
            if (programs.containsKey(cleanNew)) {
                throw new IllegalArgumentException("A program named '" + cleanNew + "' already exists");
            }
            programs.remove(oldName);
            prog.name = cleanNew;
            programs.put(cleanNew, prog);
            if (oldName.equals(activeProgramName)) {
                activeProgramName = cleanNew;
            }
        }
        if (newDescription != null) {
            prog.description = newDescription;
        }
        saveProjectToDisk();
        return true;
    }

    public synchronized boolean deleteProgram(String name) {
        if (name == null || !programs.containsKey(name)) return false;
        if (programs.size() <= 1) {
            throw new IllegalStateException("Cannot delete the last remaining Program on the Gateway.");
        }
        LadderProgram removed = programs.remove(name);
        if (removed != null) {
            if (name.equals(activeProgramName)) {
                activeProgramName = programs.keySet().iterator().next();
            }
            saveProjectToDisk();
            return true;
        }
        return false;
    }

    public void registerTimerTags(String baseTagPath, long presetMs, String description) {
        String norm = TagImageManager.normalizeTagPath(baseTagPath);
        if (norm.isEmpty()) return;
        if (udtManager != null && udtManager.isAvailable() && !norm.startsWith("[" + TagImageManager.MANAGED_PROVIDER_NAME + "]")) {
            try {
                String shortName = norm.contains("/") ? norm.substring(norm.lastIndexOf('/') + 1) : norm;
                if (norm.startsWith(UdtTimerCounterManager.TIMERS_FOLDER_PATH + "/")) {
                    udtManager.createTimerInstance(shortName, presetMs, description);
                } else {
                    udtManager.subscribeInstanceMembers(norm, false, presetMs);
                }
                return;
            } catch (Exception e) {
                logger.warn("Fallback registering timer {}: {}", norm, e.getMessage());
            }
        }
        tagImage.registerTag(norm + ".PRE", presetMs, "DINT", description + " Preset (ms)");
        tagImage.registerTag(norm + ".ACC", 0L, "DINT", description + " Accumulated (ms)");
        tagImage.registerTag(norm + ".EN", false, "BOOL", description + " Enabled Bit");
        tagImage.registerTag(norm + ".TT", false, "BOOL", description + " Timer Timing Bit");
        tagImage.registerTag(norm + ".DN", false, "BOOL", description + " Done Bit");
    }

    public void registerCounterTags(String baseTagPath, long presetCount, String description) {
        String norm = TagImageManager.normalizeTagPath(baseTagPath);
        if (norm.isEmpty()) return;
        if (udtManager != null && udtManager.isAvailable() && !norm.startsWith("[" + TagImageManager.MANAGED_PROVIDER_NAME + "]")) {
            try {
                String shortName = norm.contains("/") ? norm.substring(norm.lastIndexOf('/') + 1) : norm;
                if (norm.startsWith(UdtTimerCounterManager.COUNTERS_FOLDER_PATH + "/")) {
                    udtManager.createCounterInstance(shortName, presetCount, description);
                } else {
                    udtManager.subscribeInstanceMembers(norm, true, presetCount);
                }
                return;
            } catch (Exception e) {
                logger.warn("Fallback registering counter {}: {}", norm, e.getMessage());
            }
        }
        tagImage.registerTag(norm + ".PRE", presetCount, "DINT", description + " Preset");
        tagImage.registerTag(norm + ".ACC", 0L, "DINT", description + " Accumulated Count");
        tagImage.registerTag(norm + ".CU", false, "BOOL", description + " Count Up Enable Bit");
        tagImage.registerTag(norm + ".DN", false, "BOOL", description + " Done Bit");
        tagImage.registerTag(norm + ".RES", false, "BOOL", description + " Reset Bit");
    }

    public void registerPidTags(String baseTagPath, String description) {
        String norm = TagImageManager.normalizeTagPath(baseTagPath);
        if (norm.isEmpty()) return;
        String desc = description != null ? description : "PID Loop";
        tagImage.registerTag(TagImageManager.memberPath(norm, "PV"), 0.0, "REAL", desc + " Process Variable");
        tagImage.registerTag(TagImageManager.memberPath(norm, "SP"), 0.0, "REAL", desc + " Setpoint");
        tagImage.registerTag(TagImageManager.memberPath(norm, "CV"), 0.0, "REAL", desc + " Control Variable (0-100%)");
        tagImage.registerTag(TagImageManager.memberPath(norm, "KP"), 1.0, "REAL", desc + " Proportional Gain");
        tagImage.registerTag(TagImageManager.memberPath(norm, "KI"), 0.1, "REAL", desc + " Integral Gain (1/s)");
        tagImage.registerTag(TagImageManager.memberPath(norm, "KD"), 0.0, "REAL", desc + " Derivative Gain (s)");
        tagImage.registerTag(TagImageManager.memberPath(norm, "ERR"), 0.0, "REAL", desc + " Loop Error (SP - PV)");
        tagImage.registerTag(TagImageManager.memberPath(norm, "EN"), false, "BOOL", desc + " Enable Bit");
        tagImage.registerTag(TagImageManager.memberPath(norm, "CYCLE_MS"), 10000L, "DINT", desc + " Time-Proportioning Cycle Window (ms)");
        tagImage.registerTag(TagImageManager.memberPath(norm, "MIN_SW_MS"), 2000L, "DINT", desc + " Minimum ON/OFF Dwell Delay (ms)");
        tagImage.registerTag(TagImageManager.memberPath(norm, "OUT"), false, "BOOL", desc + " Digital Relay Output Bit");
    }

    public synchronized void start() {
        stop();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Ignition-LadderPLC-ScanThread");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY + 1);
            scanThreadId = t.getId();
            return t;
        });
        scanTask = executor.scheduleAtFixedRate(this::executeSingleScanSafely, 100L, scanRateMs, TimeUnit.MILLISECONDS);
        logger.info("LadderPLC Cyclic Scan Engine started at {}ms scan rate (Watchdog: {}ms).", scanRateMs, watchdogMs);
    }

    public synchronized void stop() {
        if (scanTask != null) {
            scanTask.cancel(false);
            scanTask = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public synchronized void setScanRateMs(long newRateMs) {
        if (newRateMs < 5L) newRateMs = 5L;
        if (newRateMs > 10000L) newRateMs = 10000L;
        this.scanRateMs = newRateMs;
        tagImage.writeFromExternalUI("[LadderPLC]System/ScanRateMs", newRateMs);
        if (executor != null) {
            start();
        }
    }

    public void setControllerMode(ControllerMode newMode) {
        ControllerMode prev = this.mode;
        this.mode = newMode;
        if (newMode == ControllerMode.RUN || newMode == ControllerMode.PROG) {
            if (prev == ControllerMode.FAULT) {
                this.firstScanPending = true;
            }
            this.faultMessage = "No Faults";
            tagImage.writeFromExternalUI("[LadderPLC]System/FaultMessage", this.faultMessage);
        }
        tagImage.writeFromExternalUI("[LadderPLC]System/Mode", newMode.name());
    }

    public ControllerMode getControllerMode() {
        return mode;
    }

    public void executeSingleScanSafely() {
        // Both RUN (locked/read-only) and PROG (online-editable) continue scanning; only FAULT halts execution.
        if (mode == ControllerMode.FAULT) {
            return;
        }

        long startNanos = System.nanoTime();
        long watchdogLimitNanos = watchdogMs * 1_000_000L;

        try {
            // 1. Snapshot Input Image + Apply Forces
            tagImage.beginScan();
            boolean isFirstScan = firstScanPending;
            tagImage.writeValue("[LadderPLC]System/S_FS", isFirstScan);

            // Mark all routines across all programs unscanned prior to cycle
            for (LadderProgram prog : programs.values()) {
                for (LadderRoutine r : prog.routines.values()) {
                    r.scannedLastCycle = false;
                }
            }

            // 2. Execute every ENABLED Program's MainRoutine (which calls Subroutines via JSR)
            for (LadderProgram prog : programs.values()) {
                if (!prog.enabled) {
                    continue;
                }
                long progStart = System.nanoTime();
                LadderRoutine main = prog.routines.get(prog.mainRoutineName);
                if (main != null) {
                    executeRoutine(prog, main, 0, startNanos, watchdogLimitNanos, Collections.emptyList());
                }
                double pMs = Math.round(((System.nanoTime() - progStart) / 1_000_000.0) * 1000.0) / 1000.0;
                prog.lastScanMs = pMs;
                if (pMs > prog.maxScanMs) {
                    prog.maxScanMs = pMs;
                }
                prog.scanCount++;
            }

            // 3. Flush only dirty outputs to Ignition Gateway Tags
            tagImage.flushOutputs();
            if (isFirstScan) {
                firstScanPending = false;
                tagImage.writeValue("[LadderPLC]System/S_FS", false);
            }

            long elapsedNanos = System.nanoTime() - startNanos;
            double elapsedMs = elapsedNanos / 1_000_000.0;
            lastScanMs = Math.round(elapsedMs * 1000.0) / 1000.0;
            if (lastScanMs > maxScanMs) {
                maxScanMs = lastScanMs;
            }
            long count = scanCount.incrementAndGet();
            avgScanMs = Math.round(((avgScanMs * 0.95) + (lastScanMs * 0.05)) * 1000.0) / 1000.0;

            if (count % 10 == 0) {
                tagImage.writeFromExternalUI("[LadderPLC]System/ScanCount", count);
                tagImage.writeFromExternalUI("[LadderPLC]System/LastScanMs", lastScanMs);
                tagImage.writeFromExternalUI("[LadderPLC]System/MaxScanMs", maxScanMs);
                tagImage.writeFromExternalUI("[LadderPLC]System/AvgScanMs", avgScanMs);
                tagImage.writeFromExternalUI("[LadderPLC]System/DirtyWritesTotal", tagImage.getTotalDirtyWrites());
            }
        } catch (WatchdogFaultException wfe) {
            triggerMajorFault("Major Fault (Code 06 - Watchdog Overrun): " + wfe.getMessage());
        } catch (Exception e) {
            triggerMajorFault("Major Fault (Execution Error): " + e.getMessage());
            logger.error("LadderPLC scan fault", e);
        }
    }

    private void triggerMajorFault(String reason) {
        this.mode = ControllerMode.FAULT;
        this.faultMessage = reason;
        tagImage.writeFromExternalUI("[LadderPLC]System/Mode", "FAULT");
        tagImage.writeFromExternalUI("[LadderPLC]System/FaultMessage", reason);
        logger.error("LadderPLC transitioned to FAULT mode: {}", reason);
    }

    private void executeRoutine(
            LadderProgram program,
            LadderRoutine routine,
            int callDepth,
            long scanStartNanos,
            long watchdogLimitNanos,
            List<Object> inputParams
    ) {
        if (callDepth > MAX_JSR_DEPTH) {
            throw new WatchdogFaultException("Max JSR Subroutine Recursion Depth (" + MAX_JSR_DEPTH + ") exceeded in " + program.name + "/" + routine.name);
        }

        long routineStartNanos = System.nanoTime();
        routine.scannedLastCycle = true;
        routine.executionCount++;
        subroutineReturnedEarly.set(false);

        List<LadderRung> rungList = routine.rungs;
        int rungIdx = 0;
        int jmpLoops = 0;

        while (rungIdx < rungList.size()) {
            if (System.nanoTime() - scanStartNanos > watchdogLimitNanos) {
                throw new WatchdogFaultException("Scan exceeded Watchdog threshold (" + watchdogMs + " ms) in " + program.name + "/" + routine.name + " at Rung " + rungIdx);
            }

            LadderRung rung = rungList.get(rungIdx);
            if (rung.ast == null) {
                rung.recompile();
            }

            EvalContext ctx = new EvalContext(program, routine, callDepth, scanStartNanos, watchdogLimitNanos, inputParams);
            boolean out = evaluateNode(rung.ast, true, ctx);
            rung.powerOut = out;

            if (subroutineReturnedEarly.get()) {
                subroutineReturnedEarly.set(false);
                break;
            }

            if (ctx.jumpTargetLabel != null) {
                int targetIdx = findLabelRungIndex(rungList, ctx.jumpTargetLabel);
                if (targetIdx >= 0) {
                    if (targetIdx <= rungIdx) {
                        jmpLoops++;
                        if (jmpLoops > MAX_JMP_LOOPS) {
                            throw new WatchdogFaultException("Infinite backward JMP loop to label '" + ctx.jumpTargetLabel + "' in routine " + routine.name);
                        }
                    }
                    rungIdx = targetIdx;
                    continue;
                }
            }

            rungIdx++;
        }

        routine.lastExecutionUs = Math.round(((System.nanoTime() - routineStartNanos) / 1000.0) * 100.0) / 100.0;
    }

    private int findLabelRungIndex(List<LadderRung> rungs, String labelName) {
        for (int i = 0; i < rungs.size(); i++) {
            LadderRung r = rungs.get(i);
            if (r.ast != null && containsLabel(r.ast, labelName)) {
                return i;
            }
        }
        return -1;
    }

    private boolean containsLabel(RungNode node, String labelName) {
        if (node.type == RungNode.NodeType.INSTRUCTION) {
            return "LBL".equalsIgnoreCase(node.instruction)
                    && !node.operands.isEmpty()
                    && labelName.equalsIgnoreCase(node.operands.get(0));
        }
        for (RungNode c : node.children) {
            if (containsLabel(c, labelName)) return true;
        }
        for (RungNode b : node.branches) {
            if (containsLabel(b, labelName)) return true;
        }
        return false;
    }

    private boolean evaluateNode(RungNode node, boolean rungIn, EvalContext ctx) {
        if (node == null) return rungIn;
        node.powerIn = rungIn;

        if (node.type == RungNode.NodeType.SERIES) {
            boolean currentFlow = rungIn;
            for (RungNode child : node.children) {
                currentFlow = evaluateNode(child, currentFlow, ctx);
                if (subroutineReturnedEarly.get() || ctx.jumpTargetLabel != null) {
                    break;
                }
            }
            node.powerOut = currentFlow;
            node.energized = currentFlow;
            return currentFlow;
        } else if (node.type == RungNode.NodeType.PARALLEL) {
            boolean anyBranchTrue = false;
            for (RungNode branch : node.branches) {
                boolean bOut = evaluateNode(branch, rungIn, ctx);
                if (bOut) {
                    anyBranchTrue = true;
                }
            }
            node.powerOut = anyBranchTrue;
            node.energized = anyBranchTrue;
            return anyBranchTrue;
        } else {
            boolean out = evaluateInstruction(node, rungIn, ctx);
            node.powerOut = out;
            return out;
        }
    }

    private String resolveAoiOp(String op, EvalContext ctx) {
        if (op == null || op.isEmpty() || op.equals("?") || ctx.aoiInstanceBase == null) {
            return op;
        }
        if (op.startsWith("[") || op.equalsIgnoreCase("S:FS") || RungNode.isNumericLiteral(op)) {
            return op;
        }
        return TagImageManager.memberPath(ctx.aoiInstanceBase, op);
    }

    private boolean evaluateInstruction(RungNode node, boolean rungIn, EvalContext ctx) {
        String inst = node.instruction != null ? node.instruction : "NOP";
        List<String> ops = node.operands;
        String rawOp0 = ops.size() > 0 ? ops.get(0) : "";
        String rawOp1 = ops.size() > 1 ? ops.get(1) : "";
        String rawOp2 = ops.size() > 2 ? ops.get(2) : "";
        String op0 = (inst.equals("JSR") || inst.equals("JMP") || inst.equals("LBL") || inst.equals("AOI")) ? rawOp0 : resolveAoiOp(rawOp0, ctx);
        String op1 = inst.equals("AOI") ? rawOp1 : resolveAoiOp(rawOp1, ctx);
        String op2 = resolveAoiOp(rawOp2, ctx);

        switch (inst) {
            case "AFI": {
                node.energized = false;
                return false;
            }
            case "NOP": {
                node.energized = rungIn;
                return rungIn;
            }
            case "XIC": {
                boolean bit = tagImage.readBoolean(op0);
                node.energized = bit;
                return rungIn && bit;
            }
            case "XIO": {
                boolean bit = !tagImage.readBoolean(op0);
                node.energized = bit;
                return rungIn && bit;
            }
            case "ONS": {
                boolean rising = rungIn && !node.lastScanRungIn;
                node.lastScanRungIn = rungIn;
                if (!op0.isEmpty()) {
                    tagImage.writeValue(op0, rising);
                }
                node.energized = rising;
                return rising;
            }
            case "OSR": {
                boolean prev = !op0.isEmpty() ? tagImage.readBoolean(op0) : node.lastScanRungIn;
                boolean rising = rungIn && !prev;
                node.lastScanRungIn = rungIn;
                if (!op0.isEmpty()) tagImage.writeValue(op0, rungIn);
                if (!op1.isEmpty()) tagImage.writeValue(op1, rising);
                node.energized = rising;
                return rising;
            }
            case "OSF": {
                boolean prev = !op0.isEmpty() ? tagImage.readBoolean(op0) : node.lastScanRungIn;
                boolean falling = !rungIn && prev;
                node.lastScanRungIn = rungIn;
                if (!op0.isEmpty()) tagImage.writeValue(op0, rungIn);
                if (!op1.isEmpty()) tagImage.writeValue(op1, falling);
                node.energized = falling;
                return falling;
            }
            case "CLR": {
                if (rungIn && !op0.isEmpty()) {
                    Object cur = tagImage.readValue(op0);
                    tagImage.writeValue(op0, (cur instanceof Boolean) ? false : 0L);
                }
                node.energized = rungIn;
                return rungIn;
            }
            case "OTE": {
                tagImage.writeValue(op0, rungIn);
                node.energized = rungIn;
                return rungIn;
            }
            case "OTL": {
                if (rungIn) {
                    tagImage.writeValue(op0, true);
                }
                node.energized = rungIn;
                return rungIn;
            }
            case "OTU": {
                if (rungIn) {
                    tagImage.writeValue(op0, false);
                }
                node.energized = rungIn;
                return rungIn;
            }
            case "EQU": {
                boolean cond = Math.abs(tagImage.readDouble(op0) - tagImage.readDouble(op1)) < 1e-6;
                node.energized = rungIn && cond;
                return rungIn && cond;
            }
            case "NEQ": {
                boolean cond = Math.abs(tagImage.readDouble(op0) - tagImage.readDouble(op1)) >= 1e-6;
                node.energized = rungIn && cond;
                return rungIn && cond;
            }
            case "GRT": {
                boolean cond = tagImage.readDouble(op0) > tagImage.readDouble(op1);
                node.energized = rungIn && cond;
                return rungIn && cond;
            }
            case "GEQ": {
                boolean cond = tagImage.readDouble(op0) >= tagImage.readDouble(op1);
                node.energized = rungIn && cond;
                return rungIn && cond;
            }
            case "LES": {
                boolean cond = tagImage.readDouble(op0) < tagImage.readDouble(op1);
                node.energized = rungIn && cond;
                return rungIn && cond;
            }
            case "LEQ": {
                boolean cond = tagImage.readDouble(op0) <= tagImage.readDouble(op1);
                node.energized = rungIn && cond;
                return rungIn && cond;
            }
            case "LIM": {
                double low = tagImage.readDouble(op0);
                double test = tagImage.readDouble(op1);
                double high = tagImage.readDouble(op2);
                boolean cond = (low <= high) ? (test >= low && test <= high) : (test >= low || test <= high);
                node.energized = rungIn && cond;
                return rungIn && cond;
            }
            case "MOV": {
                if (rungIn) {
                    Object val = tagImage.readValue(op0);
                    tagImage.writeValue(op1, val);
                }
                node.energized = rungIn;
                return rungIn;
            }
            case "ADD": {
                if (rungIn) {
                    double res = Math.round((tagImage.readDouble(op0) + tagImage.readDouble(op1)) * 10000.0) / 10000.0;
                    tagImage.writeValue(op2, res);
                }
                node.energized = rungIn;
                return rungIn;
            }
            case "SUB": {
                if (rungIn) {
                    double res = Math.round((tagImage.readDouble(op0) - tagImage.readDouble(op1)) * 10000.0) / 10000.0;
                    tagImage.writeValue(op2, res);
                }
                node.energized = rungIn;
                return rungIn;
            }
            case "MUL": {
                if (rungIn) {
                    double res = Math.round((tagImage.readDouble(op0) * tagImage.readDouble(op1)) * 10000.0) / 10000.0;
                    tagImage.writeValue(op2, res);
                }
                node.energized = rungIn;
                return rungIn;
            }
            case "DIV": {
                if (rungIn) {
                    double divisor = tagImage.readDouble(op1);
                    if (Math.abs(divisor) > 1e-12) {
                        double res = Math.round((tagImage.readDouble(op0) / divisor) * 10000.0) / 10000.0;
                        tagImage.writeValue(op2, res);
                    }
                }
                node.energized = rungIn;
                return rungIn;
            }
            case "PID": {
                // Supports BOTH:
                //   UDT-backed 4-op form: PID <PidUdtBase> <PV> <SP> <CV>  (KP/KI/KD/ERR/EN live on <PidUdtBase>/*)
                //   Legacy 6-op form:     PID <PV> <SP> <CV> <Kp> <Ki> <Kd>
                boolean isUdtForm = ops.size() <= 4;
                String pidBase = isUdtForm ? TagImageManager.normalizeTagPath(op0) : "";
                String pvOp = isUdtForm ? op1 : op0;
                String spOp = isUdtForm ? op2 : op1;
                String cvOp = isUdtForm ? (ops.size() > 3 ? resolveAoiOp(ops.get(3), ctx) : "") : op2;

                long nowNanos = System.nanoTime();
                double dtSec = node.lastUpdateNanos == 0L
                        ? Math.max(0.001, scanRateMs / 1000.0)
                        : Math.max(0.001, Math.min(1.0, (nowNanos - node.lastUpdateNanos) / 1_000_000_000.0));
                node.lastUpdateNanos = nowNanos;

                if (!rungIn) {
                    node.pidIntegral = 0.0;
                    node.pidPrevPv = 0.0;
                    node.pidInitialized = false;
                    if (!cvOp.isEmpty() && !cvOp.equals("?")) {
                        tagImage.writeValue(cvOp, 0.0);
                    }
                    if (isUdtForm && !pidBase.isEmpty() && !pidBase.equals("?")) {
                        tagImage.writeValue(TagImageManager.memberPath(pidBase, "EN"), false);
                        tagImage.writeValue(TagImageManager.memberPath(pidBase, "CV"), 0.0);
                        tagImage.writeValue(TagImageManager.memberPath(pidBase, "ERR"), 0.0);
                    }
                    node.energized = false;
                    return false;
                }

                double pv = (!pvOp.isEmpty() && !pvOp.equals("?"))
                        ? tagImage.readDouble(pvOp)
                        : (isUdtForm && !pidBase.isEmpty() ? tagImage.readDouble(TagImageManager.memberPath(pidBase, "PV")) : 0.0);
                double sp = (!spOp.isEmpty() && !spOp.equals("?"))
                        ? tagImage.readDouble(spOp)
                        : (isUdtForm && !pidBase.isEmpty() ? tagImage.readDouble(TagImageManager.memberPath(pidBase, "SP")) : 0.0);

                double kp, ki, kd;
                if (isUdtForm && !pidBase.isEmpty() && !pidBase.equals("?")) {
                    kp = tagImage.readDouble(TagImageManager.memberPath(pidBase, "KP"));
                    ki = tagImage.readDouble(TagImageManager.memberPath(pidBase, "KI"));
                    kd = tagImage.readDouble(TagImageManager.memberPath(pidBase, "KD"));
                } else {
                    String op3 = ops.size() > 3 ? resolveAoiOp(ops.get(3), ctx) : "1.0";
                    String op4 = ops.size() > 4 ? resolveAoiOp(ops.get(4), ctx) : "0.0";
                    String op5 = ops.size() > 5 ? resolveAoiOp(ops.get(5), ctx) : "0.0";
                    kp = tagImage.readDouble(op3);
                    ki = tagImage.readDouble(op4);
                    kd = tagImage.readDouble(op5);
                }

                double error = sp - pv;
                double pTerm = kp * error;

                // Derivative-on-measurement (-Kd * dPV/dt), zero on first enabled scan to avoid kick
                double dTerm = 0.0;
                if (node.pidInitialized) {
                    dTerm = -kd * ((pv - node.pidPrevPv) / dtSec);
                }
                node.pidPrevPv = pv;
                node.pidInitialized = true;

                // Integral term stored in output units [0..100] with strict anti-windup clamping
                if (Math.abs(ki) < 1e-12) {
                    node.pidIntegral = 0.0;
                } else {
                    double nextI = node.pidIntegral + (ki * error * dtSec);
                    double unclamped = pTerm + nextI + dTerm;
                    boolean saturatingHigh = unclamped > 100.0 && error > 0.0;
                    boolean saturatingLow = unclamped < 0.0 && error < 0.0;
                    if (!saturatingHigh && !saturatingLow) {
                        node.pidIntegral = Math.max(0.0, Math.min(100.0, nextI));
                    }
                }

                double cv = Math.max(0.0, Math.min(100.0, pTerm + node.pidIntegral + dTerm));
                double roundedCv = Math.round(cv * 10000.0) / 10000.0;
                double roundedErr = Math.round(error * 10000.0) / 10000.0;
                if (!cvOp.isEmpty() && !cvOp.equals("?")) {
                    tagImage.writeValue(cvOp, roundedCv);
                }
                if (isUdtForm && !pidBase.isEmpty() && !pidBase.equals("?")) {
                    tagImage.writeValue(TagImageManager.memberPath(pidBase, "EN"), true);
                    tagImage.writeValue(TagImageManager.memberPath(pidBase, "PV"), pv);
                    tagImage.writeValue(TagImageManager.memberPath(pidBase, "SP"), sp);
                    tagImage.writeValue(TagImageManager.memberPath(pidBase, "CV"), roundedCv);
                    tagImage.writeValue(TagImageManager.memberPath(pidBase, "ERR"), roundedErr);
                }
                node.energized = true;
                return true;
            }
            case "PIDD": {
                // Digital / Relay Time-Proportioned PID with built-in anti-chatter dwell delay:
                //   PIDD <PidUdtBase> <PV> <SP> <OutBit>
                // Uses UDT members: KP, KI, KD, CYCLE_MS (default 10000 ms), MIN_SW_MS (default 2000 ms),
                // and updates PV, SP, CV (0-100% duty), ERR, EN, OUT, plus writes BOOL to <OutBit>.
                String pidBase = TagImageManager.normalizeTagPath(op0);
                String pvOp = op1;
                String spOp = op2;
                String outOp = ops.size() > 3 ? resolveAoiOp(ops.get(3), ctx) : "";

                long nowNanos = System.nanoTime();
                double dtMs = node.lastUpdateNanos == 0L
                        ? Math.max(1.0, (double) scanRateMs)
                        : Math.max(1.0, Math.min(1000.0, (nowNanos - node.lastUpdateNanos) / 1_000_000.0));
                double dtSec = dtMs / 1000.0;
                node.lastUpdateNanos = nowNanos;

                if (!rungIn) {
                    node.pidIntegral = 0.0;
                    node.pidPrevPv = 0.0;
                    node.pidInitialized = false;
                    node.pidCycleElapsedMs = 0.0;
                    node.pidSinceSwitchMs = 0.0;
                    node.pidDigitalOut = false;
                    if (!outOp.isEmpty() && !outOp.equals("?")) {
                        tagImage.writeValue(outOp, false);
                    }
                    if (!pidBase.isEmpty() && !pidBase.equals("?")) {
                        tagImage.writeValue(TagImageManager.memberPath(pidBase, "EN"), false);
                        tagImage.writeValue(TagImageManager.memberPath(pidBase, "CV"), 0.0);
                        tagImage.writeValue(TagImageManager.memberPath(pidBase, "ERR"), 0.0);
                        tagImage.writeValue(TagImageManager.memberPath(pidBase, "OUT"), false);
                    }
                    node.energized = false;
                    return false;
                }

                double pv = (!pvOp.isEmpty() && !pvOp.equals("?"))
                        ? tagImage.readDouble(pvOp)
                        : (!pidBase.isEmpty() ? tagImage.readDouble(TagImageManager.memberPath(pidBase, "PV")) : 0.0);
                double sp = (!spOp.isEmpty() && !spOp.equals("?"))
                        ? tagImage.readDouble(spOp)
                        : (!pidBase.isEmpty() ? tagImage.readDouble(TagImageManager.memberPath(pidBase, "SP")) : 0.0);

                double kp = 1.0, ki = 0.1, kd = 0.0;
                double cycleMs = 10000.0;
                double minSwitchMs = 2000.0;
                if (!pidBase.isEmpty() && !pidBase.equals("?")) {
                    kp = tagImage.readDouble(TagImageManager.memberPath(pidBase, "KP"));
                    ki = tagImage.readDouble(TagImageManager.memberPath(pidBase, "KI"));
                    kd = tagImage.readDouble(TagImageManager.memberPath(pidBase, "KD"));
                    double rawCycle = tagImage.readDouble(TagImageManager.memberPath(pidBase, "CYCLE_MS"));
                    double rawMinSw = tagImage.readDouble(TagImageManager.memberPath(pidBase, "MIN_SW_MS"));
                    if (rawCycle >= 200.0) cycleMs = rawCycle;
                    if (rawMinSw >= 0.0) minSwitchMs = rawMinSw;
                }

                double error = sp - pv;
                double pTerm = kp * error;
                double dTerm = 0.0;
                boolean firstScan = !node.pidInitialized;
                if (node.pidInitialized) {
                    dTerm = -kd * ((pv - node.pidPrevPv) / dtSec);
                }
                node.pidPrevPv = pv;
                node.pidInitialized = true;

                if (Math.abs(ki) < 1e-12) {
                    node.pidIntegral = 0.0;
                } else {
                    double nextI = node.pidIntegral + (ki * error * dtSec);
                    double unclamped = pTerm + nextI + dTerm;
                    boolean saturatingHigh = unclamped > 100.0 && error > 0.0;
                    boolean saturatingLow = unclamped < 0.0 && error < 0.0;
                    if (!saturatingHigh && !saturatingLow) {
                        node.pidIntegral = Math.max(0.0, Math.min(100.0, nextI));
                    }
                }

                double cv = Math.max(0.0, Math.min(100.0, pTerm + node.pidIntegral + dTerm));
                double roundedCv = Math.round(cv * 10000.0) / 10000.0;
                double roundedErr = Math.round(error * 10000.0) / 10000.0;

                // Time-proportioning window with minimum ON/OFF dwell delay (anti-chatter)
                if (firstScan) {
                    node.pidCycleElapsedMs = 0.0;
                    node.pidSinceSwitchMs = minSwitchMs; // allow immediate initial decision
                } else {
                    node.pidCycleElapsedMs = (node.pidCycleElapsedMs + dtMs) % cycleMs;
                    node.pidSinceSwitchMs += dtMs;
                }

                double onTimeMs = (cv / 100.0) * cycleMs;
                boolean desiredOut;
                if (cv <= 0.0 || onTimeMs < minSwitchMs) {
                    desiredOut = false;
                } else if (cv >= 100.0 || (cycleMs - onTimeMs) < minSwitchMs) {
                    desiredOut = true;
                } else {
                    desiredOut = node.pidCycleElapsedMs < onTimeMs;
                }

                if (desiredOut != node.pidDigitalOut && node.pidSinceSwitchMs >= minSwitchMs) {
                    node.pidDigitalOut = desiredOut;
                    node.pidSinceSwitchMs = 0.0;
                }

                if (!outOp.isEmpty() && !outOp.equals("?")) {
                    tagImage.writeValue(outOp, node.pidDigitalOut);
                }
                if (!pidBase.isEmpty() && !pidBase.equals("?")) {
                    tagImage.writeValue(TagImageManager.memberPath(pidBase, "EN"), true);
                    tagImage.writeValue(TagImageManager.memberPath(pidBase, "PV"), pv);
                    tagImage.writeValue(TagImageManager.memberPath(pidBase, "SP"), sp);
                    tagImage.writeValue(TagImageManager.memberPath(pidBase, "CV"), roundedCv);
                    tagImage.writeValue(TagImageManager.memberPath(pidBase, "ERR"), roundedErr);
                    tagImage.writeValue(TagImageManager.memberPath(pidBase, "OUT"), node.pidDigitalOut);
                }
                node.energized = true;
                return node.pidDigitalOut;
            }
            case "TON": {
                String timerBase = TagImageManager.normalizeTagPath(op0);
                if (timerBase.isEmpty()) {
                    node.energized = false;
                    return false;
                }
                String prePath = TagImageManager.memberPath(timerBase, "PRE");
                String accPath = TagImageManager.memberPath(timerBase, "ACC");
                String enPath = TagImageManager.memberPath(timerBase, "EN");
                String ttPath = TagImageManager.memberPath(timerBase, "TT");
                String dnPath = TagImageManager.memberPath(timerBase, "DN");

                long preset = (!op1.isEmpty() && !op1.equals("?")) ? (long) tagImage.readDouble(op1) : (long) tagImage.readDouble(prePath);
                tagImage.writeValue(prePath, preset);
                long nowNanos = System.nanoTime();
                long deltaMs = node.lastUpdateNanos == 0L ? scanRateMs : Math.max(1L, (nowNanos - node.lastUpdateNanos) / 1_000_000L);
                node.lastUpdateNanos = nowNanos;
                long prevAcc = (long) tagImage.readDouble(accPath);

                boolean dnOut = false;
                if (!rungIn) {
                    tagImage.writeThrottledValue(accPath, 0L, ACC_FLUSH_INTERVAL_MS, prevAcc != 0L);
                    tagImage.writeValue(enPath, false);
                    tagImage.writeValue(ttPath, false);
                    tagImage.writeValue(dnPath, false);
                    node.energized = false;
                } else {
                    long acc = prevAcc + deltaMs;
                    boolean dn = acc >= preset;
                    if (dn) acc = preset;
                    boolean justFinished = dn && prevAcc < preset;
                    tagImage.writeValue(enPath, true);
                    tagImage.writeThrottledValue(accPath, acc, ACC_FLUSH_INTERVAL_MS, justFinished);
                    tagImage.writeValue(dnPath, dn);
                    tagImage.writeValue(ttPath, !dn);
                    node.energized = true;
                    dnOut = dn;
                }
                return dnOut;
            }
            case "TOF": {
                String timerBase = TagImageManager.normalizeTagPath(op0);
                if (timerBase.isEmpty()) {
                    node.energized = false;
                    return false;
                }
                String prePath = TagImageManager.memberPath(timerBase, "PRE");
                String accPath = TagImageManager.memberPath(timerBase, "ACC");
                String enPath = TagImageManager.memberPath(timerBase, "EN");
                String ttPath = TagImageManager.memberPath(timerBase, "TT");
                String dnPath = TagImageManager.memberPath(timerBase, "DN");

                long preset = (!op1.isEmpty() && !op1.equals("?")) ? (long) tagImage.readDouble(op1) : (long) tagImage.readDouble(prePath);
                tagImage.writeValue(prePath, preset);
                long nowNanos = System.nanoTime();
                long deltaMs = node.lastUpdateNanos == 0L ? scanRateMs : Math.max(1L, (nowNanos - node.lastUpdateNanos) / 1_000_000L);
                node.lastUpdateNanos = nowNanos;
                long prevAcc = (long) tagImage.readDouble(accPath);

                boolean dnOut = false;
                if (rungIn) {
                    tagImage.writeThrottledValue(accPath, 0L, ACC_FLUSH_INTERVAL_MS, prevAcc != 0L);
                    tagImage.writeValue(enPath, true);
                    tagImage.writeValue(ttPath, false);
                    tagImage.writeValue(dnPath, true);
                    node.energized = true;
                    dnOut = true;
                } else {
                    boolean prevDn = tagImage.readBoolean(dnPath);
                    if (!prevDn) {
                        // Cold start, already expired, or reset via RES: stay idle (EN=0, TT=0, DN=0)
                        tagImage.writeValue(enPath, false);
                        tagImage.writeValue(ttPath, false);
                        tagImage.writeValue(dnPath, false);
                        node.energized = false;
                    } else {
                        long acc = prevAcc + deltaMs;
                        boolean dn = acc < preset;
                        if (!dn) acc = preset;
                        boolean justExpired = !dn && prevAcc < preset;
                        tagImage.writeValue(enPath, false);
                        tagImage.writeThrottledValue(accPath, acc, ACC_FLUSH_INTERVAL_MS, justExpired);
                        tagImage.writeValue(dnPath, dn);
                        tagImage.writeValue(ttPath, dn);
                        node.energized = dn;
                        dnOut = dn;
                    }
                }
                return dnOut;
            }
            case "RTO": {
                String timerBase = TagImageManager.normalizeTagPath(op0);
                if (timerBase.isEmpty()) {
                    node.energized = false;
                    return false;
                }
                String prePath = TagImageManager.memberPath(timerBase, "PRE");
                String accPath = TagImageManager.memberPath(timerBase, "ACC");
                String enPath = TagImageManager.memberPath(timerBase, "EN");
                String ttPath = TagImageManager.memberPath(timerBase, "TT");
                String dnPath = TagImageManager.memberPath(timerBase, "DN");

                long preset = (!op1.isEmpty() && !op1.equals("?")) ? (long) tagImage.readDouble(op1) : (long) tagImage.readDouble(prePath);
                tagImage.writeValue(prePath, preset);
                long nowNanos = System.nanoTime();
                long deltaMs = node.lastUpdateNanos == 0L ? scanRateMs : Math.max(1L, (nowNanos - node.lastUpdateNanos) / 1_000_000L);
                node.lastUpdateNanos = nowNanos;
                long prevAcc = (long) tagImage.readDouble(accPath);

                boolean dnOut;
                if (!rungIn) {
                    // Retentive: retain ACC and DN when rung goes false
                    tagImage.writeValue(enPath, false);
                    tagImage.writeValue(ttPath, false);
                    dnOut = tagImage.readBoolean(dnPath);
                    node.energized = dnOut;
                } else {
                    long acc = prevAcc + deltaMs;
                    boolean dn = acc >= preset;
                    if (dn) acc = preset;
                    boolean justFinished = dn && prevAcc < preset;
                    tagImage.writeValue(enPath, true);
                    tagImage.writeThrottledValue(accPath, acc, ACC_FLUSH_INTERVAL_MS, justFinished);
                    tagImage.writeValue(dnPath, dn);
                    tagImage.writeValue(ttPath, !dn);
                    node.energized = true;
                    dnOut = dn;
                }
                return dnOut;
            }
            case "CTU": {
                String counterBase = TagImageManager.normalizeTagPath(op0);
                if (counterBase.isEmpty()) {
                    node.energized = false;
                    return false;
                }
                String prePath = TagImageManager.memberPath(counterBase, "PRE");
                String accPath = TagImageManager.memberPath(counterBase, "ACC");
                String cuPath = TagImageManager.memberPath(counterBase, "CU");
                String dnPath = TagImageManager.memberPath(counterBase, "DN");
                String resPath = TagImageManager.memberPath(counterBase, "RES");

                long preset = (!op1.isEmpty() && !op1.equals("?")) ? (long) tagImage.readDouble(op1) : (long) tagImage.readDouble(prePath);
                tagImage.writeValue(prePath, preset);
                // Ladder Logic Editor behavior: edge detection uses the counter's own .CU bit in tag memory
                // so online edits / Assemble & Save never trigger a false rising edge!
                boolean prevCu = tagImage.readBoolean(cuPath);
                boolean risingEdge = rungIn && !prevCu;
                node.lastScanRungIn = rungIn;

                if (tagImage.readBoolean(resPath)) {
                    tagImage.writeThrottledValue(accPath, 0L, ACC_FLUSH_INTERVAL_MS, true);
                    tagImage.writeValue(cuPath, false);
                    tagImage.writeValue(dnPath, false);
                    node.energized = false;
                    return false;
                }

                long acc = (long) tagImage.readDouble(accPath);
                if (risingEdge) {
                    acc++;
                    tagImage.writeThrottledValue(accPath, acc, ACC_FLUSH_INTERVAL_MS, acc >= preset);
                }
                boolean dn = acc >= preset;
                tagImage.writeValue(cuPath, rungIn);
                tagImage.writeValue(dnPath, dn);
                node.energized = rungIn || dn;
                return dn;
            }
            case "CTD": {
                String counterBase = TagImageManager.normalizeTagPath(op0);
                if (counterBase.isEmpty()) {
                    node.energized = false;
                    return false;
                }
                String prePath = TagImageManager.memberPath(counterBase, "PRE");
                String accPath = TagImageManager.memberPath(counterBase, "ACC");
                String cdPath = TagImageManager.memberPath(counterBase, "CD");
                String dnPath = TagImageManager.memberPath(counterBase, "DN");
                String resPath = TagImageManager.memberPath(counterBase, "RES");

                long preset = (!op1.isEmpty() && !op1.equals("?")) ? (long) tagImage.readDouble(op1) : (long) tagImage.readDouble(prePath);
                tagImage.writeValue(prePath, preset);
                // Ladder Logic Editor behavior: edge detection uses the counter's own .CD bit in tag memory
                boolean prevCd = tagImage.readBoolean(cdPath);
                boolean risingEdge = rungIn && !prevCd;
                node.lastScanRungIn = rungIn;

                if (tagImage.readBoolean(resPath)) {
                    tagImage.writeThrottledValue(accPath, 0L, ACC_FLUSH_INTERVAL_MS, true);
                    tagImage.writeValue(cdPath, false);
                    tagImage.writeValue(dnPath, false);
                    node.energized = false;
                    return false;
                }

                long acc = (long) tagImage.readDouble(accPath);
                if (risingEdge) {
                    acc--;
                    tagImage.writeThrottledValue(accPath, acc, ACC_FLUSH_INTERVAL_MS, acc < preset);
                }
                boolean dn = acc >= preset;
                tagImage.writeValue(cdPath, rungIn);
                tagImage.writeValue(dnPath, dn);
                node.energized = rungIn || dn;
                return dn;
            }
            case "RES": {
                if (rungIn && !op0.isEmpty()) {
                    String base = TagImageManager.normalizeTagPath(op0);
                    if (!base.isEmpty()) {
                        tagImage.writeThrottledValue(TagImageManager.memberPath(base, "ACC"), 0L, ACC_FLUSH_INTERVAL_MS, true);
                        tagImage.writeValue(TagImageManager.memberPath(base, "EN"), false);
                        tagImage.writeValue(TagImageManager.memberPath(base, "TT"), false);
                        tagImage.writeValue(TagImageManager.memberPath(base, "DN"), false);
                        tagImage.writeValue(TagImageManager.memberPath(base, "CU"), false);
                        tagImage.writeValue(TagImageManager.memberPath(base, "CD"), false);
                    }
                }
                node.energized = rungIn;
                return rungIn;
            }
            case "AOI": {
                node.energized = rungIn;
                if (!op0.isEmpty() && !op0.equals("?") && !op1.isEmpty() && !op1.equals("?")) {
                    AoiDefinition aoi = aois.get(op0);
                    if (aoi != null) {
                        String instBase = TagImageManager.normalizeTagPath(op1);
                        tagImage.writeValue(TagImageManager.memberPath(instBase, "EnableIn"), rungIn);
                        if (rungIn) {
                            EvalContext aoiCtx = new EvalContext(ctx.program, ctx.routine, ctx.callDepth + 1,
                                    ctx.scanStartNanos, ctx.watchdogLimitNanos, Collections.emptyList());
                            aoiCtx.aoiInstanceBase = instBase;
                            for (LadderRung r : aoi.rungs) {
                                if (r.ast == null) r.recompile();
                                evaluateNode(r.ast, true, aoiCtx);
                            }
                        }
                        tagImage.writeValue(TagImageManager.memberPath(instBase, "EnableOut"), rungIn);
                    }
                }
                return rungIn;
            }
            case "JSR": {
                node.energized = rungIn;
                if (rungIn && !op0.isEmpty() && !op0.equals("?")) {
                    LadderRoutine targetSub = ctx.program.routines.get(op0);
                    if (targetSub != null) {
                        List<Object> params = new ArrayList<>();
                        for (int i = 1; i < ops.size(); i++) {
                            params.add(tagImage.readValue(ops.get(i)));
                        }
                        executeRoutine(ctx.program, targetSub, ctx.callDepth + 1, ctx.scanStartNanos, ctx.watchdogLimitNanos, params);
                    }
                }
                return rungIn;
            }
            case "SBR": {
                node.energized = rungIn;
                if (rungIn && ctx.inputParams != null) {
                    for (int i = 0; i < ops.size() && i < ctx.inputParams.size(); i++) {
                        tagImage.writeValue(ops.get(i), ctx.inputParams.get(i));
                    }
                }
                return rungIn;
            }
            case "RET": {
                node.energized = rungIn;
                if (rungIn) {
                    subroutineReturnedEarly.set(true);
                }
                return rungIn;
            }
            case "JMP": {
                node.energized = rungIn;
                if (rungIn && !op0.isEmpty() && !op0.equals("?")) {
                    ctx.jumpTargetLabel = op0;
                }
                return rungIn;
            }
            case "LBL": {
                node.energized = rungIn;
                return rungIn;
            }
            default:
                node.energized = rungIn;
                return rungIn;
        }
    }

    public void refreshAllTagSubscriptions() {
        for (LadderProgram prog : programs.values()) {
            for (LadderRoutine routine : prog.routines.values()) {
                for (LadderRung rung : routine.rungs) {
                    if (rung.ast == null) {
                        rung.recompile();
                    }
                    Set<String> paths = MnemonicCompiler.extractTagPaths(rung.ast);
                    for (String p : paths) {
                        if (p == null || p.isEmpty() || p.equals("?")) continue;
                        String norm = TagImageManager.normalizeTagPath(p);
                        if (norm.startsWith(UdtTimerCounterManager.TIMERS_FOLDER_PATH + "/")) {
                            String rel = norm.substring(UdtTimerCounterManager.TIMERS_FOLDER_PATH.length() + 1);
                            String baseName = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
                            udtManager.subscribeInstanceMembers(UdtTimerCounterManager.TIMERS_FOLDER_PATH + "/" + baseName, false, 5000L);
                        } else if (norm.startsWith(UdtTimerCounterManager.COUNTERS_FOLDER_PATH + "/")) {
                            String rel = norm.substring(UdtTimerCounterManager.COUNTERS_FOLDER_PATH.length() + 1);
                            String baseName = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
                            udtManager.subscribeInstanceMembers(UdtTimerCounterManager.COUNTERS_FOLDER_PATH + "/" + baseName, true, 100L);
                        } else if (norm.startsWith(UdtTimerCounterManager.PIDS_FOLDER_PATH + "/")) {
                            String rel = norm.substring(UdtTimerCounterManager.PIDS_FOLDER_PATH.length() + 1);
                            String baseName = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
                            udtManager.subscribePidMembers(UdtTimerCounterManager.PIDS_FOLDER_PATH + "/" + baseName);
                        } else if (p.endsWith(".DN") || p.endsWith(".ACC") || p.endsWith(".PRE") || p.endsWith(".TT") || p.endsWith(".EN") || p.endsWith(".CU")) {
                            String base = p.substring(0, p.lastIndexOf('.'));
                            registerTimerTags(base, 1000L, "Auto-registered Timer/Counter");
                        } else if (norm.startsWith("[" + TagImageManager.MANAGED_PROVIDER_NAME + "]")
                                && !norm.startsWith("[" + TagImageManager.MANAGED_PROVIDER_NAME + "]System/")) {
                            // Ensure non-persisted ManagedTagProvider tags (e.g. imported .ACD folders) are re-created after restart
                            int bracketEnd = norm.indexOf(']');
                            String tail = norm.substring(bracketEnd + 1);
                            // Strip array index or struct member suffix for clean base tag registration
                            int cut = -1;
                            for (int k = 0; k < tail.length(); k++) {
                                char ch = tail.charAt(k);
                                if (ch == '[' || ch == '.' || ch == ':') {
                                    cut = k;
                                    break;
                                }
                            }
                            String basePath = cut > 0 ? norm.substring(0, bracketEnd + 1 + cut) : norm;
                            if (basePath.contains("/Timers/")) {
                                int tiIdx = basePath.indexOf("/Timers/") + "/Timers/".length();
                                int nextSl = basePath.indexOf('/', tiIdx);
                                String timerBase = nextSl > 0 ? basePath.substring(0, nextSl) : basePath;
                                registerTimerTags(timerBase, 1000L, "Auto-registered Timer");
                            } else if (basePath.contains("/Counters/")) {
                                int ciIdx = basePath.indexOf("/Counters/") + "/Counters/".length();
                                int nextSl = basePath.indexOf('/', ciIdx);
                                String counterBase = nextSl > 0 ? basePath.substring(0, nextSl) : basePath;
                                registerCounterTags(counterBase, 100L, "Auto-registered Counter");
                            } else if (basePath.contains("/PIDs/")) {
                                int piIdx = basePath.indexOf("/PIDs/") + "/PIDs/".length();
                                int nextSl = basePath.indexOf('/', piIdx);
                                String pidBase = nextSl > 0 ? basePath.substring(0, nextSl) : basePath;
                                registerPidTags(pidBase, "Auto-registered PID");
                            } else if (tagImage.readValue(basePath) == null) {
                                String dt = basePath.contains("/Booleans/") ? "BOOL"
                                        : basePath.contains("/Floats/") ? "REAL"
                                        : basePath.contains("/Strings/") ? "STRING"
                                        : "DINT";
                                Object defVal = "BOOL".equals(dt) ? Boolean.FALSE
                                        : "REAL".equals(dt) ? 0.0
                                        : "STRING".equals(dt) ? "" : 0L;
                                tagImage.registerTag(basePath, defVal, dt, "Auto-registered Ladder Tag");
                            }
                        } else {
                            tagImage.ensureSubscribed(p);
                        }
                    }
                }
            }
        }
    }

    /**
     * Lists all Timer & Counter UDT instances along with their live values and
     * cross-reference usage across all programs/routines/rungs (including duplicate owner warnings).
     */
    public List<Map<String, Object>> getTimersCountersWithUsage(String filterKind) {
        List<Map<String, Object>> items = udtManager != null
                ? udtManager.listInstances(filterKind)
                : new ArrayList<>();

        Map<String, List<Map<String, Object>>> usageMap = new LinkedHashMap<>();
        for (LadderProgram prog : programs.values()) {
            for (LadderRoutine routine : prog.routines.values()) {
                for (LadderRung rung : routine.rungs) {
                    if (rung.ast == null) rung.recompile();
                    collectTimerCounterUsage(rung.ast, prog.name, routine.name, rung.number, usageMap);
                }
            }
        }

        for (Map<String, Object> item : items) {
            String basePath = String.valueOf(item.get("basePath"));
            List<Map<String, Object>> refs = usageMap.getOrDefault(basePath, Collections.emptyList());
            item.put("usedIn", refs);
            item.put("usageCount", refs.size());
            long ownerCount = refs.stream()
                    .filter(r -> {
                        String inst = String.valueOf(r.get("instruction"));
                        return "TON".equals(inst) || "TOF".equals(inst) || "RTO".equals(inst)
                                || "CTU".equals(inst) || "CTD".equals(inst) || "PID".equals(inst) || "PIDD".equals(inst);
                    })
                    .count();
            item.put("ownerCount", ownerCount);
            item.put("hasDuplicateOwner", ownerCount > 1);
        }
        return items;
    }

    private static void collectTimerCounterUsage(RungNode node, String progName, String routineName, int rungNum,
                                                 Map<String, List<Map<String, Object>>> usageMap) {
        if (node == null) return;
        if (node.type == RungNode.NodeType.INSTRUCTION) {
            String inst = node.instruction != null ? node.instruction.toUpperCase() : "";
            for (String op : node.operands) {
                if (op == null || op.isEmpty() || op.equals("?")) continue;
                String norm = TagImageManager.normalizeTagPath(op);
                String base = null;
                if (norm.startsWith(UdtTimerCounterManager.TIMERS_FOLDER_PATH + "/")) {
                    String rel = norm.substring(UdtTimerCounterManager.TIMERS_FOLDER_PATH.length() + 1);
                    String name = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
                    base = UdtTimerCounterManager.TIMERS_FOLDER_PATH + "/" + name;
                } else if (norm.startsWith(UdtTimerCounterManager.COUNTERS_FOLDER_PATH + "/")) {
                    String rel = norm.substring(UdtTimerCounterManager.COUNTERS_FOLDER_PATH.length() + 1);
                    String name = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
                    base = UdtTimerCounterManager.COUNTERS_FOLDER_PATH + "/" + name;
                } else if (norm.startsWith(UdtTimerCounterManager.PIDS_FOLDER_PATH + "/")) {
                    String rel = norm.substring(UdtTimerCounterManager.PIDS_FOLDER_PATH.length() + 1);
                    String name = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
                    base = UdtTimerCounterManager.PIDS_FOLDER_PATH + "/" + name;
                }
                if (base != null) {
                    Map<String, Object> ref = new LinkedHashMap<>();
                    ref.put("program", progName);
                    ref.put("routine", routineName);
                    ref.put("rung", rungNum);
                    ref.put("instruction", inst);
                    usageMap.computeIfAbsent(base, k -> new ArrayList<>()).add(ref);
                }
            }
            return;
        }
        for (RungNode c : node.children) collectTimerCounterUsage(c, progName, routineName, rungNum, usageMap);
        for (RungNode b : node.branches) collectTimerCounterUsage(b, progName, routineName, rungNum, usageMap);
    }

    /**
     * Updates all routine mnemonics referencing oldBasePath to newBasePath after a UDT rename.
     */
    public synchronized void renameTimerCounterReferences(String oldBasePath, String newBasePath) {
        if (oldBasePath == null || newBasePath == null || oldBasePath.equals(newBasePath)) return;
        for (LadderProgram prog : programs.values()) {
            for (LadderRoutine routine : prog.routines.values()) {
                boolean changed = false;
                for (LadderRung rung : routine.rungs) {
                    if (rung.mnemonic != null && rung.mnemonic.contains(oldBasePath)) {
                        rung.mnemonic = rung.mnemonic.replace(oldBasePath, newBasePath);
                        rung.recompile();
                        changed = true;
                    }
                }
                if (changed) {
                    routine.reindexAndCompile();
                }
            }
        }
        refreshAllTagSubscriptions();
        saveProjectToDisk();
    }

    public synchronized void upsertRoutine(String name, String type, String description, List<Map<String, Object>> rawRungs) {
        upsertRoutine(activeProgramName, name, type, description, rawRungs);
    }

    public synchronized void upsertRoutine(String programName, String name, String type, String description, List<Map<String, Object>> rawRungs) {
        upsertRoutine(programName, name, type, description, rawRungs, false);
    }

    public synchronized void upsertRoutine(String programName, String name, String type, String description,
                                           List<Map<String, Object>> rawRungs, boolean deferPersist) {
        LadderProgram prog = (programName != null && programs.containsKey(programName))
                ? programs.get(programName)
                : getActiveProgram();
        LadderRoutine routine = prog.routines.computeIfAbsent(name, n -> new LadderRoutine(n, type, description));
        if (type != null && !type.isEmpty()) routine.type = type;
        if (description != null) routine.description = description;
        if (rawRungs != null) {
            List<LadderRung> updated = new ArrayList<>();
            int idx = 0;
            for (Map<String, Object> rm : rawRungs) {
                String comment = rm.get("comment") != null ? rm.get("comment").toString() : "";
                String mnemonic = rm.get("mnemonic") != null ? rm.get("mnemonic").toString() : "";
                updated.add(new LadderRung(idx++, comment, mnemonic));
            }
            routine.rungs = updated;
        }
        routine.reindexAndCompile();
        if (!deferPersist) {
            refreshAllTagSubscriptions();
            saveProjectToDisk();
        }
    }

    public synchronized boolean deleteRoutine(String name) {
        return deleteRoutine(activeProgramName, name);
    }

    public synchronized boolean deleteRoutine(String programName, String name) {
        LadderProgram prog = (programName != null && programs.containsKey(programName))
                ? programs.get(programName)
                : getActiveProgram();
        if (name == null || name.equalsIgnoreCase(prog.mainRoutineName)) {
            return false;
        }
        boolean removed = prog.routines.remove(name) != null;
        if (removed) {
            saveProjectToDisk();
        }
        return removed;
    }

    public synchronized boolean renameRoutine(String programName, String oldName, String newName, String newDescription) {
        LadderProgram prog = (programName != null && programs.containsKey(programName))
                ? programs.get(programName)
                : getActiveProgram();
        if (oldName == null || !prog.routines.containsKey(oldName)) return false;
        LadderRoutine existing = prog.routines.get(oldName);
        if (newDescription != null) {
            existing.description = newDescription;
        }
        String cleanNew = newName != null ? newName.trim() : "";
        if (!cleanNew.isEmpty() && !cleanNew.equals(oldName)) {
            if (oldName.equalsIgnoreCase(prog.mainRoutineName) || prog.routines.containsKey(cleanNew)) {
                return false;
            }
            prog.routines.remove(oldName);
            existing.name = cleanNew;
            prog.routines.put(cleanNew, existing);
        }
        saveProjectToDisk();
        return true;
    }

    public Map<String, List<Map<String, Object>>> getCrossReferences() {
        Map<String, List<Map<String, Object>>> xrefs = new LinkedHashMap<>();
        for (LadderProgram prog : programs.values()) {
            for (LadderRoutine routine : prog.routines.values()) {
                for (LadderRung rung : routine.rungs) {
                    if (rung.ast == null) rung.recompile();
                    collectXrefs(prog.name + " / " + routine.name, rung.number, rung.ast, xrefs);
                }
            }
        }
        return xrefs;
    }

    public List<String> findUsedTagsUnderPrefix(String rawTagOrFolderPath) {
        String norm = TagImageManager.normalizeTagPath(rawTagOrFolderPath);
        if (norm.isEmpty()) return Collections.emptyList();
        String slashPrefix = norm.endsWith("/") ? norm : (norm + "/");
        String dotPrefix = norm + ".";
        List<String> used = new ArrayList<>();
        for (String refTag : getCrossReferences().keySet()) {
            if (refTag == null) continue;
            if (refTag.equals(norm) || refTag.startsWith(slashPrefix) || refTag.startsWith(dotPrefix)) {
                used.add(refTag);
            }
        }
        return used;
    }

    public synchronized void replaceTagPrefixInProject(String oldPrefix, String newPrefix) {
        if (oldPrefix == null || newPrefix == null || oldPrefix.equals(newPrefix)) return;
        boolean changed = false;
        for (LadderProgram prog : programs.values()) {
            for (LadderRoutine routine : prog.routines.values()) {
                for (LadderRung rung : routine.rungs) {
                    if (rung.ast == null) rung.recompile();
                    if (replaceInAst(rung.ast, oldPrefix, newPrefix)) {
                        rung.mnemonic = MnemonicCompiler.toMnemonic(rung.ast);
                        changed = true;
                    }
                }
            }
        }
        for (AoiDefinition aoi : aois.values()) {
            for (LadderRung rung : aoi.rungs) {
                if (rung.ast == null) rung.recompile();
                if (replaceInAst(rung.ast, oldPrefix, newPrefix)) {
                    rung.mnemonic = MnemonicCompiler.toMnemonic(rung.ast);
                    changed = true;
                }
            }
        }
        if (changed) {
            saveProjectToDisk();
        }
    }

    private boolean replaceInAst(RungNode node, String oldPrefix, String newPrefix) {
        if (node == null) return false;
        boolean changed = false;
        if (node.type == RungNode.NodeType.INSTRUCTION && node.operands != null) {
            String oldSlash = oldPrefix.endsWith("/") ? oldPrefix : (oldPrefix + "/");
            String oldDot = oldPrefix + ".";
            for (int i = 0; i < node.operands.size(); i++) {
                String op = node.operands.get(i);
                if (op == null) continue;
                if (op.equals(oldPrefix)) {
                    node.operands.set(i, newPrefix);
                    changed = true;
                } else if (op.startsWith(oldSlash)) {
                    node.operands.set(i, (newPrefix.endsWith("/") ? newPrefix : newPrefix + "/") + op.substring(oldSlash.length()));
                    changed = true;
                } else if (op.startsWith(oldDot)) {
                    node.operands.set(i, newPrefix + "." + op.substring(oldDot.length()));
                    changed = true;
                }
            }
        } else if (node.type == RungNode.NodeType.SERIES && node.children != null) {
            for (RungNode c : node.children) {
                if (replaceInAst(c, oldPrefix, newPrefix)) changed = true;
            }
        } else if (node.type == RungNode.NodeType.PARALLEL && node.branches != null) {
            for (RungNode b : node.branches) {
                if (replaceInAst(b, oldPrefix, newPrefix)) changed = true;
            }
        }
        return changed;
    }

    private void collectXrefs(String routineName, int rungNum, RungNode node, Map<String, List<Map<String, Object>>> xrefs) {
        if (node == null) return;
        if (node.type == RungNode.NodeType.INSTRUCTION) {
            String inst = node.instruction != null ? node.instruction.toUpperCase() : "";
            for (int i = 0; i < node.operands.size(); i++) {
                String op = node.operands.get(i);
                if (op == null || op.isEmpty() || op.equals("?") || RungNode.isNumericLiteral(op)) continue;
                if (inst.equals("JSR") || inst.equals("JMP") || inst.equals("LBL")) continue;
                String norm = TagImageManager.normalizeTagPath(op);
                if (norm.isEmpty()) continue;
                boolean destructive = inst.equals("OTE") || inst.equals("OTL") || inst.equals("OTU")
                        || inst.equals("TON") || inst.equals("TOF") || inst.equals("CTU") || inst.equals("RES")
                        || (inst.equals("MOV") && i == 1)
                        || ((inst.equals("ADD") || inst.equals("SUB") || inst.equals("MUL") || inst.equals("DIV") || inst.equals("PID")) && i == 2);

                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("routine", routineName);
                ref.put("rung", rungNum);
                ref.put("instruction", inst);
                ref.put("access", destructive ? "WRITE (Destructive)" : "READ");
                xrefs.computeIfAbsent(norm, k -> new ArrayList<>()).add(ref);
            }
        } else if (node.type == RungNode.NodeType.SERIES) {
            for (RungNode c : node.children) collectXrefs(routineName, rungNum, c, xrefs);
        } else if (node.type == RungNode.NodeType.PARALLEL) {
            for (RungNode b : node.branches) collectXrefs(routineName, rungNum, b, xrefs);
        }
    }

    public Map<String, Object> getFullStudioSnapshot() {
        return getStudioSnapshot(null, true);
    }

    /**
     * Returns the Studio snapshot. When {@code activeRoutineName} is specified and exists in the
     * active program, only that routine's full rung ASTs &amp; live values are serialized while
     * other routines return lightweight organizer summaries (dramatically reducing Gateway CPU
     * and JSON payload size for large imported .ACD/.L5X projects).
     */
    public Map<String, Object> getStudioSnapshot(String activeRoutineName, boolean includeCrossReferences) {
        Map<String, Object> root = new LinkedHashMap<>();
        LadderProgram activeProg = getActiveProgram();

        Map<String, Object> controller = new LinkedHashMap<>();
        controller.put("name", "Ignition_Gateway_SoftPLC");
        controller.put("mode", mode.name());
        controller.put("scanRateMs", scanRateMs);
        controller.put("watchdogMs", watchdogMs);
        controller.put("scanCount", scanCount.get());
        controller.put("lastScanMs", lastScanMs);
        controller.put("maxScanMs", maxScanMs);
        controller.put("avgScanMs", avgScanMs);
        controller.put("faultMessage", faultMessage);
        controller.put("dirtyWritesTotal", tagImage.getTotalDirtyWrites());
        controller.put("writesPerSec", tagImage.getWritesPerSec());
        controller.put("activeForcesCount", tagImage.getForcedTags().size());
        controller.put("activeProgram", activeProg.name);
        controller.put("mainRoutine", activeProg.mainRoutineName);
        root.put("controller", controller);

        // List all Programs sorted by name (with MainProgram first)
        List<String> progNames = new ArrayList<>(programs.keySet());
        progNames.sort((a, b) -> {
            if (a.equals("MainProgram")) return -1;
            if (b.equals("MainProgram")) return 1;
            return a.compareToIgnoreCase(b);
        });
        List<Map<String, Object>> programSummaries = new ArrayList<>();
        int totalRoutines = 0;
        int totalRungs = 0;
        int totalAstNodes = 0;
        int enabledProgramsCount = 0;
        for (String pn : progNames) {
            LadderProgram lp = programs.get(pn);
            if (lp.enabled) enabledProgramsCount++;
            totalRoutines += lp.routines.size();
            totalRungs += lp.getTotalRungCount();
            totalAstNodes += lp.getTotalAstNodeCount();
            programSummaries.add(lp.toSummaryMap());
        }
        root.put("programs", programSummaries);
        root.put("activeProgram", activeProg.name);

        // Build Module Resource & Performance Diagnostics
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long heapTotalBytes = Runtime.getRuntime().totalMemory();
        long heapFreeBytes = Runtime.getRuntime().freeMemory();
        long heapMaxBytes = Runtime.getRuntime().maxMemory();
        double heapUsedMb = Math.round(((heapTotalBytes - heapFreeBytes) / (1024.0 * 1024.0)) * 10.0) / 10.0;
        double heapCommittedMb = Math.round((heapTotalBytes / (1024.0 * 1024.0)) * 10.0) / 10.0;
        double heapMaxMb = Math.round((heapMaxBytes / (1024.0 * 1024.0)) * 10.0) / 10.0;

        int tagCacheSize = tagImage.getAllTagsSnapshot().size();
        double moduleRamKb = Math.round(((programs.size() * 512.0 + totalRoutines * 256.0 + totalRungs * 192.0 + totalAstNodes * 128.0 + tagCacheSize * 256.0) / 1024.0) * 100.0) / 100.0;
        double dutyCyclePct = scanRateMs > 0 ? Math.round(((avgScanMs / (double) scanRateMs) * 100.0) * 1000.0) / 1000.0 : 0.0;
        double systemCpuPct = Math.round((dutyCyclePct / Math.max(1, cpuCores)) * 1000.0) / 1000.0;

        long threadCpuMs = 0L;
        int gatewayTotalThreads = Thread.activeCount();
        try {
            java.lang.management.ThreadMXBean tmxb = java.lang.management.ManagementFactory.getThreadMXBean();
            gatewayTotalThreads = tmxb.getThreadCount();
            if (scanThreadId > 0 && tmxb.isThreadCpuTimeSupported()) {
                long nanos = tmxb.getThreadCpuTime(scanThreadId);
                if (nanos > 0) threadCpuMs = nanos / 1_000_000L;
            }
        } catch (Exception ignored) {
        }

        diagnostics.put("moduleThreadCount", executor != null ? 1 : 0);
        diagnostics.put("threadsPerProgram", 0);
        diagnostics.put("scanThreadName", "Ignition-LadderPLC-ScanThread");
        diagnostics.put("gatewayTotalThreads", gatewayTotalThreads);
        diagnostics.put("totalPrograms", programs.size());
        diagnostics.put("enabledPrograms", enabledProgramsCount);
        diagnostics.put("totalRoutines", totalRoutines);
        diagnostics.put("totalRungs", totalRungs);
        diagnostics.put("compiledAstNodes", totalAstNodes);
        diagnostics.put("cachedTagsCount", tagCacheSize);
        diagnostics.put("moduleEstimatedRamKb", moduleRamKb);
        diagnostics.put("jvmHeapUsedMb", heapUsedMb);
        diagnostics.put("jvmHeapCommittedMb", heapCommittedMb);
        diagnostics.put("jvmHeapMaxMb", heapMaxMb);
        diagnostics.put("cpuCores", cpuCores);
        diagnostics.put("scanDutyCyclePct", dutyCyclePct);
        diagnostics.put("estimatedSystemCpuPct", systemCpuPct);
        diagnostics.put("threadCpuTimeMs", threadCpuMs);
        root.put("diagnostics", diagnostics);

        // Sort routines of activeProgram with MainRoutine first
        List<String> names = new ArrayList<>(activeProg.routines.keySet());
        names.sort((a, b) -> {
            if (a.equals(activeProg.mainRoutineName)) return -1;
            if (b.equals(activeProg.mainRoutineName)) return 1;
            return a.compareToIgnoreCase(b);
        });
        boolean scopeToSingleRoutine = activeRoutineName != null && !activeRoutineName.isEmpty()
                && activeProg.routines.containsKey(activeRoutineName);
        Map<String, Object> routineMap = new LinkedHashMap<>();
        for (String name : names) {
            LadderRoutine r = activeProg.routines.get(name);
            if (!scopeToSingleRoutine || name.equals(activeRoutineName)) {
                routineMap.put(name, r.toMap(tagImage));
            } else {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("name", r.name);
                summary.put("type", r.type);
                summary.put("description", r.description);
                summary.put("rungCount", r.rungs.size());
                summary.put("summaryOnly", true);
                summary.put("rungs", Collections.emptyList());
                routineMap.put(name, summary);
            }
        }
        root.put("routines", routineMap);
        root.put("aois", getAoiSnapshot());
        root.put("tags", tagImage.getAllTagsSnapshot());
        if (includeCrossReferences) {
            root.put("crossReferences", getCrossReferences());
        }
        return root;
    }

    public Map<String, Object> getAoiSnapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(aois.keySet());
        Collections.sort(names);
        for (String name : names) {
            AoiDefinition aoi = aois.get(name);
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("name", aoi.name);
            am.put("description", aoi.description);
            am.put("params", aoi.params);
            List<Map<String, Object>> rungsList = new ArrayList<>();
            for (LadderRung r : aoi.rungs) {
                rungsList.add(r.toMap(tagImage));
            }
            am.put("rungs", rungsList);
            map.put(name, am);
        }
        return map;
    }

    public synchronized AoiDefinition upsertAoi(String name, String description,
                                                List<Map<String, Object>> rawParams,
                                                List<Map<String, Object>> rawRungs) {
        String cleanName = UdtTimerCounterManager.sanitizeTagName(name);
        if (cleanName.isEmpty()) throw new IllegalArgumentException("AOI name is required.");
        AoiDefinition aoi = new AoiDefinition(cleanName, description);
        if (rawParams != null) {
            for (Map<String, Object> pm : rawParams) {
                String pName = pm.get("name") != null ? pm.get("name").toString().trim() : "";
                if (pName.isEmpty()) continue;
                String dt = pm.get("dataType") != null ? pm.get("dataType").toString() : "BOOL";
                String usage = pm.get("usage") != null ? pm.get("usage").toString() : "INOUT";
                aoi.params.add(new AoiDefinition.AoiParam(pName, dt, usage));
            }
        }
        if (rawRungs != null) {
            int idx = 0;
            for (Map<String, Object> rm : rawRungs) {
                String comment = rm.get("comment") != null ? rm.get("comment").toString() : "";
                String mnemonic = rm.get("mnemonic") != null ? rm.get("mnemonic").toString() : "";
                aoi.rungs.add(new LadderRung(idx++, comment, mnemonic));
            }
        }
        aoi.reindexAndCompile();
        aois.put(cleanName, aoi);
        if (udtManager != null && udtManager.isAvailable()) {
            udtManager.ensureAoiUdtDefinition(aoi);
        }
        saveProjectToDisk();
        return aoi;
    }

    public synchronized boolean deleteAoi(String name) {
        boolean removed = aois.remove(name) != null;
        if (removed) saveProjectToDisk();
        return removed;
    }

    public synchronized boolean renameAoi(String oldName, String newName, String newDescription) {
        if (oldName == null || !aois.containsKey(oldName)) return false;
        AoiDefinition existing = aois.get(oldName);
        if (newDescription != null) {
            existing.description = newDescription;
        }
        String cleanNew = newName != null ? newName.trim() : "";
        if (!cleanNew.isEmpty() && !cleanNew.equals(oldName)) {
            if (aois.containsKey(cleanNew)) return false;
            aois.remove(oldName);
            existing.name = cleanNew;
            aois.put(cleanNew, existing);
            if (udtManager != null) {
                udtManager.ensureAoiUdtDefinition(existing);
            }
        }
        saveProjectToDisk();
        return true;
    }

    public Map<String, AoiDefinition> getAoiMap() {
        return aois;
    }

    public synchronized void saveProjectToDisk() {
        if (storageFile == null) return;
        try {
            File parent = storageFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Map<String, Object> persisted = new LinkedHashMap<>();
            persisted.put("scanRateMs", scanRateMs);
            persisted.put("watchdogMs", watchdogMs);
            persisted.put("activeProgramName", activeProgramName);
            persisted.put("programs", programs);
            persisted.put("aois", aois);
            String json = gson.toJson(persisted);
            Files.write(storageFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warn("Could not save LadderPLC project to disk: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized void loadProjectFromDisk() {
        if (storageFile == null || !storageFile.exists()) return;
        try {
            loadDefaultIndustrialProject();
            String json = new String(Files.readAllBytes(storageFile.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> data = gson.fromJson(json, Map.class);
            if (data == null) return;

            if (data.containsKey("programs")) {
                Map<String, Object> rawProgs = (Map<String, Object>) data.get("programs");
                if (rawProgs != null && !rawProgs.isEmpty()) {
                    programs.clear();
                    for (Map.Entry<String, Object> entry : rawProgs.entrySet()) {
                        String pJson = gson.toJson(entry.getValue());
                        LadderProgram lp = gson.fromJson(pJson, LadderProgram.class);
                        lp.ensureCompiled();
                        programs.put(entry.getKey(), lp);
                    }
                    if (data.get("activeProgramName") != null) {
                        String ap = data.get("activeProgramName").toString();
                        if (programs.containsKey(ap)) {
                            activeProgramName = ap;
                        }
                    }
                }
            } else if (data.containsKey("routines")) {
                // Migrate legacy single-program project.json into MainProgram
                Map<String, Object> rawRoutines = (Map<String, Object>) data.get("routines");
                if (rawRoutines != null && !rawRoutines.isEmpty()) {
                    LadderProgram mainProg = getActiveProgram();
                    mainProg.routines.clear();
                    for (Map.Entry<String, Object> entry : rawRoutines.entrySet()) {
                        String rJson = gson.toJson(entry.getValue());
                        LadderRoutine lr = gson.fromJson(rJson, LadderRoutine.class);
                        lr.reindexAndCompile();
                        mainProg.routines.put(entry.getKey(), lr);
                    }
                }
            }
            if (data.containsKey("aois")) {
                Map<String, Object> rawAois = (Map<String, Object>) data.get("aois");
                if (rawAois != null) {
                    for (Map.Entry<String, Object> entry : rawAois.entrySet()) {
                        String aJson = gson.toJson(entry.getValue());
                        AoiDefinition aoi = gson.fromJson(aJson, AoiDefinition.class);
                        if (aoi != null) {
                            aoi.reindexAndCompile();
                            aois.put(entry.getKey(), aoi);
                            if (udtManager != null && udtManager.isAvailable()) {
                                udtManager.ensureAoiUdtDefinition(aoi);
                            }
                        }
                    }
                }
            }
            refreshAllTagSubscriptions();
        } catch (Exception e) {
            logger.warn("Failed loading project from disk, falling back to default project: {}", e.getMessage());
            loadDefaultIndustrialProject();
        }
    }

    private static class EvalContext {
        final LadderProgram program;
        final LadderRoutine routine;
        final int callDepth;
        final long scanStartNanos;
        final long watchdogLimitNanos;
        final List<Object> inputParams;
        String jumpTargetLabel = null;
        String aoiInstanceBase = null;

        EvalContext(LadderProgram program, LadderRoutine routine, int callDepth, long scanStartNanos, long watchdogLimitNanos, List<Object> inputParams) {
            this.program = program;
            this.routine = routine;
            this.callDepth = callDepth;
            this.scanStartNanos = scanStartNanos;
            this.watchdogLimitNanos = watchdogLimitNanos;
            this.inputParams = inputParams;
        }
    }

    private static class WatchdogFaultException extends RuntimeException {
        WatchdogFaultException(String message) {
            super(message);
        }
    }
}
