package com.antigravity.ladder.gateway;

import com.antigravity.ladder.engine.AoiDefinition;
import com.antigravity.ladder.engine.CyclicScanEngine;
import com.antigravity.ladder.engine.LadderProgram;
import com.antigravity.ladder.engine.TagImageManager;
import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.GsonBuilder;
import com.inductiveautomation.ignition.common.user.AuthenticatedUser;
import com.inductiveautomation.ignition.common.user.BasicAuthChallenge;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.user.UserSourceManager;
import com.inductiveautomation.ignition.gateway.user.UserSourceProfile;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Servlet mounted at /system/ladder providing both the Ladder Logic Studio Web IDE
 * and real-time JSON API endpoints for multi-program management, live power-flow
 * monitoring, routine editing, tag writing/forcing, and Gateway Tag browsing.
 * Enforces Ignition User Source login with the required "plc_tech" role.
 */
public class LadderStudioServlet extends HttpServlet {

    public static final String REQUIRED_ROLE = "plc_tech";
    private static final String SESSION_USER_ATTR = "ladder_auth_user";
    private static final String SESSION_ROLES_ATTR = "ladder_auth_roles";
    private static final String SESSION_SOURCE_ATTR = "ladder_auth_source";

    private final Gson gson = new GsonBuilder().serializeNulls().create();

    @SuppressWarnings("unchecked")
    private boolean isAuthorizedSession(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return false;
        Object user = session.getAttribute(SESSION_USER_ATTR);
        Object rolesObj = session.getAttribute(SESSION_ROLES_ATTR);
        if (user != null && rolesObj instanceof List) {
            List<String> roles = (List<String>) rolesObj;
            for (String r : roles) {
                if (REQUIRED_ROLE.equalsIgnoreCase(r)) {
                    return true;
                }
            }
        }
        // Seamless SSO: if the user is already signed into the Ignition Gateway Web UI
        // (e.g. via /app/ladder-programs), inspect Ignition's WebAuthSession on this HttpSession
        // and honor their Ignition roles (including plc_tech).
        return tryAuthorizeFromIgnitionGatewaySession(session);
    }

    @SuppressWarnings("unchecked")
    private boolean tryAuthorizeFromIgnitionGatewaySession(HttpSession session) {
        try {
            java.util.Enumeration<String> names = session.getAttributeNames();
            while (names != null && names.hasMoreElements()) {
                String attrName = names.nextElement();
                Object val = session.getAttribute(attrName);
                if (val == null) continue;
                String clsName = val.getClass().getName();
                Object webUser = null;
                if (clsName.contains("WebAuthSession")) {
                    Object ctx = val.getClass().getMethod("getContext").invoke(val);
                    if (ctx != null) {
                        Object opt = ctx.getClass().getMethod("getUser").invoke(ctx);
                        if (opt instanceof java.util.Optional && ((java.util.Optional<?>) opt).isPresent()) {
                            webUser = ((java.util.Optional<?>) opt).get();
                        }
                    }
                } else if (clsName.contains("WebAuthUser")) {
                    webUser = val;
                }
                if (webUser != null) {
                    Object uNameObj = webUser.getClass().getMethod("getUserName").invoke(webUser);
                    String username = uNameObj != null ? uNameObj.toString() : "";
                    List<String> roles = new ArrayList<>();
                    Object rolesColl = webUser.getClass().getMethod("getRoles").invoke(webUser);
                    if (rolesColl instanceof java.util.Collection) {
                        for (Object r : (java.util.Collection<?>) rolesColl) {
                            if (r != null) roles.add(r.toString());
                        }
                    }
                    boolean hasRole = roles.stream().anyMatch(r -> REQUIRED_ROLE.equalsIgnoreCase(r));
                    // Also check live default UserSource in case plc_tech was recently added to this user
                    if (!hasRole && !username.isEmpty()) {
                        try {
                            LadderGatewayHook hook = LadderGatewayHook.getInstance();
                            GatewayContext gctx = hook != null ? hook.getContext() : null;
                            UserSourceManager usm = gctx != null ? gctx.getUserSourceManager() : null;
                            UserSourceProfile prof = usm != null ? usm.getProfile("default") : null;
                            if (prof != null) {
                                java.util.Optional<com.inductiveautomation.ignition.common.user.User> optU = prof.getUser(username);
                                if (optU.isPresent() && optU.get().getRoles() != null) {
                                    for (String r : optU.get().getRoles()) {
                                        if (r != null && !roles.contains(r)) roles.add(r);
                                    }
                                    hasRole = roles.stream().anyMatch(r -> REQUIRED_ROLE.equalsIgnoreCase(r));
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    if (hasRole) {
                        session.setAttribute(SESSION_USER_ATTR, username);
                        session.setAttribute(SESSION_ROLES_ATTR, roles);
                        session.setAttribute(SESSION_SOURCE_ATTR, "gateway");
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void sendAuthRequired(HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("ok", false);
        err.put("authRequired", true);
        err.put("requiredRole", REQUIRED_ROLE);
        err.put("error", "Sign in required (role: " + REQUIRED_ROLE + ")");
        sendJson(resp, err);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getParameter("action");
        String programParam = req.getParameter("program");
        LadderGatewayHook hook = LadderGatewayHook.getInstance();

        if ("authStatus".equalsIgnoreCase(action)) {
            HttpSession session = req.getSession(false);
            boolean authed = isAuthorizedSession(req);
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("ok", true);
            status.put("authenticated", authed);
            status.put("requiredRole", REQUIRED_ROLE);
            if (authed && session != null) {
                status.put("username", session.getAttribute(SESSION_USER_ATTR));
                status.put("roles", session.getAttribute(SESSION_ROLES_ATTR));
                status.put("userSource", session.getAttribute(SESSION_SOURCE_ATTR));
            }
            sendJson(resp, status);
            return;
        }

        if (action != null && !action.isEmpty() && !isAuthorizedSession(req)) {
            sendAuthRequired(resp);
            return;
        }

        if (programParam != null && !programParam.isEmpty() && hook != null && hook.getScanEngine() != null) {
            hook.getScanEngine().setActiveProgram(programParam);
        }

        if ("state".equalsIgnoreCase(action)) {
            if (hook != null && hook.getScanEngine() != null) {
                String routineParam = req.getParameter("routine");
                String xrefsParam = req.getParameter("includeXrefs");
                boolean includeXrefs = (xrefsParam == null) || !"0".equals(xrefsParam);
                sendJson(resp, hook.getScanEngine().getStudioSnapshot(routineParam, includeXrefs));
            } else {
                sendJson(resp, Map.of("error", "Engine not initialized"));
            }
            return;
        }

        if ("browse".equalsIgnoreCase(action)) {
            String path = req.getParameter("path");
            if (hook != null && hook.getTagImageManager() != null) {
                sendJson(resp, Map.of("tags", hook.getTagImageManager().browseGatewayTags(path)));
            } else {
                sendJson(resp, Map.of("tags", List.of()));
            }
            return;
        }

        if ("timersCounters".equalsIgnoreCase(action)) {
            String kind = req.getParameter("kind");
            if (hook != null && hook.getScanEngine() != null) {
                sendJson(resp, Map.of("items", hook.getScanEngine().getTimersCountersWithUsage(kind)));
            } else {
                sendJson(resp, Map.of("items", List.of()));
            }
            return;
        }

        if ("aoiInstances".equalsIgnoreCase(action)) {
            if (hook != null && hook.getScanEngine() != null) {
                sendJson(resp, Map.of("items", hook.getScanEngine().getUdtManager().listAoiInstances()));
            } else {
                sendJson(resp, Map.of("items", List.of()));
            }
            return;
        }

        // Default: Serve the Ladder Logic Studio Web IDE HTML
        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        java.io.File devHtml = new java.io.File("/Users/felipe/.gemini/antigravity/scratch/ignition-ladder-plc/src/main/resources/web/index.html");
        if (devHtml.exists() && devHtml.canRead()) {
            resp.getOutputStream().write(java.nio.file.Files.readAllBytes(devHtml.toPath()));
            return;
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("web/index.html")) {
            if (in == null) {
                resp.getWriter().write("<h1>Ladder Logic Studio resource not found</h1>");
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            resp.getOutputStream().write(bos.toByteArray());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        LadderGatewayHook hook = LadderGatewayHook.getInstance();
        if (hook == null || hook.getScanEngine() == null || hook.getTagImageManager() == null) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            sendJson(resp, Map.of("ok", false, "error", "LadderPLC Engine not running"));
            return;
        }

        CyclicScanEngine engine = hook.getScanEngine();
        TagImageManager tagImage = hook.getTagImageManager();

        StringBuilder sb = new StringBuilder();
        String line;
        try (var reader = req.getReader()) {
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }

        Map<String, Object> body = gson.fromJson(sb.toString(), Map.class);
        if (body == null) {
            sendJson(resp, Map.of("ok", false, "error", "Empty request body"));
            return;
        }

        String action = body.get("action") != null ? body.get("action").toString() : "";

        // Handle login & logout before enforcing session check
        if ("login".equals(action)) {
            String username = body.get("username") != null ? body.get("username").toString().trim() : "";
            String password = body.get("password") != null ? body.get("password").toString() : "";
            String userSource = body.get("userSource") != null && !body.get("userSource").toString().trim().isEmpty()
                    ? body.get("userSource").toString().trim()
                    : "default";
            if (username.isEmpty() || password.isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                sendJson(resp, Map.of("ok", false, "error", "Username and password are required."));
                return;
            }
            try {
                GatewayContext ctx = hook.getContext();
                UserSourceManager usm = ctx != null ? ctx.getUserSourceManager() : null;
                UserSourceProfile profile = usm != null ? usm.getProfile(userSource) : null;
                if (profile == null) {
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    sendJson(resp, Map.of("ok", false, "error", "User source '" + userSource + "' not found."));
                    return;
                }
                AuthenticatedUser authUser = profile.authenticate(new BasicAuthChallenge(username, password).toSimpleAuthChallenge());
                if (authUser == null) {
                    resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    sendJson(resp, Map.of("ok", false, "error", "Invalid username or password."));
                    return;
                }
                List<String> roles = authUser.getRoles() != null ? new ArrayList<>(authUser.getRoles()) : new ArrayList<>();
                boolean hasRole = roles.stream().anyMatch(r -> REQUIRED_ROLE.equalsIgnoreCase(r));
                if (!hasRole) {
                    resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    sendJson(resp, Map.of(
                            "ok", false,
                            "error", "Access denied: user '" + authUser.getUsername() + "' does not have the required '" + REQUIRED_ROLE + "' role."
                    ));
                    return;
                }
                HttpSession session = req.getSession(true);
                session.setMaxInactiveInterval(8 * 3600); // 8 hours
                session.setAttribute(SESSION_USER_ATTR, authUser.getUsername());
                session.setAttribute(SESSION_ROLES_ATTR, roles);
                session.setAttribute(SESSION_SOURCE_ATTR, userSource);

                Map<String, Object> loginRes = new LinkedHashMap<>();
                loginRes.put("ok", true);
                loginRes.put("authenticated", true);
                loginRes.put("username", authUser.getUsername());
                loginRes.put("roles", roles);
                loginRes.put("userSource", userSource);
                sendJson(resp, loginRes);
            } catch (Exception e) {
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                sendJson(resp, Map.of("ok", false, "error", "Authentication failed: " + e.getMessage()));
            }
            return;
        }

        if ("logout".equals(action)) {
            HttpSession session = req.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            sendJson(resp, Map.of("ok", true, "authenticated", false));
            return;
        }

        if (!isAuthorizedSession(req)) {
            sendAuthRequired(resp);
            return;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);

        try {
            switch (action) {
                case "selectProgram": {
                    String progName = body.get("name").toString();
                    engine.setActiveProgram(progName);
                    break;
                }
                case "createProgram": {
                    String progName = body.get("name").toString();
                    String desc = body.get("description") != null ? body.get("description").toString() : "";
                    boolean enabled = body.get("enabled") == null || Boolean.parseBoolean(body.get("enabled").toString());
                    engine.createProgram(progName, desc, enabled);
                    break;
                }
                case "toggleProgram": {
                    String progName = body.get("name").toString();
                    boolean enabled = Boolean.parseBoolean(body.get("enabled").toString());
                    engine.setProgramEnabled(progName, enabled);
                    break;
                }
                case "renameProgram": {
                    String oldName = body.get("oldName").toString();
                    String newName = body.get("newName").toString();
                    String desc = body.get("description") != null ? body.get("description").toString() : null;
                    engine.renameProgram(oldName, newName, desc);
                    break;
                }
                case "deleteProgram": {
                    String progName = body.get("name").toString();
                    engine.deleteProgram(progName);
                    break;
                }
                case "setMode": {
                    String modeStr = body.get("mode") != null ? body.get("mode").toString().toUpperCase() : "RUN";
                    if ("PROG".equals(modeStr)) {
                        engine.setControllerMode(CyclicScanEngine.ControllerMode.PROG);
                    } else {
                        engine.setControllerMode(CyclicScanEngine.ControllerMode.RUN);
                    }
                    break;
                }
                case "setScanRate": {
                    long rate = ((Number) body.get("scanRateMs")).longValue();
                    engine.setScanRateMs(rate);
                    break;
                }
                case "writeTag": {
                    String path = body.get("path").toString();
                    Object val = body.get("value");
                    tagImage.writeFromExternalUI(path, val);
                    break;
                }
                case "createTag": {
                    String path = body.get("path").toString();
                    String dataType = body.get("dataType") != null ? body.get("dataType").toString() : "BOOL";
                    String desc = body.get("description") != null ? body.get("description").toString() : "";
                    Object initVal = body.get("value");
                    int arraySize = 0;
                    if (body.get("arraySize") != null) {
                        try {
                            arraySize = Integer.parseInt(body.get("arraySize").toString().trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (arraySize > 0) {
                        int count = Math.min(arraySize, 1000);
                        String normBase = TagImageManager.normalizeTagPath(path);
                        int lastSlash = normBase.lastIndexOf('/');
                        int bracketEnd = normBase.indexOf(']');
                        String leafName = lastSlash > bracketEnd
                                ? normBase.substring(lastSlash + 1)
                                : normBase.substring(bracketEnd + 1);
                        for (int idx = 0; idx < count; idx++) {
                            String elemPath = normBase + "/" + leafName + "[" + idx + "]";
                            String elemDesc = (desc.isEmpty() ? (leafName + " element") : desc) + " [" + idx + "]";
                            if ("TIMER".equalsIgnoreCase(dataType)) {
                                long pre = initVal instanceof Number ? ((Number) initVal).longValue() : 5000L;
                                engine.registerTimerTags(elemPath, pre, elemDesc);
                            } else if ("COUNTER".equalsIgnoreCase(dataType)) {
                                long pre = initVal instanceof Number ? ((Number) initVal).longValue() : 100L;
                                engine.registerCounterTags(elemPath, pre, elemDesc);
                            } else if ("PID".equalsIgnoreCase(dataType)) {
                                engine.registerPidTags(elemPath, elemDesc);
                            } else {
                                tagImage.registerTag(elemPath, initVal, dataType, elemDesc);
                            }
                        }
                        result.put("createdCount", count);
                    } else {
                        if ("TIMER".equalsIgnoreCase(dataType)) {
                            long pre = initVal instanceof Number ? ((Number) initVal).longValue() : 5000L;
                            engine.registerTimerTags(path, pre, desc);
                        } else if ("COUNTER".equalsIgnoreCase(dataType)) {
                            long pre = initVal instanceof Number ? ((Number) initVal).longValue() : 100L;
                            engine.registerCounterTags(path, pre, desc);
                        } else if ("PID".equalsIgnoreCase(dataType)) {
                            engine.registerPidTags(path, desc);
                        } else {
                            tagImage.registerTag(path, initVal, dataType, desc);
                        }
                        result.put("createdCount", 1);
                    }
                    break;
                }
                case "createOpcTag": {
                    String provider = body.get("provider") != null ? body.get("provider").toString() : "default";
                    String path = body.get("path") != null ? body.get("path").toString() : "";
                    String opcServer = body.get("opcServer") != null ? body.get("opcServer").toString() : "Ignition OPC UA Server";
                    String opcItemPath = body.get("opcItemPath") != null ? body.get("opcItemPath").toString() : "";
                    String dataType = body.get("dataType") != null ? body.get("dataType").toString() : "BOOL";
                    String desc = body.get("description") != null ? body.get("description").toString() : "";
                    String created = engine.getUdtManager().createOpcTag(provider, path, opcServer, opcItemPath, dataType, desc);
                    result.put("path", created);
                    break;
                }
                case "deleteTag": {
                    String path = body.get("path") != null ? body.get("path").toString() : "";
                    List<String> used = engine.findUsedTagsUnderPrefix(path);
                    if (!used.isEmpty()) {
                        throw new IllegalStateException("Cannot delete tag \"" + path + "\" because it is used in ladder logic: " + String.join(", ", used));
                    }
                    result.put("deleted", tagImage.deleteTag(path));
                    break;
                }
                case "createFolder": {
                    String folderPath = body.get("path") != null ? body.get("path").toString() : "";
                    String createdPath = engine.getUdtManager().createFolder(folderPath);
                    result.put("folderPath", createdPath);
                    break;
                }
                case "deleteFolder": {
                    String folderPath = body.get("path") != null ? body.get("path").toString() : "";
                    List<String> used = engine.findUsedTagsUnderPrefix(folderPath);
                    if (!used.isEmpty()) {
                        throw new IllegalStateException("Cannot delete folder \"" + folderPath + "\" because the following tag(s) inside it are used in ladder logic: " + String.join(", ", used));
                    }
                    engine.getUdtManager().deleteFolder(folderPath);
                    result.put("deleted", true);
                    break;
                }
                case "moveTag": {
                    String oldPath = body.get("oldPath") != null ? body.get("oldPath").toString() : "";
                    String newPath = body.get("newPath") != null ? body.get("newPath").toString() : "";
                    boolean moved = tagImage.moveTag(oldPath, newPath);
                    if (moved) {
                        engine.replaceTagPrefixInProject(oldPath, newPath);
                    }
                    result.put("moved", moved);
                    result.put("newPath", newPath);
                    break;
                }
                case "moveFolder": {
                    String oldFolder = body.get("oldPath") != null ? body.get("oldPath").toString().trim() : "";
                    String newFolder = body.get("newPath") != null ? body.get("newPath").toString().trim() : "";
                    while (oldFolder.endsWith("/")) oldFolder = oldFolder.substring(0, oldFolder.length() - 1);
                    while (newFolder.endsWith("/")) newFolder = newFolder.substring(0, newFolder.length() - 1);
                    if (oldFolder.isEmpty() || newFolder.isEmpty() || oldFolder.equals(newFolder)
                            || newFolder.startsWith(oldFolder + "/")) {
                        throw new IllegalArgumentException("Invalid target folder for move.");
                    }
                    String created = engine.getUdtManager().createFolder(newFolder);
                    List<String> childKeys = tagImage.getTagKeysWithPrefix(oldFolder);
                    String oldPrefix = oldFolder + "/";
                    for (String ck : childKeys) {
                        String rel = ck.substring(oldPrefix.length());
                        tagImage.moveTag(ck, created + "/" + rel);
                    }
                    engine.replaceTagPrefixInProject(oldFolder, created);
                    engine.getUdtManager().deleteFolder(oldFolder);
                    result.put("moved", true);
                    result.put("newPath", created);
                    break;
                }
                case "importFolderTags": {
                    String baseFolder = body.get("folder") != null ? body.get("folder").toString().trim() : "[LadderPLC]LadderLogic";
                    if (!baseFolder.startsWith("[")) {
                        baseFolder = "[LadderPLC]" + baseFolder;
                    }
                    while (baseFolder.endsWith("/") && !baseFolder.endsWith("]")) {
                        baseFolder = baseFolder.substring(0, baseFolder.length() - 1);
                    }
                    if (!baseFolder.equals("[LadderPLC]")) {
                        try {
                            engine.getUdtManager().createFolder(baseFolder);
                        } catch (Exception ignored) {
                        }
                    }
                    List<Map<String, Object>> tagsList = (List<Map<String, Object>>) body.get("tags");
                    int count = 0;
                    if (tagsList != null) {
                        for (Map<String, Object> tm : tagsList) {
                            if (tm == null) continue;
                            Object rawP = tm.get("path") != null ? tm.get("path") : tm.get("name");
                            if (rawP == null) continue;
                            String pStr = rawP.toString().trim();
                            if (pStr.isEmpty()) continue;
                            String fullPath;
                            if (pStr.startsWith("[")) {
                                fullPath = pStr;
                            } else {
                                while (pStr.startsWith("/")) pStr = pStr.substring(1);
                                fullPath = baseFolder.endsWith("]") ? (baseFolder + pStr) : (baseFolder + "/" + pStr);
                            }
                            int lastSlash = fullPath.lastIndexOf('/');
                            if (lastSlash > "[LadderPLC]".length()) {
                                String subFolder = fullPath.substring(0, lastSlash);
                                try {
                                    engine.getUdtManager().createFolder(subFolder);
                                } catch (Exception ignored) {
                                }
                            }
                            String dt = tm.get("dataType") != null ? tm.get("dataType").toString() : "BOOL";
                            String desc = tm.get("description") != null ? tm.get("description").toString() : "";
                            Object val = tm.get("value");
                            if (val == null) {
                                val = "BOOL".equalsIgnoreCase(dt) ? false : ("REAL".equalsIgnoreCase(dt) ? 0.0 : ("STRING".equalsIgnoreCase(dt) ? "" : 0));
                            }
                            tagImage.registerTag(fullPath, val, dt, desc);
                            count++;
                        }
                    }
                    result.put("importedCount", count);
                    break;
                }
                case "createTimerCounter": {
                    String kind = body.get("kind") != null ? body.get("kind").toString() : "TIMER";
                    String name = body.get("name").toString();
                    long preset = body.get("preset") instanceof Number ? ((Number) body.get("preset")).longValue()
                            : ("COUNTER".equalsIgnoreCase(kind) ? 100L : 5000L);
                    String desc = body.get("description") != null ? body.get("description").toString() : "";
                    String basePath = engine.getUdtManager().createInstance(kind, name, preset, desc);
                    result.put("basePath", basePath);
                    break;
                }
                case "renameTimerCounter": {
                    String oldBasePath = body.get("basePath").toString();
                    String newName = body.get("newName").toString();
                    String newBasePath = engine.getUdtManager().renameInstance(oldBasePath, newName);
                    engine.renameTimerCounterReferences(oldBasePath, newBasePath);
                    result.put("basePath", newBasePath);
                    break;
                }
                case "deleteTimerCounter": {
                    String basePath = body.get("basePath").toString();
                    engine.getUdtManager().deleteInstance(basePath);
                    break;
                }
                case "forceTag": {
                    String path = body.get("path").toString();
                    Object val = body.get("value");
                    tagImage.setForce(path, val);
                    break;
                }
                case "unforceTag": {
                    String path = body.get("path").toString();
                    tagImage.removeForce(path);
                    break;
                }
                case "clearForces": {
                    tagImage.clearAllForces();
                    break;
                }
                case "syncTags": {
                    int pruned = tagImage.pruneDeletedExternalTags();
                    result.put("pruned", pruned);
                    break;
                }
                case "saveRoutine": {
                    boolean offlineSave = Boolean.TRUE.equals(body.get("offlineSave"))
                            || "true".equalsIgnoreCase(String.valueOf(body.get("offlineSave")));
                    if (!offlineSave && engine.getControllerMode() == CyclicScanEngine.ControllerMode.RUN) {
                        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        sendJson(resp, Map.of(
                                "ok", false,
                                "error", "Cannot modify rungs while Online in RUN mode. Switch to PROG Mode or go OFFLINE to make changes."
                        ));
                        return;
                    }
                    String progName = body.get("program") != null ? body.get("program").toString() : null;
                    String routineName = body.get("name").toString();
                    String type = body.get("type") != null ? body.get("type").toString() : "SUBROUTINE";
                    String desc = body.get("description") != null ? body.get("description").toString() : "";
                    List<Map<String, Object>> rungs = (List<Map<String, Object>>) body.get("rungs");
                    engine.upsertRoutine(progName, routineName, type, desc, rungs);
                    break;
                }
                case "deleteRoutine": {
                    String progName = body.get("program") != null ? body.get("program").toString() : null;
                    String routineName = body.get("name").toString();
                    boolean deleted = engine.deleteRoutine(progName, routineName);
                    result.put("deleted", deleted);
                    break;
                }
                case "renameRoutine": {
                    String progName = body.get("program") != null ? body.get("program").toString() : null;
                    String oldName = body.get("oldName").toString();
                    String newName = body.get("newName") != null ? body.get("newName").toString() : oldName;
                    String desc = body.get("description") != null ? body.get("description").toString() : null;
                    result.put("renamed", engine.renameRoutine(progName, oldName, newName, desc));
                    break;
                }
                case "resetDemo": {
                    tagImage.clearNonSystemTags();
                    engine.loadEmptyProject();
                    engine.saveProjectToDisk();
                    break;
                }
                case "saveAoi": {
                    String aoiName = body.get("name").toString();
                    String desc = body.get("description") != null ? body.get("description").toString() : "";
                    List<Map<String, Object>> params = (List<Map<String, Object>>) body.get("params");
                    List<Map<String, Object>> rungs = (List<Map<String, Object>>) body.get("rungs");
                    engine.upsertAoi(aoiName, desc, params, rungs);
                    break;
                }
                case "deleteAoi": {
                    String aoiName = body.get("name").toString();
                    result.put("deleted", engine.deleteAoi(aoiName));
                    break;
                }
                case "renameAoi": {
                    String oldName = body.get("oldName").toString();
                    String newName = body.get("newName") != null ? body.get("newName").toString() : oldName;
                    String desc = body.get("description") != null ? body.get("description").toString() : null;
                    result.put("renamed", engine.renameAoi(oldName, newName, desc));
                    break;
                }
                case "createAoiInstance": {
                    String aoiName = body.get("aoiName").toString();
                    String instName = body.get("instanceName").toString();
                    String desc = body.get("description") != null ? body.get("description").toString() : "";
                    AoiDefinition aoi = engine.getAoiMap().get(aoiName);
                    if (aoi == null) {
                        throw new IllegalArgumentException("Unknown AOI definition: " + aoiName);
                    }
                    String basePath = engine.getUdtManager().createAoiInstance(aoi, instName, desc);
                    result.put("basePath", basePath);
                    break;
                }
                default:
                    result.put("ok", false);
                    result.put("error", "Unknown action: " + action);
            }
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }

        result.put("state", engine.getFullStudioSnapshot());
        sendJson(resp, result);
    }

    private void sendJson(HttpServletResponse resp, Object payload) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        resp.getWriter().write(gson.toJson(payload));
    }
}
