# OpenTSx: Customers and benefits

> Status: **analysis and hypotheses**, as of 2026-10-12; sources retrieved on 2026-10-12. Part 2 of the business
> documents ([Overview](README.md)); Part 1: [Market research](01-MARKTRECHERCHE-DIGITAL-TWINS.md),
> Part 3: [Marketing campaign](03-MARKETING-KAMPAGNE.md).
>
> **How to read this.** Market figures come from the sources named; calculated derivations are marked as such.
> Statements on pain points, willingness to pay and buying decision makers are **hypotheses**. They are based on the
> research and the project documents, not on customer interviews, and must be checked in conversations.
> Willingness to pay is deliberately given as effort (consultant days) instead of in EUR; daily rates vary widely.

---

## 1. Summary

**Who are the customers?** Organizations that operate or assess a **coupled energy system** whose parts belong to
different parties, and that must justify their decisions. Three segments have priority (section 5):

1. **Operators of coupled clusters: wind farm, electrolyzer, storage, offtaker and marketing.** Benefit: The
   coordination potential is quantified (coordinated operating policy minus reference operation, same information)
   before anyone invests or changes the operation. Causal chains such as "offtaker closes valve → pressure →
   curtailment → schedule deviation" become visible in advance. A bit-identical replay serves all parties as a shared
   factual basis when deviations occur.
2. **Municipal utilities (with grid, retail, generation, heat) and, through them, energy communities.** Benefit: an
   open, self-operated cluster twin across business lines and contracts. It adds operating policies, tariffs and energy
   sharing (Section 42c EnWG, the Energy Industry Act, since 2026-06-01) to existing grid twins and shows for every
   number whether it is measured or estimated.
3. **Research, labs and engineering consultancies.** Benefit: reproducible experiments and studies (same run, same
   hash), data storage with provenance for test benches (LabVIEW bridge as a concept) and an open reference
   implementation for the question "tightly or loosely coupled". This segment provides credibility, allies and funding.

**The house and the boat** are **narrative anchors** with reach, not revenue segments of the first phase. Energy
advisors and manufacturers of small energy systems are later channels.

**What OpenTSx can show today:** three runnable scenarios on synthetic or simulated data, verified episode replay on
KafScale and the conformance of the loose coupling in the work branch. **What is missing:** real data, a pilot
customer, a security concept for the return path, interfaces such as IEC 104 and OPC UA (section 6).

---

## 2. Benefit building blocks

Each segment benefit in section 4 is made up of these building blocks. The "Status" column refers to
[Part 1, section 3](01-MARKTRECHERCHE-DIGITAL-TWINS.md).

| No. | Benefit building block | What the customer gets | Technical basis | Status |
|-----|------------------------|------------------------|-----------------|--------|
| N1 | **Quantify the coordination potential** | What separate operations management costs and what coordinated action brings: contribution margin, avoidable curtailment, H2 demand coverage, storage stress, benefit and extra cost per party | evaluation principle "coordinated alternative − reference operation, same boundary conditions and information"; perfect foresight only as a marked upper bound ([TASK-011, P8](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md)) | principle and demos available; potential report with real data open |
| N2 | **Compute first, then act** | What-if of a decision before the intervention; forecast from the current state with an exchangeable operating policy; early warning ("about 1 h left until curtailment") | three operating modes on the same atoms ([07, section 5](../datamodel-2026/07-DIGITAL-TWIN-BSP.md)) | variant T implemented |
| N3 | **Trust through provenance** | Every number shows whether it is measured, reported, forecast, estimated or synthetic; potential reports state their measured share | provenance ladder with taint propagation ([07, section 4.4](../datamodel-2026/07-DIGITAL-TWIN-BSP.md)) | implemented; percentage display per KPI proposed |
| N4 | **Reproducible to the bit** | Shared factual basis in disputes about deviations; audit of decisions; reproducible studies | deterministic runs with run id and state hash; episode replay with checksums on KafScale | implemented (the twin locally, the episodes on KafScale) |
| N5 | **Data sovereignty and federation** | Each party operates its atoms itself and shares only ports; on-premises operation possible | variant L via Smart Topics; Apache 2.0; Kafka protocol, Iceberg | in the work branch, simulated |
| N6 | **Low entry barrier** | Stage A (data and potential) without simulation; every stage ends at a value gate | stages A to D ([07, section 6](../datamodel-2026/07-DIGITAL-TWIN-BSP.md)) | method described; Modbus reader available |
| N7 | **Safety stays local** | Proposals go to the plants only after approval; local protection logic always takes precedence | approval workflow, command and acknowledgment contract ([09](../datamodel-2026/09-STUDIO-LABVIEW-STEUERPULT.md)) | concept |

**What we do not promise:** savings without measurement, real-time control, a replacement for control systems,
protection equipment or standards-compliant certificates (for example energy performance certificates), certifications.

---

## 3. Market sizes (sourced figures)

| Metric | Value | As of | Source |
|--------|-------|-------|--------|
| Onshore wind turbines | **29,248** turbines, **69,952 MW** | 2026-06-30 | [FA Wind und Solar on behalf of BWE (German Wind Energy Association) and VDMA Power Systems](https://www.fachagentur-wind-solar.de/fileadmin/Veroeffentlichungen/Wind/Daten/FA_Wind_Solar_Status_des_Windenergieausbaus_an_Land_Halbjahr_2026.pdf) |
| of which without entitlement to EEG (Renewable Energy Sources Act) remuneration since 2026-01-01 | **12.1 GW** (summary; chapter 3.2 states 12.2 GW) | 2026-06-30 | ibid. |
| Average age of the fleet; repowering in the first half of the year | 15.7 years; 135 turbines with 770 MW (one third of the new capacity) | 2026-06-30 | ibid. |
| Offshore wind turbines | **1,764** turbines, about **10.8 GW** | 2026-06-30 | [Stiftung Offshore-Windenergie (German Offshore Wind Energy Foundation)](https://www.offshore-stiftung.de/de/status-quo-offshore-windenergie.php) |
| Electrolysis capacity in operation | **181 MWel**; another 1.3 GWel with an investment decision or under construction; according to EWI (Institute of Energy Economics at the University of Cologne), the target of 10 GW by 2030 will probably be missed (8.7 GW even if all announced projects are carried out) | 2026-01 | [EWI](https://www.ewi.uni-koeln.de/en/news/ewi-analysis-electrolysis-ramp-up-in-germany-is-proceeding-slowly/) |
| Electrolysis, later status | 180.5 MWel in 44 projects; 27 projects with 1,271 MWel at the investment decision stage or under construction | 2026-03-31 | [EWI/BDEW hydrogen monitor](https://www.bdew.de/media/documents/260528_EWI_BDEW_Wasserstoffmonitor_Bericht_final.pdf) |
| PPA deals in Germany | 27 deals with 1.3 GW (2024: 51 with 2.2 GW) | 2025 | [dena (German Energy Agency) PPA market analysis 2025 (data: Pexapark)](https://www.dena.de/fileadmin/dena/Publikationen/PDFs/2026/PPA_Marktanalyse_2025.pdf) |
| Member companies of the VKU (German Association of Local Public Utilities) | about **1,600** (energy, water/wastewater, waste, telecommunications) | 2025 | [VKU figures, data, facts 2025](https://www.vku.de/fileadmin/user_upload/Verbandsseite/Publikationen/2025/VKU_ZDF_2025_DE.pdf) |
| Electricity distribution system operators | 851 | 2025 | [Statista chart based on BNetzA (Federal Network Agency) and BKartA (Federal Cartel Office)](https://emvg.energie-und-management.de/filestore/newsimgorg/Statista-Grafiken/Diverse_2026/statistic_id152937_anzahl-der-stromnetzbetreiber-in-deutschland-bis-2025.orig.pdf) (secondary source; **not verified** in the monitoring report) |
| Smart metering systems (iMSys) | about 3.2 million out of 54.1 million metering points (**5.9 %**); 26.1 % of mandatory installation cases equipped | 2026-06-30 | [ZfK](https://www.zfk.de/digitalisierung/smart-city-energy/smart-meter-fortschritt-im-ersten-halbjahr-weiter-viele-nachzuegler), [BNetzA](https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/NetzzugangMesswesen/Mess-undZaehlwesen/iMSys/artikel.html) |
| Energy cooperatives | about 1,020 with 230,000 members (secondary source); DGRV (German Cooperative and Raiffeisen Confederation) itself: about 1,000 organized cooperatives, over 220,000 people | end of 2025 / 2026-07 | [Cleanthinking](https://www.cleanthinking.de/?p=1073948), [DGRV statement on EEG 2027](https://www.dgrv.de/wp-content/uploads/2026/07/Stellungnahme_Referentenentwurf_EEG_2027_DGRV.pdf) |
| Stationary battery storage systems | 2,222,454 in the Marktstammdatenregister (core energy market data register), about 80 % of them PV home storage | end of 2025 | [pv magazine](https://www.pv-magazine.de/2026/01/06/weniger-neue-batteriespeicher-2025-installiert-aber-mit-mehr-kapazitaet/) |
| Dwellings | 44.0 million | 2025-12-31 | [Destatis (Federal Statistical Office)](https://www.destatis.de/DE/Presse/Pressemitteilungen/2026/07/PD26_250_31231.html) |
| Share of dwellings with oil heating | **17.2 %** (gas 56.1 %, district heating 15.5 %, heat pumps/solar/geothermal 4.5 %) | 2025 | [BDEW (German Association of Energy and Water Industries), as of 2026-06](https://www.bdew.de/media/documents/Wohnungsbestand_Beheizungsstruktur_Entw_ab_1998_online_o_dw_jaehrlich_CMi_19062026.pdf) |
| *calculated:* dwellings with oil heating | *≈ 7.6 million* (17.2 % × 44.0 million; an approximation and upper bound, because the BDEW share refers to dwellings with heating) | n/a | own calculation |
| Oil boilers; gas-fired systems; single-room fireplaces | **4.7 million**; 14.8 million; 11.7 million | 2025 | [Chimney sweep trade association (ZIV), 2026-05-22](https://www.schornsteinfeger.de/sites/default/files/pressemeldungen/downloads/05%202026%20Pressemeldung%20Erhebungen%202025.pdf) |
| Energy efficiency experts | 22,800 listed, about 20,600 findable online | 2025-12 | [dena](https://www.dena.de/fileadmin/dena/Publikationen/PDFs/2026/FS_EEE_Liste.pdf) |
| Motorhomes | about 907,900 | 2024-01-01 | [Statista based on KBA (Federal Motor Transport Authority)](https://de.statista.com/statistik/daten/studie/152231/umfrage/anzahl-der-wohnmobile-in-deutschland) |
| Berths in marinas | 206,279 | survey of 2015 (outdated) | [German Environment Agency (UBA), Texte 68/2015](https://www.umweltbundesamt.de/publikationen/sicherung-der-verlaesslichkeit-der-antifouling) |

**Regulatory triggers**

| Trigger | Content | Source |
|---------|---------|--------|
| Gebäudemodernisierungsgesetz (GModG, building modernization law) | passed by the Bundestag on 2026-07-10, largely in force since 2026-07-29; removed the 65 % rule and the mandatory advice when installing oil and gas heating; "bio staircase" for new fossil heating systems (10 % from 2029, 15 % from 2030, 30 % from 2035, 60 % from 2040); fuels climate-neutral from 2045 | [Haufe, 2026-07-30](https://www.haufe.de/immobilien/wirtschaft-politik/gebaeudemodernisierungsgesetz-gmg_84342_683694.html) |
| Section 14a EnWG | controllable consumption devices above 4.2 kW (heat pumps, wall boxes, storage, cooling) since 2024-01-01; grid fee modules 1 to 3 | [Finanztip](https://finanztip.de/stromtarife/steuerbare-verbrauchseinrichtungen-14a-enwg/) (determinations BK6-22-300 and BK8-22/010-A **not seen** in the original) |
| Energy sharing, Section 42c EnWG | since 2026-06-01, joint use of renewable electricity within the balancing area of a distribution grid; from 2028-06-01 also in the adjacent area; smart meter required, full grid fees | [Görg](https://www.goerg.de/de/aktuelles/veroeffentlichungen/13-04-2026/bald-strom-mit-nachbarn-teilen-ein-ausblick-auf-das-ab-1-juni-2026-geltende-energy-sharing-gemaess-ss-42c-enwg-chancen-und-grenzen), [Gleiss Lutz](https://www.gleisslutz.com/en/know-how/energy-sharing-joint-use-renewable-electricity-under-new-section-42c-energy-industry-act) |
| Municipal heat planning, Section 4 WPG (Heat Planning Act) | heat plans for municipalities with more than 100,000 inhabitants by 2026-06-30, all others by 2028-06-30 | [Section 4 WPG](https://www.buzer.de/4_WPG.htm) |
| RFNBO rules (Commission Delegated Regulation (EU) 2023/1184) | temporal correlation of electricity and hydrogen monthly until the end of 2029, hourly from 2030 | [CMS](https://cms.law/de/deu/legal-updates/erneuerbare-energien-eu-regeln-fuer-erneuerbaren-wasserstoff-update-1), [Europex](https://www.europex.org/?p=26232) (regulation text **not seen**) |
| BAFA (Federal Office for Economic Affairs and Export Control) energy advice for residential buildings | 50 % grant, at most EUR 650 (single-family and two-family houses); iSFP (individual renovation roadmap) bonus for applications from 2026-07-21 only at EUR 30,000 or more of eligible investment | [Schwäbisch Hall](https://www.schwaebisch-hall.de/foerderungen/bafa-foerderung.html), [Cleanthinking](https://www.cleanthinking.de/?p=1073862) (secondary sources; BAFA original **not seen**) |

**Deliberately not quantified:** the number of direct marketers, industrial sites with self-supply, engineering
consultancies in the energy sector, test bench labs with LabVIEW, sailing boats. There was no reliable source for these.

---

## 4. Segments

Each segment follows the same grid: personas, pain points and *jobs to be done*, benefit from OpenTSx, evidence today
and needed, objections with answers, willingness to pay (hypothesis), buying decision makers and entry offer.

### 4.1 Wind farm operators and asset managers

- **Personas:** technical operations management, commercial asset management, management of a community wind farm.
- **Pain points:** curtailment and operating constraints (grid restrictions, bat curtailment, field work) without
  quantified costs; 12.1 GW without EEG remuneration since the start of the year call for decisions on continued
  operation, PPA or repowering; falling PPA prices and negative prices; data spread across manufacturer portals and
  direct marketers.
- **Jobs to be done:** "Show me what separate operation costs and what direct supply, storage or electrolysis would
  bring, before I invest." "Prove to me who caused a deviation."
- **Benefit:** N1 and N6 first: a stage A potential report (overlap of generation and consumption, direct supply share
  versus grid feed-in, order of magnitude in EUR/a, provenance shares), then what-if (N2) and replay (N4). In addition,
  the IEC 61400-25-2 catalog, power curve analysis and pattern search from the existing foundation.
- **Evidence today:** wind farm simulator with ground truth, IEC data point catalog, power curve analysis, the H2 chain
  scenario, operating constraints atom. **Needed:** real SCADA and meter data, turbine and wake atoms in the twin, a
  reference project.
- **Objections:** "We already have SCADA and an APM system." → OpenTSx replaces neither. It reads their data and
  computes the coupling to offtakers, storage and marketing. "Too big for the benefit." → Stage A works without
  simulation and ends at a value gate.
- **Willingness to pay (hypothesis):** stage A as a fixed-price project with the scope of an external expert
  assessment, roughly 5 to 15 consultant days; follow-up stages as a project or operations contract.
- **Buying decision makers:** management or asset management (budget), technical operations management (data,
  access), shareholders, IT and OT service providers.
- **Entry offer:** "Stage A potential report" from existing meter and schedule data.

### 4.2 Direct marketers and PPA traders

- **Personas:** portfolio management, forecasting and scheduling, PPA sales.
- **Pain points:** schedule deviations and balancing energy; their own forecast model does not see the coupling to
  electrolysis and direct customers; a shrinking PPA market (2025: 27 deals) with tougher competition.
- **Jobs to be done:** "Understand how the electrolyzer's operating policy affects my schedule." "Rehearse the new
  supply before the metering concept is changed."
- **Benefit:** PPA shadow operation (scenario S3: schedule and forecast against actual generation, deviation per
  15 min, costs), what-if of schedule adjustments, replay as a shared factual basis (N4), federated: the marketer atom
  runs on the marketer's side (N5).
- **Evidence today:** the atom "direct marketer" (deviation, extra and short quantities, revenue) in scenario S1.
  **Needed:** real schedule and forecast data, market data collector (in progress), balancing energy prices.
- **Objections:** "We have our own trading and forecasting systems." → These stay; their forecasts become ports with
  provenance `FORECAST` or `REPORTED`.
- **Willingness to pay (hypothesis):** low at first, interest as a co-user of a cluster pilot; later per portfolio,
  if deviation costs fall measurably.
- **Buying decision makers:** head of portfolio management, risk management, IT.
- **Entry offer:** participation in the cluster pilot with their own schedule data.

### 4.3 Electrolyzer and H2 project operators

- **Personas:** H2 project management, plant operations management, commercial management, offtaker.
- **Pain points:** minimum load, ramps, line pressure and offtake fluctuations; expensive power procurement; few,
  large projects under cost pressure (181 MWel in operation, the 10 GW target at risk); in future, hourly temporal
  correlation under the RFNBO rules from 2030.
- **Jobs to be done:** "Warn me before the pressure curtails the plant, and show me the countermeasure." "Show me how
  much hydrogen I can produce with the wind farm next door, and at what cost." "Help me document the temporal
  correlation in a traceable way."
- **Benefit:** time to limit and early warning, what-if before the intervention, forecast with a pressure-aware policy
  (N2); coordination potential with the wind farm and storage (N1); provenance per value and replay as a basis for
  evidence (N3, N4). Certification remains the business of the certifiers.
- **Evidence today:** scenario S1 on synthetic data. The valve closes, and the twin warns when less than one hour of
  buffer remains; at 40 bar the electrolyzer curtails; the marketer has to market about 5 MW more. The what-if with
  reopening at 02:00 avoids the shutdown; the `pressure-aware` policy avoids shutdown and overpressure at the cost of
  an earlier schedule deviation ([`opentsx-twin`](../../opentsx-twin/README.md)).
  **Needed:** operating data via IEC 104, OPC UA or export; a confirmed chain with real data (gate C).
- **Objections:** "The manufacturer already supplies a twin" (for example the Siemens Hydrogen Performance Suite,
  Part 1, section 4.1). → That twin optimizes the plant; OpenTSx models the cluster across ownership boundaries and can
  import its results as a port. "Safety?" → Local protection logic always wins; proposals go back only after
  approval.
- **Willingness to pay (hypothesis):** medium to high if shutdowns or schedule penalties fall measurably; project
  budget, often funded.
- **Buying decision makers:** project management, plant operations management, controlling, offtaker, funding bodies.
- **Entry offer:** shadow operation of the H2 chain on real operating data (stage B/C).

### 4.4 Municipal utilities and distribution system operators (DSOs)

- **Personas:** head of digitalization, energy data management, grid planning, product development in sales,
  generation and heat, management.
- **Pain points:** many roles in one company (grid, retail, generation, heat) with separate systems; short-staffed
  teams; obligations under Section 14a, the smart meter rollout and municipal heat planning; new products such as
  energy sharing; concern about dependence on a single vendor.
- **Jobs to be done:** "Show me what coordinated operation of CHP (combined heat and power) units, storage, PV, the
  heating network and customer flexibility brings." "Compute energy sharing products and tariffs before we offer them."
  "We want to keep the data ourselves."
- **Benefit:** cluster twin across business lines and contracts (N1, N2), provenance per value where metering is
  sparse (N3: a 5.9 % smart meter rate means a lot of estimation), replay for billing questions (N4), in-house
  operation (N5). **Boundary:** grid twins (envelio, VENIOS, PowerFactory, Gridscale X; Part 1, section 4.4) compute
  load flow and grid state. OpenTSx computes assets, operating policies and contracts and imports grid results as ports.
- **Evidence today:** Modbus reader, DWD collector (work branch), house twin, balance node with priorities.
  **Needed:** import of smart meter and EDM (energy data management) data, atoms for the heating network, the CHP unit
  and energy sharing allocation, a reference municipal utility.
- **Objections:** "We already have a grid platform." → OpenTSx complements it. "Who operates it?" → Self-hosting on
  k3s ([DevOps guide](../devops/README.md)) or operation by a service provider.
- **Willingness to pay (hypothesis):** project budgets for digitalization and funded projects; because of Apache 2.0,
  license costs are not a lever, but services and operation are.
- **Buying decision makers:** management, head of digitalization or IT, business departments, municipal shareholder,
  data protection and information security.
- **Entry offer:** neighborhood twin in shadow operation for an energy sharing or heat project.

### 4.5 Industrial sites with self-supply and storage

- **Personas:** energy management, plant management, energy procurement.
- **Pain points:** load peaks and grid fees, PPAs, the business case for storage, production restrictions.
- **Benefit:** coordination potential across PV, CHP unit, storage and production (N1); what-if before the investment
  (N2); traceable runs as a basis for energy management (N4).
- **Evidence today:** battery atom, balance node. **Needed:** atoms for industrial loads, tariff models, a pilot site.
- **Boundary:** EMS vendors (OpenEMS, Entrix for commercial storage, Part 1, section 4.5) control storage; OpenTSx
  evaluates alternatives across assets and contracts.
- **Willingness to pay (hypothesis):** medium; investment decisions justify a study.
- **Buying decision makers:** plant management, energy managers, controlling.

### 4.6 Energy communities and citizen energy

- **Personas:** board of an energy cooperative (often volunteers), service providers of the community.
- **Pain points:** energy sharing under Section 42c EnWG requires smart meters, quarter-hour data and a traceable
  allocation; full grid fees squeeze profitability; member trust.
- **Benefit:** transparent allocation "who got how much, from which source, measured or estimated" (N3); what-if on
  the number of members, storage and assets (N2); replay for billing questions (N4); free to use.
- **Evidence today:** none specific. **Needed:** allocation atom, import of meter data, member view.
- **Willingness to pay (hypothesis):** low; more likely via municipal utilities, service providers or funding
  programs.
- **Role:** channel and multiplier; access via municipal utilities and cooperative associations.

### 4.7 Engineering consultancies and system integrators

- **Personas:** project engineers, head of digitalization in the consultancy, SCADA and meter integrators.
- **Pain points:** every project builds its data pipelines anew; customers demand vendor neutrality and traceable
  studies.
- **Benefit:** reusable atom catalog and wiring plans; reproducible studies (N4); Studio for presentations; Apache 2.0
  allows embedding in their own services.
- **Willingness to pay (hypothesis):** training, support, partner program.
- **Role:** sales and implementation partner, not end customer.

### 4.8 Research and labs

- **Personas:** university chairs for energy informatics, co-simulation and control; test bench labs; doctoral
  students.
- **Pain points:** test bench data sits in files without provenance; experiments are hard to repeat after staff
  changes; co-simulation studies need reproducibility.
- **Benefit:** episodes with provenance, replay and catalog in OpenMetadata (N3, N4); LabVIEW bridge (MQTT → infinimesh
  → KafScale, approved setpoints back; [09](../datamodel-2026/09-STUDIO-LABVIEW-STEUERPULT.md), **concept**); an open
  reference implementation for the question "tightly or loosely coupled" (Part 1, section 6.4); teaching material with
  the house and the boat.
- **Evidence today:** Studio prototype, measurement of the coupling variants in the work branch. **Needed:** LabVIEW
  publisher, JSON schemas, a demonstration lab.
- **Willingness to pay (hypothesis):** low for licenses, high for joint funding applications and workshops.
- **Role:** credibility, co-development, papers.

### 4.9 Energy advisors and homeowners

- **Personas:** an energy advisor from the dena expert list (22,800 entries); homeowners with oil and wood heating
  (4.7 million oil boilers, 11.7 million single-room fireplaces).
- **Pain points:** The GModG changed the rules again in July 2026. Owners have more freedom of choice, but also more
  uncertainty. People distrust savings promises; advice takes time.
- **Jobs to be done:** "Which measure pays off first in my house?" "Will the oil last until March?" "Did the measure
  really achieve anything?"
- **Benefit:** calibration against bills with goodness-of-fit measures (CV(RMSE), NMBE) and an **identifiability
  report** that states openly what cannot be determined from the data; levers as alternative minus reference,
  explicitly modeled; proof only through measured periods before and after the measure
  ([08-HAUS-ZWILLING](../datamodel-2026/08-HAUS-ZWILLING.md)).
- **Evidence today:** On simulated bills, the calibration recovers the heat loss only as H/η and reports hot water as
  not identifiable. The reading error of the dipstick is larger than the signal. This honesty is itself the evidence.
  **Needed:** real bills, a user interface for advisors, DWD retrieval (work branch).
- **Important limit:** OpenTSx does not replace standards-compliant calculations (for example for energy performance
  certificates) or subsidized energy advice.
- **Willingness to pay (hypothesis):** owners low; advisors medium if the time savings are measurable.
- **Role:** narrative anchor and reach; advisors as a later channel.

### 4.10 Boat, motorhome and off-grid owners (showcase)

- **Benefit:** autonomy and unmet demand on the sailing trip; levers: panel area, storage size, Starlink use;
  calibration against logger data ([10-BOOT-ZWILLING](../datamodel-2026/10-BOOT-ZWILLING.md)).
- **Evidence today:** modeled: at about 530 Wh/day, Starlink Mini is the largest consumer; with Starlink Standard,
  about 2.4 kWh are missing over two weeks; on simulated logs, the calibration recovers hidden values to within
  about 1 %.
- **Role:** stories, community, entry point for developers; bridge to manufacturers (section 4.11).

### 4.11 Manufacturers of small energy systems

- **Personas:** product management and support at manufacturers of power stations, home storage batteries and
  off-grid systems.
- **Pain points:** the customer question "What size do I need?", support cases ("Why was the battery empty?"),
  product planning without field data.
- **Benefit:** sizing as a what-if, calibrated against (approved) fleet logs; replay for support and warranty cases;
  embeddable under Apache 2.0.
- **Evidence today:** boat twin with power station. **Needed:** device connections, a manufacturer pilot.
- **Willingness to pay (hypothesis):** medium; embedding, support or a development contract.

---

## 5. Prioritization

The assessment is qualitative (high, medium, low) and reasoned; it must be checked in conversations.

| Segment | Problem pressure | Fit with today's state | Data access | Sales cycle | Willingness to pay | Leverage (reference, multiplier) |
|---------|------------------|------------------------|-------------|-------------|--------------------|----------------------------------|
| Cluster wind + H2 + marketing (4.1 to 4.3) | high | **high** (S1 is exactly this case) | medium (in preparation) | long | high | **high** |
| Municipal utilities (4.4), with energy communities (4.6) | high | medium | medium | long | medium | **high** |
| Research and labs (4.8), with engineering consultancies (4.7) | medium | high | high | short to medium | low to medium | high |
| Industry (4.5) | high | low | medium | long | medium to high | medium |
| Energy advisors and homeowners (4.9) | medium | high | high | short | low | medium |
| Manufacturers of small systems (4.11) | medium | medium | medium | medium | medium | medium |
| Boat and off-grid (4.10) | low | high | high | short | low | low (reach) |

### The three most important segments and their benefit

1. **Cluster of wind farm, electrolyzer, storage, offtaker and marketing.**
   - Benefit: The coordination potential is quantified; causal chains and countermeasures become visible before the
     intervention; the replay gives all parties the same facts.
   - Rationale: Scenario S1 is exactly this case, a project setting is in preparation, and a success would be the
     strongest reference.
2. **Municipal utilities, and through them energy communities.**
   - Benefit: an open cluster twin across business lines and contracts, operated in-house, complementing existing
     grid platforms; provenance per value where metering is sparse.
   - Rationale: about 1,600 VKU member companies, new obligations and products (Section 14a, energy sharing, heat
     planning) and a strong wish for data sovereignty.
3. **Research and labs, with engineering consultancies.**
   - Benefit: reproducibility, provenance and an open reference for the question "tightly or loosely coupled".
   - Rationale: quick access; papers and funded projects build credibility and allies before production customers
     exist.

The house and the boat carry the **communication**: they make the method understandable in one minute (Part 3).

---

## 6. Evidence today and needed

| Evidence | Today | Needed for phase 1 | Needed for phase 2 |
|----------|-------|--------------------|--------------------|
| Scenarios | H2 chain, house, boat on synthetic or simulated data | one scenario on real data (cluster stage A or the owner's house) | two pilot customers with a final report |
| Replay | episode replay verified on KafScale; twin runs bit-identical | twin topics on KafScale | replay used in a real dispute or audit |
| Federation | variant L in the work branch, simulated | merge to `master`, runs with real processors | operation across two owners |
| Provenance | provenance ladder implemented | measured share per KPI in the report | a customer uses provenance in a decision |
| Interfaces | Modbus (tested against the simulator), DWD (work branch) | real meter register map, market data | IEC 104, OPC UA |
| Security | approval principle as a concept | security concept for the return path | external review |
| Publication | concept documents, code, tests | preprint "Digital Twins on Logs" | peer-reviewed publication |

---

## 7. Cross-cutting objections and answers

| Objection | Answer |
|-----------|--------|
| "We have seen digital twins before. Lots of graphics, little benefit." | OpenTSx starts with a number, not with a graphic: the coordination potential from your data (stage A), with provenance shares. |
| "Open source is a risk. Who is liable, who maintains it?" | Code and formats stay yours, even without us. Operation and support can be agreed as a service. The dependence is lower than with proprietary platforms (see the AWS discontinuations, Part 1, section 4.6). |
| "You have no production customer." | Correct. That is why the entry is a limited stage with a value gate, and all results can be recalculated. |
| "How accurate is the model?" | As accurate as the calibration shows. We report goodness-of-fit measures and quantities that are not identifiable, instead of hiding them. |
| "May the twin control our plant?" | Only after approval, and local protection logic always takes precedence. The normal case is advising, not controlling. |
| "Our data does not leave our premises." | It does not have to: on-premises operation; in the loose variant, the parties share only ports. |

---

## 8. Business models

| Model | Description | Advantages | Disadvantages |
|-------|-------------|------------|---------------|
| **Services around open source** | fixed-price packages per stage (A: potential report, B: shadow and replay, C: causal chains, D: optimization), wiring plan engineering, integration | immediate revenue, learning from the customer, fits Apache 2.0 | scales with person-days; dependence on the owner |
| **Open core** | an open-source core, paid extensions (for example multi-tenancy, federation in operation, IEC 104/OPC UA connectors, security functions) | scalable, clear license revenue | strains the trust of the community; boundary hard to draw; the Apache 2.0 core allows forks |
| **Operated twin (managed twin)** | operation per site or cluster as a subscription | recurring revenue; low entry barrier for customers | operational responsibility, information security, liability |
| **Training and enablement** | courses for engineering consultancies, municipal utilities, labs; workshops "Twin in a day" | low risk, builds up community and partners | small volumes |
| **Research cooperation without certification claims** | code stays Apache 2.0; offer: joint funding applications, supervision, co-authorship | credibility, financing of development | slow, administrative effort, little revenue |
| **Partner and vendor embedding** | integrators and vendors use OpenTSx in their own offerings; support or development contracts | reach | dependence on partners; the brand becomes less visible |

**Recommendation:** In phases 0 and 1, **services plus research cooperation**, with stage A as a clearly defined
fixed-price package. For phase 2, the **operated twin** with a support subscription is planned. Open core only for
clearly operational functions and only once a community exists. A trademark policy for the name "OpenTSx" should be
in place before partner offers (trademark search **not done**).

---

## 9. Sources (retrieved 2026-10-12)

All figures and rules are backed by a link in section 3. Project basis:
[07-DIGITAL-TWIN-BSP](../datamodel-2026/07-DIGITAL-TWIN-BSP.md),
[08-HAUS-ZWILLING](../datamodel-2026/08-HAUS-ZWILLING.md),
[09-STUDIO-LABVIEW-STEUERPULT](../datamodel-2026/09-STUDIO-LABVIEW-STEUERPULT.md),
[10-BOOT-ZWILLING](../datamodel-2026/10-BOOT-ZWILLING.md),
[TASK-011](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md),
[`opentsx-twin`](../../opentsx-twin/README.md). Competition and research: [Part 1](01-MARKTRECHERCHE-DIGITAL-TWINS.md).
