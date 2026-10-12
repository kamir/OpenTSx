# TASK-012 – Briefing für den lokalen Agenten

> Stand **12.10.2026**, `master` = `0df08c8`. Verfasst von der Cloud-Session, die den 2026-Refresh (TASK-011) gebaut
> und die PRs aufgeräumt hat. Empfänger: der Agent auf dem Rechner des Owners. Sprache der Rückmeldungen: Deutsch.

## 1. Warum du, und nicht die Cloud-Session

Die Cloud-Session hat Code, Doku und PRs erledigt. Ihr fehlt alles, was nur auf dem Rechner des Owners geht. Dafür
bist du da:

- **Rechte:** Die Cloud-Session darf keine Branches löschen und keine Tags pushen (Proxy: 403).
- **Hardware und Netz:** Docker, k3s, KafScale im LAN, später der Modbus-Umsetzer an den Zählern.
- **Zugangsdaten:** ENTSO-E-Token, netztransparenz-OAuth-Client. Sie kommen nur über Umgebungsvariablen und landen
  nie in Git.
- **Private Daten:** Abrechnungen, Bootsdaten, Fotos und Datenblätter. Sie bleiben in `config/private/`.

Ungetestet ist bisher alles, was echte Infrastruktur braucht: Docker-Build der Images, ein echter k3s-Lauf, KafScale
aus den Pods heraus und das Backend-Image auf Python 3.12. Das ist deine Kernaufgabe (Abschnitt 4, Aufgabe B).

## 2. Erst lesen

1. [`README.md`](../README.md): Positionierung, Module, Schnellstart.
2. [`TASK-011`](TASK-011-datamodel-refresher-2026.md): vor allem „Offene Punkte / Backlog“ und das Progress Log.
3. [`docs/devops/anleitung-lokale-testinstallation.md`](../docs/devops/anleitung-lokale-testinstallation.md): deine
   Arbeitsanleitung für Aufgabe B. Dazu [`operations.md`](../docs/devops/operations.md) und
   [`troubleshooting.md`](../docs/devops/troubleshooting.md).
4. [`opentsx-market/README.md`](../opentsx-market/README.md): Quellen, Archiv, Backfill (Aufgabe C).
5. [`CONTRIBUTING.md`](../CONTRIBUTING.md) und [`SECURITY.md`](../SECURITY.md).

## 3. Feste Regeln

- **Nie committen:**
  - `config/private/**`: Abrechnungen, Bootsdaten, Inventar, Fotos.
  - `deploy/k3s/.env` und `deploy/k3s/overlays/local/`.
  - `data/history/`.
  - Probe-Berichte mit LAN-IPs.
  - Tokens und Schlüssel: `ENTSOE_TOKEN`, `NTP_CLIENT_ID`, `NTP_CLIENT_SECRET`, `OPENMETADATA_JWT`, S3-Schlüssel.

  Vor jedem Commit `git status` lesen. Ein Treffer in diesen Pfaden ist ein Abbruchgrund.
- **`config/private/ccloud.props`** ist trotz `.gitignore` versioniert (Demo-Schlüssel). Nicht anfassen. Rotation,
  Entfernen und Bereinigen der Historie entscheidet der Owner.
- **Jede Änderung** läuft über einen Branch und einen PR nach `master`.
  - Merge nur bei grüner CI; die CI hat 19 bis 20 Checks: Java 17/21, Python 3.10 bis 3.13, KafScale-Konformität,
    Java→Iceberg→Python, k3s-Skripte, GitGuardian.
  - Gemergt wird mit Merge-Commit.
  - Kein Force-Push auf fremde Branches, keine umgeschriebene Historie. Tests werden nie übersprungen oder
    abgeschaltet.
- **Vor jedem Push** laufen die Prüfungen, die auch die CI macht:
  - `mvn -B -ntp verify`, bei kleinen Änderungen `mvn -B -ntp -pl <modul> -am verify`
  - `cd python-package && python -m pytest -q`
  - `python3 tools/check_links.py` bei Doku-Änderungen
  - `shellcheck -x -P SCRIPTDIR deploy/k3s/*.sh deploy/docker/*.sh` bei Skripten
- **Dependabot:**
  - Majors sind per Konfiguration ausgeschlossen; sie stehen als geplante Migration im TASK-011-Backlog.
  - Ebenfalls ausgeschlossen: Avro, Iceberg-Minors, JFreeChart, zstd-jni und `eslint-plugin-react-refresh` ab 0.5.
  - Gruppen-PRs lokal prüfen und bei grüner CI mergen. Bei einem Konflikt `@dependabot rebase` kommentieren
    (du kannst das, die Cloud-Session nicht) oder master hineinmergen.
- **Sprache:**
  - Konzeptdokumente (`docs/datamodel-2026/`, `docs/business/`, TASK-Dateien) auf Deutsch.
  - README, Handbuch, Code und Commit-Messages auf Englisch.
  - Commit-Betreff im Imperativ, im Text das Warum.
- **Ergebnisse mit echten Daten** (Kalibrierung, Rechnungen, Bootsdaten) gehen nur an den Owner, nie ins Repo. Ins
  Repo kommen nur allgemeine Fixes.

## 4. Aufgaben, in dieser Reihenfolge

### A. Repo-Hygiene (Minuten)

1. **Gemergte Branches löschen.** Diese 27 Branches sind fertig: Ihr Inhalt ist in `master`, die vier
   `p3`/`p4`/`p5`/`datamodel-refresher`-Branches enthalten nur noch Merge-Commits.
   ```bash
   git fetch --prune origin
   git push origin --delete claude/festive-ride-wkhpev claude/wizardly-maxwell-juwbz5 \
     opentsx/2026-deps-batch opentsx/2026-deps-batch2 opentsx/2026-devops-k3s opentsx/2026-dwd \
     opentsx/2026-explorer opentsx/2026-hygiene opentsx/2026-install-guide opentsx/2026-integration \
     opentsx/2026-market-history opentsx/2026-market-research opentsx/2026-modbus opentsx/2026-ops \
     opentsx/2026-p7-processor opentsx/2026-p9-boat opentsx/2026-p9-house opentsx/2026-p9-loose \
     opentsx/2026-p9-scenarios opentsx/2026-p9-twin opentsx/2026-saas-deps opentsx/2026-user-manual \
     opentsx/2026-datamodel-refresher opentsx/2026-p3-store-spi opentsx/2026-p4-stats-openmetadata \
     opentsx/2026-p5-retrieval dependabot/npm_and_yarn/opentsx-saas-frontend/brace-expansion-1.1.21
   ```
   Vorher mit `git branch -r --merged origin/master` gegenprüfen. Lösche nur Branches, die dort stehen oder oben
   genannt sind.
   - **Ausnahme `claude/festive-ride-wkhpev`:** Lösche ihn erst, wenn der PR mit diesem Briefing gemergt ist. Gilt
     das nicht, nimm ihn aus der Liste.
2. **Sicherungs-Tag für das entfernte Archiv** (#152): Die Archiv-Module sind aus `master` entfernt. Ihr letzter Stand
   ist `315f01d`.
   ```bash
   git tag -a archive-2026-10 315f01d -m "Stand vor dem Entfernen der Archiv-Module" && git push origin archive-2026-10
   ```
3. **Security-Tab prüfen.** Die Warnungen zu `archive/modules/**` sollten nach #152 geschlossen sein. Erwartet bleiben
   die Frontend-Befunde (Abschnitt 6). Mit `gh`:
   ```bash
   gh api repos/kamir/OpenTSx/dependabot/alerts --paginate \
     --jq '.[] | select(.state=="open") | [.number, .dependency.manifest_path, .security_advisory.severity] | @tsv'
   ```
4. **Backlog in TASK-011 nachziehen** (kleiner Doku-PR):
   - „Event-Synchronisation: Q > 1 …“ ist mit #110 erledigt: abhaken.
   - Bei „Flink 1.20/2.x-Upgrade“ vermerken, dass Flink 1.20.5 mit #147 drin ist. Die Adapter sind noch offen.

**Fertig, wenn:** `git branch -r` nur noch `master` (und ggf. neue Arbeits-Branches) zeigt, der Tag existiert und die
offenen Alerts aufgelistet sind.

### B. Lokale Testinstallation (Kernaufgabe)

Arbeitsanleitung: [`anleitung-lokale-testinstallation.md`](../docs/devops/anleitung-lokale-testinstallation.md).

- **Voraussetzungen (Teil A):** Java 21, Maven ≥ 3.9, Docker mit compose, k3s, kubectl.
- **Falls ein Raspberry Pi das Ziel ist:** `OPENTSX_PLATFORM=linux/arm64`.

```bash
deploy/k3s/60-probe.sh --dry-run --local-kafscale        # Plan, Konfiguration und Manifeste prüfen, ändert nichts
deploy/k3s/60-probe.sh --local-kafscale --online --verbose
```

- **Was die Probe macht:** Sie startet KafScale lokal, das die LAN-IP des Hosts annonciert
  (`KAFSCALE_ADVERTISED_HOST`). Dann baut sie die Images, importiert sie in k3s, konfiguriert, deployt und lässt alle
  Demos laufen. Am Ende schreibt sie `probe-report-<UTC>.md` und `probe-logs-<UTC>/`.
- **Bekannte Falle:** Der Compose-Default von KafScale annonciert `localhost`. Pods brauchen eine Adresse, die sie
  erreichen. Siehe `deploy/k3s/env.example` (`KAFSCALE_NETWORK` = `direct` | `hostnetwork` | `service`).

Zusätzlich, weil bisher nie gebaut:

```bash
docker build -t opentsx/saas-backend:probe opentsx-saas-backend            # Python 3.12
docker run --rm opentsx/saas-backend:probe python -c "import app.main; print('ok')"
docker build -t opentsx/saas-frontend:probe opentsx-saas-frontend
deploy/k3s/70-backup.sh --quiesce --out /tmp/opentsx-backup   # danach testweise wiederherstellen:
deploy/k3s/71-restore.sh /tmp/opentsx-backup --yes
```

**Fertig, wenn:**
- alle Probe-Schritte PASS sind oder jeder FAIL/SKIP erklärt ist;
- die beiden SaaS-Images bauen und der App-Import läuft;
- Backup und Restore einmal durchgelaufen sind.

Gefundene Fehler in Skripten oder Manifesten behebst du per PR, wobei die k3s-CI-Prüfung grün bleiben muss.

**Den Bericht nicht committen.** Er enthält LAN-IPs. Gib dem Owner eine bereinigte Zusammenfassung.

### C. Markthistorie mit Zugangsdaten

Erst wenn der Owner die Zugangsdaten liefert.

- **Zugangsdaten setzen:** Nur in der Shell oder einer ungetrackten Datei setzen, zum Beispiel
  `config/private/market.env`, die du mit `source` lädst. Gebraucht werden `ENTSOE_TOKEN`, `NTP_CLIENT_ID` und
  `NTP_CLIENT_SECRET`.
- **Ratenlimit bei netztransparenz.de:** höchstens 2 Anfragen pro Sekunde. Bei wiederholter Überschreitung wird die
  IP für 2 Stunden gesperrt. Der Backfill pausiert von sich aus (mindestens 600 ms), also `--delay-ms` nicht senken.
- **Ohne OAuth-Client:** Die CSV-Downloads der Webseite lassen sich mit
  `market rebap|marktwert --offline <datei> --from <JJJJ-MM-TT> --to <JJJJ-MM-TT> --archive data/history`
  einspielen.

```bash
bin/opentsx market backfill --archive data/history --series market --from-year 2019   # fortsetzbar; der Satz „market“
                                     # enthält SMARD, Energy-Charts, ENTSO-E, reBAP und Marktwerte; bisher übersprungene
                                     # Quellen werden jetzt mit Zugangsdaten nachgeholt
bin/opentsx market weather-backfill --archive data/history --from-year 2019
bin/opentsx market report --archive data/history                                      # Manifeste, Prüfsummen, Qualität
bin/opentsx twin  --history data/history --year 2024
bin/opentsx house --history data/history --weather-year 2022-2025
bin/opentsx boat  --history data/history --weather-year 2024
```

**Fertig, wenn:**
- `market report` die ENTSO-E- und netztransparenz-Pakete ohne unplausible Werte zeigt;
- die drei Zwillinge auf echten Jahren laufen;
- `opentsx-market/README.md` §6 per PR aktualisiert ist (Mengen und Lücken, keine Daten).

**Optional:** die History-CronJobs in k3s (`deploy/k3s/history/`).

### D. Private Daten einspielen

Erst wenn der Owner sie ablegt.

- **Haus:** `config/private/abrechnungen.csv`, Vorlage `opentsx-twin/src/main/resources/twin/abrechnungen-vorlage.csv`.
  Aufruf: `bin/opentsx house --calibrate config/private/abrechnungen.csv [--weather config/private/tagesmittel.csv]`.
  Siehe [`08-HAUS-ZWILLING.md`](../docs/datamodel-2026/08-HAUS-ZWILLING.md) §10.
- **Boot:** `bin/opentsx boat --data config/private/bootsdaten.csv --calibrate`. Siehe
  [`10-BOOT-ZWILLING.md`](../docs/datamodel-2026/10-BOOT-ZWILLING.md).
- **Explorer:** Fotos, Datenblätter und echtes Inventar-YAML nach `config/private/`. Siehe
  [`12-EXPLORATIVES-UI-INVENTAR.md`](../docs/datamodel-2026/12-EXPLORATIVES-UI-INVENTAR.md).

**Fertig, wenn** der Owner die Kalibrierergebnisse hat. Im Repo ändert sich höchstens Code, der dabei als fehlerhaft
auffiel.

### E. Zähler über Modbus

Erst wenn Umsetzer und Registerkarten da sind.

- **Registerkarte** als YAML (registermap v1) anlegen, Vorlage
  `opentsx-modbus/src/main/resources/modbus/example-grid-meter.yaml`. Prüfen mit
  `bin/opentsx modbus check|read|poll` gegen den Umsetzer.
- **Konkrete Daten** wie Host, Port, Zählernummern und Messlokation nur in `deploy/k3s/.env` (`MODBUS_HOST`,
  `MODBUS_PORT`, `MODBUS_MAP`) oder `config/private/`.
- **Ins Repo** darf eine Registerkarte nur, wenn sie allgemein ist (Zählertyp, keine Anlagendaten).

## 5. Was du nicht selbst entscheidest (fragen)

- Rotation und Entfernen von `ccloud.props`.
- Kampagne: Kategoriename und Markenrecherche, Budgetstufe, Pilotpartner, Freigaben
  ([`docs/business/`](../docs/business/README.md)). Geplanter Start ist der 19.10.2026.
- Entscheidungen D6, D14–D17, D18–D24 und D30–D36 (TASK-011).
- Parameter für den echten k3s-Betrieb: KafScale-Adresse, Lake-Pfad, Architektur, OpenMetadata-Instanz,
  Aufbewahrungsdauer.
- Jede Major-Migration aus Abschnitt 6.

## 6. Backlog, nur nach Freigabe durch den Owner

Diese Punkte stehen in TASK-011 unter „Major-Upgrades“, als Migration geplant:
- Avro 1.12.2 (Klassen-Freigabe für v2- und Legacy-Klassen)
- Iceberg-Writer-API (1.11+)
- JFreeChart 1.5
- Confluent 8, JUnit 6, json-schema-validator 3
- Frontend-Migration: React 19, Tailwind 4, React Router 7, ESLint-Flat-Config u. a. Sie behebt auch die offenen
  Audit-Befunde, darunter den kritischen in maplibre-gl, das über Plotly mitkommt.

Kleinere Hygiene-Punkte:
- Profile mit privaten Hosts in `opentsx-lg/src/main/resources/config/`
- `sample_data/` gegen `data/sample_datasets/`
- PyPI-Veröffentlichung

## 7. Rückmeldung an den Owner

Pro Aufgabe:
- **Erledigt:** was, mit Links zu PRs.
- **Ergebnis:** eine PASS/FAIL-Tabelle.
- **Offen:** Punkte mit konkreter Frage.

Keine Geheimnisse, keine LAN-IPs, keine echten Verbrauchs- oder Bootsdaten in Berichten, Issues oder PR-Texten.
