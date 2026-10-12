# PDF generation

The user manual (`docs/user-manual`) and this developer guide are Honkit (GitBook-compatible) books. Each has a
`README.md`, a `SUMMARY.md` and a `book.json`.

```bash
mvn -Ppdf-manual                 # runs bin/build_docs_pdfs.sh (Docker image from bin/Dockerfile.docs-pdf)
bin/build_docs_pdfs.sh           # directly; writes target/OpenTSx-User-Manual.pdf and target/OpenTSx-DevGuide.pdf
```

The script uses `honkit` (or the legacy `gitbook` CLI) and Calibre's `ebook-convert`, both inside the Docker image.
To preview the manual as HTML, run `npx honkit serve docs/user-manual`.

The previous Pandoc-based script (`build_manual_pdf.sh`) and the old notes are in `archive/`.
