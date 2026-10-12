# OpenTSx – Kunden und Nutzen

> Status: **Analyse und Hypothesen**, Stand 12.10.2026; Quellen abgerufen am 12.10.2026. Teil 2 der
> Business-Unterlagen ([Übersicht](README.md)); Teil 1: [Marktrecherche](01-MARKTRECHERCHE-DIGITAL-TWINS.md),
> Teil 3: [Marketing-Kampagne](03-MARKETING-KAMPAGNE.md).
>
> **Lesehilfe.** Marktzahlen stammen aus den genannten Quellen; rechnerische Ableitungen sind als solche markiert.
> Aussagen zu Schmerzpunkten, Zahlungsbereitschaft und Einkaufsentscheidern sind **Hypothesen**. Sie beruhen auf der
> Recherche und den Projektunterlagen, nicht auf Kundeninterviews, und müssen in Gesprächen geprüft werden.
> Zahlungsbereitschaft wird bewusst in Aufwand (Beratertage) statt in Euro angegeben; Tagessätze schwanken stark.

---

## 1. Kurzfassung

**Wer sind die Kunden?** Organisationen, die ein **gekoppeltes Energiesystem** betreiben oder bewerten, dessen Teile
verschiedenen Parteien gehören, und die Entscheidungen begründen müssen. Drei Segmente haben Vorrang (§5):

1. **Betreiber gekoppelter Verbünde: Windpark, Elektrolyseur, Speicher, Abnehmer und Vermarktung.** Nutzen: Das
   Koordinationspotenzial wird beziffert (koordinierte Fahrweise minus Referenzbetrieb, gleiche Information), bevor
   investiert oder umgestellt wird. Kausalketten wie „Abnehmer schließt Schieber → Druck → Abregelung →
   Fahrplanabweichung“ werden vorher sichtbar. Ein bitgleiches Replay dient allen Parteien als gemeinsame Faktenbasis
   bei Abweichungen.
2. **Stadtwerke (mit Netz, Vertrieb, Erzeugung, Wärme) und über sie Energiegemeinschaften.** Nutzen: Ein offener,
   selbst betriebener Verbundzwilling über Sparten und Verträge. Er ergänzt vorhandene Netzzwillinge um Fahrweisen,
   Tarife und Energy Sharing (§ 42c EnWG seit 01.06.2026) und macht bei jeder Zahl sichtbar, ob sie gemessen oder
   geschätzt ist.
3. **Forschung, Labore und Ingenieurbüros.** Nutzen: reproduzierbare Experimente und Studien (gleicher Lauf, gleicher
   Hash), Datenhaltung mit Herkunft für Prüfstände (LabVIEW-Brücke als Konzept) und eine offene Referenzimplementierung
   für die Frage „eng oder lose gekoppelt“. Dieses Segment liefert Glaubwürdigkeit, Mitstreiter und Fördermittel.

**Haus und Boot** sind **Erzählanker** mit Reichweite, keine Umsatzsegmente der ersten Phase. Energieberater und
Hersteller kleiner Energiesysteme sind spätere Kanäle.

**Was OpenTSx heute belegen kann:** drei lauffähige Szenarien auf synthetischen bzw. simulierten Daten, verifiziertes
Episoden-Replay auf KafScale und die Konformität der losen Kopplung im Arbeitszweig. **Was fehlt:** echte Daten, ein
Pilotkunde, ein Sicherheitskonzept für den Rückweg, Schnittstellen wie IEC 104 und OPC UA (§6).

---

## 2. Nutzenbausteine

Jeder Segmentnutzen in §4 setzt sich aus diesen Bausteinen zusammen. Die Spalte „Stand“ verweist auf
[Teil 1, §3](01-MARKTRECHERCHE-DIGITAL-TWINS.md).

| Nr. | Nutzenbaustein | Was der Kunde bekommt | technische Grundlage | Stand |
|-----|----------------|-----------------------|----------------------|-------|
| N1 | **Koordinationspotenzial beziffern** | Was getrennte Betriebsführung kostet und abgestimmtes Handeln bringt: Deckungsbeitrag, vermeidbare Abregelung, H2-Bedarfsdeckung, Speicherbeanspruchung, Nutzen und Mehrkosten je Beteiligtem | Bewertungsprinzip „koordinierte Alternative − Referenzbetrieb, gleiche Randbedingungen und Information“; perfekte Voraussicht nur als markierte Obergrenze ([TASK-011, P8](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md)) | Prinzip und Demos vorhanden; Potenzialbericht mit echten Daten offen |
| N2 | **Erst rechnen, dann handeln** | What-if einer Entscheidung vor dem Eingriff; Prognose ab dem aktuellen Zustand mit austauschbarer Fahrweise; Frühwarnung („noch etwa 1 h bis zur Abregelung“) | drei Betriebsarten auf denselben Atomen ([07 §5](../datamodel-2026/07-DIGITAL-TWIN-BSP.md)) | Variante T umgesetzt |
| N3 | **Vertrauen durch Herkunft** | Jede Zahl zeigt, ob sie gemessen, gemeldet, prognostiziert, geschätzt oder synthetisch ist; Potenzialberichte nennen ihren Messanteil | Herkunftsleiter mit Taint-Propagation ([07 §4.4](../datamodel-2026/07-DIGITAL-TWIN-BSP.md)) | umgesetzt; Prozentanzeige je Kennzahl vorgeschlagen |
| N4 | **Nachvollziehbar bis aufs Bit** | Gemeinsame Faktenbasis bei Streit über Abweichungen; Audit von Entscheidungen; reproduzierbare Studien | deterministische Läufe mit Lauf-Id und Zustands-Hash; Episoden-Replay mit Prüfsummen auf KafScale | umgesetzt (Zwilling lokal, Episoden auf KafScale) |
| N5 | **Datenhoheit und Föderation** | Jede Partei betreibt ihre Atome selbst und teilt nur Ports; Betrieb on-premises möglich | Variante L über Smart Topics; Apache 2.0; Kafka-Protokoll, Iceberg | im Arbeitszweig, simuliert |
| N6 | **Niedriger Einstieg** | Stufe A (Daten und Potenzial) ohne Simulation; jede Stufe endet an einer Wertschranke | Stufen A–D ([07 §6](../datamodel-2026/07-DIGITAL-TWIN-BSP.md)) | Methode beschrieben; Modbus-Leser vorhanden |
| N7 | **Sicherheit bleibt lokal** | Vorschläge gehen nur nach Freigabe an Anlagen; lokale Schutzlogik hat immer Vorrang | Freigabe-Workflow, Kommando- und Quittungsvertrag ([09](../datamodel-2026/09-STUDIO-LABVIEW-STEUERPULT.md)) | Konzept |

**Was wir nicht versprechen:** Einsparungen ohne Messung, Echtzeitregelung, Ersatz für Leitsysteme, Schutztechnik oder
normgerechte Nachweise (etwa Energieausweise), Zertifizierungen.

---

## 3. Marktgrößen (belegte Zahlen)

| Kennzahl | Wert | Stand | Quelle |
|----------|------|-------|--------|
| Windenergieanlagen an Land | **29.248** Anlagen, **69.952 MW** | 30.06.2026 | [FA Wind und Solar im Auftrag von BWE und VDMA Power Systems](https://www.fachagentur-wind-solar.de/fileadmin/Veroeffentlichungen/Wind/Daten/FA_Wind_Solar_Status_des_Windenergieausbaus_an_Land_Halbjahr_2026.pdf) |
| davon seit 01.01.2026 ohne EEG-Vergütungsanspruch | **12,1 GW** (Zusammenfassung; Kapitel 3.2 nennt 12,2 GW) | 30.06.2026 | ebd. |
| Durchschnittsalter des Bestands; Repowering im 1. Halbjahr | 15,7 Jahre; 135 Anlagen mit 770 MW (ein Drittel der neuen Leistung) | 30.06.2026 | ebd. |
| Windenergieanlagen auf See | **1.764** Anlagen, ca. **10,8 GW** | 30.06.2026 | [Stiftung Offshore-Windenergie](https://www.offshore-stiftung.de/de/status-quo-offshore-windenergie.php) |
| Elektrolyseleistung in Betrieb | **181 MWel**; weitere 1,3 GWel mit Investitionsentscheidung oder im Bau; Ziel 10 GW bis 2030 wird laut EWI voraussichtlich verfehlt (8,7 GW selbst bei Umsetzung aller angekündigten Projekte) | 01/2026 | [EWI](https://www.ewi.uni-koeln.de/en/news/ewi-analysis-electrolysis-ramp-up-in-germany-is-proceeding-slowly/) |
| Elektrolyse, Folgestand | 180,5 MWel in 44 Projekten; 27 Projekte mit 1.271 MWel in Investitionsentscheidung oder Bau | 31.03.2026 | [EWI/BDEW Wasserstoffmonitor](https://www.bdew.de/media/documents/260528_EWI_BDEW_Wasserstoffmonitor_Bericht_final.pdf) |
| PPA-Abschlüsse in Deutschland | 27 Abschlüsse mit 1,3 GW (2024: 51 mit 2,2 GW) | 2025 | [dena PPA-Marktanalyse 2025 (Daten Pexapark)](https://www.dena.de/fileadmin/dena/Publikationen/PDFs/2026/PPA_Marktanalyse_2025.pdf) |
| Mitgliedsunternehmen des VKU | rund **1.600** (Energie, Wasser/Abwasser, Abfall, Telekommunikation) | 2025 | [VKU Zahlen, Daten, Fakten 2025](https://www.vku.de/fileadmin/user_upload/Verbandsseite/Publikationen/2025/VKU_ZDF_2025_DE.pdf) |
| Stromverteilnetzbetreiber | 851 | 2025 | [Statista-Grafik nach BNetzA/BKartA](https://emvg.energie-und-management.de/filestore/newsimgorg/Statista-Grafiken/Diverse_2026/statistic_id152937_anzahl-der-stromnetzbetreiber-in-deutschland-bis-2025.orig.pdf) (Sekundärquelle; im Monitoringbericht **nicht verifiziert**) |
| Intelligente Messsysteme | rund 3,2 Mio. bei 54,1 Mio. Messstellen (**5,9 %**); Pflichteinbaufälle zu 26,1 % ausgestattet | 30.06.2026 | [ZfK](https://www.zfk.de/digitalisierung/smart-city-energy/smart-meter-fortschritt-im-ersten-halbjahr-weiter-viele-nachzuegler), [BNetzA](https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/NetzzugangMesswesen/Mess-undZaehlwesen/iMSys/artikel.html) |
| Energiegenossenschaften | rund 1.020 mit 230.000 Mitgliedern (Sekundärquelle); DGRV selbst: rund 1.000 organisierte Genossenschaften, über 220.000 Menschen | Ende 2025 / 07/2026 | [Cleanthinking](https://www.cleanthinking.de/?p=1073948), [DGRV-Stellungnahme EEG 2027](https://www.dgrv.de/wp-content/uploads/2026/07/Stellungnahme_Referentenentwurf_EEG_2027_DGRV.pdf) |
| Stationäre Batteriespeicher | 2.222.454 im Marktstammdatenregister, rund 80 % davon PV-Heimspeicher | Ende 2025 | [pv magazine](https://www.pv-magazine.de/2026/01/06/weniger-neue-batteriespeicher-2025-installiert-aber-mit-mehr-kapazitaet/) |
| Wohnungen | 44,0 Mio. | 31.12.2025 | [Destatis](https://www.destatis.de/DE/Presse/Pressemitteilungen/2026/07/PD26_250_31231.html) |
| Anteil der Wohnungen mit Ölheizung | **17,2 %** (Gas 56,1 %, Fernwärme 15,5 %, Wärmepumpen/Solar/Geothermie 4,5 %) | 2025 | [BDEW, Stand 06/2026](https://www.bdew.de/media/documents/Wohnungsbestand_Beheizungsstruktur_Entw_ab_1998_online_o_dw_jaehrlich_CMi_19062026.pdf) |
| *rechnerisch:* Wohnungen mit Ölheizung | *≈ 7,6 Mio.* (17,2 % × 44,0 Mio.; Näherung und Obergrenze, da sich der BDEW-Anteil auf Wohnungen mit Heizung bezieht) | – | eigene Rechnung |
| Ölheizkessel; Gasfeuerungen; Einzelraumfeuerstätten | **4,7 Mio.**; 14,8 Mio.; 11,7 Mio. | 2025 | [Schornsteinfegerhandwerk (ZIV), 22.05.2026](https://www.schornsteinfeger.de/sites/default/files/pressemeldungen/downloads/05%202026%20Pressemeldung%20Erhebungen%202025.pdf) |
| Energieeffizienz-Expertinnen und -Experten | 22.800 gelistet, rund 20.600 online auffindbar | 12/2025 | [dena](https://www.dena.de/fileadmin/dena/Publikationen/PDFs/2026/FS_EEE_Liste.pdf) |
| Wohnmobile | rund 907.900 | 01.01.2024 | [Statista nach KBA](https://de.statista.com/statistik/daten/studie/152231/umfrage/anzahl-der-wohnmobile-in-deutschland) |
| Liegeplätze in Sportboothäfen | 206.279 | Erhebung 2015 (veraltet) | [Umweltbundesamt, Texte 68/2015](https://www.umweltbundesamt.de/publikationen/sicherung-der-verlaesslichkeit-der-antifouling) |

**Regulatorische Auslöser**

| Auslöser | Inhalt | Quelle |
|----------|--------|--------|
| Gebäudemodernisierungsgesetz (GModG) | vom Bundestag am 10.07.2026 beschlossen, weitgehend seit 29.07.2026 in Kraft; 65-%-Regel und Beratungspflicht beim Einbau von Öl- und Gasheizungen gestrichen; „Biotreppe“ für neue fossile Heizungen (10 % ab 2029, 15 % ab 2030, 30 % ab 2035, 60 % ab 2040); Brennstoffe ab 2045 klimaneutral | [Haufe, 30.07.2026](https://www.haufe.de/immobilien/wirtschaft-politik/gebaeudemodernisierungsgesetz-gmg_84342_683694.html) |
| § 14a EnWG | steuerbare Verbrauchseinrichtungen über 4,2 kW (Wärmepumpen, Wallboxen, Speicher, Kälte) seit 01.01.2024; Netzentgeltmodule 1–3 | [Finanztip](https://finanztip.de/stromtarife/steuerbare-verbrauchseinrichtungen-14a-enwg/) (Festlegungen BK6-22-300 und BK8-22/010-A im Original **nicht eingesehen**) |
| Energy Sharing, § 42c EnWG | seit 01.06.2026 gemeinsame Nutzung erneuerbaren Stroms im Bilanzierungsgebiet eines Verteilnetzes; ab 01.06.2028 auch im angrenzenden Gebiet; Smart Meter nötig, volle Netzentgelte | [Görg](https://www.goerg.de/de/aktuelles/veroeffentlichungen/13-04-2026/bald-strom-mit-nachbarn-teilen-ein-ausblick-auf-das-ab-1-juni-2026-geltende-energy-sharing-gemaess-ss-42c-enwg-chancen-und-grenzen), [Gleiss Lutz](https://www.gleisslutz.com/en/know-how/energy-sharing-joint-use-renewable-electricity-under-new-section-42c-energy-industry-act) |
| Kommunale Wärmeplanung, § 4 WPG | Wärmepläne für Gemeinden über 100.000 Einwohner bis 30.06.2026, übrige bis 30.06.2028 | [§ 4 WPG](https://www.buzer.de/4_WPG.htm) |
| RFNBO-Regeln (Delegierte Verordnung (EU) 2023/1184) | zeitliche Korrelation von Strom und Wasserstoff monatlich bis Ende 2029, stündlich ab 2030 | [CMS](https://cms.law/de/deu/legal-updates/erneuerbare-energien-eu-regeln-fuer-erneuerbaren-wasserstoff-update-1), [Europex](https://www.europex.org/?p=26232) (Verordnungstext **nicht eingesehen**) |
| BAFA-Energieberatung für Wohngebäude | 50 % Zuschuss, höchstens 650 € (Ein- und Zweifamilienhaus); iSFP-Bonus für Anträge ab 21.07.2026 erst ab 30.000 € förderfähiger Investition | [Schwäbisch Hall](https://www.schwaebisch-hall.de/foerderungen/bafa-foerderung.html), [Cleanthinking](https://www.cleanthinking.de/?p=1073862) (Sekundärquellen; BAFA-Original **nicht eingesehen**) |

**Bewusst nicht beziffert:** Zahl der Direktvermarkter, Industriestandorte mit Eigenversorgung, Ingenieurbüros im
Energiebereich, Prüfstandslabore mit LabVIEW, Segelboote. Dafür gab es keine belastbare Quelle.

---

## 4. Segmente

Jedes Segment folgt demselben Raster: Personas, Schmerzpunkte und Aufgaben (*Jobs to be done*), Nutzen durch OpenTSx,
Belege heute und benötigt, Einwände mit Antworten, Zahlungsbereitschaft (Hypothese), Einkaufsentscheider und
Einstiegsangebot.

### 4.1 Windpark-Betreiber und Asset-Manager

- **Personas:** technische Betriebsführung, kaufmännisches Asset-Management, Geschäftsführung eines Bürgerwindparks.
- **Schmerzpunkte:** Abregelung und Betriebsvorgaben (Netzrestriktionen, Fledermausabschaltung, Ackerarbeit) ohne
  bezifferte Kosten; 12,1 GW ohne EEG-Vergütung seit Jahresbeginn verlangen Entscheidungen über Weiterbetrieb, PPA oder
  Repowering; fallende PPA-Preise und negative Preise; Daten verteilt auf Herstellerportale und
  Direktvermarkter.
- **Aufgaben:** „Zeig mir, was getrennter Betrieb kostet und was Direktbelieferung, Speicher oder Elektrolyse
  bringen würden, bevor ich investiere.“ „Belege mir, wer eine Abweichung verursacht hat.“
- **Nutzen:** N1 und N6 zuerst: ein Potenzialbericht der Stufe A (Überlappung von Erzeugung und Verbrauch,
  Direktbelieferungsanteil gegen Einspeisung, Größenordnung in €/a, Herkunftsanteile), dann What-if (N2) und Replay
  (N4). Dazu der IEC-61400-25-2-Katalog, Leistungskurvenanalyse und Mustersuche aus dem vorhandenen Fundament.
- **Belege heute:** Windpark-Simulator mit Ground Truth, IEC-Datenpunktkatalog, Leistungskurvenanalyse, Szenario
  H2-Kette, Betriebsvorgaben-Atom. **Benötigt:** echte SCADA- und Zählerdaten, WEA- und Wake-Atome im Zwilling, ein
  Referenzprojekt.
- **Einwände:** „Wir haben schon SCADA und ein APM-System.“ → OpenTSx ersetzt beides nicht. Es liest deren Daten und
  rechnet die Kopplung zu Abnehmern, Speicher und Vermarktung. „Zu groß für den Nutzen.“ → Stufe A kommt ohne
  Simulation aus und endet an einer Wertschranke.
- **Zahlungsbereitschaft (Hypothese):** Stufe A als Festpreisprojekt im Umfang einer externen Gutachterleistung, grob
  5–15 Beratertage; Folgestufen als Projekt oder Betriebsvertrag.
- **Einkaufsentscheider:** Geschäftsführung bzw. Asset-Management (Budget), technische Betriebsführung (Daten,
  Zugang), Gesellschafter, IT- und OT-Dienstleister.
- **Einstieg:** „Potenzialbericht Stufe A“ aus vorhandenen Zähler- und Fahrplandaten.

### 4.2 Direktvermarkter und PPA-Händler

- **Personas:** Portfoliomanagement, Prognose und Fahrplan, Vertrieb von PPAs.
- **Schmerzpunkte:** Fahrplanabweichungen und Ausgleichsenergie; Kopplung an Elektrolyse und Direktabnehmer, die das
  eigene Prognosemodell nicht sieht; ein schrumpfender PPA-Markt (2025: 27 Abschlüsse) mit härterem Wettbewerb.
- **Aufgaben:** „Verstehe, wie sich die Fahrweise des Elektrolyseurs auf meinen Fahrplan auswirkt.“ „Übe die neue
  Belieferung, bevor das Messkonzept umgestellt wird.“
- **Nutzen:** PPA-Schattenbetrieb (Szenario S3: Fahrplan und Prognose gegen tatsächliche Erzeugung, Abweichung je
  15 min, Kosten), What-if von Fahrplananpassungen, Replay als gemeinsame Faktenbasis (N4), föderiert: Das
  Vermarkter-Atom läuft beim Vermarkter (N5).
- **Belege heute:** Atom „Direktvermarkter“ (Abweichung, Mehr- und Mindermengen, Erlös) im Szenario S1.
  **Benötigt:** echte Fahrplan- und Prognosedaten, Marktdaten-Sammler (in Arbeit), Ausgleichsenergiepreise.
- **Einwände:** „Wir haben eigene Handels- und Prognosesysteme.“ → Diese bleiben; ihre Prognosen werden zu Ports mit
  Herkunft `FORECAST` oder `REPORTED`.
- **Zahlungsbereitschaft (Hypothese):** zunächst gering, Interesse als Mitnutzer eines Verbundpiloten; später je
  Portfolio, falls Abweichungskosten messbar sinken.
- **Einkaufsentscheider:** Leitung Portfoliomanagement, Risikomanagement, IT.
- **Einstieg:** Teilnahme am Verbundpiloten mit eigenen Fahrplandaten.

### 4.3 Elektrolyseur- und H2-Projektbetreiber

- **Personas:** Projektleitung H2, Betriebsleitung der Anlage, kaufmännische Leitung, Abnehmer (Offtaker).
- **Schmerzpunkte:** Mindestlast, Rampen, Leitungsdruck und Abnahmeschwankungen; teurer Strombezug; wenige, große
  Projekte unter Kostendruck (181 MWel in Betrieb, Ziel 10 GW gefährdet); künftig stündliche zeitliche Korrelation nach
  den RFNBO-Regeln ab 2030.
- **Aufgaben:** „Warne mich, bevor der Druck die Anlage abregelt, und zeig mir die Gegenmaßnahme.“ „Zeig mir, wie viel
  Wasserstoff ich mit dem Windpark nebenan zu welchen Kosten erzeugen kann.“ „Hilf mir, die zeitliche Korrelation
  nachvollziehbar zu belegen.“
- **Nutzen:** Zeit-bis-Grenze und Frühwarnung, What-if vor dem Eingriff, Prognose mit druckbewusster Fahrweise
  (N2); Koordinationspotenzial mit Windpark und Speicher (N1); Herkunft je Wert und Replay als Grundlage für Nachweise
  (N3, N4). Die Zertifizierung bleibt Sache der Zertifizierer.
- **Belege heute:** Szenario S1 auf synthetischen Daten. Der Schieber schließt, der Zwilling warnt, wenn weniger als
  eine Stunde Puffer bleibt; bei 40 bar regelt der Elektrolyseur ab; der Vermarkter muss etwa 5 MW mehr vermarkten.
  Das What-if mit Wiederöffnung um 02:00 vermeidet die Abschaltung; die Fahrweise `pressure-aware` vermeidet
  Abschaltung und Überdruck auf Kosten einer früheren Fahrplanabweichung ([`opentsx-twin`](../../opentsx-twin/README.md)).
  **Benötigt:** Betriebsdaten über IEC 104, OPC UA oder Export; bestätigte Kette mit echten Daten (Schranke C).
- **Einwände:** „Der Hersteller liefert schon einen Zwilling“ (etwa die Siemens Hydrogen Performance Suite, Teil 1
  §4.1). → Diese optimiert die Anlage; OpenTSx bildet den Verbund über Eigentümergrenzen ab und kann ihre Ergebnisse
  als Port übernehmen. „Sicherheit?“ → Lokale Schutzlogik gewinnt immer; Vorschläge gehen nur nach Freigabe zurück.
- **Zahlungsbereitschaft (Hypothese):** mittel bis hoch, wenn Abschaltungen oder Fahrplanstrafen messbar sinken;
  Projektbudget, oft gefördert.
- **Einkaufsentscheider:** Projektleitung, Betriebsleitung, Controlling, Abnehmer, Fördermittelgeber.
- **Einstieg:** Schattenbetrieb der H2-Kette auf echten Betriebsdaten (Stufe B/C).

### 4.4 Stadtwerke und Verteilnetzbetreiber

- **Personas:** Leitung Digitalisierung, Energiedatenmanagement, Netzplanung, Produktentwicklung im Vertrieb,
  Erzeugung und Wärme, Geschäftsführung.
- **Schmerzpunkte:** viele Rollen in einem Haus (Netz, Vertrieb, Erzeugung, Wärme) mit getrennten Systemen; knappe
  Teams; Pflichten aus § 14a, Smart-Meter-Rollout und kommunaler Wärmeplanung; neue Produkte wie Energy Sharing;
  Sorge vor Abhängigkeit von einem Anbieter.
- **Aufgaben:** „Zeig mir, was abgestimmter Betrieb von BHKW, Speicher, PV, Wärmenetz und Kundenflexibilität bringt.“
  „Rechne Energy-Sharing-Produkte und Tarife durch, bevor wir sie anbieten.“ „Wir wollen die Daten selbst behalten.“
- **Nutzen:** Verbundzwilling über Sparten und Verträge (N1, N2), Herkunft je Wert bei dünner Messlage (N3: 5,9 %
  Smart-Meter-Quote heißt viel Schätzung), Replay für Abrechnungsfragen (N4), Betrieb im eigenen Haus (N5).
  **Abgrenzung:** Netzzwillinge (envelio, VENIOS, PowerFactory, Gridscale X; Teil 1 §4.4) rechnen Lastfluss und
  Netzzustand. OpenTSx rechnet Anlagen, Fahrweisen und Verträge und übernimmt Netzergebnisse als Ports.
- **Belege heute:** Modbus-Leser, DWD-Sammler (Arbeitszweig), Haus-Zwilling, Bilanzknoten mit Prioritäten.
  **Benötigt:** Import von Smart-Meter- und EDM-Daten, Atome für Wärmenetz, BHKW und Energy-Sharing-Aufteilung, ein
  Referenz-Stadtwerk.
- **Einwände:** „Wir haben bereits eine Netzplattform.“ → OpenTSx ergänzt sie. „Wer betreibt das?“ → Selbstbetrieb
  auf k3s ([DevOps-Leitfaden](../devops/README.md)) oder Betrieb durch einen Dienstleister.
- **Zahlungsbereitschaft (Hypothese):** Projektbudgets für Digitalisierung und Förderprojekte; Lizenzkosten sind wegen
  Apache 2.0 kein Hebel, Dienstleistung und Betrieb schon.
- **Einkaufsentscheider:** Geschäftsführung, Leitung Digitalisierung oder IT, Fachbereiche, kommunaler Gesellschafter,
  Datenschutz und Informationssicherheit.
- **Einstieg:** Quartierszwilling im Schattenbetrieb für ein Energy-Sharing- oder Wärmeprojekt.

### 4.5 Industriestandorte mit Eigenversorgung und Speicher

- **Personas:** Energiemanagement, Werksleitung, Einkauf Energie.
- **Schmerzpunkte:** Lastspitzen und Netzentgelte, PPAs, Speicher-Business-Case, Produktionsrestriktionen.
- **Nutzen:** Koordinationspotenzial über PV, BHKW, Speicher und Produktion (N1); What-if vor der Investition (N2);
  nachvollziehbare Läufe als Grundlage für das Energiemanagement (N4).
- **Belege heute:** Batterie-Atom, Bilanzknoten. **Benötigt:** Atome für industrielle Lasten, Tarifmodelle, ein
  Pilotstandort.
- **Abgrenzung:** EMS-Anbieter (OpenEMS, Entrix für Gewerbespeicher, Teil 1 §4.5) steuern Speicher; OpenTSx bewertet
  Alternativen über Anlagen und Verträge.
- **Zahlungsbereitschaft (Hypothese):** mittel; Investitionsentscheidungen rechtfertigen eine Studie.
- **Einkaufsentscheider:** Werksleitung, Energiemanager, Controlling.

### 4.6 Energiegemeinschaften und Bürgerenergie

- **Personas:** Vorstand einer Energiegenossenschaft (oft ehrenamtlich), Dienstleister der Gemeinschaft.
- **Schmerzpunkte:** Energy Sharing nach § 42c EnWG verlangt Smart Meter, Viertelstundendaten und eine
  nachvollziehbare Aufteilung; volle Netzentgelte drücken die Wirtschaftlichkeit; Vertrauen der Mitglieder.
- **Nutzen:** transparente Aufteilung „wer bekam wie viel, aus welcher Quelle, gemessen oder geschätzt“ (N3);
  What-if zu Mitgliederzahl, Speicher und Anlagen (N2); Replay für Abrechnungsfragen (N4); kostenlos nutzbar.
- **Belege heute:** keine spezifischen. **Benötigt:** Aufteilungs-Atom, Import von Zählerdaten, Mitgliederansicht.
- **Zahlungsbereitschaft (Hypothese):** gering; eher über Stadtwerke, Dienstleister oder Förderprogramme.
- **Rolle:** Kanal und Multiplikator; Zugang über Stadtwerke und Genossenschaftsverbände.

### 4.7 Ingenieurbüros und Systemintegratoren

- **Personas:** Projektingenieurinnen, Leitung Digitalisierung im Büro, Integratoren für SCADA und Zähler.
- **Schmerzpunkte:** jedes Projekt baut Datenpipelines neu; Kunden verlangen Herstellerneutralität und
  nachvollziehbare Studien.
- **Nutzen:** wiederverwendbarer Atomkatalog und Schaltpläne; reproduzierbare Studien (N4); Studio für die
  Präsentation; Apache 2.0 erlaubt die Einbettung in eigene Dienstleistungen.
- **Zahlungsbereitschaft (Hypothese):** Schulungen, Support, Partnerprogramm.
- **Rolle:** Vertriebs- und Umsetzungspartner, nicht Endkunde.

### 4.8 Forschung und Labore

- **Personas:** Lehrstühle für Energieinformatik, Kosimulation und Regelung; Prüfstandslabore; Doktorandinnen und
  Doktoranden.
- **Schmerzpunkte:** Prüfstandsdaten liegen in Dateien ohne Herkunft; Experimente lassen sich nach Personalwechsel
  schwer wiederholen; Kosimulationsstudien brauchen Reproduzierbarkeit.
- **Nutzen:** Episoden mit Herkunft, Replay und Katalog in OpenMetadata (N3, N4); LabVIEW-Brücke (MQTT → infinimesh →
  KafScale, freigegebene Sollwerte zurück; [09](../datamodel-2026/09-STUDIO-LABVIEW-STEUERPULT.md), **Konzept**);
  offene Referenzimplementierung für die Frage „eng oder lose gekoppelt“ (Teil 1 §6.4); Lehrmaterial mit Haus und Boot.
- **Belege heute:** Studio-Prototyp, Messung der Kopplungsvarianten im Arbeitszweig. **Benötigt:** LabVIEW-Publisher,
  JSON-Schemas, ein Demonstrationslabor.
- **Zahlungsbereitschaft (Hypothese):** gering für Lizenzen, hoch für gemeinsame Förderanträge und Workshops.
- **Rolle:** Glaubwürdigkeit, Mitentwicklung, Paper.

### 4.9 Energieberater und Hauseigentümer

- **Personas:** Energieberaterin aus der dena-Expertenliste (22.800 Einträge); Hauseigentümer mit Öl- und
  Holzheizung (4,7 Mio. Ölheizkessel, 11,7 Mio. Einzelraumfeuerstätten).
- **Schmerzpunkte:** Das GModG hat die Regeln im Juli 2026 erneut geändert. Eigentümer haben mehr Wahlfreiheit, aber
  auch mehr Unsicherheit. Einsparversprechen werden misstraut; Beratung kostet Zeit.
- **Aufgaben:** „Welche Maßnahme lohnt sich in meinem Haus zuerst?“ „Reicht das Öl bis März?“ „Hat die Maßnahme
  wirklich etwas gebracht?“
- **Nutzen:** Kalibrierung an Abrechnungen mit Gütemaßen (CV(RMSE), NMBE) und einem **Identifizierbarkeitsbericht**,
  der offen sagt, was sich aus den Daten nicht bestimmen lässt; Hebel als Alternative minus Referenz, ausdrücklich
  modelliert; Nachweis erst durch gemessene Zeiträume vor und nach der Maßnahme
  ([08-HAUS-ZWILLING](../datamodel-2026/08-HAUS-ZWILLING.md)).
- **Belege heute:** Auf simulierten Abrechnungen findet die Kalibrierung den Wärmeverlust nur als H/η wieder und meldet
  Warmwasser als nicht bestimmbar. Der Ablesefehler des Peilstabs ist größer als das Signal. Genau diese Ehrlichkeit
  ist der Beleg. **Benötigt:** echte Abrechnungen, eine Oberfläche für Beraterinnen, DWD-Abruf (Arbeitszweig).
- **Wichtige Grenze:** OpenTSx ersetzt keine normgerechten Berechnungen (etwa für Energieausweise) und keine
  geförderte Energieberatung.
- **Zahlungsbereitschaft (Hypothese):** Eigentümer gering; Beraterinnen mittel bei messbarer Zeitersparnis.
- **Rolle:** Erzählanker und Reichweite; Beraterinnen als späterer Kanal.

### 4.10 Boot-, Wohnmobil- und Off-Grid-Eigner (Schaufenster)

- **Nutzen:** Autonomie und ungedeckter Bedarf auf dem Törn; Hebel Modulfläche, Speichergröße, Starlink-Nutzung;
  Kalibrierung an Loggerdaten ([10-BOOT-ZWILLING](../datamodel-2026/10-BOOT-ZWILLING.md)).
- **Belege heute:** modelliert: Starlink Mini ist mit rund 530 Wh/Tag der größte Verbraucher; mit Starlink Standard
  fehlen über zwei Wochen rund 2,4 kWh; auf simulierten Logs findet die Kalibrierung verdeckte Werte auf etwa 1 %.
- **Rolle:** Geschichten, Community, Einstieg für Entwicklerinnen; Brücke zu Herstellern (§4.11).

### 4.11 Hersteller kleiner Energiesysteme

- **Personas:** Produktmanagement und Support bei Herstellern von Powerstations, Heimspeichern und Off-Grid-Systemen.
- **Schmerzpunkte:** Kundenfrage „Welche Größe brauche ich?“, Supportfälle („Warum war der Akku leer?“),
  Produktplanung ohne Felddaten.
- **Nutzen:** Dimensionierung als What-if, kalibriert an (freigegebenen) Flottenlogs; Replay für Support- und
  Gewährleistungsfälle; einbettbar unter Apache 2.0.
- **Belege heute:** Boot-Zwilling mit Powerstation. **Benötigt:** Geräteanbindungen, ein Herstellerpilot.
- **Zahlungsbereitschaft (Hypothese):** mittel; Einbettung, Support oder Entwicklungsauftrag.

---

## 5. Priorisierung

Die Einschätzung ist qualitativ (hoch, mittel, niedrig) und begründet; sie ist mit Gesprächen zu prüfen.

| Segment | Problemdruck | Passung zum heutigen Stand | Datenzugang | Verkaufszyklus | Zahlungsbereitschaft | Hebel (Referenz, Multiplikator) |
|---------|--------------|----------------------------|-------------|----------------|----------------------|---------------------------------|
| Verbund Wind + H2 + Vermarktung (4.1–4.3) | hoch | **hoch** (S1 ist genau dieser Fall) | mittel (in Vorbereitung) | lang | hoch | **hoch** |
| Stadtwerke (4.4), mit Energiegemeinschaften (4.6) | hoch | mittel | mittel | lang | mittel | **hoch** |
| Forschung und Labore (4.8), mit Ingenieurbüros (4.7) | mittel | hoch | hoch | kurz bis mittel | niedrig bis mittel | hoch |
| Industrie (4.5) | hoch | niedrig | mittel | lang | mittel bis hoch | mittel |
| Energieberater und Hauseigentümer (4.9) | mittel | hoch | hoch | kurz | niedrig | mittel |
| Hersteller kleiner Systeme (4.11) | mittel | mittel | mittel | mittel | mittel | mittel |
| Boot und Off-Grid (4.10) | niedrig | hoch | hoch | kurz | niedrig | niedrig (Reichweite) |

### Die drei wichtigsten Segmente und ihr Nutzen

1. **Verbund aus Windpark, Elektrolyseur, Speicher, Abnehmer und Vermarktung.**
   - Nutzen: Das Koordinationspotenzial wird beziffert; Kausalketten und Gegenmaßnahmen werden vor dem Eingriff
     sichtbar; das Replay liefert allen Parteien dieselben Fakten.
   - Begründung: Das Szenario S1 ist genau dieser Fall, ein Projektumfeld ist in Vorbereitung, und ein Erfolg wäre die
     stärkste Referenz.
2. **Stadtwerke, über sie Energiegemeinschaften.**
   - Nutzen: ein offener Verbundzwilling über Sparten und Verträge im eigenen Haus, ergänzend zu vorhandenen
     Netzplattformen; Herkunft je Wert bei dünner Messlage.
   - Begründung: rund 1.600 VKU-Mitgliedsunternehmen, neue Pflichten und Produkte (§ 14a, Energy Sharing, Wärmeplanung)
     und ein ausgeprägter Wunsch nach Datenhoheit.
3. **Forschung und Labore, mit Ingenieurbüros.**
   - Nutzen: Reproduzierbarkeit, Herkunft und eine offene Referenz für die Frage „eng oder lose gekoppelt“.
   - Begründung: kurzer Zugang, Paper und Förderprojekte schaffen Glaubwürdigkeit und Mitstreiter, bevor
     Produktivkunden da sind.

Haus und Boot tragen die **Kommunikation**: Sie machen die Methode in einer Minute verständlich (Teil 3).

---

## 6. Belege heute und benötigt

| Beleg | heute | benötigt für Phase 1 | benötigt für Phase 2 |
|-------|-------|----------------------|----------------------|
| Szenarien | H2-Kette, Haus, Boot auf synthetischen bzw. simulierten Daten | ein Szenario auf echten Daten (Verbund Stufe A oder Haus des Owners) | zwei Pilotkunden mit Abschlussbericht |
| Replay | Episoden-Replay auf KafScale verifiziert; Zwillingsläufe bitgleich | Zwillings-Topics auf KafScale | Replay in einem echten Streitfall oder Audit genutzt |
| Föderation | Variante L im Arbeitszweig, simuliert | Merge nach `master`, Läufe mit echten Prozessoren | Betrieb über zwei Eigentümer |
| Herkunft | Herkunftsleiter umgesetzt | Messanteil je Kennzahl im Bericht | Kunde nutzt Herkunft in einer Entscheidung |
| Schnittstellen | Modbus (gegen Simulator getestet), DWD (Arbeitszweig) | echte Zählerkarte, Marktdaten | IEC 104, OPC UA |
| Sicherheit | Freigabeprinzip als Konzept | Sicherheitskonzept für den Rückweg | externe Prüfung |
| Veröffentlichung | Konzeptdokumente, Code, Tests | Preprint „Digital Twins on Logs“ | begutachtete Veröffentlichung |

---

## 7. Übergreifende Einwände und Antworten

| Einwand | Antwort |
|---------|---------|
| „Digitale Zwillinge haben wir schon gesehen. Viel Grafik, wenig Nutzen.“ | OpenTSx beginnt mit einer Zahl, nicht mit einer Grafik: dem Koordinationspotenzial aus Ihren Daten (Stufe A), mit Herkunftsanteilen. |
| „Open Source ist ein Risiko. Wer haftet, wer pflegt?“ | Code und Formate bleiben Ihnen, auch ohne uns. Betrieb und Support sind als Dienstleistung vereinbar. Die Abhängigkeit ist geringer als bei proprietären Plattformen (vgl. AWS-Abkündigungen, Teil 1 §4.6). |
| „Ihr habt keinen Produktivkunden.“ | Richtig. Darum ist der Einstieg eine begrenzte Stufe mit Wertschranke, und alle Ergebnisse sind nachrechenbar. |
| „Wie genau ist das Modell?“ | So genau wie die Kalibrierung zeigt. Wir weisen Gütemaße und nicht bestimmbare Größen aus, statt sie zu verschweigen. |
| „Darf der Zwilling unsere Anlage steuern?“ | Nur nach Freigabe, und lokale Schutzlogik hat immer Vorrang. Der Normalfall ist Beraten, nicht Steuern. |
| „Unsere Daten verlassen das Haus nicht.“ | Müssen sie nicht: Betrieb on-premises; in der losen Variante teilen Parteien nur Ports. |

---

## 8. Geschäftsmodelle

| Modell | Beschreibung | Vorteile | Nachteile |
|--------|--------------|----------|-----------|
| **Dienstleistung um Open Source** | Festpreispakete je Stufe (A: Potenzialbericht, B: Schatten und Replay, C: Kausalketten, D: Optimierung), Schaltplan-Engineering, Anbindung | sofortiger Umsatz, Lernen am Kunden, passt zu Apache 2.0 | skaliert mit Personentagen; Abhängigkeit vom Owner |
| **Open Core** | offener Kern, kostenpflichtige Erweiterungen (z. B. Mandantenfähigkeit, Föderation im Betrieb, Konnektoren IEC 104/OPC UA, Sicherheitsfunktionen) | skalierbar, klare Lizenzerlöse | belastet das Vertrauen der Community; Abgrenzung schwierig; Apache-2.0-Kern erlaubt Forks |
| **Betriebener Zwilling (Managed Twin)** | Betrieb je Standort oder Verbund als Abonnement | wiederkehrender Umsatz; niedrige Einstiegshürde für Kunden | Betriebsverantwortung, Informationssicherheit, Haftung |
| **Schulung und Befähigung** | Kurse für Ingenieurbüros, Stadtwerke, Labore; Workshops „Zwilling in einem Tag“ | geringes Risiko, baut Community und Partner auf | kleine Volumina |
| **Forschungskooperation ohne Zertifizierungsanspruch** | Code bleibt Apache 2.0; Angebot: gemeinsame Förderanträge, Betreuung, Mitautorschaft | Glaubwürdigkeit, Finanzierung von Entwicklung | langsam, Verwaltungsaufwand, wenig Umsatz |
| **Partner- und Herstellereinbettung** | Integratoren und Hersteller nutzen OpenTSx in eigenen Angeboten; Support- oder Entwicklungsverträge | Reichweite | Abhängigkeit von Partnern; Marke wird weniger sichtbar |

**Empfehlung:** In Phase 0 und 1 **Dienstleistung plus Forschungskooperation**, mit Stufe A als klar umrissenem
Festpreispaket. Für Phase 2 ist der **betriebene Zwilling** mit Support-Abonnement vorgesehen. Open Core nur für
eindeutig betriebliche Funktionen und erst, wenn eine Community da ist. Eine Markenrichtlinie für den Namen „OpenTSx“
sollte vor Partnerangeboten feststehen (Markenrecherche **nicht durchgeführt**).

---

## 9. Quellen (Abruf 12.10.2026)

Alle Zahlen und Regeln sind in §3 mit Link belegt. Projektgrundlagen:
[07-DIGITAL-TWIN-BSP](../datamodel-2026/07-DIGITAL-TWIN-BSP.md),
[08-HAUS-ZWILLING](../datamodel-2026/08-HAUS-ZWILLING.md),
[09-STUDIO-LABVIEW-STEUERPULT](../datamodel-2026/09-STUDIO-LABVIEW-STEUERPULT.md),
[10-BOOT-ZWILLING](../datamodel-2026/10-BOOT-ZWILLING.md),
[TASK-011](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md),
[`opentsx-twin`](../../opentsx-twin/README.md). Wettbewerb und Forschung: [Teil 1](01-MARKTRECHERCHE-DIGITAL-TWINS.md).
