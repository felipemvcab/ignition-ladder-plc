(function (root, factory) {
  if (typeof exports === "object" && typeof module === "object") {
    module.exports = factory(require("react"));
  } else if (typeof define === "function" && define.amd) {
    define("LadderLogicStudio", ["react"], factory);
  } else if (typeof exports === "object") {
    exports["LadderLogicStudio"] = factory(require("react"));
  } else {
    root["LadderLogicStudio"] = factory(root["React"] || root["react"]);
  }
})(typeof self !== "undefined" ? self : this, function (React) {
  "use strict";

  var h = React.createElement;
  var useState = React.useState;
  var useEffect = React.useEffect;

  function diagCard(title, primaryValue, subtitle, detailsList, accentColor) {
    return h(
      "div",
      {
        style: {
          background: "#181f2c",
          border: "1px solid #2b3648",
          borderTop: "3px solid " + (accentColor || "#38bdf8"),
          borderRadius: "8px",
          padding: "14px 16px",
          display: "flex",
          flexDirection: "column",
          gap: "6px"
        }
      },
      h(
        "div",
        { style: { fontSize: "11px", fontWeight: 700, textTransform: "uppercase", letterSpacing: "0.5px", color: "#94a3b8" } },
        title
      ),
      h(
        "div",
        { style: { fontSize: "20px", fontWeight: 800, color: "#f8fafc", fontFamily: "monospace" } },
        primaryValue
      ),
      h(
        "div",
        { style: { fontSize: "11.5px", color: accentColor || "#38bdf8", fontWeight: 600 } },
        subtitle
      ),
      h(
        "div",
        { style: { marginTop: "4px", paddingTop: "6px", borderTop: "1px solid #232d3d", display: "flex", flexDirection: "column", gap: "3px" } },
        (detailsList || []).map(function (line, i) {
          return h(
            "div",
            { key: i, style: { fontSize: "11.5px", color: "#cbd5e1", display: "flex", justifyContent: "space-between" } },
            h("span", { style: { color: "#8b9bb4" } }, line[0]),
            h("span", { style: { fontFamily: "monospace", fontWeight: 600 } }, line[1])
          );
        })
      )
    );
  }

  function LadderGatewayProgramsPage() {
    var _state = useState(null);
    var studioState = _state[0];
    var setStudioState = _state[1];

    var _modal = useState(false);
    var showCreateModal = _modal[0];
    var setShowCreateModal = _modal[1];

    var _newName = useState("");
    var newProgName = _newName[0];
    var setNewProgName = _newName[1];

    var _newDesc = useState("");
    var newProgDesc = _newDesc[0];
    var setNewProgDesc = _newDesc[1];

    var _authNeeded = useState(false);
    var authNeeded = _authNeeded[0];
    var setAuthNeeded = _authNeeded[1];

    var _authErr = useState("");
    var authErr = _authErr[0];
    var setAuthErr = _authErr[1];

    var _loginUser = useState("");
    var loginUser = _loginUser[0];
    var setLoginUser = _loginUser[1];

    var _loginPass = useState("");
    var loginPass = _loginPass[0];
    var setLoginPass = _loginPass[1];

    var _loginSrc = useState("default");
    var loginSrc = _loginSrc[0];
    var setLoginSrc = _loginSrc[1];

    function fetchPrograms() {
      fetch("/system/ladder?action=state", { credentials: "same-origin" })
        .then(function (r) { return r.json(); })
        .then(function (data) {
          if (data && data.authRequired) {
            setAuthNeeded(true);
            return;
          }
          if (data && data.controller) {
            setAuthNeeded(false);
            setAuthErr("");
            setStudioState(data);
          }
        })
        .catch(function () {});
    }

    function handleGatewayLogin(e) {
      if (e) e.preventDefault();
      setAuthErr("");
      fetch("/system/ladder", {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          action: "login",
          username: (loginUser || "").trim(),
          password: loginPass || "",
          userSource: (loginSrc || "default").trim() || "default"
        })
      })
        .then(function (r) { return r.json(); })
        .then(function (res) {
          if (res && res.ok && res.authenticated) {
            setLoginPass("");
            setAuthNeeded(false);
            setAuthErr("");
            fetchPrograms();
          } else {
            setAuthErr((res && res.error) || "Sign in failed (role plc_tech required).");
          }
        })
        .catch(function (err) {
          setAuthErr("Network error: " + (err && err.message ? err.message : err));
        });
    }

    useEffect(function () {
      fetchPrograms();
      var timer = setInterval(fetchPrograms, 1000);
      return function () { clearInterval(timer); };
    }, []);

    function postApi(payload) {
      return fetch("/system/ladder", {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      })
        .then(function (r) { return r.json(); })
        .then(function (res) {
          if (res && res.authRequired) {
            setAuthNeeded(true);
            setAuthErr(res.error || "Sign in required (role: plc_tech).");
            return res;
          }
          if (res && res.state) {
            setStudioState(res.state);
          }
          if (res && res.ok === false && res.error) {
            alert(res.error);
          }
          return res;
        });
    }

    function openProgramEditorInNewTab(progName) {
      var url = "/system/ladder?program=" + encodeURIComponent(progName);
      window.open(url, "_blank", "noopener");
    }

    function handleCreateProgram(e) {
      if (e) e.preventDefault();
      var clean = (newProgName || "").trim();
      if (!clean) return;
      postApi({
        action: "createProgram",
        name: clean,
        description: (newProgDesc || "").trim(),
        enabled: true
      }).then(function () {
        setShowCreateModal(false);
        setNewProgName("");
        setNewProgDesc("");
      });
    }

    function handleToggleProgram(progName, nextEnabled) {
      postApi({
        action: "toggleProgram",
        name: progName,
        enabled: nextEnabled
      });
    }

    function handleRenameProgram(prog) {
      var nextName = window.prompt('Rename Program "' + prog.name + '" to:', prog.name);
      if (!nextName || !nextName.trim()) return;
      var nextDesc = window.prompt("Program Description:", prog.description || "");
      postApi({
        action: "renameProgram",
        oldName: prog.name,
        newName: nextName.trim(),
        description: nextDesc !== null ? nextDesc : (prog.description || "")
      });
    }

    function handleDeleteProgram(progName) {
      if (!window.confirm('Are you sure you want to delete program "' + progName + '" and all of its routines?')) {
        return;
      }
      postApi({
        action: "deleteProgram",
        name: progName
      });
    }

    var programs = (studioState && studioState.programs) || [];
    var ctrl = (studioState && studioState.controller) || {};
    var diag = (studioState && studioState.diagnostics) || {};

    var scanRate = ctrl.scanRateMs || 50;
    var avgScan = Number(ctrl.avgScanMs || 0);
    var lastScan = Number(ctrl.lastScanMs || 0);
    var maxScan = Number(ctrl.maxScanMs || 0);
    var dutyPct = diag.scanDutyCyclePct !== undefined ? Number(diag.scanDutyCyclePct) : ((avgScan / scanRate) * 100);
    var sysCpuPct = diag.estimatedSystemCpuPct !== undefined ? Number(diag.estimatedSystemCpuPct) : (dutyPct / 4);
    var moduleRamKb = diag.moduleEstimatedRamKb !== undefined ? Number(diag.moduleEstimatedRamKb) : 12.5;
    var heapUsedMb = diag.jvmHeapUsedMb || 0;
    var heapMaxMb = diag.jvmHeapMaxMb || 2048;

    if (authNeeded) {
      return h(
        "div",
        {
          style: {
            padding: "48px 24px",
            display: "flex",
            justifyContent: "center",
            alignItems: "center",
            minHeight: "calc(100vh - 60px)",
            background: "var(--neutral-10, #14181f)",
            fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
          }
        },
        h(
          "form",
          {
            onSubmit: handleGatewayLogin,
            style: {
              background: "#181f2a",
              border: "1px solid #38bdf8",
              borderRadius: "10px",
              padding: "24px",
              width: "min(400px, 95vw)",
              display: "flex",
              flexDirection: "column",
              gap: "12px",
              boxShadow: "0 20px 50px rgba(0,0,0,0.65)"
            }
          },
          h("div", { style: { fontSize: "16px", fontWeight: 800, color: "#f8fafc" } }, "🔒 Ladder Logic Studio Login"),
          h("div", { style: { fontSize: "12px", color: "#94a3b8" } }, "Sign in with an Ignition account assigned the plc_tech role."),
          authErr ? h("div", { style: { padding: "8px 10px", borderRadius: "5px", background: "rgba(239,68,68,0.16)", border: "1px solid #ef4444", color: "#fca5a5", fontSize: "12px" } }, authErr) : null,
          h("input", {
            type: "text",
            required: true,
            placeholder: "Username",
            value: loginUser,
            onChange: function (e) { setLoginUser(e.target.value); },
            style: { background: "#0d1117", border: "1px solid #334155", color: "#f8fafc", padding: "8px 10px", borderRadius: "5px", fontSize: "13px" }
          }),
          h("input", {
            type: "password",
            required: true,
            placeholder: "Password",
            value: loginPass,
            onChange: function (e) { setLoginPass(e.target.value); },
            style: { background: "#0d1117", border: "1px solid #334155", color: "#f8fafc", padding: "8px 10px", borderRadius: "5px", fontSize: "13px" }
          }),
          h("input", {
            type: "text",
            placeholder: "User Source (default)",
            value: loginSrc,
            onChange: function (e) { setLoginSrc(e.target.value); },
            style: { background: "#0d1117", border: "1px solid #334155", color: "#cbd5e1", padding: "7px 10px", borderRadius: "5px", fontSize: "12px", fontFamily: "monospace" }
          }),
          h("button", {
            type: "submit",
            style: { background: "#10b981", color: "#052e16", border: "none", borderRadius: "5px", padding: "9px 14px", fontSize: "13px", fontWeight: 700, cursor: "pointer" }
          }, "Sign In")
        )
      );
    }

    return h(
      "div",
      {
        style: {
          padding: "24px 32px",
          fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
          color: "var(--neutral-90, #e2e8f0)",
          background: "var(--neutral-10, #14181f)",
          minHeight: "calc(100vh - 60px)"
        }
      },
      // Header Row
      h(
        "div",
        {
          style: {
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            marginBottom: "18px",
            paddingBottom: "14px",
            borderBottom: "1px solid #2b3545"
          }
        },
        h(
          "div",
          null,
          h(
            "div",
            { style: { display: "flex", alignItems: "center", gap: "12px" } },
            h(
              "span",
              { style: { fontSize: "22px", fontWeight: 700, color: "#f8fafc" } },
              "Ladder Logic Studio — Programs & Diagnostics"
            ),
            h(
              "span",
              {
                style: {
                  fontSize: "12px",
                  padding: "3px 10px",
                  borderRadius: "4px",
                  fontWeight: 700,
                  background: ctrl.mode === "RUN" ? "rgba(0,230,118,0.16)" : "rgba(245,158,11,0.2)",
                  color: ctrl.mode === "RUN" ? "#00e676" : "#fbbf24",
                  border: "1px solid " + (ctrl.mode === "RUN" ? "#00e676" : "#fbbf24")
                }
              },
              "Engine: " + (ctrl.mode || "RUN") + " (" + scanRate + " ms Periodic Task)"
            )
          ),
          h(
            "div",
            { style: { fontSize: "13px", color: "#94a3b8", marginTop: "5px" } },
            "Felipe Integrations • Click any program below to open its Ladder Logic Editor in a new tab."
          )
        ),
        h(
          "button",
          {
            onClick: function () {
              setNewProgName("Program_" + (programs.length + 1));
              setNewProgDesc("Ladder Logic Control Program");
              setShowCreateModal(true);
            },
            style: {
              background: "#059669",
              color: "#ffffff",
              border: "1px solid #10b981",
              borderRadius: "6px",
              padding: "9px 16px",
              fontSize: "13px",
              fontWeight: 700,
              cursor: "pointer"
            }
          },
          "+ New Program"
        )
      ),

      // 4-Card Resource & Performance Diagnostics Grid
      h(
        "div",
        {
          style: {
            display: "grid",
            gridTemplateColumns: "repeat(auto-fit, minmax(240px, 1fr))",
            gap: "14px",
            marginBottom: "22px"
          }
        },
        diagCard(
          "Thread Architecture",
          (diag.moduleThreadCount ?? 1) + " Dedicated Thread",
          "0 Extra Threads Per Added Program",
          [
            ["Thread Name", diag.scanThreadName || "Ignition-LadderPLC-ScanThread"],
            ["Execution Model", "Sequential Deterministic Scan"],
            ["Active / Total Programs", (diag.enabledPrograms ?? programs.length) + " / " + programs.length],
            ["Gateway Total JVM Threads", String(diag.gatewayTotalThreads || "—")]
          ],
          "#00e676"
        ),
        diagCard(
          "CPU & Scan Duty Cycle",
          sysCpuPct.toFixed(3) + "% Gateway CPU",
          "Scan Duty Cycle: " + dutyPct.toFixed(2) + "% of " + scanRate + "ms window",
          [
            ["Last / Avg Scan Time", lastScan.toFixed(3) + " ms / " + avgScan.toFixed(3) + " ms"],
            ["Max Peak Scan / Watchdog", maxScan.toFixed(3) + " ms / " + (ctrl.watchdogMs || 500) + " ms"],
            ["Thread Cumulative CPU", (diag.threadCpuTimeMs || 0) + " ms (" + (diag.cpuCores || 4) + " cores)"],
            ["Completed Scan Cycles", String(ctrl.scanCount || 0)]
          ],
          "#38bdf8"
        ),
        diagCard(
          "RAM & Memory Footprint",
          moduleRamKb.toFixed(2) + " KB Module RAM",
          "Zero-Allocation Pre-Compiled ASTs",
          [
            ["Compiled AST Nodes", (diag.compiledAstNodes || 0) + " nodes (" + (diag.totalRungs || 0) + " rungs)"],
            ["Total Routines Loaded", String(diag.totalRoutines || 0)],
            ["Gateway JVM Heap Used", heapUsedMb + " MB / " + heapMaxMb + " MB"],
            ["GC Impact During Scan", "0 allocations/scan (In-Place)"]
          ],
          "#a78bfa"
        ),
        diagCard(
          "Tag I/O & Dirty-Write Cache",
          (ctrl.writesPerSec || 0) + " Writes/sec",
          "Event-Driven Input Cache (0 Polling)",
          [
            ["Cached & Subscribed Tags", String(diag.cachedTagsCount || 0) + " tags"],
            ["Cumulative Dirty Writes", String(ctrl.dirtyWritesTotal || 0)],
            ["Active Tag Forces", String(ctrl.activeForcesCount || 0)],
            ["Unchanged Coils Skipped", "100% filtered in-memory"]
          ],
          "#f59e0b"
        )
      ),

      // Inline Create Program Card (when active)
      showCreateModal
        ? h(
            "form",
            {
              onSubmit: handleCreateProgram,
              style: {
                background: "#1c2432",
                border: "1px solid #38bdf8",
                borderRadius: "8px",
                padding: "16px 20px",
                marginBottom: "20px",
                display: "flex",
                gap: "12px",
                alignItems: "flex-end",
                flexWrap: "wrap"
              }
            },
            h(
              "div",
              { style: { display: "flex", flexDirection: "column", gap: "5px", flex: "1", minWidth: "200px" } },
              h("label", { style: { fontSize: "12px", color: "#94a3b8", fontWeight: 600 } }, "Program Name"),
              h("input", {
                type: "text",
                value: newProgName,
                onChange: function (e) { setNewProgName(e.target.value); },
                placeholder: "e.g. PackagingLine_PLC",
                required: true,
                style: {
                  background: "#0f141c",
                  border: "1px solid #334155",
                  color: "#f8fafc",
                  padding: "8px 12px",
                  borderRadius: "5px",
                  fontSize: "13px"
                }
              })
            ),
            h(
              "div",
              { style: { display: "flex", flexDirection: "column", gap: "5px", flex: "2", minWidth: "260px" } },
              h("label", { style: { fontSize: "12px", color: "#94a3b8", fontWeight: 600 } }, "Description"),
              h("input", {
                type: "text",
                value: newProgDesc,
                onChange: function (e) { setNewProgDesc(e.target.value); },
                placeholder: "Description of this PLC program...",
                style: {
                  background: "#0f141c",
                  border: "1px solid #334155",
                  color: "#f8fafc",
                  padding: "8px 12px",
                  borderRadius: "5px",
                  fontSize: "13px"
                }
              })
            ),
            h(
              "div",
              { style: { display: "flex", gap: "8px" } },
              h(
                "button",
                {
                  type: "submit",
                  style: {
                    background: "#059669",
                    color: "#fff",
                    border: "1px solid #10b981",
                    borderRadius: "5px",
                    padding: "8px 16px",
                    fontSize: "13px",
                    fontWeight: 700,
                    cursor: "pointer"
                  }
                },
                "Create Program"
              ),
              h(
                "button",
                {
                  type: "button",
                  onClick: function () { setShowCreateModal(false); },
                  style: {
                    background: "#263042",
                    color: "#cbd5e1",
                    border: "1px solid #3b4963",
                    borderRadius: "5px",
                    padding: "8px 14px",
                    fontSize: "13px",
                    cursor: "pointer"
                  }
                },
                "Cancel"
              )
            )
          )
        : null,

      // Programs Table
      h(
        "div",
        {
          style: {
            background: "#181f2c",
            border: "1px solid #2b3648",
            borderRadius: "8px",
            overflow: "hidden"
          }
        },
        h(
          "table",
          {
            style: {
              width: "100%",
              borderCollapse: "collapse",
              fontSize: "13px"
            }
          },
          h(
            "thead",
            null,
            h(
              "tr",
              { style: { background: "#202938", borderBottom: "1px solid #2e3a4e", textAlign: "left", color: "#94a3b8" } },
              h("th", { style: { padding: "12px 16px" } }, "Program Name"),
              h("th", { style: { padding: "12px 16px" } }, "Status"),
              h("th", { style: { padding: "12px 16px" } }, "Description"),
              h("th", { style: { padding: "12px 16px" } }, "Routines & AST Memory"),
              h("th", { style: { padding: "12px 16px" } }, "Program Scan Time"),
              h("th", { style: { padding: "12px 16px", textAlign: "right" } }, "Actions")
            )
          ),
          h(
            "tbody",
            null,
            programs.map(function (prog) {
              var pBudgetPct = scanRate > 0 ? ((Number(prog.lastScanMs || 0) / scanRate) * 100).toFixed(2) : "0.00";
              return h(
                "tr",
                {
                  key: prog.name,
                  onClick: function () { openProgramEditorInNewTab(prog.name); },
                  style: {
                    borderBottom: "1px solid #222c3c",
                    cursor: "pointer",
                    transition: "background 0.12s"
                  },
                  onMouseOver: function (e) { e.currentTarget.style.background = "#1f293a"; },
                  onMouseOut: function (e) { e.currentTarget.style.background = "transparent"; }
                },
                h(
                  "td",
                  { style: { padding: "14px 16px", fontWeight: 700, color: "#38bdf8", fontSize: "14px" } },
                  "📁 " + prog.name
                ),
                h(
                  "td",
                  { style: { padding: "14px 16px" } },
                  h(
                    "span",
                    {
                      style: {
                        padding: "3px 9px",
                        borderRadius: "4px",
                        fontSize: "11px",
                        fontWeight: 700,
                        background: prog.enabled ? "rgba(0,230,118,0.16)" : "rgba(148,163,184,0.15)",
                        color: prog.enabled ? "#00e676" : "#94a3b8",
                        border: "1px solid " + (prog.enabled ? "#00e676" : "#475569")
                      }
                    },
                    prog.enabled ? "ENABLED • RUNNING" : "DISABLED"
                  )
                ),
                h(
                  "td",
                  { style: { padding: "14px 16px", color: "#cbd5e1" } },
                  prog.description || "—"
                ),
                h(
                  "td",
                  { style: { padding: "14px 16px", fontFamily: "monospace", color: "#94a3b8" } },
                  prog.routineCount + " routines • " + prog.totalRungs + " rungs (" + (prog.astNodeCount || 0) + " AST nodes • ~" + (prog.estimatedRamKb || 1.2) + " KB)"
                ),
                h(
                  "td",
                  { style: { padding: "14px 16px", fontFamily: "monospace", color: "#cbd5e1" } },
                  Number(prog.lastScanMs || 0).toFixed(3) + " ms (" + pBudgetPct + "% of scan • " + (prog.scanCount || 0) + " scans)"
                ),
                h(
                  "td",
                  {
                    onClick: function (e) { e.stopPropagation(); },
                    style: {
                      padding: "14px 16px",
                      display: "flex",
                      justifyContent: "flex-end",
                      gap: "8px"
                    }
                  },
                  h(
                    "button",
                    {
                      onClick: function () { openProgramEditorInNewTab(prog.name); },
                      style: {
                        background: "#0284c7",
                        color: "#fff",
                        border: "1px solid #38bdf8",
                        borderRadius: "5px",
                        padding: "5px 11px",
                        fontSize: "12px",
                        fontWeight: 600,
                        cursor: "pointer"
                      }
                    },
                    "Open Editor ↗"
                  ),
                  h(
                    "button",
                    {
                      onClick: function () { handleToggleProgram(prog.name, !prog.enabled); },
                      style: {
                        background: prog.enabled ? "#78350f" : "#065f46",
                        color: prog.enabled ? "#fef3c7" : "#ecfdf5",
                        border: "1px solid " + (prog.enabled ? "#f59e0b" : "#10b981"),
                        borderRadius: "5px",
                        padding: "5px 11px",
                        fontSize: "12px",
                        fontWeight: 600,
                        cursor: "pointer"
                      }
                    },
                    prog.enabled ? "Disable" : "Enable"
                  ),
                  h(
                    "button",
                    {
                      onClick: function () { handleRenameProgram(prog); },
                      style: {
                        background: "#263042",
                        color: "#e2e8f0",
                        border: "1px solid #3f4e68",
                        borderRadius: "5px",
                        padding: "5px 11px",
                        fontSize: "12px",
                        fontWeight: 600,
                        cursor: "pointer"
                      }
                    },
                    "Rename"
                  ),
                  h(
                    "button",
                    {
                      onClick: function () { handleDeleteProgram(prog.name); },
                      style: {
                        background: "#7f1d1d",
                        color: "#fee2e2",
                        border: "1px solid #ef4444",
                        borderRadius: "5px",
                        padding: "5px 11px",
                        fontSize: "12px",
                        fontWeight: 600,
                        cursor: "pointer"
                      }
                    },
                    "Delete"
                  )
                )
              );
            })
          )
        )
      )
    );
  }

  var exportsObj = {};
  Object.defineProperty(exportsObj, "__esModule", { value: true });
  Object.defineProperty(exportsObj, "LadderLogicStudio", {
    enumerable: true,
    get: function () {
      return LadderGatewayProgramsPage;
    }
  });
  Object.defineProperty(exportsObj, "default", {
    enumerable: true,
    get: function () {
      return LadderGatewayProgramsPage;
    }
  });
  return exportsObj;
});
