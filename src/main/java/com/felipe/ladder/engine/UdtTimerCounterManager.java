package com.felipe.ladder.engine;

import com.inductiveautomation.ignition.common.browsing.BrowseFilter;
import com.inductiveautomation.ignition.common.browsing.Results;
import com.inductiveautomation.ignition.common.model.values.BasicQualifiedValue;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import com.inductiveautomation.ignition.common.tags.browsing.NodeDescription;
import com.inductiveautomation.ignition.common.tags.config.BasicTagConfiguration;
import com.inductiveautomation.ignition.common.tags.config.CollisionPolicy;
import com.inductiveautomation.ignition.common.tags.config.TagConfiguration;
import com.inductiveautomation.ignition.common.tags.config.properties.WellKnownTagProps;
import com.inductiveautomation.ignition.common.tags.config.types.OpcTagTypeProperties;
import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.TagProvider;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Manages real Ignition UDT Definitions (LadderLogic/TIMER and LadderLogic/COUNTER)
 * and UDT Instances inside [LadderPLC]LadderLogic/Timers and [LadderPLC]LadderLogic/Counters.
 */
public class UdtTimerCounterManager {

    private static final Logger logger = LoggerFactory.getLogger(UdtTimerCounterManager.class);

    public static final String DEFAULT_PROVIDER = TagImageManager.MANAGED_PROVIDER_NAME;
    public static final String UDT_FOLDER_NAME = "LadderLogic";
    public static final String TIMER_TYPE_ID = "LadderLogic/TIMER";
    public static final String COUNTER_TYPE_ID = "LadderLogic/COUNTER";
    public static final String PID_TYPE_ID = "LadderLogic/PID";
    public static final String TIMERS_FOLDER_PATH = "[LadderPLC]LadderLogic/Timers";
    public static final String COUNTERS_FOLDER_PATH = "[LadderPLC]LadderLogic/Counters";
    public static final String PIDS_FOLDER_PATH = "[LadderPLC]LadderLogic/PIDs";
    public static final String AOIS_FOLDER_PATH = "[LadderPLC]LadderLogic/AOIs";

    private static final String[] TIMER_MEMBERS = {"PRE", "ACC", "EN", "TT", "DN"};
    private static final String[] COUNTER_MEMBERS = {"PRE", "ACC", "CU", "CD", "DN", "OV", "UN", "RES"};
    public static final String[] PID_MEMBERS = {"PV", "SP", "CV", "KP", "KI", "KD", "ERR", "EN", "CYCLE_MS", "MIN_SW_MS", "OUT"};

    private final GatewayTagManager tagManager;
    private final TagImageManager tagImage;
    private volatile boolean definitionsInitialized = false;

    public UdtTimerCounterManager(GatewayTagManager tagManager, TagImageManager tagImage) {
        this.tagManager = tagManager;
        this.tagImage = tagImage;
    }

    public boolean isAvailable() {
        return tagManager != null && tagManager.getTagProvider(DEFAULT_PROVIDER) != null;
    }

    /**
     * Ensures [LadderPLC]_types_/LadderLogic/{TIMER,COUNTER} UDT definitions and
     * [LadderPLC]LadderLogic/{Timers,Counters,AOIs} folders exist.
     */
    public synchronized boolean ensureUdtDefinitionsAndFolders() {
        if (!isAvailable()) {
            return false;
        }
        try {
            TagProvider provider = tagManager.getTagProvider(DEFAULT_PROVIDER);
            if (provider == null) return false;

            // 1. Create _types_/LadderLogic folder + TIMER & COUNTER UDT definitions
            BasicTagConfiguration typesFolder = new BasicTagConfiguration();
            typesFolder.setName(UDT_FOLDER_NAME);
            typesFolder.setType(TagObjectType.Folder);
            typesFolder.set(WellKnownTagProps.Path, TagPathParser.parse("[" + DEFAULT_PROVIDER + "]_types_/" + UDT_FOLDER_NAME));

            BasicTagConfiguration timerUdt = buildTimerUdtDefinition();
            BasicTagConfiguration counterUdt = buildCounterUdtDefinition();
            BasicTagConfiguration pidUdt = buildPidUdtDefinition();
            typesFolder.addChild(timerUdt);
            typesFolder.addChild(counterUdt);
            typesFolder.addChild(pidUdt);

            // 2. Create [LadderPLC]LadderLogic/{Timers,Counters,PIDs,AOIs} instance folders
            BasicTagConfiguration rootFolder = new BasicTagConfiguration();
            rootFolder.setName(UDT_FOLDER_NAME);
            rootFolder.setType(TagObjectType.Folder);
            rootFolder.set(WellKnownTagProps.Path, TagPathParser.parse("[" + DEFAULT_PROVIDER + "]" + UDT_FOLDER_NAME));

            BasicTagConfiguration timersFolder = new BasicTagConfiguration();
            timersFolder.setName("Timers");
            timersFolder.setType(TagObjectType.Folder);
            timersFolder.set(WellKnownTagProps.Path, TagPathParser.parse(TIMERS_FOLDER_PATH));

            BasicTagConfiguration countersFolder = new BasicTagConfiguration();
            countersFolder.setName("Counters");
            countersFolder.setType(TagObjectType.Folder);
            countersFolder.set(WellKnownTagProps.Path, TagPathParser.parse(COUNTERS_FOLDER_PATH));

            BasicTagConfiguration pidsFolder = new BasicTagConfiguration();
            pidsFolder.setName("PIDs");
            pidsFolder.setType(TagObjectType.Folder);
            pidsFolder.set(WellKnownTagProps.Path, TagPathParser.parse(PIDS_FOLDER_PATH));

            BasicTagConfiguration aoisFolder = new BasicTagConfiguration();
            aoisFolder.setName("AOIs");
            aoisFolder.setType(TagObjectType.Folder);
            aoisFolder.set(WellKnownTagProps.Path, TagPathParser.parse(AOIS_FOLDER_PATH));

            rootFolder.addChild(timersFolder);
            rootFolder.addChild(countersFolder);
            rootFolder.addChild(pidsFolder);
            rootFolder.addChild(aoisFolder);

            List<TagConfiguration> configs = new ArrayList<>();
            configs.add(typesFolder);
            configs.add(rootFolder);

            provider.saveTagConfigsAsync(configs, CollisionPolicy.MergeOverwrite).get(5, TimeUnit.SECONDS);
            definitionsInitialized = true;
            logger.info("Verified Ignition UDT definitions [LadderPLC]_types_/{TIMER,COUNTER} and folders [LadderPLC]LadderLogic/{{Timers,Counters,AOIs}}.");
            return true;
        } catch (Exception e) {
            logger.warn("Could not initialize LadderLogic UDT definitions in [LadderPLC]: {}", e.getMessage());
            return false;
        }
    }

    private BasicTagConfiguration buildTimerUdtDefinition() {
        BasicTagConfiguration udt = new BasicTagConfiguration();
        udt.setName("TIMER");
        udt.setType(TagObjectType.UdtType);
        udt.set(WellKnownTagProps.Documentation, "Industrial PLC TIMER UDT (PRE, ACC, EN, TT, DN)");
        udt.addChild(memoryMember("PRE", DataType.Int8, 5000L, "Timer Preset (ms)"));
        udt.addChild(memoryMember("ACC", DataType.Int8, 0L, "Timer Accumulated (ms)"));
        udt.addChild(memoryMember("EN", DataType.Boolean, false, "Timer Enable Bit"));
        udt.addChild(memoryMember("TT", DataType.Boolean, false, "Timer Timing Bit"));
        udt.addChild(memoryMember("DN", DataType.Boolean, false, "Timer Done Bit"));
        return udt;
    }

    private BasicTagConfiguration buildCounterUdtDefinition() {
        BasicTagConfiguration udt = new BasicTagConfiguration();
        udt.setName("COUNTER");
        udt.setType(TagObjectType.UdtType);
        udt.set(WellKnownTagProps.Documentation, "Industrial PLC COUNTER UDT (PRE, ACC, CU, CD, DN, OV, UN)");
        udt.addChild(memoryMember("PRE", DataType.Int8, 100L, "Counter Preset"));
        udt.addChild(memoryMember("ACC", DataType.Int8, 0L, "Counter Accumulated Count"));
        udt.addChild(memoryMember("CU", DataType.Boolean, false, "Count Up Enable Bit"));
        udt.addChild(memoryMember("CD", DataType.Boolean, false, "Count Down Enable Bit"));
        udt.addChild(memoryMember("DN", DataType.Boolean, false, "Counter Done Bit"));
        udt.addChild(memoryMember("OV", DataType.Boolean, false, "Counter Overflow Bit"));
        udt.addChild(memoryMember("UN", DataType.Boolean, false, "Counter Underflow Bit"));
        udt.addChild(memoryMember("RES", DataType.Boolean, false, "Counter Reset Bit (resets ACC=0 & DN=0 when TRUE)"));
        return udt;
    }

    private BasicTagConfiguration buildPidUdtDefinition() {
        BasicTagConfiguration udt = new BasicTagConfiguration();
        udt.setName("PID");
        udt.setType(TagObjectType.UdtType);
        udt.set(WellKnownTagProps.Documentation, "Industrial PLC PID UDT (PV, SP, CV, KP, KI, KD, ERR, EN)");
        udt.addChild(memoryMember("PV", DataType.Float8, 0.0, "Process Variable"));
        udt.addChild(memoryMember("SP", DataType.Float8, 0.0, "Setpoint"));
        udt.addChild(memoryMember("CV", DataType.Float8, 0.0, "Control Variable Output (0-100%)"));
        udt.addChild(memoryMember("KP", DataType.Float8, 1.0, "Proportional Gain"));
        udt.addChild(memoryMember("KI", DataType.Float8, 0.1, "Integral Gain (1/s)"));
        udt.addChild(memoryMember("KD", DataType.Float8, 0.0, "Derivative Gain (s)"));
        udt.addChild(memoryMember("ERR", DataType.Float8, 0.0, "Loop Error (SP - PV)"));
        udt.addChild(memoryMember("EN", DataType.Boolean, false, "PID Enable Bit"));
        udt.addChild(memoryMember("CYCLE_MS", DataType.Int4, 10000, "Digital PID Time-Proportioning Cycle Window (ms)"));
        udt.addChild(memoryMember("MIN_SW_MS", DataType.Int4, 2000, "Digital PID Minimum ON/OFF Dwell Delay (ms)"));
        udt.addChild(memoryMember("OUT", DataType.Boolean, false, "Digital PID Relay Output Bit"));
        return udt;
    }

    private BasicTagConfiguration memoryMember(String name, DataType dt, Object defaultVal, String doc) {
        BasicTagConfiguration tag = new BasicTagConfiguration();
        tag.setName(name);
        tag.setType(TagObjectType.AtomicTag);
        tag.set(WellKnownTagProps.ValueSource, WellKnownTagProps.MEMORY_TAG_TYPE);
        tag.set(WellKnownTagProps.DataType, dt);
        tag.set(WellKnownTagProps.Value, new BasicQualifiedValue(defaultVal));
        tag.set(WellKnownTagProps.Documentation, doc);
        return tag;
    }

    public String createTimerInstance(String rawName, long presetMs, String description) throws Exception {
        return createInstance("TIMER", rawName, presetMs, description);
    }

    public String createCounterInstance(String rawName, long presetCount, String description) throws Exception {
        return createInstance("COUNTER", rawName, presetCount, description);
    }

    public String createFolder(String rawFolderPath) throws Exception {
        if (tagManager == null) {
            throw new IllegalStateException("GatewayTagManager not available.");
        }
        TagProvider provider = tagManager.getTagProvider(DEFAULT_PROVIDER);
        if (provider == null) {
            throw new IllegalStateException("Tag provider [LadderPLC] is not available.");
        }
        String clean = rawFolderPath == null ? "" : rawFolderPath.trim();
        if (clean.startsWith("[" + DEFAULT_PROVIDER + "]")) {
            clean = clean.substring(("[" + DEFAULT_PROVIDER + "]").length());
        }
        while (clean.startsWith("/")) clean = clean.substring(1);
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Folder path cannot be empty.");
        }
        String[] segments = clean.split("/");
        BasicTagConfiguration rootCfg = null;
        BasicTagConfiguration curCfg = null;
        StringBuilder accPath = new StringBuilder("[" + DEFAULT_PROVIDER + "]");
        for (int i = 0; i < segments.length; i++) {
            String seg = sanitizeTagName(segments[i]);
            if (seg.isEmpty()) continue;
            if (i > 0) accPath.append("/");
            accPath.append(seg);
            BasicTagConfiguration folderCfg = new BasicTagConfiguration();
            folderCfg.setName(seg);
            folderCfg.setType(TagObjectType.Folder);
            folderCfg.set(WellKnownTagProps.Path, TagPathParser.parse(accPath.toString()));
            if (rootCfg == null) {
                rootCfg = folderCfg;
            } else {
                curCfg.addChild(folderCfg);
            }
            curCfg = folderCfg;
        }
        if (rootCfg == null) {
            throw new IllegalArgumentException("Folder path cannot be empty.");
        }
        provider.saveTagConfigsAsync(Collections.singletonList(rootCfg), CollisionPolicy.MergeOverwrite)
                .get(5, TimeUnit.SECONDS);
        return accPath.toString();
    }

    public void deleteFolder(String rawFolderPath) throws Exception {
        if (tagManager == null) {
            throw new IllegalStateException("GatewayTagManager not available.");
        }
        TagProvider provider = tagManager.getTagProvider(DEFAULT_PROVIDER);
        if (provider == null) {
            throw new IllegalStateException("Tag provider [LadderPLC] is not available.");
        }
        String norm = rawFolderPath == null ? "" : rawFolderPath.trim();
        if (!norm.startsWith("[" + DEFAULT_PROVIDER + "]")) {
            norm = "[" + DEFAULT_PROVIDER + "]" + norm;
        }
        while (norm.endsWith("/")) norm = norm.substring(0, norm.length() - 1);
        if (norm.equals("[" + DEFAULT_PROVIDER + "]") || norm.startsWith("[" + DEFAULT_PROVIDER + "]System")) {
            throw new IllegalArgumentException("Cannot delete root or System folder.");
        }
        TagPath tp = TagPathParser.parse(norm);
        provider.removeTagConfigsAsync(Collections.singletonList(tp)).get(5, TimeUnit.SECONDS);
        tagImage.deleteTagsWithPrefix(norm);
    }

    public String createInstance(String kind, String rawName, long preset, String description) throws Exception {
        String cleanName = sanitizeTagName(rawName);
        if (cleanName.isEmpty()) {
            throw new IllegalArgumentException("Instance name cannot be empty.");
        }
        boolean isPid = "PID".equalsIgnoreCase(kind);
        boolean isCounter = "COUNTER".equalsIgnoreCase(kind);
        String folderPath = isPid ? PIDS_FOLDER_PATH : (isCounter ? COUNTERS_FOLDER_PATH : TIMERS_FOLDER_PATH);
        String basePath = folderPath + "/" + cleanName;

        if (!definitionsInitialized) {
            ensureUdtDefinitionsAndFolders();
        }

        TagProvider provider = tagManager != null ? tagManager.getTagProvider(DEFAULT_PROVIDER) : null;
        if (provider == null) {
            throw new IllegalStateException("Tag provider [LadderPLC] is not available.");
        }

        BasicTagConfiguration rootFolder = new BasicTagConfiguration();
        rootFolder.setName(UDT_FOLDER_NAME);
        rootFolder.setType(TagObjectType.Folder);

        BasicTagConfiguration subFolder = new BasicTagConfiguration();
        subFolder.setName(isPid ? "PIDs" : (isCounter ? "Counters" : "Timers"));
        subFolder.setType(TagObjectType.Folder);

        BasicTagConfiguration instance = new BasicTagConfiguration();
        instance.setName(cleanName);
        instance.setType(TagObjectType.UdtInstance);
        instance.set(WellKnownTagProps.TypeId, isPid ? PID_TYPE_ID : (isCounter ? COUNTER_TYPE_ID : TIMER_TYPE_ID));
        if (description != null && !description.isBlank()) {
            instance.set(WellKnownTagProps.Documentation, description.trim());
        }

        if (!isPid) {
            // Set initial PRE value on Timer/Counter UDT instance
            BasicTagConfiguration preOverride = new BasicTagConfiguration();
            preOverride.setName("PRE");
            preOverride.setType(TagObjectType.AtomicTag);
            preOverride.set(WellKnownTagProps.Value, new BasicQualifiedValue(preset));
            instance.addChild(preOverride);
        }

        subFolder.addChild(instance);
        rootFolder.addChild(subFolder);

        provider.saveTagConfigsAsync(Collections.singletonList(rootFolder), CollisionPolicy.MergeOverwrite)
                .get(5, TimeUnit.SECONDS);

        if (isPid) {
            subscribePidMembers(basePath);
        } else {
            subscribeInstanceMembers(basePath, isCounter, preset);
        }
        logger.info("Created {} UDT instance at {}", isPid ? "PID" : (isCounter ? "COUNTER" : "TIMER"), basePath);
        return basePath;
    }

    public void subscribePidMembers(String basePath) {
        for (String m : PID_MEMBERS) {
            String memberPath = basePath + "/" + m;
            if ("EN".equals(m) || "OUT".equals(m)) {
                tagImage.registerTag(memberPath, false, "BOOL", "PID " + m);
            } else if ("CYCLE_MS".equals(m)) {
                tagImage.registerTag(memberPath, 10000L, "DINT", "Digital PID Cycle Window (ms)");
            } else if ("MIN_SW_MS".equals(m)) {
                tagImage.registerTag(memberPath, 2000L, "DINT", "Digital PID Min Switch Delay (ms)");
            } else if ("KP".equals(m)) {
                tagImage.registerTag(memberPath, 1.0, "REAL", "PID Proportional Gain");
            } else if ("KI".equals(m)) {
                tagImage.registerTag(memberPath, 0.1, "REAL", "PID Integral Gain (1/s)");
            } else {
                tagImage.registerTag(memberPath, 0.0, "REAL", "PID " + m);
            }
        }
    }

    public void subscribeInstanceMembers(String basePath, boolean isCounter, long defaultPreset) {
        String[] members = isCounter ? COUNTER_MEMBERS : TIMER_MEMBERS;
        for (String m : members) {
            String memberPath = basePath + "/" + m;
            if ("PRE".equals(m)) {
                tagImage.seedIfAbsent(memberPath, defaultPreset, "DINT", "");
            } else if ("ACC".equals(m)) {
                tagImage.seedIfAbsent(memberPath, 0L, "DINT", "");
            } else {
                tagImage.seedIfAbsent(memberPath, false, "BOOL", "");
            }
            tagImage.ensureSubscribed(memberPath);
        }
    }

    public String renameInstance(String oldBasePath, String rawNewName) throws Exception {
        String cleanNewName = sanitizeTagName(rawNewName);
        if (cleanNewName.isEmpty()) {
            throw new IllegalArgumentException("New name cannot be empty.");
        }
        if (tagManager == null) {
            throw new IllegalStateException("GatewayTagManager not available.");
        }
        TagPath oldTp = TagPathParser.parse(oldBasePath);
        tagManager.renameTag(oldTp, cleanNewName, CollisionPolicy.Abort).get(5, TimeUnit.SECONDS);

        int lastSlash = oldBasePath.lastIndexOf('/');
        String newBasePath = (lastSlash > 0 ? oldBasePath.substring(0, lastSlash + 1) : "") + cleanNewName;
        if (oldBasePath.contains("/PIDs/")) {
            subscribePidMembers(newBasePath);
        } else {
            boolean isCounter = oldBasePath.contains("/Counters/");
            subscribeInstanceMembers(newBasePath, isCounter, isCounter ? 100L : 5000L);
        }
        return newBasePath;
    }

    public void deleteInstance(String basePath) throws Exception {
        if (tagManager == null) {
            throw new IllegalStateException("GatewayTagManager not available.");
        }
        TagProvider provider = tagManager.getTagProvider(DEFAULT_PROVIDER);
        if (provider == null) {
            throw new IllegalStateException("Tag provider [LadderPLC] is not available.");
        }
        TagPath tp = TagPathParser.parse(basePath);
        provider.removeTagConfigsAsync(Collections.singletonList(tp)).get(5, TimeUnit.SECONDS);
    }

    public List<Map<String, Object>> listInstances(String filterKind) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (!isAvailable()) {
            return list;
        }
        if (filterKind == null || filterKind.isEmpty() || "ALL".equalsIgnoreCase(filterKind) || "TIMER".equalsIgnoreCase(filterKind)) {
            browseFolderInstances(TIMERS_FOLDER_PATH, "TIMER", list);
        }
        if (filterKind == null || filterKind.isEmpty() || "ALL".equalsIgnoreCase(filterKind) || "COUNTER".equalsIgnoreCase(filterKind)) {
            browseFolderInstances(COUNTERS_FOLDER_PATH, "COUNTER", list);
        }
        if (filterKind == null || filterKind.isEmpty() || "ALL".equalsIgnoreCase(filterKind) || "PID".equalsIgnoreCase(filterKind)) {
            browseFolderInstances(PIDS_FOLDER_PATH, "PID", list);
        }
        return list;
    }

    private void browseFolderInstances(String folderPath, String kind, List<Map<String, Object>> out) {
        try {
            TagPath tp = TagPathParser.parse(folderPath);
            Results<NodeDescription> results = tagManager.browseAsync(tp, BrowseFilter.NONE).get(3, TimeUnit.SECONDS);
            if (results == null || results.getResults() == null) {
                return;
            }
            boolean isCounter = "COUNTER".equals(kind);
            boolean isPid = "PID".equals(kind);
            for (NodeDescription nd : results.getResults()) {
                if (!isPid && nd.getObjectType() == TagObjectType.Folder) continue;
                String name = nd.getName();
                String basePath = folderPath + "/" + name;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("basePath", basePath);
                row.put("kind", kind);
                if (isPid) {
                    subscribePidMembers(basePath);
                    row.put("pv", tagImage.readDouble(basePath + "/PV"));
                    row.put("sp", tagImage.readDouble(basePath + "/SP"));
                    row.put("cv", tagImage.readDouble(basePath + "/CV"));
                    row.put("kp", tagImage.readDouble(basePath + "/KP"));
                    row.put("ki", tagImage.readDouble(basePath + "/KI"));
                    row.put("kd", tagImage.readDouble(basePath + "/KD"));
                    row.put("err", tagImage.readDouble(basePath + "/ERR"));
                    row.put("en", tagImage.readBoolean(basePath + "/EN"));
                } else {
                    subscribeInstanceMembers(basePath, isCounter, isCounter ? 100L : 5000L);
                    row.put("pre", (long) tagImage.readDouble(basePath + "/PRE"));
                    row.put("acc", (long) tagImage.readDouble(basePath + "/ACC"));
                    row.put("dn", tagImage.readBoolean(basePath + "/DN"));
                    if (isCounter) {
                        row.put("cu", tagImage.readBoolean(basePath + "/CU"));
                        row.put("cd", tagImage.readBoolean(basePath + "/CD"));
                    } else {
                        row.put("en", tagImage.readBoolean(basePath + "/EN"));
                        row.put("tt", tagImage.readBoolean(basePath + "/TT"));
                    }
                }
                out.add(row);
            }
        } catch (Exception e) {
            logger.debug("Could not browse UDT folder {}: {}", folderPath, e.getMessage());
        }
    }

    public boolean ensureAoiUdtDefinition(AoiDefinition aoi) {
        if (!isAvailable() || aoi == null || aoi.name == null || aoi.name.isBlank()) {
            return false;
        }
        try {
            TagProvider provider = tagManager.getTagProvider(DEFAULT_PROVIDER);
            if (provider == null) return false;

            String udtName = "AOI_" + sanitizeTagName(aoi.name);
            BasicTagConfiguration typesFolder = new BasicTagConfiguration();
            typesFolder.setName(UDT_FOLDER_NAME);
            typesFolder.setType(TagObjectType.Folder);

            BasicTagConfiguration udt = new BasicTagConfiguration();
            udt.setName(udtName);
            udt.setType(TagObjectType.UdtType);
            udt.set(WellKnownTagProps.Documentation, aoi.description != null ? aoi.description : ("Add-On Instruction " + aoi.name));
            udt.addChild(memoryMember("EnableIn", DataType.Boolean, false, "AOI EnableIn"));
            udt.addChild(memoryMember("EnableOut", DataType.Boolean, false, "AOI EnableOut"));

            for (AoiDefinition.AoiParam p : aoi.params) {
                if (p == null || p.name == null || p.name.isBlank()) continue;
                String pn = sanitizeTagName(p.name);
                if ("EnableIn".equalsIgnoreCase(pn) || "EnableOut".equalsIgnoreCase(pn)) continue;
                String dtStr = p.dataType != null ? p.dataType.toUpperCase() : "BOOL";
                DataType dt = DataType.Boolean;
                Object defVal = false;
                if ("DINT".equals(dtStr) || "INT".equals(dtStr)) {
                    dt = DataType.Int8;
                    defVal = 0L;
                } else if ("REAL".equals(dtStr) || "FLOAT".equals(dtStr)) {
                    dt = DataType.Float8;
                    defVal = 0.0;
                }
                udt.addChild(memoryMember(pn, dt, defVal, (p.usage != null ? p.usage : "INOUT") + " Parameter"));
            }

            typesFolder.addChild(udt);
            provider.saveTagConfigsAsync(Collections.singletonList(typesFolder), CollisionPolicy.MergeOverwrite)
                    .get(5, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to save AOI UDT definition for {}: {}", aoi.name, e.getMessage());
            return false;
        }
    }

    public String createAoiInstance(AoiDefinition aoi, String rawInstanceName, String description) throws Exception {
        String cleanInst = sanitizeTagName(rawInstanceName);
        if (cleanInst.isEmpty()) {
            throw new IllegalArgumentException("AOI instance name cannot be empty.");
        }
        if (!definitionsInitialized) {
            ensureUdtDefinitionsAndFolders();
        }
        ensureAoiUdtDefinition(aoi);

        TagProvider provider = tagManager != null ? tagManager.getTagProvider(DEFAULT_PROVIDER) : null;
        if (provider == null) {
            throw new IllegalStateException("Tag provider [LadderPLC] is not available.");
        }

        String udtTypeId = UDT_FOLDER_NAME + "/AOI_" + sanitizeTagName(aoi.name);
        BasicTagConfiguration rootFolder = new BasicTagConfiguration();
        rootFolder.setName(UDT_FOLDER_NAME);
        rootFolder.setType(TagObjectType.Folder);

        BasicTagConfiguration aoisFolder = new BasicTagConfiguration();
        aoisFolder.setName("AOIs");
        aoisFolder.setType(TagObjectType.Folder);

        BasicTagConfiguration instCfg = new BasicTagConfiguration();
        instCfg.setName(cleanInst);
        instCfg.setType(TagObjectType.UdtInstance);
        instCfg.set(WellKnownTagProps.TypeId, udtTypeId);
        if (description != null && !description.isBlank()) {
            instCfg.set(WellKnownTagProps.Documentation, description.trim());
        }

        aoisFolder.addChild(instCfg);
        rootFolder.addChild(aoisFolder);

        provider.saveTagConfigsAsync(Collections.singletonList(rootFolder), CollisionPolicy.MergeOverwrite)
                .get(5, TimeUnit.SECONDS);

        String basePath = AOIS_FOLDER_PATH + "/" + cleanInst;
        subscribeAoiMembers(basePath, aoi);
        return basePath;
    }

    public void subscribeAoiMembers(String basePath, AoiDefinition aoi) {
        if (basePath == null || basePath.isEmpty()) return;
        tagImage.seedIfAbsent(basePath + "/EnableIn", false, "BOOL", "AOI EnableIn");
        tagImage.ensureSubscribed(basePath + "/EnableIn");
        tagImage.seedIfAbsent(basePath + "/EnableOut", false, "BOOL", "AOI EnableOut");
        tagImage.ensureSubscribed(basePath + "/EnableOut");
        if (aoi != null && aoi.params != null) {
            for (AoiDefinition.AoiParam p : aoi.params) {
                if (p == null || p.name == null || p.name.isBlank()) continue;
                String dt = p.dataType != null ? p.dataType.toUpperCase() : "BOOL";
                Object defVal = "BOOL".equals(dt) ? false : ("REAL".equals(dt) ? 0.0 : 0L);
                String mp = basePath + "/" + p.name;
                tagImage.seedIfAbsent(mp, defVal, dt, "AOI Param");
                tagImage.ensureSubscribed(mp);
            }
        }
    }

    public List<Map<String, Object>> listAoiInstances() {
        List<Map<String, Object>> list = new ArrayList<>();
        if (!isAvailable()) return list;
        try {
            Results<NodeDescription> results = tagManager.browseAsync(TagPathParser.parse(AOIS_FOLDER_PATH), BrowseFilter.NONE).get(3, TimeUnit.SECONDS);
            if (results != null && results.getResults() != null) {
                for (NodeDescription nd : results.getResults()) {
                    if (nd.getObjectType() == TagObjectType.Folder) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", nd.getName());
                    row.put("basePath", AOIS_FOLDER_PATH + "/" + nd.getName());
                    list.add(row);
                }
            }
        } catch (Exception e) {
            logger.debug("Could not browse AOIs folder: {}", e.getMessage());
        }
        return list;
    }

    public String createOpcTag(String providerName, String relativePath, String opcServer, String opcItemPath,
                               String dataTypeStr, String description) throws Exception {
        if (tagManager == null) {
            throw new IllegalStateException("GatewayTagManager not available.");
        }
        String provName = (providerName == null || providerName.isBlank()) ? "default" : providerName.trim();
        TagProvider provider = tagManager.getTagProvider(provName);
        if (provider == null) {
            throw new IllegalStateException("Tag provider [" + provName + "] is not available.");
        }
        String rel = relativePath == null ? "" : relativePath.trim();
        if (rel.startsWith("[" + provName + "]")) {
            rel = rel.substring(provName.length() + 2);
        }
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty()) {
            throw new IllegalArgumentException("Tag path cannot be empty.");
        }

        // Ensure parent folders exist in target provider
        String[] parts = rel.split("/");
        List<TagConfiguration> configs = new ArrayList<>();
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) acc.append("/");
            acc.append(parts[i]);
            TagConfiguration folderCfg = BasicTagConfiguration.createNew(
                    TagPathParser.parse("[" + provName + "]" + acc));
            folderCfg.setType(TagObjectType.Folder);
            configs.add(folderCfg);
        }

        String fullPath = "[" + provName + "]" + rel;
        TagConfiguration tagCfg = BasicTagConfiguration.createNew(TagPathParser.parse(fullPath));
        tagCfg.setType(TagObjectType.AtomicTag);
        tagCfg.set(WellKnownTagProps.ValueSource, OpcTagTypeProperties.TAG_TYPE);
        tagCfg.set(OpcTagTypeProperties.OPCServer,
                (opcServer == null || opcServer.isBlank()) ? "Ignition OPC UA Server" : opcServer.trim());
        tagCfg.set(OpcTagTypeProperties.OPCItemPath, opcItemPath);

        DataType dt = DataType.Int4;
        String dtUp = dataTypeStr != null ? dataTypeStr.trim().toUpperCase() : "BOOL";
        if ("BOOL".equals(dtUp) || "BOOLEAN".equals(dtUp)) {
            dt = DataType.Boolean;
        } else if ("REAL".equals(dtUp) || "FLOAT".equals(dtUp) || "FLOAT4".equals(dtUp)) {
            dt = DataType.Float4;
        } else if ("DOUBLE".equals(dtUp) || "FLOAT8".equals(dtUp)) {
            dt = DataType.Float8;
        } else if ("STRING".equals(dtUp)) {
            dt = DataType.String;
        } else if ("INT2".equals(dtUp) || "SHORT".equals(dtUp)) {
            dt = DataType.Int2;
        } else {
            dt = DataType.Int4;
        }
        tagCfg.set(WellKnownTagProps.DataType, dt);
        if (description != null && !description.isBlank()) {
            tagCfg.set(WellKnownTagProps.Documentation, description);
        }
        configs.add(tagCfg);

        provider.saveTagConfigsAsync(configs, CollisionPolicy.MergeOverwrite).get(5, TimeUnit.SECONDS);
        return fullPath;
    }

    public static String sanitizeTagName(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
    }
}
