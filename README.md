<div align="center">

# ⚡ Ignition Ladder Logic Studio (`ignition-ladder-plc`)

**Studio 5000-Style Ladder Logic SoftPLC Engine & Browser-Based Web IDE for Inductive Automation's Ignition Platform (8.1 & 8.3)**

[![Ignition 8.3](https://img.shields.io/badge/Ignition-8.3%2B-00d4ff?style=for-the-badge&logo=data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0iI2ZmZiIgZD0iTTEzIDJMMiAxNGg3bC0yIDhsMTEtMTJoLTdsMi04eiIvPjwvc3ZnPg==)](https://github.com/felipemvcab/ignition-ladder-plc/releases/latest)
[![Ignition 8.1](https://img.shields.io/badge/Ignition-8.1%2B-3b82f6?style=for-the-badge)](https://github.com/felipemvcab/ignition-ladder-plc/releases/latest)
[![Release](https://img.shields.io/github/v/release/felipemvcab/ignition-ladder-plc?style=for-the-badge&color=10b981)](https://github.com/felipemvcab/ignition-ladder-plc/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-f59e0b?style=for-the-badge)](LICENSE)

[**📥 Download Latest Signed `.modl` (v1.0.0)**](https://github.com/felipemvcab/ignition-ladder-plc/releases/latest) • [**🌐 Live Showcase Portal**](https://felipemvcab.github.io/ignition-ladder-plc/) • [**📖 Instruction Set**](#-supported-instruction-set) • [**🚀 Quick Start**](#-installation--quick-start)

</div>

---

## 🔍 Overview

**Ladder Logic Studio** (`com.antigravity.ladderplc`) is a self-contained, zero-dependency **Gateway-scoped SoftPLC module** for Inductive Automation's Ignition SCADA platform. It embeds a deterministic cyclic ladder logic execution engine and a rich, browser-based Studio 5000-style visual Ladder Logic IDE directly inside the Ignition Gateway—requiring no external PLC hardware or desktop IDE.

All controller tags, UDT structures (`TIMER`, `COUNTER`), and Add-On Instruction (`AOI`) instances are automatically synchronized in real time with a dedicated Ignition Managed Tag Provider (`[LadderPLC]`), making every tag immediately browsable and read/write accessible from **Perspective**, **Vision**, **Tag Scripts**, and **OPC-UA**.

---

## ✨ Key Features

- **⚡ Deterministic Cyclic Scan Engine (`CyclicScanEngine`)**
  - Configurable scan period with sub-millisecond multi-subroutine execution (~50–150 µs per full scan).
  - Full parallel branch evaluation (`BST` / `NXB` / `BND`), nested branches, seal-in circuits, and rung-condition-in tracking.
  - Subroutines & Program Control (`JSR`, `SBR`, `RET`, `JMP`, `LBL`, `MCR`, `ONS`, `OSR`, `OSF`).
  - Add-On Instructions (`AOI`) with isolated local parameters and instance data structures.
- **🏷️ Native Ignition Managed Tag Provider (`[LadderPLC]`)**
  - Automatically registers `[LadderPLC]ControllerTags/...` with live UDT structures for Timers (`.PRE`, `.ACC`, `.EN`, `.TT`, `.DN`) and Counters (`.PRE`, `.ACC`, `.CU`, `.CD`, `.DN`, `.OV`, `.UN`).
  - **Dirty-Write Batching**: Only pushes tag changes to Ignition's Tag Manager when values actually mutate, eliminating tag event bus flooding.
  - **Hardware / HMI I/O Override & Tag Forcing**: Force any boolean or numeric tag ON/OFF/value from the Web IDE regardless of scan logic.
- **🖥️ Embedded Studio 5000-Style Web IDE (`/system/ladder`)**
  - Visual drag-and-drop & mnemonic rung editor with live green power-flow energization rails.
  - Multi-rung selection (`Shift+Click`), copy/paste, undo/redo, and inline tag value editing.
  - Built-in **Cross-Reference Browser**, **Tag Watch Table**, **Trend Chart**, and **Routine/AOI Organizer**.
  - Native **Ignition 8.3 Gateway Sidebar Navigation** (`/app/ladder-programs`) plus standalone Web IDE endpoint (`/system/ladder`).
- **📦 Dual Ignition 8.3 & 8.1 Compatibility**
  - Signed `.modl` builds provided for both **Ignition 8.3+** (`jakarta.servlet`, Java 17) and **Ignition 8.1+** (`javax.servlet`, Java 11).

---

## 📥 Downloads

Grab the pre-built, self-signed `.modl` files directly from the [**Latest GitHub Release**](https://github.com/felipemvcab/ignition-ladder-plc/releases/latest):

| Target Platform | Module File | Java / Servlet ABI | Direct Download |
| :--- | :--- | :--- | :--- |
| **Ignition 8.3.x** | `LadderLogic-SoftPLC-1.0.0.modl` | Java 17 / `jakarta.servlet` | [**Download for 8.3**](https://github.com/felipemvcab/ignition-ladder-plc/releases/download/v1.0.0/LadderLogic-SoftPLC-1.0.0.modl) |
| **Ignition 8.1.x** | `LadderLogic-SoftPLC-1.0.0-Ignition8.1.modl` | Java 11 / `javax.servlet` | [**Download for 8.1**](https://github.com/felipemvcab/ignition-ladder-plc/releases/download/v1.0.0/LadderLogic-SoftPLC-1.0.0-Ignition8.1.modl) |

---

## 🧰 Supported Instruction Set

| Category | Instructions | Description |
| :--- | :--- | :--- |
| **Bit / Relay Logic** | `XIC`, `XIO`, `OTE`, `OTL`, `OTU`, `ONS`, `OSR`, `OSF` | Examine If Closed/Open, Output Energize, Latch/Unlatch, Rising/Falling One-Shots |
| **Timers & Counters** | `TON`, `TOF`, `RTO`, `CTU`, `CTD`, `RES` | On-Delay, Off-Delay, Retentive Timer On, Count Up, Count Down, Reset |
| **Comparison** | `EQU`, `NEQ`, `LES`, `LEQ`, `GRT`, `GEQ`, `LIM`, `MEQ` | Equal, Not Equal, Less/Greater Than, Limit Test, Masked Equal |
| **Math & Compute** | `ADD`, `SUB`, `MUL`, `DIV`, `MOD`, `SQR`, `SQRT`, `NEG`, `ABS`, `CPT` | Arithmetic operations and free-form expression Compute (`CPT`) |
| **Advanced Math / Trig** | `SIN`, `COS`, `TAN`, `ASN`, `ACS`, `ATN`, `LN`, `LOG`, `XPY` | Trigonometric, natural/base-10 log, and exponentiation |
| **Move & Bitwise** | `MOV`, `MVM`, `CLR`, `SWPB`, `AND`, `OR`, `XOR`, `NOT`, `BTD` | Move, Masked Move, Clear, Swap Bytes, Bitwise Logic, Bit Field Distribute |
| **Array & File** | `COP`, `FLL`, `AVE`, `STD`, `MIN`, `MAX`, `FFL`, `FFU`, `LFL`, `LFU`, `BSL`, `BSR` | Copy/Fill, Statistics, FIFO/LIFO Stacks, Bit Shift Left/Right |
| **Process Control** | `PID`, `SCL`, `SCP` | Closed-loop PID controller, Scale, and Scale with Parameters (`SCP`) |
| **Program Flow & AOI** | `JSR`, `SBR`, `RET`, `JMP`, `LBL`, `MCR`, `NOP`, `AFI`, `AOI` | Jump to Subroutine, Return, Jump/Label, Master Control Reset, Custom AOIs |

---

## 🚀 Installation & Quick Start

1. **Install the Module**
   - In your Ignition Gateway web UI, navigate to **Config → System → Modules** (or **Platform → Modules** in Ignition 8.3).
   - Click **Install or Upgrade a Module...** and upload `LadderLogic-SoftPLC-1.0.0.modl` (for 8.3) or `LadderLogic-SoftPLC-1.0.0-Ignition8.1.modl` (for 8.1).
   - Accept the self-signed certificate from **Felipe Integrations**.
2. **Open the Ladder Logic Studio Web IDE**
   - **Ignition 8.3**: Click **Ladder Logic Studio → Programs** in the Gateway navigation sidebar, or visit:
     ```text
     http://<your-gateway-ip>:8088/system/ladder
     ```
   - **Ignition 8.1**: Visit `http://<your-gateway-ip>:8088/system/ladder`.
3. **Browse Live Tags in Designer**
   - Open the **Ignition Designer** → Tag Browser → select the **`LadderPLC`** tag provider.
   - All PLC tags under `[LadderPLC]ControllerTags/` update live and can be dragged directly onto Perspective or Vision views.

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Ignition Gateway (8.1 / 8.3)                     │
│                                                                         │
│  ┌───────────────────────────────┐    ┌──────────────────────────────┐  │
│  │   LadderStudioServlet         │    │  ManagedTagProvider          │  │
│  │   (/system/ladder Web IDE)    │    │  ([LadderPLC] Tag Provider)  │  │
│  └──────────────┬────────────────┘    └──────────────▲───────────────┘  │
│                 │ REST / JSON Snapshot               │ Dirty-Write      │
│                 ▼                                    │ Batch Sync       │
│  ┌───────────────────────────────────────────────────┴───────────────┐  │
│  │                      CyclicScanEngine                             │  │
│  │  • MnemonicCompiler (BST/NXB/BND AST Builder)                     │  │
│  │  • MainRoutine ──► JSR Subroutines & AOI Instances                │  │
│  │  • TagImageManager & UdtTimerCounterManager (Force Table + I/O)   │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 🛠️ Building from Source

```bash
# Requires JDK 17+ and Ignition SDK libraries
./build.sh
```

The build script compiles the Gateway sources, executes the automated scan engine & randomized instruction validation suite (`InstructionValidationSuite`), benchmarks 1,000 multi-subroutine scans, and outputs signed `.modl` binaries in `build/dist/`.

---

## 📄 License

Released under the [MIT License](LICENSE) by **Felipe Integrations** ([@felipemvcab](https://github.com/felipemvcab)).
*Independent community module; not an official product of Inductive Automation.*
