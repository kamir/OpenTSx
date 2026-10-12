# OpenTSx – Marktrecherche: Digitale Zwillinge und die Kopplung von Live- und Simulationsdaten

> Status: **Recherchebericht**, Stand 12.10.2026. Alle Quellen wurden am **12.10.2026** abgerufen, sofern nicht anders
> angegeben. Teil 1 der Business-Unterlagen ([Übersicht](README.md)); Teil 2: [Kunden und Nutzen](02-KUNDEN-UND-NUTZEN.md),
> Teil 3: [Marketing-Kampagne](03-MARKETING-KAMPAGNE.md).
>
> **Was hier bewertet wird:** die Selbstbeschreibung der Anbieter in Primärquellen (Produktseiten, Dokumentation,
> Pressemitteilungen), ergänzt um Standards und Fachliteratur. Es wurden keine Produkte getestet und keine Gespräche mit
> Anbietern geführt. Herstellerangaben sind Herstelleraussagen, keine unabhängigen Belege. Was sich an keiner
> Primärquelle prüfen ließ, ist als **nicht verifiziert** markiert. Ein „nicht gefunden“ ist ein Negativbefund in den
> geprüften Quellen, kein Beweis, dass es etwas nicht gibt.

---

## 1. Kurzfassung

Die Frage des Owners lautet: *„Gibt es bei Anbietern von Digital Twins eine direkte Ankopplung von Live-Daten und
simulierten Daten wie in unserem Modell?“*

**Antwort: Ja. Die Kopplung selbst ist verbreitet und bei mehreren Anbietern reifer als bei OpenTSx. Die Kombination,
die OpenTSx verfolgt, haben wir in den geprüften Quellen dagegen nicht gefunden.**

1. **Live-getriebene Simulation ist Stand der Technik**, aber jeweils in einer Domäne und in einem Herstellerstack.
   Netze: Zustandsschätzung online am Leitsystem (DIgSILENT PowerFactory über OPC, ETAP) und Verteilnetz-Zwillinge
   aus Smart-Meter- und GIS-Daten (Siemens Gridscale X, envelio, VENIOS). Prozessindustrie: AVEVA Process Simulation
   mit Live-Daten aus dem PI System, Cognite Simulator Integration. Wasserstoff: Siemens Hydrogen Performance Suite mit
   physikbasiertem Prozesszwilling und Echtzeitoptimierung, angekündigt am 08.10.2026 für eine 100-MW-Anlage.
   Maschinen und Komponenten: Siemens Simcenter Executable Digital Twin mit Kalman-Filter, Ansys Twin Builder mit
   Kalibrierung an Sensoren. Wind: DNV WindGEMINI (SCADA plus aeroelastische Lebensdauerrechnung, Stand 2017).
2. **What-if und Prognose aus demselben Modell** bieten ebenfalls mehrere Anbieter. ETAP nennt Zustandsschätzung,
   *Predictive Simulation*, What-if und Ereignis-Playback auf einer Plattform. Das ist das nächstliegende Gegenstück zu
   den drei Betriebsarten von OpenTSx, allerdings auf elektrische Netze beschränkt.
3. **Herkunft je Wert:** Qualitätskennzeichen je Messwert sind Standard (OPC UA StatusCodes, IEC-61850-Qualität mit
   `source = process | substituted`). Eine durchgängige Herkunftsklasse je Wert (gemessen, gemeldet, prognostiziert,
   geschätzt, synthetisch), die durch eine Modellkette bis in die Kennzahlen propagiert wird, haben wir in keiner
   geprüften Produktdokumentation gefunden.
4. **Reproduzierbares Replay:** Ereignis-Playback (ETAP), Historien in Historian-Systemen und versionierte Modelle und
   Routinen (Cognite) gibt es. Ein bitgleich wiederholbarer Lauf des ganzen Zwillings, dessen Kennung sich aus Plan,
   Eingängen, Eingriffen und Code-Version ergibt, ist in den geprüften Quellen kein dokumentiertes Produktmerkmal.
5. **Offenheit:** Die kommerziellen Plattformen sind proprietär; einige unterstützen FMI. Offene Bausteine (mosaik,
   HELICS, pandapower, PyPSA, OpenEMS, Eclipse Ditto, Eclipse BaSyx, OpenTwins) decken jeweils Teile ab. In den
   geprüften Quellen verbindet keiner live gekoppelten Betrieb, Herkunft je Wert und Replay auf einem Log.
6. **Klein bis groß:** Die Angebote sind auf eine Größenklasse und Domäne zugeschnitten: Netz, Prozessanlage, Turbine
   oder Haus. Ein gemeinsamer Bausteinkatalog vom Boot bis zum Verbund aus Windpark und Elektrolyseur ist ungewöhnlich.
   Bei OpenTSx ist er aber nur als Proof of Concept belegt.
7. **Über Eigentümergrenzen:** Datenräume (Catena-X mit Verwaltungsschale und Dataspace Connector) und das
   Forschungsprojekt TwinEU (föderiertes Ökosystem von Netzzwillingen, Laufzeit bis 12/2026) adressieren
   Datenhoheit und Austausch. VPP-Plattformen steuern Anlagen vieler Eigentümer, aber zentral. Eine föderierte
   Ausführung eines gemeinsamen Zwillings mit nachgewiesener Ergebnisgleichheit zur engen Kopplung haben wir außerhalb
   der Kosimulationsforschung nicht gefunden. Bei OpenTSx existiert sie nur im Arbeitszweig und auf simulierter
   Wandzeit (§3).
8. **Der Markt konsolidiert sich.** PTC hat ThingWorx und Kepware an TPG verkauft (03/2026), Hexagon hat Octave
   abgespalten (05/2026), Emerson hält AspenTech vollständig (03/2025) und NI mit LabVIEW (10/2023), Bosch will Uptake
   übernehmen (03/2026), E.ON hält envelio und gridX. AWS hat 2025/2026 mehrere IoT-Dienste eingestellt; IoT
   TwinMaker selbst ist in den geprüften AWS-Mitteilungen nicht betroffen.

**Folgerung:** OpenTSx sollte nicht mit „Wir koppeln Live-Daten und Simulation“ werben. Das können andere, oft besser.
Tragfähig ist die Kombination: **dieselben Bausteine in drei Betriebsarten mit austauschbarer Fahrweise, Herkunft je
Wert bis in jede Kennzahl, bitgleiches Replay auf einem offenen Log, föderierbare Ausführung über Eigentümergrenzen,
offen unter Apache 2.0, und das Bewertungsprinzip „koordiniert minus Referenz, gleiche Information“.** Diese
Kombination ist heute ein Proof of Concept auf synthetischen und simulierten Daten, ohne Produktivkunden (§3).

---

## 2. Begriffe und Prüfkriterien

### 2.1 Was „direkte Kopplung“ bedeutet

| Quelle | Kernaussage | Bezug zur Frage |
|--------|-------------|-----------------|
| ISO/IEC 30173:2023 | Ein Digital Twin ist „the digital representation of a target entity with data connections that enable convergence between the physical and digital states at an appropriate rate of synchronization“ ([ISO](https://www.iso.org/standard/81442.html)) | Kopplung gehört zur Definition; die Synchronisationsrate ist Teil der Auslegung |
| Kritzinger et al. 2018 | Unterscheidung *Digital Model*, *Digital Shadow* und *Digital Twin* nach Richtung und Automatisierung des Datenflusses ([DOI 10.1016/j.ifacol.2018.08.474](https://doi.org/10.1016/j.ifacol.2018.08.474)) | Shadow = automatischer Fluss real → digital; Twin = in beide Richtungen |
| National Academies (Bericht 2024) | Die bidirektionale Wechselwirkung zwischen virtuell und physisch ist zentral, automatisiert oder mit dem Menschen in der Schleife; dazu VVUQ (Verifikation, Validierung, Unsicherheitsquantifizierung) ([NASEM](https://www.nationalacademies.org/news/2023/12/new-report-urges-multiagency-action-to-support-potentially-transformative-digital-twins-research)) | stützt die OpenTSx-Regel: Mensch und Optimierer an derselben Schnittstelle, Freigabe vor dem Rückweg |
| AWS „L4 Living Digital Twin“ | Modellparameter werden mit realen Daten laufend aktualisiert, probabilistisch (bayesianisch) kalibriert ([AWS IoT Blog](https://aws.amazon.com/blogs/iot/l4-living-digital-twins/), [AWS HPC Blog](https://aws.amazon.com/blogs/hpc/deploying-level-4-digital-twin-self-calibrating-virtual-sensors-on-aws)) | Kalibrierung als Teil der Kopplung |
| ISO/IEC 30186:2025 | Reifegradmodell und Leitfaden zur Reifegradbewertung für Digital Twins ([BSI-Eintrag](https://knowledge.bsigroup.com/products/bs-iso-iec-30186-digital-twin-maturity-model-and-guidance-for-a-maturity-assessment)) | Inhalt kostenpflichtig, **nicht eingesehen** |

### 2.2 Die acht Prüfkriterien

| Krit. | Frage | „●“ verlangt in der Primärquelle |
|-------|-------|-----------------------------------|
| **a** | Wird die Simulation laufend von Live-Messungen getrieben oder korrigiert (Schatten, Nowcast, Zustandsschätzung, Kalibrierung)? | ausdrückliche Beschreibung des Live-Betriebs eines Modells, nicht nur Visualisierung von Messwerten |
| **b** | Lassen sich What-if-Varianten vom aktuellen (Live-)Zustand aus rechnen? | dokumentierte Szenario- oder What-if-Funktion auf dem betriebenen Modell |
| **c** | Gibt es Prognose oder modellprädiktive Optimierung aus demselben Zustand? | Vorhersage, Fahrplan- oder Optimierungsfunktion auf dem Zwilling |
| **d** | Ist die Herkunft je Wert (gemessen, geschätzt, simuliert) für Nutzer sichtbar? | Kennzeichnung je Wert, über Berechnungen hinweg |
| **e** | Ist ein Lauf reproduzierbar wiederholbar (Replay)? | deterministische Wiederholung oder versionierte, wiederholbare Läufe |
| **f** | Offen und herstellerneutral? | offene Lizenz oder offener Standard; „◐“ bei proprietär mit offenen Schnittstellen |
| **g** | Skaliert dasselbe Konzept von kleinen Systemen (Haus, Boot) bis Windpark plus Elektrolyseur? | belegte Anwendungen über Größenklassen |
| **h** | Funktioniert der Zwilling über Eigentümergrenzen hinweg (föderiert)? | Teile laufen bei verschiedenen Eigentümern, gekoppelt über definierte Schnittstellen |

**Skala:** ● belegt (Primärquelle, meist Herstellerangabe) · ◐ teilweise oder eingeschränkt · ○ in den geprüften
Quellen nicht gefunden · ? nicht verifiziert oder unklar.

---

## 3. Referenzpunkt: OpenTSx heute

Die folgende Tabelle trennt, was auf `master` existiert, was in Arbeitszweigen liegt (Stand 12.10.2026, noch nicht in
`master`) und was geplant ist. Grundlage: [07-DIGITAL-TWIN-BSP](../datamodel-2026/07-DIGITAL-TWIN-BSP.md),
[`opentsx-twin`](../../opentsx-twin/README.md), [TASK-011](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md),
[DevOps: heute vs. geplant](../devops/today-vs-planned.md).

| Krit. | Stand `master` | Arbeitszweige (noch nicht gemergt) | geplant | Bewertung |
|-------|----------------|------------------------------------|---------|-----------|
| a | Betriebsart *Shadow* in Variante T; Residuum Messung − Sensorsicht im Bilanzknoten; Kalibrierung Haus (Abrechnungen) und Boot (Loggerdaten) **auf simulierten Daten**; Modbus-Poller, getestet gegen einen Zählersimulator | DWD-Sammler (MOSMIX, Messungen; `opentsx/2026-dwd`) | Live-Bindung über KafScale-Topics; IEC 60870-5-104; Marktdaten-Sammler (in Arbeit) | ◐ |
| b | `Twin.whatIf`: Referenz- gegen Alternativ-Eingriffslog auf derselben Historie; Hebel für Haus und Boot | – | What-if-Endpunkt der Prediction-API | ● |
| c | `Twin.forecast` ab Snapshot mit austauschbarer Policy (Regel `pressure-aware` mit rollierendem Horizont) | – | Optimierer als Policy-Atom (MPC, P8.4) | ◐ |
| d | Herkunftsleiter `MEASURED > REPORTED > FORECAST > ESTIMATED > SYNTHETIC` je Portwert und Schritt, Taint-Propagation; jede Reihe trägt ihre schlechteste Herkunft, das Studio zeigt sie an | – | Messanteil in Prozent je Kennzahl (Vorschlag D21); Herkunft im produktiven Studio | ● |
| e | bitgleiche Wiederholung über verketteten Zustands-Hash; Lauf-Id aus Plan-, Eingangs- und Eingriffs-Hash plus Version; Episoden-Replay auf KafScale verifiziert (P2) | – | Zwillings-Topics auf KafScale, Zustand in Iceberg | ● |
| f | Apache 2.0; Kafka-Protokoll (KafScale), Apache Iceberg, OpenMetadata; Java und Python | – | – | ● |
| g | drei Szenarien mit denselben Bausteinen: H2-Kette (1-min-Takt), Einfamilienhaus (1 h, 8.760 Schritte), Boot (15 min, 14 Tage) | Inventar und Anlagen-Explorer (`opentsx/2026-explorer`) | WEA-, Wake-, Wetter- und Zähler-Atome im Zwilling; Skalierungstests | ◐ |
| h | Topic-Vertrag (`PortMessage`, `Barrier`) als Code | Variante L (lose, `opentsx/2026-p9-loose`): mit Veraltung 0 bitgleich zu T; gemessene Entwurfsregel (§6.4) – **auf simulierter Wandzeit** | echte Prozessoren über KafScale, Betrieb bei verschiedenen Eigentümern | ◐ |

**Was es heute nicht gibt:** keinen Produktivkunden, keine Läufe auf echten Live-Daten, keine Zertifizierungen, keine
Anbindung an ein Leitsystem, keinen Optimierer jenseits einer Regel-Policy, keine produktive Oberfläche (das Studio ist
eine statische Seite: [Prototyp](../prototypes/smart-topics-studio.html)). Ein Projektumfeld mit Windpark,
Elektrolyseur und Direktvermarktung ist in Vorbereitung; der Datenzugang (Zähler über Modbus, IEC 104 zum
Elektrolyseurbetreiber, Fahrpläne des Vermarkters) wird erst vereinbart
([TASK-011, Backlog](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md)).

---

## 4. Anbieterlandschaft

Die Profile nennen, was die Primärquelle belegt, und wo sie schweigt. Die Bewertung je Kriterium steht in §5.

### 4.1 Industrie- und Energieplattformen

**Siemens.**
- *Simcenter Executable Digital Twin (xDT)*: ein in sich geschlossenes, ausführbares Modell, im Kern ein Reduced
  Order Model; lauffähig in der Cloud, am Rand, auf Prüfständen oder eingebettet. *Smart Virtual Sensing* verschmilzt
  Messung und Simulation mit einem Kalman-Filter. Ein xDT-Gateway verwaltet die Zwillinge auf Industrial Edge
  ([arXiv 2210.17402](https://arxiv.org/pdf/2210.17402),
  [Simcenter-Blog](https://blogs.sw.siemens.com/simcenter/simcenter-3d-smart-virtual-sensing-unveiling-the-magic/),
  [Produktseite](https://www.siemens.com/en-us/products/simcenter/engineering-data-science-ai/executable-digital-twin-gateway/)).
  Starke Live-Kopplung auf Komponentenebene, kein Energieverbund.
- *Hydrogen Performance Suite* (Pressemitteilung vom 08.10.2026): ein „digital cockpit“ mit einem physikbasierten
  Hochgenauigkeitsmodell, Echtzeitoptimierung, Live-Anlagendaten und externen Quellen wie Netzbetreibern und
  Energiemärkten; Einsatz im GalpH2Park in Sines (100 MW, bis 15.000 t H2/a)
  ([Siemens](https://press.siemens.com/global/en/pressrelease/siemens-will-digitalize-100-mw-hydrogen-plant-sines-portugal)).
  Dazu gPROMS-Zwillinge und SIMIT-Inbetriebnahme für Elektrolyseure
  ([Hystar-Webinar](https://resources.sw.siemens.com/en-US/green-hydrogen-digital-twin-for-hystar-electrolyzer-excellence/)).
  Eine Siemens-Kurzschrift fordert ausdrücklich einen Zwilling, der online läuft und ständig mit Daten aktualisiert
  wird ([Brief](https://assets.new.siemens.com/siemens/assets/api/uuid:e2190682-94a3-4933-8f45-8d1faf717832/DI-PA-Simplifying-Green-H2-Production-Brief.pdf)).
  **Der direkteste Wettbewerber im Wasserstoffsegment.**
- *Gridscale X (LV Insights)*: SaaS-Zwilling des Niederspannungsnetzes aus GIS- und Zählerdaten mit laufend
  aktualisierter Sicht auf Topologie und Netzzustand
  ([Produktseite](https://www.siemens.com/en-us/products/gridscale-x/lv-insights-x/)); Einsatz in Triest seit 2025
  ([Pressemitteilung](https://press.siemens.com/global/en/pressrelease/acegasapsamga-leverages-siemens-gridscale-x-build-digital-twin-triestes-energy-grid));
  Gridscale X verbindet Planung und Echtzeitbetrieb in einem Netzmodell
  ([Pressemitteilung](https://press.siemens.com/global/en/pressrelease/siemens-gridscale-x-redefines-system-operations-and-agentic-transmission-planning)).
  Ein Zustandsschätzer wird nicht namentlich beschrieben (**nicht verifiziert**).
- *Digital Twin Composer* (CES, 06.01.2026): photorealistische 3D-Zwillinge auf NVIDIA-Omniverse-Bibliotheken,
  verbunden mit MES-, QMS-, SPS- und IIoT-Daten; Early Access
  ([Pressemitteilung](https://news.siemens.com/en-us/digital-twin-composer-ces-2026)). *Insights Hub* ist das frühere
  MindSphere ([Siemens](https://www.siemens.com/in/en/products/software/mindsphere.html)).

**GE Vernova.** SmartSignal nutzt „AI/ML Digital Twins“ auf Basis von *similarity-based modeling* mit
Sensordaten nahe Echtzeit; APM Performance Intelligence nutzt physikbasierte Zwillinge; GridOS-Zwillinge simulieren
das Netz über verschiedene Zeithorizonte; die FAQ nennt What-if-Szenarien und Verhaltensprognosen
([GE Vernova](https://www.gevernova.com/software/innovation/digital-twin-technology)). Das „Digital Wind Farm“-Konzept
stammt von 2015 ([Pressemitteilung](https://www.ge.com/news/press-releases/ge-launches-next-evolution-wind-energy-making-renewables-more-efficient-economic));
der heutige Stand im Windgeschäft ist **nicht verifiziert**.

**AVEVA und ETAP.**
- *AVEVA Process Simulation*: „Create a high-fidelity process model as a digital twin to monitor, simulate, and
  optimize plant performance in real-time by integrating with live data systems like AVEVA PI System“; Kennzahlen,
  die nicht messbar sind, werden aus dem Modell überwacht; What-if über Betriebszustände, Ausfälle und
  Einsatzstoffe ([AVEVA](https://www.aveva.com/en/solutions/engineering/operator-training/)).
- *ETAP* (Integration mit Schneiders EcoStruxure Power Operation für Bedienertraining,
  [Pressemitteilung](https://www.se.com/ww/en/about-us/newsroom/news/press-releases/schneider-electric-and-etap-announce-new-digital-twin-integration-enabling-operator-training-and-simulations-greatly-reducing-risk-to-operations-6294ae4877bcea5360479e84)):
  „integrated Electrical Digital Twin platform“, „unified engineering and real-time platform“ mit „State Estimation &
  Load Allocation“, „Predictive Simulation“, What-if („Probe alternative actions“) und „Sequence of Events Playback“
  ([ETAP](https://etap.com/products/etap-digital-twin)). **Funktional das nächste Gegenstück zu den drei
  Betriebsarten**, beschränkt auf elektrische Systeme und proprietär.

**Emerson und AspenTech.** Der Ovation Digital Twin ist ein eingebetteter Hochgenauigkeitssimulator parallel zum
Leitsystem, um Fahrweisen vor der Anlage zu testen
([Emerson](https://www.emerson.com/en-us/automation/control-and-safety-systems/distributed-control-systems-dcs/ovation-distributed-control-system/power-plant-simulator)).
Eine laufend mit der Anlage synchronisierte Simulation galt 2022 noch als Pilot und „holy grail“
([Emerson-Blog](https://www.emersonautomationexperts.com/2022/industry/power-generation/ovation-users-conference-reimagining-a-more-sustainable-tomorrow/));
der Stand 2026 ist **nicht verifiziert**. Emerson hält AspenTech seit 12.03.2025 vollständig
([Emerson](https://www.emerson.com/en-us/news/2025/emerson-completes-acquisition-of-remaining-outstanding-shares-of-aspentech));
AspenTech bietet mit OSI monarch SCADA, EMS, ADMS und ein Microgrid-Managementsystem
([AspenTech](https://www.aspentech.com/en/resources/press-releases/aspen-technology-launches-microgrid-management-system)).
Seit 10/2023 gehört auch NI (LabVIEW) zu Emerson
([eeNews](https://www.eenewseurope.com/en/farewell-ni-as-emerson-acquisition-completes/)).

**ABB.** Im Fall Boliden Aitik läuft ein Dymola-Prozessmodell gegen eine Kopie des Leitsystems 800xA über OPC
(rund 35 Signale); die APC-Optimierung liegt getrennt in Expert Optimizer; das Modell wurde nach Sprungversuchen neu
parametriert. ABB nennt die Modelltreue als Hauptschwierigkeit
([ABB-Fallstudie](https://new.abb.com/mining/digital-applications/advanced-process-control/case-study-the-use-of-a-digital-twin-with-advanced-process-control-at-boliden-aitik-mine)).

**Hitachi Energy.** HMAX Energy (03/2026) mit dem HGÜ-Zwilling IdentiQ; Herstellerangabe: bis zu 90 % schnellere
Reaktion auf Störungen ([Hitachi Energy](https://www.hitachienergy.com/us/en/news-and-events/press-releases/2026/03/hitachi-launches-hmax-energy-a-pioneering-ai-powered-service-and-solution-suite-for-critical-energy-infrastructure)).
Details zur Kopplung **nicht verifiziert**.

**Ansys Twin Builder (Synopsys).** Reduced Order Models aus den Ansys-Solvern; „Calibrate and tune your simulation
model parameters by combining physical and virtual sensors“; Bereitstellung über Twin Deployer in Cloud, Edge oder
offline; FMI-Unterstützung ([Synopsys](https://ansys.synopsys.com/products/digital-twin/ansys-twin-builder)).

**Bentley Systems.** iTwin IoT erfasst und validiert Sensordaten in Infrastrukturzwillingen
([Bentley](https://www.bentley.com/software/itwin-iot/)). iTwin.js steht unter MIT-Lizenz, das native Modul ist nicht
quelloffen ([GitHub](https://github.com/iTwin/itwinjs-core)). Simulation auf Live-Daten **nicht verifiziert**.

**Dassault Systèmes.** Die Plattform verspricht, dass Echtzeitdaten aus dem Betrieb in den „virtual twin“ zurückfließen
([3DS-Blog Wind](https://blog.3ds.com/industries/infrastructure-energy-materials/how-virtual-twins-can-make-wind-farms-fit-for-the-future/)).
Das Energiebeispiel Red Eléctrica (12/2024) betrifft Design und Zusammenarbeit von über 1.500 Beteiligten, keinen
belegten Live-Betrieb ([Pressemitteilung](https://www.3ds.com/newsroom/press-releases/electrical-grid-operator-red-electrica-transforms-its-design-process-and-collaboration-dassault-systemes-3dexperience-platform)).

**Octave (früher Hexagon-Software).** Abspaltung am 28.05.2026 abgeschlossen
([Hexagon](https://investors.hexagon.com/share-information/octave-separation)); HxGN SDx heißt jetzt InConcert und
verwaltet Engineering- und Betriebsinformationen ([Octave](https://www.octave.com/products/engineering-information-management/inconcert)).
Kein Simulationsbezug gefunden.

**PTC ThingWorx.** Verkauf von Kepware und ThingWorx an TPG am 16.03.2026 abgeschlossen
([PTC](https://www.ptc.com/en/news/2026/ptc-completes-divestiture-of-kepware-and-thingworx-businesses)).

**IBM Maximo.** Monitor liefert Zustandsüberwachung, Predict Ausfallwahrscheinlichkeiten je Fehlermodus
([IBM-Doku](https://www.ibm.com/docs/en/mhmpmh-and-p-u/cd?topic=overview-maximo-predict)). Keine physikalische
Simulation belegt.

**Cognite Data Fusion.** Die *Simulator Integration* ist allgemein verfügbar: Simulationen laufen automatisch mit
Betriebsdaten nahe Echtzeit, Modelle und Routinen sind versioniert, Ergebnisse werden über die Zeit verfolgt;
Konnektoren für PROSPER, GAP, Petro-SIM, Symmetry und DWSIM
([Cognite Hub](https://hub.cognite.com/simulator-integration-388/simulator-integration-service-now-generally-available-in-cognite-data-fusion-4304),
[Cognite-Doku](https://docs.cognite.com/cdf/integration/guides/simulators)). **Das sauberste Muster „Datenplattform
treibt fremde Simulatoren“**, Schwerpunkt Öl und Gas.

### 4.2 Wind

- **DNV WindGEMINI** (2017): SCADA-Signale als Eingang, Echtzeitanalyse und Prognose, aeroelastische Modellierung der
  Ermüdung zur Restlebensdauer ([DNV](https://www.dnv.com/news/2017/new-windgemini-advances-wind-turbine-operations-104699/)).
  Aktueller Produktstatus **nicht verifiziert**.
- **Akselos**: Strukturzwillinge auf Basis reduzierter FE-Modelle (RB-FEA), aktualisiert mit Inspektions- und
  Sensordaten ([Akselos](https://akselos.com/digital-twins-a-comprehensive-guide/)); Offshore-Schwerpunkt.
- **Power Factors**: Unity REMS und Unity APM (10/2024) vereinen Drive und Greenbyte; Herstellerangabe über 300 GW
  ([Power Factors](https://www.powerfactors.com/news/power-factors-launches-next-generation-ai-powered-asset-performance-management-application-on-unity-platform)).
  Asset-Performance-Analytik, kein Simulationszwilling gefunden. Bazefield: **nicht geprüft**.
- **Envision EnOS Wind**: IoT-Betriebssystem für Windparks mit Datenerfassung, Verlustanalyse, ML-Zustandsbewertung,
  Leistungsprognose und AGC/AVC; Zwilling auf Turbinenebene ([Envision](https://www.envision-group.com/en/windsoftware.html)).
- **Siemens Gamesa mit NVIDIA** (2022): Wake-Surrogat, Herstellerangabe bis zu 4.000-mal schneller
  ([NVIDIA](https://blogs.nvidia.com/blog/siemens-gamesa-wind-farms-digital-twins)). Produktivstand **nicht
  verifiziert**.
- **Sentient Science DigitalClone**: physikbasierte und ML-Prognostik für Getriebe; „DigitalClone Live“ aktualisiert
  Vorhersagen mit Live-Daten ([Windpower Engineering](https://www.windpowerengineering.com/extending-life-wind-turbines-prognostics-data-modeling/)).
  Ältere Quellen, Stand 2026 **nicht verifiziert**.
- **Vestas, Nordex**: In den geprüften Quellen keine öffentlich dokumentierten Zwillingsprodukte für Betreiber
  (**nicht verifiziert**).

### 4.3 Wasserstoff und Elektrolyse

- **Siemens**: Hydrogen Performance Suite und gPROMS-Zwillinge (§4.1), der am weitesten dokumentierte Ansatz.
- **thyssenkrupp nucera**: nennt „Digital Twins“ neben *nucera evaluator* und *nucera load balancer* sowie APC und
  *pulsewatch* im Service ([thyssenkrupp nucera](https://www.thyssenkrupp-nucera.com/technologies/alkaline-water-electrolysis)).
  Funktionsumfang **nicht verifiziert**.
- **Sunfire, ITM Power, Plug Power**: keine veröffentlichten Zwillingsprodukte gefunden.
- **Forschung**: Sicherheitszwilling mit CFD-Ausbreitungsrechnung für die NREL-ARIES-Anlage
  ([OSTI](https://www.osti.gov/biblio/3011897)), Plattformarchitektur für einen industriellen Elektrolyseur
  ([ACM Energy Informatics Review](https://energy.acm.org/eir/poster-abstract-a-digital-twin-platform-applied-to-hydrogen-electrolyzers/)).

In den geprüften Quellen beschreibt kein Anbieter die Kette **Abnehmer → Leitungsdruck → Elektrolyseur → Fahrplan →
Vermarktung** ausdrücklich als ein Modell über die Grenzen der Anlage und ihrer Eigentümer hinweg. Die Siemens-Suite
bindet Netzbetreiber und Märkte als externe Quellen ein; wie weit sie Abnehmer und Vermarktungsfahrplan als gekoppelte
Bausteine rechnet, ist **nicht verifiziert**. Genau diese Kette ist Szenario S1 von OpenTSx.

### 4.4 Netze und Stadtwerke

- **DIgSILENT PowerFactory**: Zustandsschätzung für symmetrische und unsymmetrische Netze mit Fehlerdatenerkennung
  und Beobachtbarkeitsanalyse ([DIgSILENT](https://www.digsilent.de/en/state-estimation.html)); die OPC-Schnittstelle
  wird für die Echtzeitintegration mit SCADA genutzt, von Online-Zustandsschätzung bis zum Trainingssimulator
  ([DIgSILENT IT-Projekte](https://www.digsilent.de/en/it-projects.html)).
- **envelio Intelligent Grid Platform** (E.ON-Mehrheit seit 12/2021,
  [Startbase](https://www.startbase.de/news/exit-von-envelio/)): Netzzwilling, der vorhandene Daten verarbeitet und
  korrigiert; Netzplanung, Netzbetrieb mit Live-Daten und §-14a-Steuerung auf einer Datenbasis
  ([ZfK](https://www.zfk.de/digitalisierung/smart-city-energy/digitaler-zwilling-fuer-meister-und-monteure-envelio-startet-mobile-netzservices),
  [Presseportal](https://www.presseportal.de/pm/108695/6073125)).
- **VENIOS Energy Platform**: Cloud-Zwilling, der per Zustandsschätzung trotz weniger Messwerte ein hoch aufgelöstes
  Netzabbild erzeugt; Referenz Stadtwerke Schwäbisch Hall
  ([ZfK](https://www.zfk.de/digitalisierung/smart-city-energy/stadtwerke-schwaebisch-hall-machen-ihr-stromverteilnetz-transparent)).
- **PSIcontrol**: Leitsystem für Überwachung, Steuerung und Simulation; prädiktive Netzsimulation bei N-ERGIE Netz
  ([PSI](https://www.psi.de/en/solutions/products/psicontrol),
  [Industry Press](https://industry-press.com/psi-software-se-erneuert-netzleitsystem-fuer-n-ergie-netz-gmbh/)).
- **DNV Synergi Electric**: Planungs- und Analysewerkzeug mit Anbindung von GIS, SCADA und AMI; kein Live-Zwilling
  belegt ([DNV](https://www.dnv.com/software/energy/synergi-electric)).
- **Tapestry (X, Alphabet)**: Planungswerkzeug für langfristige, stündlich aufgelöste Netzsimulation
  ([X](https://X.Company/projects/tapestry/)); bei Vector (Neuseeland) im täglichen Betrieb
  ([Latitude Media](https://www.latitudemedia.com/news/exclusive-googles-grid-moonshot-is-now-tackling-distribution/)).

Netzzwillinge rechnen Lastfluss und Netzzustand. OpenTSx rechnet dagegen Anlagen, Verträge und Fahrweisen eines
Verbunds. Beides ergänzt sich (Teil 2, §4.4).

### 4.5 VPP, Flexibilität und Energiegemeinschaften

- **Next Kraftwerke** (seit 2021 Teil von Shell): SaaS-Plattform NEMOCS; Prognosen aus Anlagen-, Wetter-, Preis- und
  Netzdaten ([Next Kraftwerke](https://www.next-kraftwerke.com/energy-blog/spotlight-nemocs-setting-up-a-vpp-with-ecotricity)).
- **gridX** (E.ON, vollständig seit 12/2022): gridBox mit lokaler Echtzeitoptimierung, Cloud-Plattform XENON
  ([gridX](https://www.gridx.ai/press-releases/e-on-acquires-majority-stake-in-leading-smart-grid-company-gridx)).
- **Entrix**: Optimierung und Vermarktung von Batteriespeichern; 3 GW / 8,5 GWh unter Vertrag (03/2026)
  ([TaiyangNews](https://taiyangnews.info/technology/optimization-trading-platform-for-grid-scale-batteries-from-entrix)).
- **Kraken Flex** (Octopus Energy Group): ML- und KI-Steuerung dezentraler Anlagen; Partnerschaft mit Schneider
  Electric (06/2026); über 2 GW Flexibilität aus Haushalten ([Kraken](https://www.kraken.tech/press-releases/schneider-electric-and-kraken-partner-to-boost-grid-flexibility)).

Diese Plattformen prognostizieren und optimieren auf Live-Daten (Kriterium c), simulieren aber keine Anlagenphysik
und arbeiten zentral über die Anlagen vieler Eigentümer, nicht föderiert.

### 4.6 Hyperscaler und Datenplattformen

- **Azure Digital Twins**: weiterhin allgemein verfügbar; Zwillingsgraph mit DTDL-Modellen; Abrechnung nach
  Operationen, Nachrichten und Query Units ([Microsoft Learn](https://learn.microsoft.com/en-us/azure/digital-twins/overview),
  [Preise](https://azure.microsoft.com/en-us/pricing/details/digital-twins/)). Davon getrennt: **Fabric digital twin
  builder** (Preview, ontologiebasierte Modellierung in Real-Time Intelligence,
  [Microsoft Learn](https://learn.microsoft.com/en-us/fabric/real-time-intelligence/digital-twin-builder/overview));
  laut Drittquelle ohne Simulationsmaschine ([Drittquelle](https://fgarofalo56.github.io/Suppercharge_Microsoft_Fabric/features/digital-twin-builder/),
  **nicht verifiziert**).
- **AWS IoT TwinMaker**: Wissensgraph, 3D-Szenen und Grafana-Plugin; nutzungsbasierte Preise; laut Dokumentation
  nicht für den Betrieb kritischer Systeme gedacht ([AWS-Doku](https://docs.aws.amazon.com/iot-twinmaker/latest/guide/what-is-twinmaker.html),
  [Preise](https://aws.amazon.com/iot-twinmaker/pricing/)). **Status:** In den AWS-Verfügbarkeitsmitteilungen vom
  Oktober 2025 ([AWS](https://aws.amazon.com/about-aws/whats-new/2025/10/aws-service-availability/)) sowie März und
  Juni 2026 (Zusammenfassung: [Classmethod](https://dev.classmethod.jp/en/articles/aws-service-lifecycle-summary-20260630/))
  ist TwinMaker **nicht** aufgeführt; die Preisseite enthält keinen Hinweis auf ein Supportende. Eine Drittquelle
  behauptet eine Einstellung ([innFactory](https://innfactory.de/en/cloud/aws/products/iot-twinmaker/)) – **nicht
  verifiziert**. Im Umfeld eingestellt oder in Wartung: IoT Analytics (Ende 15.12.2025), IoT Events (Ende 20.05.2026),
  SiteWise Monitor (Wartung seit 07.11.2025), FleetWise (Wartung seit 30.04.2026), Greengrass v1 (Ende 07.10.2026)
  ([Übersicht](https://thingshost.de/en/blog/aws-iot-service-retirements-2026)). Für Kunden ist das ein Argument für
  offene Formate.
- **NVIDIA Omniverse und PhysicsNeMo**: PhysicsNeMo (früher Modulus) ist ein quelloffenes PyTorch-Framework für
  Physik-ML unter Apache 2.0 ([GitHub](https://github.com/NVIDIA/physicsnemo)); trainierte Surrogate rechnen neues
  Verhalten in Echtzeit ([NVIDIA](https://blogs.nvidia.com/blog/siemens-gamesa-wind-farms-digital-twins)).
- **Google**: Tapestry (§4.4). **Databricks**: **nicht geprüft**.

### 4.7 Open Source und Standards

- **Eclipse Ditto** (EPL-2.0): Zwilling als Zustandsspiegel von Geräten (gemeldet, gewünscht), Anbindung über Kafka,
  MQTT, AMQP; keine Simulation ([GitHub](https://github.com/eclipse-ditto/ditto), [Projekt](https://eclipse.dev/ditto/)).
- **Verwaltungsschale (AAS) und Eclipse BaSyx** (BaSyx Java SDK unter MIT, [GitHub](https://github.com/eclipse-basyx/basyx-java-server-sdk)):
  Das IDTA-Teilmodell 02005 *Provision of Simulation Models* beschreibt Simulationsmodelle wie FMUs an der
  Verwaltungsschale des Assets ([IDTA 02005](https://industrialdigitaltwin.org/wp-content/uploads/2023/01/IDTA-02005-1-0_Submodel_ProvisionOfSimulationModels.pdf)).
  Es beschreibt Modelle, führt sie nicht aus.
- **Catena-X**: Zwillinge als Verwaltungsschalen in einem Digital Twin Registry, souveräner Austausch über Eclipse
  Dataspace Connector ([CX-0002](https://catena-x.net/fileadmin/user_upload/Standard-Bibliothek/Update_Januar_2024/CX-0002-DigitalTwinsInCatenaX-v2.1.0.pdf)).
  Das Vorbild für Datenhoheit über Unternehmensgrenzen.
- **FIWARE**: Kontextbroker nach NGSI-LD und Smart Data Models für Zwillingsklassen, auch für Energie
  ([arXiv 2309.12358](https://arxiv.org/pdf/2309.12358)). Lizenz von Orion-LD **nicht verifiziert**.
- **mosaik 3** (OFFIS, LGPL-2.1): Kosimulation für Smart Grids, verbindet zeitgetaktete und ereignisdiskrete Simulation;
  FMI und OPC UA ([arXiv 2410.16937](https://arxiv.org/html/2410.16937v1), [GitHub](https://github.com/OFFIS-mosaik)).
- **HELICS** (BSD-3-Clause): Federates fordern Zeit an, der Core gewährt sie erst, wenn kein Federate in die
  Vergangenheit rechnen müsste; Zielgröße über 100.000 Federates
  ([Doku](https://docs.helics.org/en/main/user-guide/fundamental_topics/timing_configuration.html),
  [GitHub](https://github.com/GMLC-TDC/HELICS)).
- **FMI 3.0**: Co-Simulation, Model Exchange und Scheduled Execution, Clocks und Event Mode
  ([FMI-FAQ](https://fmi-standard.org/faq/), [Modelica 2021](https://ecp.ep.liu.se/index.php/modelica/article/view/179)).
  SSP: **nicht geprüft**. OpenModelica: **nicht geprüft**.
- **pandapower** (BSD-3-Clause): WLS-Zustandsschätzung mit Chi-Quadrat- und Residuentest; die Doku warnt, die
  Fehlerdatenentfernung sei „not very robust“ ([Doku](https://pandapower.readthedocs.io/en/latest/estimation.html)).
- **PyPSA** (MIT): Optimierung und Simulation von Einsatz und Ausbau ([GitHub](https://github.com/PyPSA/PyPSA)).
  Planung, kein Live-Betrieb.
- **OpenEMS**: Edge und Backend unter EPL-2.0, Oberfläche unter AGPL-3.0; simulierte Entwicklungsumgebung
  ([GitHub](https://github.com/OpenEMS/openems)); Referenzimplementierung mit Fraunhofer ISE und FENECON (06/2026,
  [OpenMUC](https://openmuc.org/news/jeebus-and-openems-open-source-solution/)).
- **Home Assistant mit EMHASS** (EMHASS unter MIT): lineare Optimierung mit PV- und Lastprognosen, MPC-Betrieb
  ([Doku](https://emhass.readthedocs.io/en/latest/), [GitHub](https://github.com/davidusb-geek/emhass)).
- **Victron VRM, Dynamic ESS**: stündlicher Lade- und Entladeplan aus Preisen, Solarprognose und Verbrauchsmustern,
  offenbar in der VRM-Cloud gerechnet; ohne Verbindung nach 12 h Rückfall auf normales ESS
  ([Victron](https://www.victronenergy.com/live/drafts:dynamic_ess)). Relevant für den Boot-Fall.
- **OpenTwins** (Universität Málaga, Apache 2.0): offene Plattform für zusammengesetzte Zwillinge, FMI- und
  ML-Integration; laut Projekt nicht für den Produktivbetrieb empfohlen ([GitHub](https://github.com/ertis-research/OpenTwins)).

### 4.8 Haus und Endkunden

- **ENTER**: 3D-Modell („digitaler Zwilling“) des Hauses aus Vor-Ort-Daten, Simulation von Sanierungsszenarien,
  individueller Sanierungsfahrplan ([ENTER](https://www.enter.de/blog/enter-vs-lokale-energieberater)). Kein laufender
  Abgleich mit Verbrauchsdaten belegt.
- **Rahmen in Deutschland**: Smart-Meter-Quote 5,9 % aller Messlokationen im ersten Halbjahr 2026
  ([ZfK](https://www.zfk.de/digitalisierung/smart-city-energy/smart-meter-fortschritt-im-ersten-halbjahr-weiter-viele-nachzuegler));
  §-14a-Steuerung seit 01.01.2024 ([Finanztip](https://finanztip.de/stromtarife/steuerbare-verbrauchseinrichtungen-14a-enwg/));
  Gebäudemodernisierungsgesetz seit 29.07.2026 in Kraft (65-%-Regel gestrichen,
  [Haufe](https://www.haufe.de/immobilien/wirtschaft-politik/gebaeudemodernisierungsgesetz-gmg_84342_683694.html)).
  Details in Teil 2.
- **Heizungs-Apps (ViCare, tado und ähnliche) und Energieberater-Software**: **nicht geprüft**.

Kalibrierte Gebäudemodelle sind in der Bauphysik verbreitet; die Gütemaße CV(RMSE) und NMBE, die der Haus-Zwilling
nutzt ([08-HAUS-ZWILLING](../datamodel-2026/08-HAUS-ZWILLING.md)), folgen dieser Praxis. Ein Produkt, das ein
kalibriertes Hausmodell aus Abrechnungen mit einem Identifizierbarkeitsbericht verbindet, haben wir nicht gefunden.

### 4.9 Marktbewegungen 2025/2026

| Datum | Ereignis | Quelle |
|-------|----------|--------|
| 12.03.2025 | Emerson übernimmt die restlichen AspenTech-Anteile | [Emerson](https://www.emerson.com/en-us/news/2025/emerson-completes-acquisition-of-remaining-outstanding-shares-of-aspentech) |
| 07.11.2025 | AWS IoT SiteWise Monitor in Wartung; weitere IoT-Dienste folgen 2026 | [AWS](https://aws.amazon.com/about-aws/whats-new/2025/10/aws-service-availability/) |
| 06.01.2026 | Siemens Digital Twin Composer (Early Access) | [Siemens](https://news.siemens.com/en-us/digital-twin-composer-ces-2026) |
| 16.03.2026 | PTC verkauft ThingWorx und Kepware an TPG | [PTC](https://www.ptc.com/en/news/2026/ptc-completes-divestiture-of-kepware-and-thingworx-businesses) |
| ca. 20.03.2026 | Bosch kündigt die Übernahme von Uptake an (Flotten-Instandhaltung) | [Bosch](https://www.bosch-presse.de/pressportal/us/en/press-release-30080.html) |
| 03/2026 | Hitachi Energy startet HMAX Energy | [Hitachi Energy](https://www.hitachienergy.com/us/en/news-and-events/press-releases/2026/03/hitachi-launches-hmax-energy-a-pioneering-ai-powered-service-and-solution-suite-for-critical-energy-infrastructure) |
| 28.05.2026 | Octave von Hexagon abgespalten | [Hexagon](https://investors.hexagon.com/share-information/octave-separation) |
| 06/2026 | Schneider Electric und Kraken: Partnerschaft für Netzflexibilität | [Kraken](https://www.kraken.tech/press-releases/schneider-electric-and-kraken-partner-to-boost-grid-flexibility) |
| 08.10.2026 | Siemens Hydrogen Performance Suite für den GalpH2Park | [Siemens](https://press.siemens.com/global/en/pressrelease/siemens-will-digitalize-100-mw-hydrogen-plant-sines-portugal) |

Große Anbieter bündeln Zwilling, Leitsystem und Anlage. Energiekonzerne kaufen Netz- und Flexibilitätssoftware.
Hyperscaler bereinigen ihre IoT-Portfolios. Daraus lässt sich eine Nachfrage nach **herstellerneutralen, offenen**
Lösungen ableiten, die Kunden selbst betreiben können. Das ist eine Hypothese; in den Quellen ist diese Nachfrage
nicht gemessen.

---

## 5. Vergleichsmatrix

Kriterien a–h wie in §2.2; Skala ● ◐ ○ ?. Lizenz und Preis nach Primärquelle; „Anfrage“ heißt, die Quelle nennt
keine Preise. Die Bewertung beruht auf den verlinkten Quellen vom 12.10.2026. Reine Simulations- und
Planungswerkzeuge erhalten bei **b** ein „◐“: Sie rechnen Szenarien, aber nicht ausdrücklich vom laufenden
Live-Zustand aus.

### 5.1 Kommerzielle Industrie- und Energieangebote

| Anbieter / Produkt | a | b | c | d | e | f | g | h | Lizenz / Preis | Zielmarkt | Beleg |
|--------------------|---|---|---|---|---|---|---|---|----------------|-----------|-------|
| Siemens Simcenter xDT | ● | ? | ? | ? | ? | ○ | ◐ | ○ | kommerziell, Anfrage | Maschinen, Komponenten, OEM | [arXiv](https://arxiv.org/pdf/2210.17402), [Siemens](https://www.siemens.com/en-us/products/simcenter/engineering-data-science-ai/executable-digital-twin-gateway/) |
| Siemens Hydrogen Performance Suite (gPROMS) | ● | ? | ● | ? | ? | ○ | ○ | ◐ | kommerziell, Anfrage | Elektrolyseanlagen | [Siemens](https://press.siemens.com/global/en/pressrelease/siemens-will-digitalize-100-mw-hydrogen-plant-sines-portugal) |
| Siemens Gridscale X LV Insights | ● | ? | ? | ? | ? | ○ | ○ | ○ | SaaS, Anfrage | Verteilnetzbetreiber | [Siemens](https://www.siemens.com/en-us/products/gridscale-x/lv-insights-x/) |
| GE Vernova SmartSignal / APM / GridOS | ● | ● | ◐ | ? | ? | ○ | ○ | ○ | kommerziell, Anfrage | Erzeuger, Netzbetreiber | [GE Vernova](https://www.gevernova.com/software/innovation/digital-twin-technology) |
| AVEVA Process Simulation mit PI System | ● | ● | ◐ | ? | ? | ○ | ○ | ○ | kommerziell, Anfrage | Prozessindustrie | [AVEVA](https://www.aveva.com/en/solutions/engineering/operator-training/) |
| ETAP Digital Twin / Real-Time | ● | ● | ● | ? | ◐ | ○ | ◐ | ○ | kommerziell, Anfrage | elektrische Netze, Industrie, Rechenzentren | [ETAP](https://etap.com/products/etap-digital-twin) |
| Emerson Ovation Digital Twin | ◐ | ● | ? | ? | ? | ○ | ○ | ○ | kommerziell, mit Leitsystem | Kraftwerke | [Emerson](https://www.emerson.com/en-us/automation/control-and-safety-systems/distributed-control-systems-dcs/ovation-distributed-control-system/power-plant-simulator) |
| ABB (Fall Boliden: Dymola + 800xA + APC) | ◐ | ● | ◐ | ? | ? | ◐ | ○ | ○ | Projekt | Bergbau, Prozess | [ABB](https://new.abb.com/mining/digital-applications/advanced-process-control/case-study-the-use-of-a-digital-twin-with-advanced-process-control-at-boliden-aitik-mine) |
| Ansys Twin Builder (Synopsys) | ● | ◐ | ◐ | ? | ? | ◐ | ◐ | ○ | kommerziell, Anfrage | Maschinen, Komponenten | [Synopsys](https://ansys.synopsys.com/products/digital-twin/ansys-twin-builder) |
| Cognite Data Fusion, Simulator Integration | ● | ◐ | ? | ? | ◐ | ◐ | ○ | ○ | SaaS, Anfrage | Öl und Gas, Prozess | [Cognite](https://docs.cognite.com/cdf/integration/guides/simulators) |
| DIgSILENT PowerFactory | ● | ◐ | ◐ | ? | ? | ○ | ○ | ○ | kommerziell, Anfrage | Netzbetreiber, Planer | [DIgSILENT](https://www.digsilent.de/en/state-estimation.html) |
| envelio Intelligent Grid Platform | ● | ◐ | ? | ? | ? | ○ | ○ | ○ | SaaS, Anfrage | Verteilnetzbetreiber | [ZfK](https://www.zfk.de/digitalisierung/smart-city-energy/digitaler-zwilling-fuer-meister-und-monteure-envelio-startet-mobile-netzservices) |
| VENIOS Energy Platform | ● | ◐ | ? | ? | ? | ○ | ○ | ○ | SaaS, Anfrage | Verteilnetzbetreiber | [ZfK](https://www.zfk.de/digitalisierung/smart-city-energy/stadtwerke-schwaebisch-hall-machen-ihr-stromverteilnetz-transparent) |
| PSIcontrol | ◐ | ◐ | ◐ | ? | ? | ○ | ○ | ○ | kommerziell, Anfrage | Netzleitsysteme | [PSI](https://www.psi.de/en/solutions/products/psicontrol) |
| DNV WindGEMINI (Stand 2017) | ● | ? | ● | ? | ? | ○ | ○ | ○ | kommerziell, Anfrage | Wind | [DNV](https://www.dnv.com/news/2017/new-windgemini-advances-wind-turbine-operations-104699/) |
| Akselos | ◐ | ◐ | ◐ | ? | ? | ○ | ○ | ○ | kommerziell, Anfrage | Offshore-Strukturen | [Akselos](https://akselos.com/digital-twins-a-comprehensive-guide/) |
| Power Factors Unity | ◐ | ○ | ◐ | ? | ? | ○ | ○ | ○ | SaaS, Anfrage | Wind-, PV-, Speicherportfolios | [Power Factors](https://www.powerfactors.com/news/power-factors-launches-next-generation-ai-powered-asset-performance-management-application-on-unity-platform) |
| Envision EnOS Wind | ◐ | ? | ◐ | ? | ? | ○ | ○ | ○ | kommerziell, Anfrage | Windparks | [Envision](https://www.envision-group.com/en/windsoftware.html) |
| Bentley iTwin IoT | ◐ | ? | ? | ? | ? | ◐ | ○ | ○ | kommerziell; iTwin.js MIT | Infrastruktur | [Bentley](https://www.bentley.com/software/itwin-iot/) |
| Dassault 3DEXPERIENCE | ? | ◐ | ? | ? | ? | ○ | ◐ | ◐ | kommerziell, Anfrage | Engineering, Design | [3DS](https://www.3ds.com/newsroom/press-releases/electrical-grid-operator-red-electrica-transforms-its-design-process-and-collaboration-dassault-systemes-3dexperience-platform) |
| IBM Maximo Monitor / Predict | ◐ | ○ | ◐ | ? | ? | ○ | ○ | ○ | kommerziell, Anfrage | Instandhaltung | [IBM](https://www.ibm.com/docs/en/mhmpmh-and-p-u/cd?topic=overview-maximo-predict) |
| VPP- und Flexplattformen (NEMOCS, gridX, Entrix, Kraken Flex) | ◐ | ○ | ● | ? | ? | ○ | ◐ | ◐ | SaaS, Erlösbeteiligung (Modell je Anbieter nicht verifiziert) | Aggregation, Speicher, Haushalte | [Next Kraftwerke](https://www.next-kraftwerke.com/energy-blog/spotlight-nemocs-setting-up-a-vpp-with-ecotricity), [gridX](https://www.gridx.ai/press-releases/e-on-acquires-majority-stake-in-leading-smart-grid-company-gridx) |

### 5.2 Hyperscaler und Plattformen

| Anbieter / Produkt | a | b | c | d | e | f | g | h | Lizenz / Preis | Zielmarkt | Beleg |
|--------------------|---|---|---|---|---|---|---|---|----------------|-----------|-------|
| Azure Digital Twins | ◐ | ○ | ○ | ? | ? | ◐ | ◐ | ○ | Verbrauch (Operationen, Nachrichten, Query Units) | Gebäude, IoT | [Microsoft](https://learn.microsoft.com/en-us/azure/digital-twins/overview) |
| Microsoft Fabric digital twin builder (Preview) | ◐ | ? | ? | ? | ? | ○ | ◐ | ○ | Fabric-Kapazität | Industrie, Daten | [Microsoft](https://learn.microsoft.com/en-us/fabric/real-time-intelligence/digital-twin-builder/overview) |
| AWS IoT TwinMaker | ◐ | ○ | ○ | ? | ? | ○ | ◐ | ○ | nutzungsbasiert (Entitäten, API-Aufrufe, Abfragen) | Industrie, Gebäude | [AWS](https://aws.amazon.com/iot-twinmaker/pricing/) |
| NVIDIA Omniverse + PhysicsNeMo | ◐ | ◐ | ◐ | ? | ? | ◐ | ◐ | ○ | PhysicsNeMo Apache 2.0; Omniverse kommerziell (nicht verifiziert) | Physik-ML, 3D | [GitHub](https://github.com/NVIDIA/physicsnemo) |
| Siemens Digital Twin Composer | ◐ | ? | ? | ? | ? | ○ | ? | ○ | Early Access | Fabriken | [Siemens](https://news.siemens.com/en-us/digital-twin-composer-ces-2026) |
| Tapestry (X, Alphabet) | ◐ | ◐ | ◐ | ? | ? | ○ | ○ | ○ | Partnerschaften | Übertragungs-, Verteilnetze | [X](https://X.Company/projects/tapestry/) |
| PTC ThingWorx (seit 03/2026 TPG) | ◐ | ? | ? | ? | ? | ○ | ◐ | ○ | kommerziell | IIoT | [PTC](https://www.ptc.com/en/news/2026/ptc-completes-divestiture-of-kepware-and-thingworx-businesses) |

### 5.3 Open Source, Standards und Forschung

| Projekt | a | b | c | d | e | f | g | h | Lizenz | Zielmarkt | Beleg |
|---------|---|---|---|---|---|---|---|---|--------|-----------|-------|
| Eclipse Ditto | ○ | ○ | ○ | ? | ? | ● | ◐ | ? | EPL-2.0 | Gerätezustände (IoT) | [GitHub](https://github.com/eclipse-ditto/ditto) |
| AAS / Eclipse BaSyx + IDTA 02005 | ○ | ○ | ○ | ? | ○ | ● | ◐ | ◐ | MIT (BaSyx Java SDK); IDTA-Spezifikation | Industrie 4.0 | [IDTA](https://industrialdigitaltwin.org/wp-content/uploads/2023/01/IDTA-02005-1-0_Submodel_ProvisionOfSimulationModels.pdf) |
| Catena-X (AAS + EDC) | ○ | ○ | ○ | ? | ? | ◐ | ○ | ● | Standards offen; Betrieb über Dienstleister | Automobil-Lieferkette | [Catena-X](https://catena-x.net/fileadmin/user_upload/Standard-Bibliothek/Update_Januar_2024/CX-0002-DigitalTwinsInCatenaX-v2.1.0.pdf) |
| FIWARE (NGSI-LD) | ○ | ○ | ○ | ? | ? | ● | ◐ | ◐ | quelloffen (Lizenz Orion-LD nicht verifiziert) | Smart City, Energie | [arXiv](https://arxiv.org/pdf/2309.12358) |
| mosaik 3 | ◐ | ◐ | ◐ | ○ | ◐ | ● | ◐ | ◐ | LGPL-2.1 | Smart-Grid-Forschung | [arXiv](https://arxiv.org/html/2410.16937v1) |
| HELICS | ◐ | ◐ | ◐ | ○ | ◐ | ● | ◐ | ◐ | BSD-3-Clause | Netz-Kosimulation | [HELICS](https://docs.helics.org/en/main/user-guide/co-simulation_overview.html) |
| FMI 3.0 (Standard) | ◐ | ◐ | ◐ | ○ | ◐ | ● | ◐ | ○ | offener Standard | Kosimulation | [FMI](https://fmi-standard.org/faq/) |
| pandapower | ◐ | ◐ | ◐ | ? | ? | ● | ○ | ○ | BSD-3-Clause | Netzberechnung | [Doku](https://pandapower.readthedocs.io/en/latest/estimation.html) |
| PyPSA | ○ | ◐ | ◐ | ○ | ◐ | ● | ◐ | ○ | MIT | Energiesystemplanung | [GitHub](https://github.com/PyPSA/PyPSA) |
| OpenEMS | ◐ | ? | ◐ | ? | ? | ● | ◐ | ◐ | EPL-2.0 / AGPL-3.0 (UI) | Speicher, Gebäude, Gewerbe | [GitHub](https://github.com/OpenEMS/openems) |
| Home Assistant + EMHASS | ◐ | ◐ | ● | ○ | ○ | ● | ○ | ○ | EMHASS MIT | Haushalte | [EMHASS](https://emhass.readthedocs.io/en/latest/) |
| Victron VRM Dynamic ESS | ◐ | ○ | ● | ○ | ○ | ◐ | ○ | ○ | Teil des Victron-Ökosystems (Konditionen nicht verifiziert) | Heim, Off-Grid, Boot | [Victron](https://www.victronenergy.com/live/drafts:dynamic_ess) |
| OpenTwins (Univ. Málaga) | ◐ | ◐ | ◐ | ? | ? | ● | ? | ○ | Apache 2.0 | Forschung | [GitHub](https://github.com/ertis-research/OpenTwins) |
| TwinEU (Horizon Europe, bis 12/2026) | ? | ? | ? | ? | ? | ◐ | ○ | ● | Forschungsprojekt | Übertragungs-, Verteilnetzbetreiber | [TU Dortmund](https://ie3.etit.tu-dortmund.de/research/third-party-projects/distribution-grid-planning-operation/twineu/) |

### 5.4 Haus und Referenz

| Angebot | a | b | c | d | e | f | g | h | Lizenz / Preis | Zielmarkt | Beleg |
|---------|---|---|---|---|---|---|---|---|----------------|-----------|-------|
| ENTER (Haus-Zwilling, Sanierungsfahrplan) | ○ | ◐ | ○ | ? | ? | ○ | ○ | ○ | Beratungsleistung (BAFA-gefördert) | Hauseigentümer | [ENTER](https://www.enter.de/blog/enter-vs-lokale-energieberater) |
| **OpenTSx** (Stand 12.10.2026, Proof of Concept) | ◐ | ● | ◐ | ● | ● | ● | ◐ | ◐ | Apache 2.0 | noch kein Produktivkunde | [Repo](../../README.md), §3 |

**Lesehilfe.** Kein Anbieter ist in allen Spalten belegt. Die Spalte **d** (Herkunft je Wert) ist bei allen
kommerziellen Angeboten „?“: Die Primärquellen äußern sich nicht dazu. Daraus folgt nicht, dass es diese Funktion
nicht gibt. Bevor OpenTSx damit wirbt, sollten die drei nächsten Wettbewerber (ETAP, AVEVA, Cognite) in Demos gezielt
danach gefragt werden (§8).

---

## 6. Forschung und Standards

### 6.1 Definitionen und Reifegrade

- **ISO/IEC 30173:2023** definiert die Kopplung über die „appropriate rate of synchronization“
  ([ISO](https://www.iso.org/standard/81442.html)); **ISO/IEC 30186:2025** liefert ein Reifegradmodell
  ([BSI](https://knowledge.bsigroup.com/products/bs-iso-iec-30186-digital-twin-maturity-model-and-guidance-for-a-maturity-assessment),
  Inhalt nicht eingesehen); **ISO 23247** (Teile 1–4, 2021) beschreibt ein Rahmenwerk für die Fertigung
  ([ISO](https://committee.iso.org/standard/75066.html)).
- Das **Digital Twin Capabilities Periodic Table** des Digital Twin Consortium (v1.1, 04/2024) ist ein
  technologieneutrales Anforderungsraster für Fähigkeiten von Zwillingen
  ([DTC User Guide](https://www.digitaltwinconsortium.org/wp-content/uploads/sites/3/2022/06/Digital-Twin-Capabilities-Periodic-Table-User-Guide.pdf)).
  Es eignet sich, um OpenTSx-Fähigkeiten in Ausschreibungen gegenüber Anbietern abzubilden.
- **Kritzinger et al. (2018)**: Viele als „Twin“ bezeichnete Systeme sind nach dieser Klassifikation nur Modelle oder
  Schatten ([DOI](https://doi.org/10.1016/j.ifacol.2018.08.474)). OpenTSx' Betriebsart Shadow ist ein Schatten; zum
  Twin wird es erst mit dem freigegebenen Rückweg ([09-STUDIO-LABVIEW-STEUERPULT](../datamodel-2026/09-STUDIO-LABVIEW-STEUERPULT.md)).

### 6.2 Datenassimilation, Zustandsschätzung, Schattenbetrieb

- **Kalman-Filter für virtuelle Sensoren** sind im Produkt angekommen (Siemens Smart Virtual Sensing, §4.1).
- **Zustandsschätzung** ist in Netzwerkzeugen Standard (PowerFactory, ETAP, VENIOS; pandapower als offene Bibliothek).
- **Bayesianische Kalibrierung** im Betrieb beschreibt AWS als Stufe 4 („Living Twin“, §2.1).
- **Ensemble-Kalman-Filter** werden für Zwillinge in Kerntechnik und Thermohydraulik erforscht
  ([ScienceDirect](https://www.sciencedirect.com/science/article/abs/pii/S0149197026000703)). Eine aktuelle Übersicht
  speziell zu Energiesystemen haben wir nicht gefunden.

**Konsequenz für OpenTSx:** Die Kalibrierung über kleinste Quadrate (Haus, Boot) und das Residuum Messung −
Sensorsicht sind solide, aber einfache Formen der Datenassimilation. Eine sequentielle Zustandsschätzung (Kalman) im
Shadow-Betrieb fehlt und ist ein naheliegender Ausbau.

### 6.3 Kosimulation und Synchronisation: eng oder lose

- **Jacobi- und Gauss-Seidel-Kopplung**: Jacobi rechnet parallel, ist aber in der Regel ungenauer
  ([Tutorial, arXiv 1809.08463](https://arxiv.org/pdf/1809.08463); Übersicht [arXiv 1702.00686](https://arxiv.org/pdf/1702.00686)).
  Schwache Kopplung führt zwangsläufig Verzögerungen an den Schnittstellen ein
  ([Modelica 2014](https://2014.international.conference.modelica.org/proceedings/html/submissions/ECP14096213_Viel.pdf)).
  Das geschichtete BSP von OpenTSx ist eine Gauss-Seidel-artige Ordnung innerhalb des Superschritts;
  Rückkopplungen kreuzen die Schrittgrenze ([07 §4.1](../datamodel-2026/07-DIGITAL-TWIN-BSP.md)).
- **Konservative Zeitsynchronisation** wie in HELICS (Zeit wird erst gewährt, wenn niemand in die Vergangenheit
  rechnen müsste) ähnelt der Variante L mit Veraltung 0.
- **Bounded Staleness (SSP)** stammt aus dem verteilten maschinellen Lernen
  ([Ho et al., NeurIPS 2013](https://proceedings.nips.cc/paper_files/paper/2013/file/b7bb35b9c6ca2aee2df08cf09d7016c2-Paper.pdf)).
- **FMI 3.0** bringt Clocks und Scheduled Execution für ereignis- und echtzeitnahe Kopplung (§4.7).

### 6.4 Einordnung der eigenen Messung

Im Arbeitszweig `opentsx/2026-p9-loose` (noch nicht in `master`) misst OpenTSx Variante L gegen Variante T auf
simulierter Wandzeit:

- L mit Veraltung 0 ist für H2-Kette, Haus und Boot **bitgleich** mit T.
- Der Durchsatz folgt der Formel „Ticks je Schritt = max über alle Schleifen Σ(1 + lag) / Σ(d + s)“.
- Die Entwurfsregel lautet: *Rückkopplungsschleifen gehören in einen Prozessor; zwischen Prozessoren reicht BSP.*
  Damit liefert L das Ergebnis von T bei voller Fließband-Leistung (bei `lag = 2` Faktor 5,9 bei der H2-Kette und
  6,0 beim Haus gegenüber BSP ohne Kolokation).

**Ehrliche Einordnung:** Die Formel entspricht der aus der Datenflussanalyse bekannten **Iterationsschranke**: das
Maximum über alle Schleifen aus Rechenzeit geteilt durch die Zahl der Verzögerungen
([York University, Vorlesung](https://www.eecs.yorku.ca/course_archive/2011-12/W/4210/L4_it_bound.pdf)). Das stützt
die Messung. Neu ist sie als Theorie nicht. Der Beitrag liegt in der Übertragung auf Zwillinge über einem Log:
Konformität bis aufs Bit, Herkunftspropagation und Betrieb über Eigentümergrenzen. Das Paper „Digital Twins on Logs“
sollte die Iterationsschranke ausdrücklich zitieren.

### 6.5 Föderation über Eigentümergrenzen

- **TwinEU** (Horizon Europe, 01/2024–12/2026, Koordination Fraunhofer, 77 Partner aus 15 Ländern): „a federated
  ecosystem of DT solutions“. Jeder Betreiber entscheidet über seine Implementierung; Standardschnittstellen und offene
  APIs sichern die Interoperabilität. Der deutsche Demonstrator mit Amprion und Westnetz zielt auf den
  Datenaustausch zwischen Übertragungs- und Verteilnetzbetreibern
  ([TU Dortmund](https://ie3.etit.tu-dortmund.de/research/third-party-projects/distribution-grid-planning-operation/twineu/)).
  **Wichtigster Anknüpfungspunkt** für die Variante L.
- **Catena-X** zeigt, wie Datenhoheit über Unternehmensgrenzen technisch und vertraglich funktioniert (AAS, Registry,
  Dataspace Connector, §4.7).

### 6.6 Herkunft und Provenienz

- **OPC UA StatusCodes** kennen u. a. `Uncertain_SubstituteValue` (manuell überschriebener Wert) und
  `Good_LocalOverride` ([OPC Foundation](https://reference.opcfoundation.org/Core/Part8/v104/docs/?r=A.7&t=1)).
- **IEC-61850-Qualität** kennt `source = process | substituted`, `test` und `operatorBlocked` (hier über das
  CIM-Modell `Quality61850` belegt, Normtext nicht eingesehen,
  [CIM](https://modellen.netbeheernederland.nl/tc57cim/cim17/schema/class/Quality61850.html)).
- **W3C PROV** modelliert Entitäten, Aktivitäten und Agenten ([arXiv 2507.01075](https://arxiv.org/pdf/2507.01075)).
  Eine Arbeit, die PROV auf die Unterscheidung „gemessen vs. simuliert“ in Zwillingen anwendet, haben wir nicht
  gefunden.

Die Herkunftsleiter von OpenTSx ist damit anschlussfähig: `MEASURED` und `REPORTED` lassen sich aus OPC-UA- und
IEC-61850-Qualitäten ableiten. `FORECAST`, `ESTIMATED` und `SYNTHETIC` sowie die Propagation durch die Modellkette
gehen darüber hinaus.

---

## 7. Ehrliche Einordnung

### 7.1 Wo Anbieter stärker sind

| Feld | Stärke der Anbieter | OpenTSx heute |
|------|---------------------|---------------|
| **Reife und Betriebserfahrung** | installierte Basis, z. B. PSIcontrol mit Systemen über 20.000 Fernwirkstationen ([PSI](https://www.psi.de/en/solutions/products/psicontrol)); envelio mit Kunden von 7.000 bis 4,8 Mio. Entnahmestellen ([ZfK](https://www.zfk.de/digitalisierung/smart-city-energy/digitaler-zwilling-fuer-meister-und-monteure-envelio-startet-mobile-netzservices)) | kein Produktivkunde |
| **Physiktiefe** | Prozessmodelle (gPROMS, AVEVA), reduzierte FE- und Strukturmodelle (Ansys, Akselos), aeroelastische Lebensdauer (DNV), Netzberechnung mit Zustandsschätzung (PowerFactory) | einfache Atome: Kennlinien, Ein-Zonen-Haus (1R1C), ideales Gas in der H2-Leitung |
| **Sicherheit und Nachweise** | Kunden in KRITIS-nahen Bereichen erwarten Nachweise, etwa nach IEC 62443 oder ISO/IEC 27001 (welche Anbieter welche besitzen, nicht einzeln verifiziert) | keine Zertifizierung, kein Sicherheitskonzept für den Rückweg jenseits des Freigabeprinzips |
| **Integration in Leitsysteme** | DCS- und SCADA-Hersteller liefern Zwilling und Leitsystem aus einer Hand (Emerson, ABB, Siemens, PSI) | Modbus-Leser; IEC 104 und OPC UA geplant |
| **Support und Ökosystem** | Service, SLA, Schulungen, Partnernetze, Marktplätze | kleines Projektteam, Community im Aufbau |
| **Oberfläche** | 3D und photorealistische Szenen (Omniverse, iTwin), ausgereifte Bedienoberflächen | statischer Studio-Prototyp |
| **Finanzkraft und Bündelung** | Siemens im H2-Segment; E.ON mit envelio und gridX | Open-Source-Projekt |

### 7.2 Wo OpenTSx sich unterscheidet

1. **Ein Bausteinsatz, drei Bindungen.** Dieselben Atome laufen als Schatten, als What-if und als Prognose. Die
   Fahrweise (Fahrplan, Mensch, Optimierer) ist über dieselbe Schnittstelle austauschbar. Ein Vorschlag lässt sich
   vor der Freigabe im Zwilling durchspielen. ETAP kommt dem am nächsten; ob dort Fahrweisen als austauschbare
   Policy modelliert werden, ist nicht verifiziert (§8).
2. **Herkunft je Wert bis in jede Kennzahl.** Fünf Klassen mit Taint-Propagation; jede Reihe trägt ihre Herkunft je
   Schritt, daraus lässt sich der Messanteil jeder Kennzahl ausweisen (als Prozentanzeige vorgeschlagen, D21). In den
   geprüften Quellen ohne Gegenstück.
3. **Bitgleiches Replay auf einem offenen Log.** Lauf-Id aus Schaltplan, Eingängen, Eingriffen und Code-Version;
   Episoden mit Prüfsummen auf KafScale. Damit lassen sich Streitfälle und Audits nachrechnen.
4. **Föderierbar mit Entwurfsregel.** Atome können bei verschiedenen Eigentümern laufen; die gemessene Regel sagt,
   was eng bleiben muss. Belegt bisher nur im Arbeitszweig auf simulierter Wandzeit.
5. **Bewertungsprinzip als Produktregel.** Koordinationspotenzial = koordinierte Alternative − Referenzbetrieb, mit
   denselben Randbedingungen und derselben Information. Modelliertes Potenzial und nachgewiesener Nutzen bleiben
   getrennt, auch in der Oberfläche.
6. **Offen und klein anfangen.** Apache 2.0, Kafka-Protokoll, Iceberg, OpenMetadata; Stufe A (Daten und Potenzial)
   braucht keine Simulation; dieselbe Methode vom Haus bis zum Verbund.
7. **Zeitreihen-Fundament.** Episoden, Streaming-Statistik, Datenqualität, Mustersuche und ein Windpark-Simulator mit
   Ground Truth sind vorhanden und bieten einen Prüfstand, den Zwillingsanbieter selten mitliefern.

### 7.3 Was kein Alleinstellungsmerkmal ist

- Live-Daten und Simulation koppeln (§1, Punkt 1).
- What-if-Szenarien und Prognosen.
- Zustandsschätzung und Kalibrierung.
- „Offen“ allein: offene Kosimulations- und IoT-Bausteine gibt es reichlich.

### 7.4 Konsequenzen

- **Positionierung** über die Kombination und den Entscheidungsnutzen, nicht über die Kopplung (Teil 3).
- **Ergänzen statt ersetzen:** Netzzwillinge, APM-Systeme, Elektrolyseur-Suiten und Leitsysteme liefern Ports an den
  Verbundzwilling. OpenTSx konkurriert nicht mit ihrem Kern.
- **Lücken schließen, die Kunden zuerst prüfen:** echte Daten (Stufe A), Sicherheitskonzept für den Rückweg,
  IEC 104 und OPC UA, Kalman-basierter Schatten, Skalierungstest.

---

## 8. Nicht verifiziert und offene Punkte

- Herkunft je Wert bei ETAP, AVEVA, Cognite und Siemens (Kriterium d): in Demos gezielt erfragen.
- Ob ETAPs *Predictive Simulation* vom aktuellen Live-Zustand aus verzweigt und ob Fahrweisen austauschbar sind.
- Aktueller Stand von DNV WindGEMINI, GE Digital Wind Farm, Sentient Science und Siemens Gamesa mit NVIDIA.
- Zwillingsangebote von Vestas, Nordex und Bazefield.
- Inhalt von ISO/IEC 30186:2025 (kostenpflichtig), SSP-Standard, OpenModelica, Databricks.
- Zustandsschätzer in Siemens Gridscale X (nicht namentlich beschrieben).
- Status AWS IoT TwinMaker: keine offizielle Ankündigung gefunden, Drittquelle widerspricht.
- Fabric digital twin builder ohne Simulationsmaschine (nur Drittquelle).
- Lizenz von FIWARE Orion-LD; Konditionen von Omniverse und Victron Dynamic ESS.
- Abschluss der Uptake-Übernahme durch Bosch (angekündigt, Abschluss nicht bestätigt).

---

## 9. Quellen (Abruf 12.10.2026)

**Normen, Definitionen, Forschung**
- ISO/IEC 30173:2023 – https://www.iso.org/standard/81442.html
- ISO/IEC 30186:2025 – https://knowledge.bsigroup.com/products/bs-iso-iec-30186-digital-twin-maturity-model-and-guidance-for-a-maturity-assessment
- ISO 23247 – https://committee.iso.org/standard/75066.html
- DTC Capabilities Periodic Table – https://www.digitaltwinconsortium.org/wp-content/uploads/sites/3/2022/06/Digital-Twin-Capabilities-Periodic-Table-User-Guide.pdf
- Kritzinger et al. 2018 – https://doi.org/10.1016/j.ifacol.2018.08.474
- National Academies – https://www.nationalacademies.org/news/2023/12/new-report-urges-multiagency-action-to-support-potentially-transformative-digital-twins-research
- AWS L4 Living Twin – https://aws.amazon.com/blogs/iot/l4-living-digital-twins/ · https://aws.amazon.com/blogs/hpc/deploying-level-4-digital-twin-self-calibrating-virtual-sensors-on-aws
- Executable Digital Twin – https://arxiv.org/pdf/2210.17402
- Kosimulation – https://arxiv.org/pdf/1702.00686 · https://arxiv.org/pdf/1809.08463 · https://2014.international.conference.modelica.org/proceedings/html/submissions/ECP14096213_Viel.pdf
- SSP – https://proceedings.nips.cc/paper_files/paper/2013/file/b7bb35b9c6ca2aee2df08cf09d7016c2-Paper.pdf
- Iterationsschranke – https://www.eecs.yorku.ca/course_archive/2011-12/W/4210/L4_it_bound.pdf
- FMI 3.0 – https://fmi-standard.org/faq/ · https://ecp.ep.liu.se/index.php/modelica/article/view/179
- EnKF-Zwilling – https://www.sciencedirect.com/science/article/abs/pii/S0149197026000703
- OPC UA – https://reference.opcfoundation.org/Core/Part8/v104/docs/?r=A.7&t=1
- IEC 61850 über CIM – https://modellen.netbeheernederland.nl/tc57cim/cim17/schema/class/Quality61850.html
- W3C PROV – https://arxiv.org/pdf/2507.01075
- TwinEU – https://ie3.etit.tu-dortmund.de/research/third-party-projects/distribution-grid-planning-operation/twineu/
- Catena-X – https://catena-x.net/fileadmin/user_upload/Standard-Bibliothek/Update_Januar_2024/CX-0002-DigitalTwinsInCatenaX-v2.1.0.pdf

**Anbieter** – alle Links in §4 und §5; zusätzlich:
- Siemens Simcenter Smart Virtual Sensing – https://blogs.sw.siemens.com/simcenter/simcenter-3d-smart-virtual-sensing-unveiling-the-magic/
- Siemens Gridscale X (Triest, Planung + Betrieb) – https://press.siemens.com/global/en/pressrelease/acegasapsamga-leverages-siemens-gridscale-x-build-digital-twin-triestes-energy-grid · https://press.siemens.com/global/en/pressrelease/siemens-gridscale-x-redefines-system-operations-and-agentic-transmission-planning
- Siemens H2 – https://resources.sw.siemens.com/en-US/green-hydrogen-digital-twin-for-hystar-electrolyzer-excellence/ · https://assets.new.siemens.com/siemens/assets/api/uuid:e2190682-94a3-4933-8f45-8d1faf717832/DI-PA-Simplifying-Green-H2-Production-Brief.pdf
- Cognite – https://hub.cognite.com/simulator-integration-388/simulator-integration-service-now-generally-available-in-cognite-data-fusion-4304
- AWS-Lebenszyklus – https://aws.amazon.com/about-aws/whats-new/2025/10/aws-service-availability/ · https://dev.classmethod.jp/en/articles/aws-service-lifecycle-summary-20260630/ · https://thingshost.de/en/blog/aws-iot-service-retirements-2026 · https://docs.aws.amazon.com/iot-twinmaker/latest/guide/what-is-twinmaker.html
