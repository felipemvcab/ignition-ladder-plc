package com.antigravity.ladder.engine;

import com.inductiveautomation.ignition.common.browsing.BrowseFilter;
import com.inductiveautomation.ignition.common.browsing.Results;
import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import com.inductiveautomation.ignition.common.tags.browsing.NodeDescription;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.event.TagChangeEvent;
import com.inductiveautomation.ignition.common.tags.model.event.TagChangeListener;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.gateway.tags.managed.ManagedTagProvider;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-Performance Tag Image Manager implementing:
 *  1. Event-driven TagChangeListener input cache (Zero polling overhead during scan)
 *  2. In-memory Scan Image Snapshot (Deterministic synchronous PLC scan semantics)
 *  3. Dirty-Write Output Batching (Only writes to Ignition Gateway tags when a value actually changes)
 *  4. Studio 5000 style Tag Forcing table
 */
public class TagImageManager {

    private static final Logger logger = LoggerFactory.getLogger(TagImageManager.class);
    public static final String MANAGED_PROVIDER_NAME = "LadderPLC";

    private final GatewayTagManager tagManager;
    private final ManagedTagProvider managedProvider;

    // Live asynchronous cache updated by Ignition TagChangeListeners and ManagedTagProvider WriteHandlers
    private final ConcurrentHashMap<String, Object> liveInputCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tagDescriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tagDataTypes = new ConcurrentHashMap<>();

    // Synchronous scan image used during a single deterministic PLC scan
    private final ConcurrentHashMap<String, Object> scanImage = new ConcurrentHashMap<>();

    // Dirty outputs modified during the current scan
    private final Map<String, Object> dirtyOutputs = new LinkedHashMap<>();

    // Forced tags table (TagPath -> Forced Value)
    private final ConcurrentHashMap<String, Object> forcedTags = new ConcurrentHashMap<>();

    // Track active Gateway subscriptions so we never subscribe twice
    private final ConcurrentHashMap<String, TagChangeListener> activeSubscriptions = new ConcurrentHashMap<>();

    // Per-tag throttle timestamps & last flushed Gateway values (for 500ms .ACC write throttling)
    private final ConcurrentHashMap<String, Long> lastThrottledFlushMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> lastGatewayFlushedValues = new ConcurrentHashMap<>();

    private static final Set<String> UDT_MEMBER_SUFFIXES = Set.of(
            ".PRE", ".ACC", ".EN", ".TT", ".DN", ".CU", ".CD", ".OV", ".UN"
    );

    // Performance metrics
    private final AtomicLong totalDirtyWrites = new AtomicLong(0);
    private final AtomicLong lastSecondWrites = new AtomicLong(0);
    private volatile long writesPerSec = 0;
    private volatile long lastRateCalcMs = System.currentTimeMillis();

    public TagImageManager(GatewayTagManager tagManager, ManagedTagProvider managedProvider) {
        this.tagManager = tagManager;
        this.managedProvider = managedProvider;
    }

    public GatewayTagManager getGatewayTagManager() {
        return tagManager;
    }

    public static String normalizeTagPath(String rawPath) {
        if (rawPath == null) return "";
        String trimmed = rawPath.trim();
        if (trimmed.isEmpty() || trimmed.equals("?") || RungNode.isNumericLiteral(trimmed)) {
            return "";
        }
        if (trimmed.equalsIgnoreCase("S:FS")) {
            return "[" + MANAGED_PROVIDER_NAME + "]System/S_FS";
        }
        if (trimmed.startsWith("[")) {
            // Map Studio 5000 dot notation (e.g. [LadderPLC]LadderLogic/Timers/T1.DN) to Ignition UDT slash notation (/DN)
            if (!trimmed.startsWith("[" + MANAGED_PROVIDER_NAME + "]") || trimmed.startsWith("[" + MANAGED_PROVIDER_NAME + "]LadderLogic/")) {
                int lastDot = trimmed.lastIndexOf('.');
                if (lastDot > 0) {
                    String suffix = trimmed.substring(lastDot).toUpperCase();
                    if (UDT_MEMBER_SUFFIXES.contains(suffix)) {
                        return trimmed.substring(0, lastDot) + "/" + suffix.substring(1);
                    }
                }
            }
            return trimmed;
        }
        return "[" + MANAGED_PROVIDER_NAME + "]ControllerTags/" + trimmed;
    }

    /**
     * Builds the member path for a Timer or Counter structure:
     *  - Ignition UDTs ([LadderPLC]LadderLogic/Timers/T1 or [default]...) use slash notation: .../T1/DN
     *  - Legacy [LadderPLC]ControllerTags tags use dot notation: .../T1.DN
     */
    public static String memberPath(String basePath, String member) {
        String normBase = normalizeTagPath(basePath);
        if (normBase.isEmpty()) return "";
        if (normBase.contains("/Timers/") || normBase.contains("/Counters/") || normBase.contains("/PIDs/") || normBase.contains("/AOIs/")) {
            return normBase + "/" + member;
        }
        if (normBase.startsWith("[" + MANAGED_PROVIDER_NAME + "]") && !normBase.startsWith("[" + MANAGED_PROVIDER_NAME + "]LadderLogic/")) {
            return normBase + "." + member;
        }
        return normBase + "/" + member;
    }

    public void seedIfAbsent(String rawPath, Object initialValue, String dataType, String description) {
        String norm = normalizeTagPath(rawPath);
        if (norm.isEmpty()) return;
        if (description != null && !description.isEmpty()) {
            tagDescriptions.putIfAbsent(norm, description);
        }
        if (dataType != null && !dataType.isEmpty()) {
            tagDataTypes.putIfAbsent(norm, dataType);
        }
        if (initialValue != null) {
            liveInputCache.putIfAbsent(norm, initialValue);
            scanImage.putIfAbsent(norm, initialValue);
        }
    }

    public void registerTag(String rawPath, Object initialValue, String dataType, String description) {
        String norm = normalizeTagPath(rawPath);
        if (norm.isEmpty() || RungNode.isNumericLiteral(norm)) return;

        if (description != null && !description.isEmpty()) {
            tagDescriptions.put(norm, description);
        }
        if (dataType != null && !dataType.isEmpty()) {
            tagDataTypes.put(norm, dataType);
        }

        if (!liveInputCache.containsKey(norm) && initialValue != null) {
            liveInputCache.put(norm, initialValue);
            scanImage.put(norm, initialValue);
        }

        String managedPrefix = "[" + MANAGED_PROVIDER_NAME + "]";
        if (norm.startsWith(managedPrefix)) {
            String subPath = norm.substring(managedPrefix.length());
            if (managedProvider != null) {
                try {
                    DataType dt = inferIgnitionDataType(initialValue, dataType);
                    managedProvider.configureTag(subPath, dt);
                    Object currentVal = liveInputCache.getOrDefault(norm, defaultForType(dt));
                    liveInputCache.putIfAbsent(norm, currentVal);
                    scanImage.putIfAbsent(norm, currentVal);
                    managedProvider.updateValue(subPath, currentVal, QualityCode.Good);
                    managedProvider.registerWriteHandler(subPath, (tp, val) -> {
                        Object coerced = coerceValue(val, liveInputCache.get(norm));
                        liveInputCache.put(norm, coerced);
                        scanImage.put(norm, coerced);
                        managedProvider.updateValue(subPath, coerced, QualityCode.Good);
                        return QualityCode.Good;
                    });
                } catch (Exception e) {
                    logger.warn("Failed to configure managed tag {}: {}", norm, e.getMessage());
                }
            }
        } else {
            ensureSubscribed(norm);
        }
    }

    public void ensureSubscribed(String rawPath) {
        String norm = normalizeTagPath(rawPath);
        if (norm.isEmpty() || RungNode.isNumericLiteral(norm)) return;

        String managedPrefix = "[" + MANAGED_PROVIDER_NAME + "]";
        if (norm.startsWith(managedPrefix)) {
            return;
        }

        if (tagManager == null || activeSubscriptions.containsKey(norm)) {
            return;
        }

        try {
            TagPath tp = TagPathParser.parse(norm);
            TagChangeListener listener = new TagChangeListener() {
                @Override
                public void tagChanged(TagChangeEvent event) {
                    if (event != null && event.getValue() != null) {
                        Object v = event.getValue().getValue();
                        if (v != null) {
                            liveInputCache.put(norm, v);
                        }
                    }
                }
            };
            TagChangeListener existing = activeSubscriptions.putIfAbsent(norm, listener);
            if (existing == null) {
                tagManager.subscribeAsync(tp, listener);
                // Also read initial value asynchronously
                tagManager.readAsync(Collections.singletonList(tp)).thenAccept(values -> {
                    if (values != null && !values.isEmpty()) {
                        QualifiedValue qv = values.get(0);
                        if (qv != null && qv.getValue() != null) {
                            liveInputCache.put(norm, qv.getValue());
                            scanImage.putIfAbsent(norm, qv.getValue());
                        }
                    }
                });
            }
        } catch (Exception e) {
            logger.warn("Could not subscribe to Gateway tag {}: {}", norm, e.getMessage());
        }
    }

    /**
     * Step 1 of PLC Scan: Snapshot all subscribed input tags into synchronous scanImage
     * and apply any active Tag Forces.
     */
    public void beginScan() {
        dirtyOutputs.clear();
        for (Map.Entry<String, Object> entry : liveInputCache.entrySet()) {
            scanImage.put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Object> force : forcedTags.entrySet()) {
            scanImage.put(force.getKey(), force.getValue());
        }
    }

    /**
     * Read value from synchronous Scan Image (or literal if numeric).
     */
    public Object readValue(String rawPathOrLiteral) {
        if (rawPathOrLiteral == null || rawPathOrLiteral.isEmpty()) {
            return false;
        }
        if (RungNode.isNumericLiteral(rawPathOrLiteral)) {
            if (rawPathOrLiteral.contains(".")) {
                return Double.parseDouble(rawPathOrLiteral);
            } else {
                return Long.parseLong(rawPathOrLiteral);
            }
        }
        String norm = normalizeTagPath(rawPathOrLiteral);
        if (forcedTags.containsKey(norm)) {
            return forcedTags.get(norm);
        }
        Object val = scanImage.get(norm);
        if (val == null) {
            val = liveInputCache.get(norm);
        }
        if (val == null && norm.endsWith("]")) {
            int lb = norm.lastIndexOf('[');
            int provEnd = norm.indexOf(']');
            if (lb > provEnd) {
                try {
                    int idx = Integer.parseInt(norm.substring(lb + 1, norm.length() - 1).trim());
                    String basePath = norm.substring(0, lb);
                    Object arrObj = scanImage.get(basePath);
                    if (arrObj == null) arrObj = liveInputCache.get(basePath);
                    if (arrObj != null && idx >= 0) {
                        if (arrObj.getClass().isArray() && idx < java.lang.reflect.Array.getLength(arrObj)) {
                            val = java.lang.reflect.Array.get(arrObj, idx);
                        } else if (arrObj instanceof List<?> && idx < ((List<?>) arrObj).size()) {
                            val = ((List<?>) arrObj).get(idx);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return val;
    }

    public boolean readBoolean(String rawPathOrLiteral) {
        Object val = readValue(rawPathOrLiteral);
        if (val == null) return false;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Number) return ((Number) val).doubleValue() != 0.0;
        String s = val.toString().trim();
        return s.equalsIgnoreCase("true") || s.equals("1");
    }

    public double readDouble(String rawPathOrLiteral) {
        Object val = readValue(rawPathOrLiteral);
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof Boolean) return ((Boolean) val) ? 1.0 : 0.0;
        try {
            return Double.parseDouble(val.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Write value during scan: mutates synchronous scanImage immediately (so downstream
     * rungs see the updated coil/register state just like Studio 5000), and marks
     * dirtyOutputs ONLY if the value differs from liveInputCache.
     */
    public void writeValue(String rawPath, Object newValue) {
        if (rawPath == null || rawPath.isEmpty() || RungNode.isNumericLiteral(rawPath)) {
            return;
        }
        String norm = normalizeTagPath(rawPath);
        Object effectiveValue = forcedTags.getOrDefault(norm, newValue);
        scanImage.put(norm, effectiveValue);

        Object currentGatewayVal = liveInputCache.get(norm);
        if (!valuesEqual(currentGatewayVal, effectiveValue)) {
            dirtyOutputs.put(norm, effectiveValue);
        } else {
            // If an earlier rung dirtied it and a later rung restored it, remove from dirtyOutputs
            dirtyOutputs.remove(norm);
        }
    }

    /**
     * Updates scanImage + liveInputCache at full scan resolution (e.g. 50ms for Timer .ACC),
     * while throttling actual Gateway tag writes to at most once per minFlushIntervalMs (500ms)
     * unless forceImmediate is true (e.g. on Timer Done or Reset).
     */
    public void writeThrottledValue(String rawPath, Object newValue, long minFlushIntervalMs, boolean forceImmediate) {
        if (rawPath == null || rawPath.isEmpty() || RungNode.isNumericLiteral(rawPath)) {
            return;
        }
        String norm = normalizeTagPath(rawPath);
        Object effectiveValue = forcedTags.getOrDefault(norm, newValue);
        scanImage.put(norm, effectiveValue);
        liveInputCache.put(norm, effectiveValue);

        long now = System.currentTimeMillis();
        long lastFlush = lastThrottledFlushMs.getOrDefault(norm, 0L);
        Object lastGw = lastGatewayFlushedValues.get(norm);

        if (!valuesEqual(lastGw, effectiveValue) && (forceImmediate || (now - lastFlush >= minFlushIntervalMs))) {
            dirtyOutputs.put(norm, effectiveValue);
            lastThrottledFlushMs.put(norm, now);
            lastGatewayFlushedValues.put(norm, effectiveValue);
        }
    }

    /**
     * Write directly from UI / API (e.g. toggling a pushbutton or editing a tag value).
     */
    public void writeFromExternalUI(String rawPath, Object newValue) {
        String norm = normalizeTagPath(rawPath);
        Object coerced = coerceValue(newValue, liveInputCache.get(norm));
        liveInputCache.put(norm, coerced);
        scanImage.put(norm, coerced);

        String managedPrefix = "[" + MANAGED_PROVIDER_NAME + "]";
        if (norm.startsWith(managedPrefix)) {
            if (managedProvider != null) {
                String subPath = norm.substring(managedPrefix.length());
                managedProvider.updateValue(subPath, coerced, QualityCode.Good);
            }
        } else if (tagManager != null) {
            try {
                TagPath tp = TagPathParser.parse(norm);
                tagManager.writeAsync(Collections.singletonList(tp), Collections.singletonList(coerced));
            } catch (Exception e) {
                logger.warn("Failed writing external tag {}: {}", norm, e.getMessage());
            }
        }
    }

    /**
     * Step 3 of PLC Scan: Flush ONLY dirty output tags to Ignition in a single batch.
     */
    public int flushOutputs() {
        long now = System.currentTimeMillis();
        if (now - lastRateCalcMs >= 1000L) {
            writesPerSec = lastSecondWrites.getAndSet(0);
            lastRateCalcMs = now;
        }

        if (dirtyOutputs.isEmpty()) {
            return 0;
        }

        int flushedCount = dirtyOutputs.size();
        String managedPrefix = "[" + MANAGED_PROVIDER_NAME + "]";
        List<TagPath> externalPaths = null;
        List<Object> externalValues = null;

        for (Map.Entry<String, Object> entry : dirtyOutputs.entrySet()) {
            String norm = entry.getKey();
            Object val = entry.getValue();
            liveInputCache.put(norm, val);

            if (norm.startsWith(managedPrefix)) {
                if (managedProvider != null) {
                    String subPath = norm.substring(managedPrefix.length());
                    try {
                        managedProvider.configureTag(subPath, inferIgnitionDataType(val, tagDataTypes.get(norm)));
                        managedProvider.updateValue(subPath, val, QualityCode.Good);
                    } catch (Exception e) {
                        logger.debug("Managed tag update error for {}: {}", norm, e.getMessage());
                    }
                }
            } else if (tagManager != null) {
                try {
                    if (externalPaths == null) {
                        externalPaths = new ArrayList<>();
                        externalValues = new ArrayList<>();
                    }
                    externalPaths.add(TagPathParser.parse(norm));
                    externalValues.add(val);
                } catch (Exception e) {
                    logger.warn("Invalid tag path for write {}: {}", norm, e.getMessage());
                }
            }
        }

        if (externalPaths != null && !externalPaths.isEmpty() && tagManager != null) {
            tagManager.writeAsync(externalPaths, externalValues);
        }

        totalDirtyWrites.addAndGet(flushedCount);
        lastSecondWrites.addAndGet(flushedCount);
        dirtyOutputs.clear();
        return flushedCount;
    }

    public void setForce(String rawPath, Object forcedValue) {
        String norm = normalizeTagPath(rawPath);
        Object coerced = coerceValue(forcedValue, liveInputCache.get(norm));
        forcedTags.put(norm, coerced);
        writeFromExternalUI(norm, coerced);
    }

    public void removeForce(String rawPath) {
        String norm = normalizeTagPath(rawPath);
        forcedTags.remove(norm);
    }

    public void clearAllForces() {
        forcedTags.clear();
    }

    public boolean isForced(String rawPath) {
        return forcedTags.containsKey(normalizeTagPath(rawPath));
    }

    public Map<String, Object> getForcedTags() {
        return new LinkedHashMap<>(forcedTags);
    }

    public Map<String, Map<String, Object>> getAllTagsSnapshot() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(liveInputCache.keySet());
        Collections.sort(keys);
        for (String k : keys) {
            if (k == null || k.trim().isEmpty()) continue;
            Map<String, Object> info = new LinkedHashMap<>();
            Object val = scanImage.getOrDefault(k, liveInputCache.get(k));
            info.put("path", k);
            info.put("value", val);
            info.put("dataType", tagDataTypes.getOrDefault(k, inferTypeName(val)));
            info.put("description", tagDescriptions.getOrDefault(k, ""));
            info.put("forced", forcedTags.containsKey(k));
            info.put("forcedValue", forcedTags.get(k));
            result.put(k, info);
        }
        return result;
    }

    public boolean moveTag(String rawOldPath, String rawNewPath) {
        String oldNorm = normalizeTagPath(rawOldPath);
        String newNorm = normalizeTagPath(rawNewPath);
        String managedPrefix = "[" + MANAGED_PROVIDER_NAME + "]";
        if (oldNorm.isEmpty() || newNorm.isEmpty() || oldNorm.equals(newNorm)) return false;
        if (!oldNorm.startsWith(managedPrefix) || !newNorm.startsWith(managedPrefix)) return false;
        if (oldNorm.startsWith(managedPrefix + "System/") || newNorm.startsWith(managedPrefix + "System/")) return false;

        Object val = scanImage.getOrDefault(oldNorm, liveInputCache.get(oldNorm));
        String dt = tagDataTypes.getOrDefault(oldNorm, inferTypeName(val));
        String desc = tagDescriptions.getOrDefault(oldNorm, "");
        boolean wasForced = forcedTags.containsKey(oldNorm);
        Object forcedVal = forcedTags.get(oldNorm);

        registerTag(newNorm, val, dt, desc);
        if (wasForced) {
            setForce(newNorm, forcedVal);
        }
        deleteTag(oldNorm);
        return true;
    }

    public List<String> getTagKeysWithPrefix(String folderFullPath) {
        if (folderFullPath == null || folderFullPath.isEmpty()) return Collections.emptyList();
        String slashPrefix = folderFullPath.endsWith("/") ? folderFullPath : (folderFullPath + "/");
        List<String> out = new ArrayList<>();
        for (String k : liveInputCache.keySet()) {
            if (k != null && k.startsWith(slashPrefix)) {
                out.add(k);
            }
        }
        return out;
    }

    public boolean deleteTag(String rawPath) {
        String norm = normalizeTagPath(rawPath);
        if (norm.isEmpty()) return false;
        String managedPrefix = "[" + MANAGED_PROVIDER_NAME + "]";
        if (norm.startsWith(managedPrefix + "System/")) {
            return false;
        }
        boolean existed = liveInputCache.remove(norm) != null;
        scanImage.remove(norm);
        tagDataTypes.remove(norm);
        tagDescriptions.remove(norm);
        forcedTags.remove(norm);

        TagChangeListener listener = activeSubscriptions.remove(norm);
        if (listener != null && tagManager != null) {
            try {
                tagManager.unsubscribeAsync(TagPathParser.parse(norm), listener);
            } catch (Exception ignored) {
            }
        }
        if (norm.startsWith(managedPrefix) && managedProvider != null) {
            try {
                String subPath = norm.substring(managedPrefix.length());
                managedProvider.removeTag(subPath);
            } catch (Exception e) {
                logger.debug("removeTag {} returned: {}", norm, e.getMessage());
            }
        }
        return existed;
    }

    public void deleteTagsWithPrefix(String folderFullPath) {
        if (folderFullPath == null || folderFullPath.isEmpty()) return;
        String slashPrefix = folderFullPath.endsWith("/") ? folderFullPath : (folderFullPath + "/");
        List<String> keys = new ArrayList<>(liveInputCache.keySet());
        for (String k : keys) {
            if (k != null && (k.equals(folderFullPath) || k.startsWith(slashPrefix))) {
                deleteTag(k);
            }
        }
    }

    public void clearNonSystemTags() {
        String sysPrefix = "[" + MANAGED_PROVIDER_NAME + "]System/";
        List<String> keys = new ArrayList<>(liveInputCache.keySet());
        for (String k : keys) {
            if (k != null && !k.startsWith(sysPrefix)) {
                deleteTag(k);
            }
        }
    }

    /**
     * Checks all cached tags in liveInputCache against the Ignition Gateway TagManager
     * and removes any that no longer exist (e.g. deleted in Designer or deleted provider).
     */
    public int pruneDeletedExternalTags() {
        if (tagManager == null) return 0;
        List<String> keysToCheck = new ArrayList<>();
        List<TagPath> pathsToCheck = new ArrayList<>();
        for (String k : liveInputCache.keySet()) {
            if (k != null) {
                try {
                    pathsToCheck.add(TagPathParser.parse(k));
                    keysToCheck.add(k);
                } catch (Exception ignored) {
                }
            }
        }
        if (keysToCheck.isEmpty()) return 0;

        int pruned = 0;
        try {
            List<QualifiedValue> values = tagManager.readAsync(pathsToCheck).get(3, java.util.concurrent.TimeUnit.SECONDS);
            for (int i = 0; i < keysToCheck.size(); i++) {
                String key = keysToCheck.get(i);
                QualifiedValue qv = (values != null && i < values.size()) ? values.get(i) : null;
                boolean notFound = qv == null || qv.getQuality() == null
                        || QualityCode.Bad_NotFound.equals(qv.getQuality())
                        || qv.getQuality().toString().contains("NotFound");
                if (notFound) {
                    TagChangeListener listener = activeSubscriptions.remove(key);
                    if (listener != null) {
                        try {
                            tagManager.unsubscribeAsync(pathsToCheck.get(i), listener);
                        } catch (Exception ignored) {
                        }
                    }
                    liveInputCache.remove(key);
                    scanImage.remove(key);
                    tagDataTypes.remove(key);
                    tagDescriptions.remove(key);
                    forcedTags.remove(key);
                    pruned++;
                }
            }
        } catch (Exception e) {
            logger.debug("pruneDeletedExternalTags failed: {}", e.getMessage());
        }
        return pruned;
    }

    public List<Map<String, Object>> browseGatewayTags(String providerOrFolder) {
        List<Map<String, Object>> items = new ArrayList<>();
        String rootPath = (providerOrFolder == null || providerOrFolder.isEmpty()) ? "[default]" : providerOrFolder;
        if (tagManager != null) {
            try {
                TagPath tp = TagPathParser.parse(rootPath);
                Results<NodeDescription> results = tagManager.browseAsync(tp, BrowseFilter.NONE).get(3, java.util.concurrent.TimeUnit.SECONDS);
                if (results != null && results.getResults() != null) {
                    for (NodeDescription nd : results.getResults()) {
                        String fullPath = rootPath.endsWith("]")
                                ? rootPath + nd.getName()
                                : rootPath + "/" + nd.getName();
                        boolean isFolder = nd.getObjectType() == com.inductiveautomation.ignition.common.tags.config.types.TagObjectType.Folder;
                        Object rawVal = nd.getCurrentValue() != null ? nd.getCurrentValue().getValue() : null;
                        String dtName = isFolder ? "Folder" : (nd.getDataType() != null ? nd.getDataType().name() : "Tag");
                        boolean isArray = !isFolder && (dtName.endsWith("Array")
                                || (rawVal != null && rawVal.getClass().isArray())
                                || rawVal instanceof List<?>);
                        Object serializedVal = rawVal;
                        int arrayLen = 0;
                        if (isArray) {
                            List<Object> elemList = new ArrayList<>();
                            if (rawVal != null && rawVal.getClass().isArray()) {
                                int len = java.lang.reflect.Array.getLength(rawVal);
                                for (int k = 0; k < len; k++) {
                                    elemList.add(java.lang.reflect.Array.get(rawVal, k));
                                }
                            } else if (rawVal instanceof List<?>) {
                                elemList.addAll((List<?>) rawVal);
                            }
                            serializedVal = elemList;
                            arrayLen = elemList.size();
                        }
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("path", fullPath);
                        info.put("value", serializedVal);
                        info.put("isFolder", isFolder);
                        info.put("isArray", isArray);
                        if (isArray) {
                            info.put("arrayLength", arrayLen);
                        }
                        info.put("dataType", dtName);
                        info.put("hasChildren", !isArray && nd.hasChildren());
                        items.add(info);
                    }
                }
            } catch (Exception e) {
                logger.debug("Browse on {} returned: {}", rootPath, e.getMessage());
            }
        }
        return items;
    }

    public void shutdown() {
        if (tagManager != null) {
            for (Map.Entry<String, TagChangeListener> entry : activeSubscriptions.entrySet()) {
                try {
                    TagPath tp = TagPathParser.parse(entry.getKey());
                    tagManager.unsubscribeAsync(tp, entry.getValue());
                } catch (Exception ignored) {
                }
            }
        }
        activeSubscriptions.clear();
    }

    public long getTotalDirtyWrites() {
        return totalDirtyWrites.get();
    }

    public long getWritesPerSec() {
        return writesPerSec;
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (Objects.equals(a, b)) return true;
        if (a instanceof Number && b instanceof Number) {
            return Math.abs(((Number) a).doubleValue() - ((Number) b).doubleValue()) < 1e-9;
        }
        return false;
    }

    private static DataType inferIgnitionDataType(Object val, String typeHint) {
        if (typeHint != null) {
            String t = typeHint.toUpperCase();
            if (t.contains("BOOL")) return DataType.Boolean;
            if (t.contains("FLOAT") || t.contains("REAL") || t.contains("DOUBLE")) return DataType.Float8;
            if (t.contains("INT") || t.contains("DINT") || t.contains("LONG")) return DataType.Int8;
            if (t.contains("STR")) return DataType.String;
        }
        if (val instanceof Boolean) return DataType.Boolean;
        if (val instanceof Float || val instanceof Double) return DataType.Float8;
        if (val instanceof Number) return DataType.Int8;
        if (val instanceof String) return DataType.String;
        return DataType.Boolean;
    }

    private static String inferTypeName(Object val) {
        if (val instanceof Boolean) return "BOOL";
        if (val instanceof Float || val instanceof Double) return "REAL";
        if (val instanceof Number) return "DINT";
        if (val instanceof String) return "STRING";
        return "BOOL";
    }

    private static Object defaultForType(DataType dt) {
        if (dt == DataType.Boolean) return false;
        if (dt == DataType.Float8 || dt == DataType.Float4) return 0.0;
        if (dt == DataType.Int8 || dt == DataType.Int4 || dt == DataType.Int2) return 0L;
        return "";
    }

    private static Object coerceValue(Object newValue, Object existingValue) {
        if (newValue == null) return false;
        if (existingValue instanceof Boolean) {
            if (newValue instanceof Boolean) return newValue;
            if (newValue instanceof Number) return ((Number) newValue).doubleValue() != 0.0;
            String s = newValue.toString().trim();
            return s.equalsIgnoreCase("true") || s.equals("1");
        }
        if (existingValue instanceof Double || existingValue instanceof Float) {
            if (newValue instanceof Number) return ((Number) newValue).doubleValue();
            try {
                return Double.parseDouble(newValue.toString().trim());
            } catch (Exception e) {
                return 0.0;
            }
        }
        if (existingValue instanceof Long || existingValue instanceof Integer) {
            if (newValue instanceof Number) return ((Number) newValue).longValue();
            try {
                return (long) Double.parseDouble(newValue.toString().trim());
            } catch (Exception e) {
                return 0L;
            }
        }
        return newValue;
    }
}
