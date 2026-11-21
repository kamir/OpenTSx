# OpenTSx: Marketing campaign "Compute first, then switch"

> Status: **campaign draft for decision**, as of 2026-10-12. Part 3 of the business documents
> ([Overview](README.md)); builds on [Part 1: Market research](01-MARKTRECHERCHE-DIGITAL-TWINS.md) and
> [Part 2: Customers and benefits](02-KUNDEN-UND-NUTZEN.md). Trade fair dates were checked on 2026-10-12 (section 8.4).
> Budget and target values are **planning assumptions** and are to be checked against quotes or baselines before
> commissioning.

---

## 0. The campaign on one page

| Field | Content |
|------|--------|
| **Campaign line** | "Compute first, then switch." |
| **Goal (90 days, 2026-10-19 to 2027-01-17)** | Establish credibility; win one signed stage A pilot from qualified conversations; submit the preprint "Digital Twins on Logs" |
| **Target groups** | 1) cluster of wind farm, electrolyzer, storage and power marketing; 2) municipal utilities, and through them energy communities; 3) research, labs and engineering consultancies. Narrative anchors: house and boat |
| **Core message** | OpenTSx makes the interplay of coupled energy systems visible and decisions testable before they are made: open, traceable and with the provenance of every number |
| **Offer** | Stage A potential report (fixed price, without simulation, with a value gate); research cooperation; open source quickstart |
| **Phases** | 0 Credibility (weeks 1 to 6) · 1 Lighthouses (weeks 4 to 13, continued until Q2 2027) · 2 Scaling (2027: E-world, Hannover Messe, The smarter E, HUSUM Wind) |
| **Channels** | LinkedIn (the owner's profile as the main voice), GitHub and open source communities, direct outreach, trade fair visits (Hamburg, Vienna), science, trade media |
| **Budget** | Tier "low": own work plus a low four-digit non-staff amount; tier "medium": plus video, design, ads, a trade fair presence, a low to mid five-digit amount (assumptions, section 10) |
| **KPIs (target assumptions)** | 12 qualified first conversations, 3 pilot proposals, 1 signed pilot, 2 lab partners with a letter of intent, 1 preprint |
| **Main risk** | Overpromising. Remedy: the claims guide (section 4) and an honesty box "today · in progress · planned" on every medium |
| **Decisions for the owner** | Category name, budget tier, outreach to the pilot partners, approval of the paper and the posts, merge of the work branches |

---

## 1. Positioning

### 1.1 Positioning statement

**German version (translated).** For operators of coupled energy systems, from the wind farm with an electrolyzer
through the municipal utility to the research lab, who must make and justify decisions across asset and ownership
boundaries, OpenTSx is the **open decision twin on a data log**. It plays a decision through before it is made. It
shows for every number whether it is measured, reported, forecast, estimated or synthetic. And it makes every run
reproducible bit for bit. Unlike the platforms of large vendors, OpenTSx is vendor-neutral and licensed under Apache
2.0, runs on open logs and table formats and lets each party operate its own part itself.

**English version.** For operators of coupled energy systems, from a wind farm with an electrolyzer to a municipal
utility or a research lab, who must make and justify decisions across asset and ownership boundaries, OpenTSx is the
**open decision twin on a data log**. It plays a decision through before it is taken, shows for every number whether it
was measured, reported, forecast, estimated or synthetic, and makes every run reproducible bit for bit. Unlike the
platforms of large vendors, OpenTSx is vendor-neutral and Apache-2.0 licensed, runs on open logs and table formats,
and lets each party operate its own part.

### 1.2 Category name

| Candidate | Strength | Weakness | Use |
|----------|--------|----------|---------|
| **Decision twin** | states the benefit; the German term is immediately understandable to German speakers | the term may already be in use (**not checked**); must not sound like "AI decides" | **Market communication (recommendation)** |
| **Digital Twins on Logs** | technically precise; title of the paper; appeals to developers | unwieldy for business departments | Paper, GitHub, developer communities (**recommendation, technical**) |
| Cluster twin | fits the case of wind plus H2 | too narrow for house, boat, lab | Cluster segment |
| Coordination twin | emphasizes the coordination potential | abstract | Internal, method |
| "Twin with guarantee of origin" | emphasizes trust | **do not use**: "guarantee of origin" is already a defined term in energy law (certificates for electricity from renewable energy in the register of the German Environment Agency, [UBA](https://www.umweltbundesamt.de/themen/klima-energie/erneuerbare-energien/herkunftsnachweisregister-hknr)) | none |

**Recommendation:** "decision twin" for the market, "Digital Twins on Logs" for technology and science. Before use,
carry out a trademark and name search (DPMA, the German Patent and Trade Mark Office; EUIPO; web); it was not done for
this document. Always say "provenance" or "provenance class", never "guarantee of origin".

### 1.3 Taglines

| German line (translated) | English line |
|---------|----------|
| **Compute first, then switch.** (campaign line) | **Decide before you switch.** |
| Every number with its provenance. | **Every number knows where it came from.** |
| What if: before it happens. | What if: before it is. |
| The interplay made visible. The decision made provable. | See the interplay. Prove the decision. |
| Computed in the open. Reproducible bit for bit. | Digital twins on logs: open, replayable, explainable. |

**Recommendation:** in German-language channels, use the German original of the campaign line (translated above as
"Compute first, then switch."); for English channels and GitHub, use "Every number knows where it came from."

---

## 2. Message house

**Roof (core message):** OpenTSx makes the interplay of coupled energy systems visible and decisions testable before
they are made: open, traceable and with the provenance of every number.

| | Pillar 1: Computation, not gut feeling | Pillar 2: Trust through provenance | Pillar 3: Reproducible to the bit | Pillar 4: Open and federated |
|---|---|---|---|---|
| **Message** | The same building blocks compute the present, the alternative and the future. | Every number says where it comes from. Modeled is not proven. | The same run, the same result, the same hash. | Each party operates its own part, only ports are shared. Local safety takes precedence. |
| **Evidence today** | H2 chain S1 with what-if and forecast; nine house levers; boat levers; coordination potential as an evaluation principle | provenance ladder with taint propagation; calibration openly reports hot water as "not identifiable" | chained state hash; episode replay tested against KafScale v1.6.0 and Apache Kafka 3.9.1; 2.18 bytes per value measured | Apache 2.0; Kafka protocol, Iceberg, OpenMetadata; variant L bit-identical to T (work branch, simulated) |
| **Reasons to believe** | atom contract with model, views, dynamics and an exchangeable policy; layered BSP, deterministic | the rule "modeled ≠ proven" is anchored in the method and the user interface | deterministic ids, checksums, run id from plan, inputs, interventions and version | measured design rule "loops in one processor, BSP in between"; DevOps guide for k3s |

**Foundation (values):** Honesty before effect · Safety stays local · Data sovereignty stays with the owner · Open code
and formats.

**General reasons to believe:** a public code base with CI and tests; a line of research since Hadoop.TS
([IJCA 2013](https://www.ijcaonline.org/archives/volume74/number17/12974-0233)) and on context networks from
time series ([PLOS ONE 2015](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0141892)); openly
communicated maturity ([DevOps: today vs. planned](../devops/today-vs-planned.md)).

**Tone of voice:** factual, concrete, the language of engineers. Numbers only with their provenance. No superlatives,
no buzzwords without substance. Rather an honest limit than a shiny promise.

---

## 3. Elevator pitches per segment

**Cluster of wind farm, electrolyzer, storage and power marketing.** Your wind farm, the electrolyzer next door, the
storage and the direct marketer decide separately today. From your existing data, OpenTSx calculates what this
separation costs and what coordination would bring, with a provenance label for every number. If the offtaker closes a
valve, you see in advance when the electrolyzer curtails and what that means for the schedule. We start with a
potential report without simulation that ends at a clear value gate.

**Direct marketer.** Your forecast sees the wind farm, but not the electrolyzer and the offtaker behind it. In shadow
operation, your schedule runs against the actual generation, including the deviation and the costs per quarter hour.
This lets you rehearse a new supply arrangement before the metering concept is switched over. Your building block can
run on your premises; only ports are shared.

**H2 project.** Minimum load, ramps, line pressure: your electrolyzer depends on the decisions of others. OpenTSx warns
you how long the buffer will still last and plays the countermeasure through before you intervene. A replay with
provenance per value makes the operating policy and the power procurement traceable for all parties involved. Local
protection logic always has the last word.

**Municipal utility.** You run grid, retail, generation and heat, often in separate systems. OpenTSx is an open
cluster twin for your organization: it plays through operating policies, tariffs and energy sharing products and shows
which number is measured and which is estimated. Your grid platform stays; its results become inputs. The data stays
with you.

**Energy community.** Energy sharing has been possible since June 2026, but trust only develops when every member can
trace who received how much electricity from which source. OpenTSx makes exactly that visible and calculates in advance
what a storage system or new members would change. Open and free to use, preferably through your municipal utility.

**Industrial site.** PV, CHP unit, storage and production: where is the potential if everything runs in coordination?
OpenTSx quantifies it against your current operation, with the same data and the same information, before you invest.

**Engineering consultancy and integrator.** Stop building a new data pipeline for every project. OpenTSx supplies
standard building blocks, wiring plans as YAML and reproducible runs that you may embed in your services. Apache
2.0, no license trap.

**Research and lab.** Your test bench data deserves provenance and repeatability. OpenTSx stores episodes with
checksums, replays them bit-identically and connects lab and model. And we are looking for partners who check our
measurement on tightly and loosely coupled twins against real test benches.

**Energy advisor.** A house model, calibrated against your clients' bills, that openly says what it does not know.
Every lever appears as a modeled alternative to the reference; it is proven only with a measured before and
after. A tool for explaining, not a substitute for standards-compliant calculations.

**Homeowner.** Which measure pays off first in your house, and will the oil last until March? The house twin
calculates it from your bills. It also says honestly how certain the number is.

**Boat and off-grid.** Will the power station last for a two-week sailing trip with Starlink? The boat twin computes
autonomy and unmet demand and learns from your logger data.

**Manufacturers of small energy systems.** "What size do I need?" and "Why was the battery empty?" are your most
expensive support questions. A calibrated twin answers both traceably and can be embedded under Apache 2.0.

---

## 4. Claims guide

Principle: **Modeled is not proven.** Every number in our communication needs a provenance and, where it is
modeled, that word.

| Topic | How we say it | Not like this | Why |
|-------|-----------------|----------|-------|
| Maturity | "Proof of concept on synthetic and simulated data; a pilot is in preparation." | "production-ready", "in use at …", "proven in practice" | no production customer |
| Savings | "modeled potential: −151 l of heating oil a year at −1 K (example house, invented assumptions)" | "saves 15 %", "guaranteed savings" | evaluation principle; risk of misleading advertising |
| Proof | "proven only after a measured before and after, weather-adjusted, larger than the calibration uncertainty" | "proven", "demonstrated" for model values | method ([08, section 8](../datamodel-2026/08-HAUS-ZWILLING.md)) |
| Replay | "repeatable bit for bit with the same wiring plan, the same inputs, the same intervention log and the same code version" | "tamper-proof", "audit-proof", "forgery-proof" | legal terms; not checked |
| Control | "proposes operating policies; the return path is used only after approval; local protection logic takes precedence" | "controls your plant automatically", "real-time control" | safety principle; not implemented |
| Federation | "measured in the work branch on simulated wall-clock time" | "federated operation tested in practice" | status per Part 1, section 3 |
| Performance | "up to factor 6 more throughput than BSP without colocation, measured in simulated ticks" | "6 times faster than Kafka Streams" or similar | no such comparison measured |
| Competition | compare categories, name sources, "not found in the sources checked" | "No vendor can …", disparaging statements about named companies | fairness; have comparative advertising checked legally beforehand |
| Provenance | "provenance per value", "provenance class" | "guarantee of origin" | the term is already defined in energy law |
| Security, certificates | "a security concept for the return path is in progress" | "IEC 62443 compliant", "KRITIS-ready", "certified" | no evidence |
| AI, quantum | "classical optimization; quantum computing only as an object of measurement" | "AI-powered" without substance, "quantum advantage" | [06](../datamodel-2026/06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md): no demonstrated advantage |
| House | "example house; no substitute for an energy performance certificate or subsidized energy advice" | "energy advice", "GModG compliant" | liability, boundaries |
| Partners | roles only ("an electrolyzer operator") until written approval is given | names of conversation or project partners | confidentiality ([07, header](../datamodel-2026/07-DIGITAL-TWIN-BSP.md)) |
| Open source | "Apache 2.0; operation and support as a service" | "free including support" | managing expectations |
| Data | "in-house operation possible; only ports are shared" | "GDPR-certified" | no such certification of the product exists |

**Approval process:** Every public number is checked against its source in the repository (document and section).
Comparative statements about named competitors go to a legal review before publication. This document is not legal
advice.

---

## 5. Campaign architecture

### 5.1 Phases

| Phase | Period | Goal | Work packages | Exit criterion |
|-------|----------|------|---------------|--------------------|
| **0: Credibility** | weeks 1 to 6 (2026-10-19 to 2026-11-29) | A stranger can run the H2 case in 30 minutes and understands what works today | review and merge of the work branches (loose coupling, DWD (German Meteorological Service), explorer, repository hygiene with LICENSE and community files); quickstart "H2 chain in 5 minutes"; three demo videos; landing page; honesty page; white paper and preprint draft; claims guide made binding | two outsiders complete the quickstart without help; landing page online; white paper approved |
| **1: Lighthouses** | weeks 4 to 13, continued until Q2 2027 | one signed pilot, two lab partners, preprint | pilot A cluster, pilot B municipal utility, pilot C lab (section 5.2); the owner's house with real bills as a story | pilot A signed; preprint submitted; letters of intent from the labs |
| **2: Scaling** | from Q1 2027 | use the reference, open up channels | case study from pilot A (only with approval); partner program for integrators; "operated twin" offer; trade fairs 2027 (section 8.4) | two paying customers; one integration partner |

### 5.2 Lighthouse pilots

| Pilot | Scope | Data | Result | Success criterion (value gate) |
|-------|--------|-------|----------|---------------------------------|
| **A: cluster wind + H2 + marketing** | stage A, then stage B (shadow and replay, PPA shadow operation S3) | grid operator meters over Modbus, the marketer's schedules and forecasts, DWD, market data; later IEC 104 to the electrolyzer operator | potential report with the order of magnitude in EUR/a and provenance shares | gate A: order of magnitude of the added value reliable enough for the decision on stage B |
| **B: municipal utility** | neighborhood or energy sharing community in shadow operation; two product variants as what-if | smart meter and EDM (energy data management) exports, generation data, tariffs | shadow run, variant comparison, provenance shares | the municipal utility decides on a product or an expansion based on the comparison |
| **C: lab** | LabVIEW bridge or co-simulation experiment tight versus loose | test bench data, test vectors | shared data set, experiment, paper contribution | experiment reproduced; joint publication or joint funding application |

---

## 6. 90-day plan with weekly content calendar

Cadence: two LinkedIn posts per week (Tuesday and Thursday), a GitHub release or a discussion every two weeks, a video
every two to three weeks. Holidays deliberately quiet.

| Week | Period (CW) | Focus | LinkedIn | GitHub / community | Direct outreach / appointments | Result |
|-------|---------------|-------------|----------|--------------------|---------------------------|----------|
| 0 | 2026-10-12 to 2026-10-18 (42) | Preparation | none | quickstart draft | target list of 30 organizations in the three segments; measure reach baselines; plan the trade fair visit to Hamburg | target list, baselines |
| 1 | 2026-10-19 to 2026-10-25 (43) | Launch: H2 chain | **Post 1** "The valve closes." (section 13); trade fair note | README section "H2 chain in 5 minutes" | **Hydrogen Technology World Expo, Hamburg, 2026-10-20 to 2026-10-22**: conversations with project operators and integrators | 5 trade fair conversations |
| 2 | 2026-10-26 to 2026-11-01 (44) | Cluster | video 1 (H2 chain, 3 min); short post "Coordination potential in one sentence" | release with video link | pilot A: meeting on the stage A scope | pilot A meeting |
| 3 | 2026-11-02 to 2026-11-08 (45) | Provenance | "Every number with its provenance" (Studio excerpt); question to the community | discussion "Provenance ladder" | write to 5 municipal utilities (digitalization, energy data) | 2 meetings with municipal utilities |
| 4 | 2026-11-09 to 2026-11-15 (46) | Grids and municipal utilities | "Why a twin on a log?"; trade fair note from Vienna | landing page online | visit **Enlit Europe, Vienna, 2026-11-10 to 2026-11-12** | landing page, trade fair conversations |
| 5 | 2026-11-16 to 2026-11-22 (47) | House | **Post 2** "−151 liters of heating oil. Modeled."; video 2 (house) | discussion "Calibration against bills" | ask the Deutsches Energieberater-Netzwerk (DEN) for a feedback round | 1 feedback round agreed |
| 6 | 2026-11-23 to 2026-11-29 (48) | Research | **Post 3** "Feedback loops belong in one processor." | measurement program documented (after the merge) | write to 6 university chairs and institutes (energy informatics, co-simulation) | preprint draft; review of phase 0 |
| 7 | 2026-11-30 to 2026-12-06 (49) | Cluster in depth | webinar invitation and follow-up report | link the webinar recording | **Webinar** "The cluster twin in 30 minutes" (live demo); stage A pilot proposal to pilot A | pilot A proposal |
| 8 | 2026-12-07 to 2026-12-13 (50) | Boat | boat story with video 3 (Starlink, autonomy) | discussion "Importing logger data" | workshop at a municipal utility (pilot B); approach the sailing community | workshop held |
| 9 | 2026-12-14 to 2026-12-20 (51) | Honest year in review | "What works in 2026, and what does not yet" | preprint on arXiv (after approval) | letter of intent from a lab | preprint, 1 lab partner |
| 10 | 2026-12-21 to 2026-12-27 (52) | quiet | one short post | maintain issues | none | none |
| 11 | 2026-12-28 to 2027-01-03 (53) | quiet | none | none | Q1 planning; prepare meeting requests for E-world | Q1 plan |
| 12 | 2027-01-04 to 2027-01-10 (1) | Method | "The one number that separate operation costs" (coordination potential explained) | release | request E-world meetings with municipal utilities and marketers; define the scope of pilot B | 4 E-world meetings |
| 13 | 2027-01-11 to 2027-01-17 (2) | Results | results of the 90 days (pilot only as a role, if approved) | roadmap update | review of all KPIs; preparation for boot (2027-01-23 to 2027-01-31) and E-world (2027-02-16 to 2027-02-18) | decision on phase 2 |

---

## 7. Assets

| Asset | Content | Status | Effort (estimate) | Purpose |
|-------|--------|--------|---------------------|-------|
| H2 chain video (3 min) | screen recording of `bin/opentsx twin` and Studio; chain, what-if, forecast | to be created | 1 to 2 days | cluster, H2, marketers |
| House video (3 min) | reference year, levers, calibration, "not identifiable" | to be created | 1 to 2 days | house, energy advisors, reach |
| Boat video (3 min) | sailing trip, Starlink, autonomy, logger data | to be created | 1 to 2 days | reach, manufacturers |
| Smart Topics Studio | statistics, forecast and what-if, node graph for all three systems | available: [prototype](../prototypes/smart-topics-studio.html) | set up hosting | demo on the landing page |
| Asset explorer | map, floor plan, deck plan, inventory | available in the work branch `opentsx/2026-explorer` (`docs/prototypes/anlagen-explorer.html`), usable after the merge | none | demo, trade fair |
| White paper "Digital Twins on Logs" (8 to 10 pages) | atoms, three operating modes, provenance, replay, tight versus loose, design rule, limits | to be written, basis [07, section 10](../datamodel-2026/07-DIGITAL-TWIN-BSP.md) | 5 to 8 days | research, technical decision makers |
| Preprint | scientific version with reference to the iteration bound (Part 1, section 6.4) | to be written | with the white paper | credibility |
| House and boat stories | one page each, text from [08](../datamodel-2026/08-HAUS-ZWILLING.md) and [10](../datamodel-2026/10-BOOT-ZWILLING.md) | to be edited | 1 day | narrative anchors |
| Interactive calculators | (a) **coordination calculator**: generation profile, electrolyzer, offtaker → overlap and direct supply share; (b) "Will the oil last?"; (c) "Autonomy on board". Each with sample data in the browser, **every number with a provenance mark**, note "modeled" | idea | 3 to 6 days per calculator | landing page, lead generation |
| One-pager per top segment | problem, benefit, stage A offer, honesty box | to be created | 1 day per segment | direct outreach, trade fair |
| Stage A offer sheet | content, required data, duration, result, value gate | to be created | 0.5 days | pilot |
| Honesty page | "today · in progress · planned" | basis available: [today-vs-planned](../devops/today-vs-planned.md) | 0.5 days | trust |
| Slide deck (10 slides) | for talks and the webinar | to be created | 1 to 2 days | webinar, trade fair |

---

## 8. Channels

### 8.1 LinkedIn

The main voice is the owner's personal profile (in German), complemented by a project page. Formats: a story with
one number, a data excerpt with provenance, an honest learning note, a short demo clip. Comments on target accounts
(municipal utilities, H2 projects, energy informatics) are part of it. Paid reach only in budget tier "medium" and
targeted (municipal utilities, H2).

### 8.2 GitHub and open source communities

README with quickstart and honesty box, GitHub Discussions, "good first issues", release notes with videos. Presence
where the building blocks come from: KafScale, Apache Iceberg, OpenMetadata; co-simulation (mosaik, HELICS); energy
system modeling. Contributions are technical notes, not advertising.

### 8.3 Trade media, science, networks

- **Trade media:** guest articles only once there is a pilot result, for example ZfK, energate messenger, pv magazine,
  ERNEUERBARE ENERGIEN (terms **not checked**).
- **Science:** arXiv preprint; submissions to ACM e-Energy (the 2027 call could not be found; in 2026 the deadline was
  2026-01-22, [overview](https://www.myhuiban.com/conference/1637?lang=en-us)), IEEE/ACM DS-RT (2026: 2026-09-16 to
  2026-09-18 in Padua, 2027 not yet announced, [2026 call](https://lists.mcs.anl.gov/pipermail/hpc-announce/2026-April/011948.html)),
  DACH+ Energy Informatics (2026: 2026-09-21 to 2026-09-23 in Linz, [Energieinstitut Linz](https://energieinstitut-linz.at/en/15th-dach-conference-on-energy-informatics/)).
  The dates for 2027 are still to be checked.
- **Energy advisors:** Deutsches Energieberater-Netzwerk (DEN) e. V.
  ([DEN](https://deutsches-energieberaternetzwerk.de/wp-content/uploads/2026/04/6PM26_Energieeffizenzexpertenliste-Light.pdf));
  other associations **not checked**. Target size: 22,800 entries in the expert list of dena (German Energy Agency).
- **Sailing and off-grid community:** boot Düsseldorf, sailing magazines and forums (**not checked**), communities around
  power stations and Victron.

### 8.4 Trade fairs and events (checked on 2026-10-12)

| Date | Event | Location | Relevance | Recommendation | Source |
|--------|---------------|-----|----------|------------|--------|
| 2026-10-20 to 2026-10-22 | Hydrogen Technology World Expo (also "Hydrogen Technology Expo Europe") | Hamburg Messe | H2 projects, electrolyzers | visit (week 1) | [WKO](https://www.wko.at/veranstaltungen/577188a4-68c8-49c4-9935-002b83c92c98), [Bremen](https://www.bremen-innovativ.de/en/veranstaltungen/hydrogen-technology-worldexpo-2026/) |
| 2026-11-10 to 2026-11-12 | Enlit Europe | Messe Wien | grids, municipal utilities, digitalization | visit (week 4) | [Canadian Trade Commissioner Service](https://www.tradecommissioner.gc.ca/en/trade-events-training/2026/10/enlit-europe.html) |
| 2027-01-23 to 2027-01-31 | boot Düsseldorf | Düsseldorf | boat story, manufacturers of small systems | optional visit | [boot.com](https://www.boot.com/en/Media_News/Press/Press_Material/Press_Releases/Cast_off_for_boot_D%C3%BCsseldorf_2027) |
| 2027-02-16 to 2027-02-18 | E-world energy & water | Essen | municipal utilities, marketers, energy data | visit; consider a talk or a joint stand | [E-world](https://e-world-essen.com/downloads/infobroschuere/) |
| 2027-04-05 to 2027-04-08 | Hannover Messe | Hanover | industry, energy, twins | visit | [Hannover Messe](https://www.hannovermesse.de/en/for-visitors/opening-hours/) |
| May 2027 (estimated, not confirmed) | Berliner Energietage | Berlin | municipalities, politics | check the date | [messen.de](https://www.messen.de/de/15715/berlin/berliner-energietage/info) |
| 2027-06-08 to 2027-06-10 | The smarter E Europe | Munich | storage, PV, EMS, manufacturers | visit | [The smarter E](https://www.thesmartere.de/for-exhibitors/application) |
| 2027-09-14 to 2027-09-17 | HUSUM Wind | Husum | wind operators, H2 | consider exhibiting with a pilot result | [HUSUM Wind](https://www.husumwind.com/en/e/husum-wind-2027.72809) |
| end of 2028-09 (provisional) | WindEnergy Hamburg (the 2026 edition took place from 2026-09-22 to 2026-09-25) | Hamburg | wind | later | [AHK India](https://indien.ahk.de/en/events2/windenergy-hamburg-2026-22-25-september-hamburg-germany) |

---

## 9. Lead funnel and KPIs

| Stage | Metric | Target assumption, 90 days | How measured |
|-------|-----------|---------------------|---------|
| Reach | impressions, new followers, landing page visits | measure the baseline in week 0; set the target after four weeks | LinkedIn analytics, privacy-friendly web analytics |
| Interest | video views, white paper downloads, GitHub stars, forks and clones | baseline plus trend | GitHub Insights, download counter |
| Evaluation | external quickstart runs, issues and discussions by outsiders | 10 runs, 5 external issues or discussions | Discussions, survey after the quickstart |
| Conversation | qualified first conversations | **12** | simple contact list |
| Workshop | workshops or webinar participants from target segments | 3 workshops or 30 participants | registrations |
| Proposal | stage A pilot proposals | **3** | proposal list |
| Closing | signed pilots; letters of intent from labs | **1**; **2** | contracts |
| Science | preprint submitted | **1** | arXiv |

**Qualifying a conversation:** (1) There is a cluster or responsibility for at least two assets or parties. (2) Data
is accessible within four weeks. (3) A decision maker is named. (4) A budget or funding route is visible. At least
three of the four must be met.

---

## 10. Budget (planning assumptions)

| Item | Tier "low" | Tier "medium" |
|--------|-----------------|----------------|
| Own work (owner) | about 1 to 2 days per week for content, conversations, demos | as for "low" |
| Videos | screen recording as own work | professional editing for three videos |
| Design | templates as own work | landing page, one-pagers and white paper layout by a designer |
| Reach | organic | targeted LinkedIn ads (municipal utilities, H2) |
| Trade fairs | visitor tickets and travel (Hamburg, Vienna, Essen) | in addition, a talk or a joint stand at E-world (check the costs) |
| Webinar and workshops | online tool | on-site workshop with catering |
| Translation | own English version | English copy editing |
| **Total non-staff costs (assumption)** | **low four-digit EUR amount** | **low to mid five-digit EUR amount** |

The amounts are rough planning assumptions without quotes. **Recommendation:** start with tier "low". Approve tier
"medium" only when a pilot A is in sight (end of week 7).

---

## 11. Partner strategy

| Partner | Role | Benefit for OpenTSx | Benefit for the partner | Next step |
|---------|-------|--------------------|------------------------|------------------|
| **KafScale** (Kafka-compatible log on S3, Apache 2.0) | log platform | joint demo "twin on an S3 log"; a conformance suite exists | a use case beyond ETL and logs | agree on the demo and a blog post |
| **infinimesh** (IoT platform with device shadow) | device layer for the lab and the on-board computer | return path via `desired` with approval | a use case in energy and labs | use the bridge as pilot C |
| Apache Iceberg, OpenMetadata | data and metadata basis | visibility in their communities | reference application | submit technical notes |
| System integrators (meters, SCADA, IEC 104, OPC UA) | implementation at the customer's site | reach, capacity | a new service offering | define a partner profile, two conversations |
| Universities (energy informatics, co-simulation, control engineering) | research, teaching | credibility, test benches, funding applications | an open reference implementation, data | pilot C, outreach in week 6 |
| TwinEU environment (Fraunhofer, grid operators) | federation | connection to European approaches | a building block for the asset and cluster level | seek contact at the end of the project (2026-12) |
| OpenEMS Association | edge EMS | a complement at the edge; no duplicate development | an evaluation layer above the EMS | seek an exchange |
| Grid platforms (envelio, VENIOS, PowerFactory users) | grid results as ports | complement, not competition | a cluster view for their customers | approach only after pilot B |
| DGRV (German Cooperative and Raiffeisen Confederation), networks of municipal utilities | channel to energy communities | access to citizen energy | a tool for energy sharing | after pilot B |
| Deutsches Energieberater-Netzwerk | feedback, channel | feedback on the house twin | a tool for explaining | feedback round in week 5 |

There are **no** formal partnerships today. Communication about partners only after written consent.

---

## 12. Risks

| Risk | Impact | Countermeasure |
|--------|---------|---------------|
| Overpromising | loss of trust, legal risks | claims guide (section 4), honesty box, approval of every number |
| No production reference | long sales cycles | stage A as a small, bounded entry; research partners as early evidence |
| The owner's capacity | campaign and development compete | budget tier "low", a clear cadence, quiet weeks |
| Bundling by large vendors (Siemens in the H2 segment, E.ON with envelio and gridX) | customers buy solutions from a single source | position OpenTSx as a neutral cluster layer that takes in their results |
| OT security and KRITIS (critical infrastructure) requirements | being excluded by grid operators | Advise, do not control; security concept for the return path before phase 2 |
| Data access depends on partners | pilots are delayed | provenance ladder with estimators and profiles; stage A with existing meter data |
| Political and regulatory changes (GModG, the building modernization law; EEG 2027, the Renewable Energy Sources Act; Section 14a) | messages become outdated | base messages on the method, not on a legal section; state data with its as-of date |
| Free riders in open source | others earn money from the code | services, operation, brand; trademark policy |
| Term confusion around "digital twin" | expectation of "3D graphics" | category name "decision twin"; start with a number, not with a picture |
| Name and trademark conflicts | forced renaming | name and trademark search before the launch (DPMA, EUIPO) |
| Data protection for house and boat data | loss of trust | real data only locally (`config/private/`), consents, data minimization on the landing page |

---

## 13. Three sample posts (LinkedIn)

All numbers come from the repository (source in parentheses, not part of the post). Publish post 3 only after the
merge of the work branch `opentsx/2026-p9-loose`.

### Post 1: Cluster (source: [`opentsx-twin`](../../opentsx-twin/README.md), scenario S1)

> **The valve closes.**
>
> 01:00. The hydrogen offtaker closes its valve. Nothing dramatic, really.
>
> But the pipeline keeps filling up, and the pressure rises. The twin reports: less than one hour of buffer. At
> 40 bar the electrolyzer curtails to protect itself. Its schedule is violated, and the direct marketer suddenly has
> to place about 5 MW more power.
>
> Four parties, four systems, one chain. Nobody did anything wrong, and it still costs money.
>
> With OpenTSx we play this chain through before it happens:
> → What-if: the valve opens again at 02:00, and the shutdown does not happen.
> → Forecast from 01:10 with a pressure-aware policy: no shutdown, no overpressure, but an earlier
> deviation from the schedule instead.
> → Every number shows whether it is measured, reported, forecast, estimated or synthetic.
>
> To be honest: this runs on synthetic data today. The code is open (Apache 2.0), and the case can be recomputed
> with one command.
>
> Do you operate a cluster of wind, electrolysis, storage or direct supply and want to know what separate
> operation costs? We start with a potential report from your existing data, entirely without simulation.
>
> #Hydrogen #WindEnergy #DigitalTwin #EnergyTransition #OpenSource

### Post 2: House (source: [08-HAUS-ZWILLING, sections 7 to 8](../datamodel-2026/08-HAUS-ZWILLING.md))

> **−151 liters of heating oil a year. Modeled.**
>
> That is how much our example house in the Ore Mountains (Erzgebirge) saves when the room temperature drops from
> 21 to 20 °C: built in 1978, oil boiler, wood stove, two occupants. All assumptions are invented.
>
> The word "modeled" is not small print. It is the core.
>
> Our house twin computes heating, hot water and appliances over one year in hourly steps. It is calibrated against
> what owners really have: electricity and oil bills, delivery notes, dipstick readings.
>
> And it says openly what it does not know. On simulated test bills, the calibration recovers the heat loss of the
> house, but not the hot water consumption: the reading error of the dipstick is larger than the signal of a
> summer month. So the report says "not identifiable" instead of giving an invented number.
>
> Three things the twin makes visible:
> → A deeper night setback brings almost nothing in a heavy house.
> → Switching off standby saves electricity, but costs a little more oil in winter.
> → The boiler is oversized; standby and cycling losses are about 4,200 kWh a year.
>
> A saving is proven only when measured periods before and after the measure have been compared on a
> weather-adjusted basis.
>
> A question to energy advisors: would a calibrated twin with an honest statement of uncertainty help you in your
> advisory work, or rather get in the way? I welcome disagreement.
>
> #EnergyAdvice #Heating #BuildingEnergy #DigitalTwin #OpenSource

### Post 3: Research (source: [07, section 10](../datamodel-2026/07-DIGITAL-TWIN-BSP.md), measurement section 10a in the work branch)

> **Feedback loops belong in one processor.**
>
> That is the design rule from our first measurement on tightly and loosely coupled digital twins.
>
> The question: a twin made of many building blocks (wind farm, electrolyzer, pipeline, storage, marketer) can run as
> one deterministic process. Or distributed: each building block where its data and its owner are, coupled only
> through a log with the Kafka protocol. What does distribution cost?
>
> What we measured, on simulated wall-clock time in three scenarios:
> 1. Without staleness the loose variant is bit-identical to the tight one: same run, same hash, even with latency,
> jitter and failures.
> 2. Throughput is limited by the feedback loops, not by the length of the chain.
> 3. Staleness in fast control loops is expensive: up to factor 6 more throughput, but with clear deviations.
> 4. If you put each loop in one processor and couple loosely only in between, you get both: the exact
> result and full pipeline throughput.
>
> This fits the iteration bound from dataflow analysis. As theory, it is therefore not new. What is new is the
> transfer to twins on top of a log: conformance down to the bit and provenance per value.
>
> We are writing this up as a paper ("Digital Twins on Logs") and are looking for labs and university chairs in energy
> informatics, co-simulation and distributed systems that challenge it with real test benches. Code and measurement
> program are open.
>
> #CoSimulation #DigitalTwin #EnergyInformatics #StreamProcessing #OpenSource

---

## 14. Landing page (outline)

1. **Header.** Headline "Compute first, then switch." Subline: "OpenTSx is the open decision twin for coupled
   energy systems: the same building blocks compute the present, the alternative and the future. Every number with its
   provenance, every run reproducible bit for bit." Two calls to action: "Demo in 5 minutes (GitHub)" and "Request a
   potential report".
2. **Problem: "Decided separately, paid for together."** The H2 chain as an animated chain (valve → pressure →
   curtailment → schedule → power marketing) with the note "synthetic data".
3. **How it works.** Atoms and wiring plan; three operating modes (shadow, what-if, forecast); provenance ladder;
   replay; tightly or loosely coupled. One graphic per point, no jargon without an explanation.
4. **Three systems, one toolkit.** Cards for the H2 cluster, the house and the boat with a video, a key number and the
   mark "modeled".
5. **Who it is for.** Tiles for cluster, municipal utilities, research; below them "also for" energy advisors and
   manufacturers.
6. **Honesty box "today · in progress · planned".** Three columns from Part 1, section 3.
7. **Open.** Apache 2.0, Kafka protocol, Iceberg, OpenMetadata; a code block with the quickstart; a link to the
   repository.
8. **Offer.** Stage A potential report: what you get, which data we need, duration, value gate.
   Research cooperation: what we offer.
9. **Knowledge.** White paper "Digital Twins on Logs", concept documents, Studio prototype, interactive calculators
   (section 7).
10. **Frequently asked questions.** Does OpenTSx control my plant? Where does my data run? What does it cost? How
    accurate is the model? What distinguishes OpenTSx from grid twins and APM systems?
11. **Contact and footer.** Contact form with privacy notice, legal notice (Impressum), privacy policy, license.
    Web analytics only with data minimization and with consent where needed.

Technology: static site (for example GitHub Pages), Studio and calculators as static files, no tracking cookies
without consent. Search terms: "digital twin energy", "wind farm electrolyzer coupling", "coordination potential",
"energy sharing twin", "open source energy twin".

---

## 15. Sources (retrieved 2026-10-12)

Trade fair dates and scientific venues: links in sections 8.3 and 8.4. Market figures and rules:
[Part 2, section 3](02-KUNDEN-UND-NUTZEN.md). Competition: [Part 1](01-MARKTRECHERCHE-DIGITAL-TWINS.md). Project
foundations: [07](../datamodel-2026/07-DIGITAL-TWIN-BSP.md), [08](../datamodel-2026/08-HAUS-ZWILLING.md),
[09](../datamodel-2026/09-STUDIO-LABVIEW-STEUERPULT.md), [10](../datamodel-2026/10-BOOT-ZWILLING.md),
[11](../datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md), [DevOps](../devops/README.md),
[TASK-011](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md).
