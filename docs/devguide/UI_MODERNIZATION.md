# Swing UI Modernization Options

This document outlines strategies for modernizing the legacy Swing-based "Macro Recorder" interface to better align with current UX standards and the roadmap for **TASK-005**.

## Option 1: Drop-in Modernization (FlatLaf) - **Selected Strategy**

**FlatLaf** (Flat Look and Feel) is a modern, open-source Look and Feel for Java Swing desktop applications. It looks like IntelliJ IDEA 2019.2+ and scales perfectly on HiDPI screens.

### Pros
- **Minimal Effort**: Requires changing only 1-2 lines of code in the `main` method.
- **Dark Mode**: Native support for Dark/Light themes.
- **HiDPI**: Excellent scaling on Retina/4K displays.
- **Zero Rewrite**: Existing `JPanel`, `JButton`, and `JFrame` code remains untouched.
- **Modern Standards**: Uses modern fonts (San Francisco on Mac, Segoe on Windows) and spacing.

### Cons
- Still bound by Swing's layout managers (BorderLayout, GridBagLayout).
- Animations are limited compared to web/JavaFX.

### Implementation Plan
1.  Add `com.formdev:flatlaf` dependency to `pom.xml`.
2.  Initialize `FlatDarkLaf.setup()` at application startup.
3.  Customize the "Accent Color" to match OpenTSx branding.

---

## Option 2: Material Design (WebLaF / Material-UI-Swing)

Libraries like `Material-UI-Swing` bring Google's Material Design (Android style) to Swing components.

### Pros
- **Distinctive Look**: Bold colors, shadows, and card-based layouts.
- **Interactive**: Ripple effects on buttons.

### Cons
- **Platform Alien**: Looks out of place on macOS and Windows 11.
- **Layout Issues**: Material components usually require more padding/margin, often breaking tight legacy layouts.
- **Maintenance**: Some libraries are less actively maintained than FlatLaf.

---

## Option 3: Hybrid JavaFX (JFoenix)

Embedding JavaFX scenes inside Swing `JFrames` using `JFXPanel`.

### Pros
- **Rich Graphics**: Access to CSS styling, complex charts, and hardware-accelerated animations.
- **Future Proof**: JavaFX is the successor to Swing.

### Cons
- **High Complexity**: Requires managing two UI threads (EDT and JavaFX Application Thread).
- **Rewrite**: Existing Swing panels must be gradually rewritten in FXML/Java code.
- **Heavy**: Increases application startup time and memory footprint.

---

## Decision

We proceed with **Option 1 (FlatLaf)** for the `MacroRecorder2` prototype. This provides the necessary "freshness" for the demo without diverting resources from the core goal of **TASK-005** (Processing Flow Description).
