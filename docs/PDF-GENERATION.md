# PDF Manual Generation Guide

This document explains how to generate the OpenTSx Manual in PDF format from the GitBook-style Markdown documentation.

## Overview

The OpenTSx project includes an automated PDF generation system that:
- Converts Markdown documentation to professional PDF format
- Integrates with the Maven build lifecycle
- Produces versioned manual PDFs alongside JAR artifacts
- Supports customization and styling options

## Prerequisites

### Required Software

#### 1. Pandoc (>= 2.0)

Pandoc is the universal document converter used to transform Markdown to PDF.

**Installation:**

```bash
# Ubuntu/Debian
sudo apt-get update
sudo apt-get install pandoc

# macOS
brew install pandoc

# Windows (using Chocolatey)
choco install pandoc

# Verify installation
pandoc --version
```

**Official download:** https://pandoc.org/installing.html

#### 2. LaTeX Distribution

A LaTeX distribution is required for PDF rendering. **XeLaTeX** is the default engine as it provides better Unicode support (required for box-drawing characters and special symbols in the documentation).

**TeXLive (Recommended):**

```bash
# Ubuntu/Debian (full installation ~5GB)
sudo apt-get install texlive-full

# Ubuntu/Debian (minimal installation ~500MB)
sudo apt-get install texlive texlive-latex-extra

# macOS
brew install --cask mactex

# Windows
# Download from: https://www.tug.org/texlive/windows.html
```

**MiKTeX (Alternative for Windows):**
- Download from: https://miktex.org/download

**Verify installation:**

```bash
pdflatex --version
# or
xelatex --version
```

### Checking Dependencies

Run the dependency checker:

```bash
./bin/build_manual_pdf.sh --check-only
```

This will report which dependencies are installed and which are missing.

## Building the PDF Manual

### Method 1: Maven Build (Recommended)

The PDF is automatically generated during the Maven package phase:

```bash
# Build everything including PDF
mvn clean package

# PDF location: target/OpenTSx-Manual-3.0.0.pdf
```

**Skip PDF generation:**

```bash
mvn clean package -Dskip.pdf.generation=true
```

**Advantages:**
- Integrated into normal build process
- Versioned PDF filename automatically
- No manual script execution needed
- PDF included in distribution packages

### Method 2: Direct Script Execution

For standalone PDF generation without running the full Maven build:

```bash
# Generate PDF with default settings
./bin/build_manual_pdf.sh

# Output: target/OpenTSx-Manual.pdf
```

**Custom options:**

```bash
# Custom output filename
./bin/build_manual_pdf.sh --output MyManual.pdf

# Use different PDF engine
./bin/build_manual_pdf.sh --engine xelatex

# Verbose output for debugging
./bin/build_manual_pdf.sh --verbose

# Combined options
./bin/build_manual_pdf.sh --engine xelatex --output Custom-3.0.0.pdf --verbose
```

**Advantages:**
- Faster than full Maven build
- Immediate feedback
- Useful during documentation writing
- Customization options

## PDF Generation Process

### Step-by-Step Workflow

1. **Script Initialization**
   - Checks for pandoc and LaTeX installation
   - Creates `target/documentation/` build directory
   - Sets up temporary workspace

2. **Document Merging**
   - Reads chapter order from script configuration
   - Merges all Markdown files into single document
   - Adds title page, TOC, and metadata
   - Inserts page breaks between chapters
   - Creates: `target/documentation/manual-merged.md`

3. **PDF Rendering**
   - Pandoc converts merged Markdown to LaTeX
   - LaTeX engine (pdflatex/xelatex) renders to PDF
   - Applies styling and formatting
   - Generates table of contents
   - Numbers sections automatically

4. **Output**
   - Final PDF created in `target/` directory
   - Filename: `OpenTSx-Manual-<version>.pdf` (Maven) or `OpenTSx-Manual.pdf` (script)
   - File size typically 1-5 MB depending on content

### Chapter Order

Chapters are included in the following order (defined in `bin/build_manual_pdf.sh`):

1. Introduction
   - What is OpenTSx
   - Why OpenTSx
   - Installation

2. Core Concepts
   - Architecture Overview
   - TimeSeriesObject
   - Data Model
   - Core Procedures

3. Data Operations
   - Creating Time Series
   - Loading Data
   - Transformations
   - Exporting Data

4. Statistical Analysis
   - Descriptive Statistics
   - Distribution Testing
   - Correlation

5. Advanced Topics
   - DFA Analysis
   - Hadoop Integration

6. Best Practices
   - Performance
   - Memory Management
   - Production

7. Appendix
   - API Reference
   - Glossary
   - Troubleshooting

To modify the chapter order, edit the `CHAPTERS` array in `bin/build_manual_pdf.sh`.

## Customization

### PDF Styling

Edit the Pandoc metadata block in `bin/build_manual_pdf.sh` (line ~95):

```markdown
---
title: "OpenTSx Manual"
subtitle: "Comprehensive Guide to Time Series Analysis in Java"
author: "OpenTSx Project"
date: "Version 3.0.0"
documentclass: book
geometry: margin=1in
fontsize: 11pt
toc: true
toc-depth: 3
numbersections: true
colorlinks: true
linkcolor: blue
---
```

**Common customizations:**

```markdown
# Change margins
geometry: margin=0.75in

# Change font size
fontsize: 12pt

# Change document class
documentclass: report  # or article

# Adjust TOC depth
toc-depth: 2  # Only show chapter and section

# Disable section numbering
numbersections: false

# Different link colors
linkcolor: darkblue
urlcolor: darkgreen
```

### Fonts and Typography

For advanced typography (requires xelatex):

```bash
./bin/build_manual_pdf.sh --engine xelatex
```

Then add to metadata:

```markdown
mainfont: "Times New Roman"
monofont: "Courier New"
fontsize: 10pt
linestretch: 1.2
```

### Code Highlighting

Change syntax highlighting theme in `bin/build_manual_pdf.sh`:

```bash
"--highlight-style=tango"  # Current (default)

# Alternatives:
"--highlight-style=pygments"
"--highlight-style=kate"
"--highlight-style=monochrome"
"--highlight-style=espresso"
"--highlight-style=zenburn"
"--highlight-style=haddock"
```

Preview all styles: https://pandoc.org/demo/example9/pandocs-markdown.html

## Troubleshooting

### "pandoc: command not found"

**Problem:** Pandoc is not installed or not in PATH.

**Solution:**
```bash
# Install pandoc (see Prerequisites section)
# Verify installation
which pandoc
pandoc --version
```

### "pdflatex: command not found"

**Problem:** LaTeX distribution is not installed.

**Solution:**
```bash
# Install TeXLive (see Prerequisites section)
# Verify installation
which pdflatex
pdflatex --version
```

### "! LaTeX Error: File `*.sty' not found"

**Problem:** Missing LaTeX packages.

**Solution:**
```bash
# Ubuntu/Debian: Install full TeXLive
sudo apt-get install texlive-full

# Or install specific missing package
sudo apt-get install texlive-latex-extra texlive-fonts-extra
```

### "Unicode character ... not set up for use with LaTeX"

**Problem:** Special characters (box-drawing characters: ┌, ├, │, └) require XeLaTeX/LuaLaTeX.

**Note:** XeLaTeX is now the default engine (since version 3.0.0) to handle Unicode characters in documentation.

**Solution:**
```bash
# XeLaTeX is default, but you can explicitly specify it
./bin/build_manual_pdf.sh --engine xelatex

# Or use LuaLaTeX as alternative
./bin/build_manual_pdf.sh --engine lualatex

# Only use pdflatex if documentation has no Unicode characters
./bin/build_manual_pdf.sh --engine pdflatex
```

### PDF Generation Fails During Maven Build

**Problem:** Maven build fails on PDF generation step.

**Solution 1: Skip PDF generation**
```bash
mvn clean package -Dskip.pdf.generation=true
```

**Solution 2: Check script directly**
```bash
# Run script manually to see detailed error
./bin/build_manual_pdf.sh --verbose
```

**Solution 3: Verify dependencies**
```bash
./bin/build_manual_pdf.sh --check-only
```

### Broken Links or Formatting in PDF

**Problem:** Markdown renders incorrectly in PDF.

**Solution:**
```bash
# Check the merged markdown file for issues
cat target/documentation/manual-merged.md

# Fix markdown syntax in source files
# Ensure relative links use correct paths
# Check for unsupported markdown extensions
```

### PDF is Too Large

**Problem:** Generated PDF is unexpectedly large.

**Possible Causes:**
- Embedded images are not compressed
- Too many high-resolution diagrams
- Verbose code listings

**Solutions:**
```bash
# Compress images before including
# Use vector formats (SVG) where possible
# Consider splitting into multiple PDFs
```

## Advanced Usage

### Custom PDF Template

Create a custom LaTeX template for complete control:

1. Generate default template:
```bash
pandoc -D latex > custom-template.tex
```

2. Edit `custom-template.tex` to customize layout

3. Update script to use custom template:
```bash
pandoc ... --template=custom-template.tex ...
```

### Generating Multiple Formats

Generate other formats simultaneously:

```bash
# EPUB for e-readers
pandoc manual-merged.md -o OpenTSx-Manual.epub

# HTML (single page)
pandoc manual-merged.md -o OpenTSx-Manual.html --standalone --toc

# DOCX for Microsoft Word
pandoc manual-merged.md -o OpenTSx-Manual.docx

# ODT for LibreOffice
pandoc manual-merged.md -o OpenTSx-Manual.odt
```

### CI/CD Integration

For automated builds in CI/CD pipelines:

**GitHub Actions:**

```yaml
- name: Install Dependencies
  run: |
    sudo apt-get update
    sudo apt-get install -y pandoc texlive-latex-base texlive-latex-extra

- name: Build with Maven (includes PDF)
  run: mvn clean package

- name: Upload PDF Artifact
  uses: actions/upload-artifact@v3
  with:
    name: opentsx-manual-pdf
    path: target/OpenTSx-Manual-*.pdf
```

**GitLab CI:**

```yaml
pdf_generation:
  image: pandoc/latex:latest
  script:
    - mvn clean package
  artifacts:
    paths:
      - target/OpenTSx-Manual-*.pdf
```

## Maven Configuration Details

### Plugin Configuration

The PDF generation is configured in `pom.xml`:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.1.0</version>
    <executions>
        <execution>
            <id>generate-pdf-manual</id>
            <phase>package</phase>
            <goals>
                <goal>exec</goal>
            </goals>
            <configuration>
                <executable>bash</executable>
                <workingDirectory>${project.basedir}</workingDirectory>
                <arguments>
                    <argument>${project.basedir}/bin/build_manual_pdf.sh</argument>
                    <argument>--output</argument>
                    <argument>OpenTSx-Manual-${project.version}.pdf</argument>
                </arguments>
                <skip>${skip.pdf.generation}</skip>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Build Lifecycle

PDF generation occurs during the `package` phase:

```
mvn package
  ├── compile
  ├── test
  ├── package (JAR creation)
  └── generate-pdf-manual (PDF creation) ← PDF created here
```

### Skipping PDF Generation

Control via Maven property:

```bash
# In command line
mvn package -Dskip.pdf.generation=true

# In pom.xml
<properties>
    <skip.pdf.generation>true</skip.pdf.generation>
</properties>

# In settings.xml (global)
<profiles>
    <profile>
        <id>no-pdf</id>
        <properties>
            <skip.pdf.generation>true</skip.pdf.generation>
        </properties>
    </profile>
</profiles>
```

## File Locations

### Input Files

- Markdown sources: `docs/manual/**/*.md`
- Build script: `bin/build_manual_pdf.sh`
- Maven config: `pom.xml`

### Intermediate Files

- Merged markdown: `target/documentation/manual-merged.md`
- LaTeX intermediate: `target/documentation/*.tex` (if preserved)

### Output Files

- Final PDF (Maven): `target/OpenTSx-Manual-<version>.pdf`
- Final PDF (script): `target/OpenTSx-Manual.pdf`

## Best Practices

### During Documentation Writing

1. **Preview frequently:**
   ```bash
   ./bin/build_manual_pdf.sh
   ```

2. **Check specific chapters:**
   - Comment out unwanted chapters in `CHAPTERS` array
   - Rebuild PDF to see only relevant sections
   - Restore full array when done

3. **Test all links:**
   - Ensure internal links work in PDF
   - Use section anchors correctly
   - Verify cross-references

### Before Release

1. **Full clean build:**
   ```bash
   mvn clean package
   ```

2. **Verify PDF quality:**
   - Check table of contents is complete
   - Verify all chapters are included
   - Test all hyperlinks
   - Review code highlighting
   - Check page breaks and formatting

3. **Version verification:**
   - Ensure version number is correct in title page
   - Check filename includes correct version

4. **Distribution:**
   - Include PDF in release artifacts
   - Upload to documentation website
   - Link from README.md

## Support

### Getting Help

- **Script issues:** Check `bin/build_manual_pdf.sh` source code
- **Maven issues:** Review `pom.xml` plugin configuration
- **Pandoc issues:** https://pandoc.org/getting-help.html
- **LaTeX issues:** https://tex.stackexchange.com/

### Reporting Bugs

When reporting PDF generation issues, include:

1. Operating system and version
2. Pandoc version (`pandoc --version`)
3. LaTeX version (`pdflatex --version`)
4. Error messages (use `--verbose` flag)
5. Contents of `target/documentation/manual-merged.md`

## References

- **Pandoc Manual:** https://pandoc.org/MANUAL.html
- **Pandoc Markdown:** https://pandoc.org/MANUAL.html#pandocs-markdown
- **LaTeX Documentation:** https://www.latex-project.org/help/documentation/
- **Maven Exec Plugin:** https://www.mojohaus.org/exec-maven-plugin/

---

**Last Updated:** 2025-12-20
**OpenTSx Version:** 3.0.0
