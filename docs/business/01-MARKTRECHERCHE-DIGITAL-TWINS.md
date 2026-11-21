# OpenTSx: Market research on digital twins and the coupling of live and simulation data

> Status: **research report**, as of 2026-10-12. All sources were retrieved on **2026-10-12** unless stated otherwise.
> Part 1 of the business documents ([Overview](README.md)); Part 2: [Customers and benefits](02-KUNDEN-UND-NUTZEN.md),
> Part 3: [Marketing campaign](03-MARKETING-KAMPAGNE.md).
>
> **What is rated here:** the vendors' own descriptions in primary sources (product pages, documentation, press
> releases), supplemented by standards and technical literature. No products were tested and no conversations with
> vendors took place. Vendor statements are claims by the vendor, not independent evidence. Whatever could not be
> checked against a primary source is marked as **not verified**. A "not found" is a negative finding in the sources
> checked, not proof that something does not exist.

---

## 1. Summary

The owner's question is: *"Do vendors of digital twins offer a direct coupling of live data and simulated data as in
our model?"*

**Answer: Yes. The coupling itself is widespread, and at several vendors it is more mature than at OpenTSx. By
contrast, we did not find the combination OpenTSx pursues in the sources checked.**

1. **Live-driven simulation is state of the art**, but in each case within one domain and one vendor stack.
   Grids: online state estimation at the control system (DIgSILENT PowerFactory via OPC, ETAP) and distribution grid
   twins built from smart meter and GIS data (Siemens Gridscale X, envelio, VENIOS). Process industry: AVEVA Process
   Simulation with live data from the PI System, Cognite Simulator Integration. Hydrogen: Siemens Hydrogen Performance
   Suite with a physics-based process twin and real-time optimization, announced on 2026-10-08 for a 100 MW plant.
   Machines and components: Siemens Simcenter Executable Digital Twin with a Kalman filter, Ansys Twin Builder with
   calibration against sensors. Wind: DNV WindGEMINI (SCADA plus aeroelastic lifetime calculation, as of 2017).
2. **What-if and forecast from the same model** are also offered by several vendors. ETAP lists state estimation,
   *Predictive Simulation*, what-if and event playback on one platform. This is the closest counterpart to the three
   operating modes of OpenTSx, but it is limited to electrical grids.
3. **Provenance per value:** quality flags per measured value are standard (OPC UA StatusCodes, IEC 61850 quality with
   `source = process | substituted`). We did not find an end-to-end provenance class per value (measured, reported,
   forecast, estimated, synthetic) that is propagated through a model chain down to the KPIs in any of the product
   documentation checked.
4. **Reproducible replay:** event playback (ETAP), histories in historian systems and versioned models and routines
   (Cognite) exist. A run of the whole twin that is repeatable bit for bit, with an identifier derived from the plan,
   inputs, interventions and code version, is not a documented product feature in the sources checked.
5. **Openness:** the commercial platforms are proprietary; some support FMI. Open building blocks (mosaik, HELICS,
   pandapower, PyPSA, OpenEMS, Eclipse Ditto, Eclipse BaSyx, OpenTwins) each cover parts. In the sources checked, none
   of them combines live-coupled operation, provenance per value and replay on a log.
6. **Small to large:** the offerings are tailored to one size class and domain: grid, process plant, turbine or house.
   A shared building-block catalog from the boat to the cluster of wind farm and electrolyzer is unusual. At OpenTSx,
   however, it is shown only as a proof of concept.
7. **Across ownership boundaries:** data spaces (Catena-X with the Asset Administration Shell and Dataspace Connector)
   and the research project TwinEU (a federated ecosystem of grid twins, running until 2026-12) address data
   sovereignty and exchange. VPP platforms control assets of many owners, but centrally. Outside co-simulation
   research, we did not find a federated execution of a shared twin with results proven equal to those of tight
   coupling. At OpenTSx it exists only in the work branch and on simulated wall-clock time (section 3).
8. **The market is consolidating.** PTC sold ThingWorx and Kepware to TPG (2026-03), Hexagon spun off Octave
   (2026-05), Emerson fully owns AspenTech (2025-03) and NI with LabVIEW (2023-10), Bosch intends to acquire Uptake
   (2026-03), E.ON owns envelio and gridX. AWS discontinued several IoT services in 2025/2026; IoT TwinMaker itself is
   not affected in the AWS announcements checked.

**Conclusion:** OpenTSx should not use "We couple live data and simulation" as an advertising claim. Others can do that,
often better. What holds up is the combination: **the same building blocks in three operating modes with an exchangeable
operating policy, provenance per value down to every KPI, bit-identical replay on an open log, federable execution
across ownership boundaries, open under Apache 2.0, and the evaluation principle "coordinated minus reference, same
information".** Today this combination is a proof of concept on synthetic and simulated data, without production
customers (section 3).

---

## 2. Terms and assessment criteria

### 2.1 What "direct coupling" means

| Source | Key statement | Relevance to the question |
|--------|---------------|---------------------------|
| ISO/IEC 30173:2023 | A digital twin is "the digital representation of a target entity with data connections that enable convergence between the physical and digital states at an appropriate rate of synchronization" ([ISO](https://www.iso.org/standard/81442.html)) | Coupling is part of the definition; the synchronization rate is part of the design |
| Kritzinger et al. 2018 | Distinction between *Digital Model*, *Digital Shadow* and *Digital Twin* by direction and automation of the data flow ([DOI 10.1016/j.ifacol.2018.08.474](https://doi.org/10.1016/j.ifacol.2018.08.474)) | Shadow = automatic flow real → digital; Twin = in both directions |
| National Academies (2024 report) | The bidirectional interaction between virtual and physical is central, automated or with a human in the loop; plus VVUQ (verification, validation, uncertainty quantification) ([NASEM](https://www.nationalacademies.org/news/2023/12/new-report-urges-multiagency-action-to-support-potentially-transformative-digital-twins-research)) | supports the OpenTSx rule: human and optimizer at the same interface, approval before the return path |
| AWS "L4 Living Digital Twin" | Model parameters are continuously updated with real data and calibrated probabilistically (Bayesian) ([AWS IoT Blog](https://aws.amazon.com/blogs/iot/l4-living-digital-twins/), [AWS HPC Blog](https://aws.amazon.com/blogs/hpc/deploying-level-4-digital-twin-self-calibrating-virtual-sensors-on-aws)) | Calibration as part of the coupling |
| ISO/IEC 30186:2025 | Maturity model and guidance for maturity assessment of digital twins ([BSI entry](https://knowledge.bsigroup.com/products/bs-iso-iec-30186-digital-twin-maturity-model-and-guidance-for-a-maturity-assessment)) | Content requires payment, **not seen** |

### 2.2 The eight assessment criteria

| Crit. | Question | What "●" requires in the primary source |
|-------|----------|------------------------------------------|
| **a** | Is the simulation continuously driven or corrected by live measurements (shadow, nowcast, state estimation, calibration)? | explicit description of the live operation of a model, not only a visualization of measured values |
| **b** | Can what-if variants be computed from the current (live) state? | documented scenario or what-if function on the operated model |
| **c** | Is there a forecast or model predictive optimization from the same state? | prediction, schedule or optimization function on the twin |
| **d** | Is the provenance per value (measured, estimated, simulated) visible to users? | labeling per value, across calculations |
| **e** | Can a run be repeated reproducibly (replay)? | deterministic repetition or versioned, repeatable runs |
| **f** | Open and vendor-neutral? | open license or open standard; "◐" for proprietary with open interfaces |
| **g** | Does the same concept scale from small systems (house, boat) to a wind farm plus electrolyzer? | documented applications across size classes |
| **h** | Does the twin work across ownership boundaries (federated)? | parts run at different owners, coupled via defined interfaces |

**Scale:** ● documented (primary source, mostly a vendor statement) · ◐ partial or limited · ○ not found in the sources
checked · ? not verified or unclear.

---

## 3. Reference point: OpenTSx today

The following table separates what exists on `master`, what sits in work branches (as of 2026-10-12, not yet in
`master`) and what is planned. Basis: [07-DIGITAL-TWIN-BSP](../datamodel-2026/07-DIGITAL-TWIN-BSP.md),
[`opentsx-twin`](../../opentsx-twin/README.md), [TASK-011](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md),
[DevOps: today vs. planned](../devops/today-vs-planned.md).

| Crit. | Status on `master` | Work branches (not yet merged) | Planned | Rating |
|-------|--------------------|--------------------------------|---------|--------|
| a | *shadow* mode in variant T; residual of measurement − sensor view in the balance node; calibration of the house (bills) and the boat (logger data) **on simulated data**; Modbus poller, tested against a meter simulator | DWD collector (MOSMIX, measurements; `opentsx/2026-dwd`) | live binding via KafScale topics; IEC 60870-5-104; market data collector (in progress) | ◐ |
| b | `Twin.whatIf`: reference versus alternative intervention log on the same history; levers for house and boat | none | what-if endpoint of the Prediction API | ● |
| c | `Twin.forecast` from a snapshot with an exchangeable policy (rule `pressure-aware` with a rolling horizon) | none | optimizer as a policy atom (MPC, P8.4) | ◐ |
| d | provenance ladder `MEASURED > REPORTED > FORECAST > ESTIMATED > SYNTHETIC` per port value and step, taint propagation; every series carries its worst provenance, and the Studio displays it | none | measured share in percent per KPI (proposal D21); provenance in the production Studio | ● |
| e | bit-identical repetition via a chained state hash; run id from the plan, input and intervention hashes plus version; episode replay verified on KafScale (P2) | none | twin topics on KafScale, state in Iceberg | ● |
| f | Apache 2.0; Kafka protocol (KafScale), Apache Iceberg, OpenMetadata; Java and Python | none | none | ● |
| g | three scenarios with the same building blocks: H2 chain (1 min time step), single-family house (1 h, 8,760 steps), boat (15 min, 14 days) | inventory and asset explorer (`opentsx/2026-explorer`) | turbine, wake, weather and meter atoms in the twin; scaling tests | ◐ |
| h | topic contract (`PortMessage`, `Barrier`) as code | variant L (loose, `opentsx/2026-p9-loose`): with staleness 0 bit-identical to T; measured design rule (section 6.4), **on simulated wall-clock time** | real processors via KafScale, operation at different owners | ◐ |

**What does not exist today:** no production customer, no runs on real live data, no certifications, no connection to
a control system, no optimizer beyond a rule policy, no production user interface (the Studio is a static page:
[prototype](../prototypes/smart-topics-studio.html)). A project setting with a wind farm, an electrolyzer and direct
marketing is in preparation; the data access (meters via Modbus, IEC 104 to the electrolyzer operator, the marketer's
schedules) is yet to be agreed ([TASK-011, backlog](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md)).

---

## 4. Vendor landscape

The profiles state what the primary source confirms and where it is silent. The rating per criterion is in section 5.

### 4.1 Industrial and energy platforms

**Siemens.**
- *Simcenter Executable Digital Twin (xDT)*: a self-contained, executable model, at its core a reduced order model;
  runs in the cloud, at the edge, on test benches or embedded. *Smart Virtual Sensing* fuses measurement and
  simulation with a Kalman filter. An xDT gateway manages the twins on Industrial Edge
  ([arXiv 2210.17402](https://arxiv.org/pdf/2210.17402),
  [Simcenter blog](https://blogs.sw.siemens.com/simcenter/simcenter-3d-smart-virtual-sensing-unveiling-the-magic/),
  [product page](https://www.siemens.com/en-us/products/simcenter/engineering-data-science-ai/executable-digital-twin-gateway/)).
  Strong live coupling at component level, no energy cluster.
- *Hydrogen Performance Suite* (press release of 2026-10-08): a "digital cockpit" with a physics-based high-fidelity
  model, real-time optimization, live plant data and external sources such as grid operators and energy markets;
  for use at the GalpH2Park in Sines (100 MW, up to 15,000 t H2/a)
  ([Siemens](https://press.siemens.com/global/en/pressrelease/siemens-will-digitalize-100-mw-hydrogen-plant-sines-portugal)).
  In addition, gPROMS twins and SIMIT commissioning for electrolyzers
  ([Hystar webinar](https://resources.sw.siemens.com/en-US/green-hydrogen-digital-twin-for-hystar-electrolyzer-excellence/)).
  A Siemens brief explicitly calls for a twin that runs online and is constantly updated with data
  ([brief](https://assets.new.siemens.com/siemens/assets/api/uuid:e2190682-94a3-4933-8f45-8d1faf717832/DI-PA-Simplifying-Green-H2-Production-Brief.pdf)).
  **The most direct competitor in the hydrogen segment.**
- *Gridscale X (LV Insights)*: SaaS twin of the low-voltage grid built from GIS and meter data, with a continuously
  updated view of topology and grid state
  ([product page](https://www.siemens.com/en-us/products/gridscale-x/lv-insights-x/)); in use in Trieste since 2025
  ([press release](https://press.siemens.com/global/en/pressrelease/acegasapsamga-leverages-siemens-gridscale-x-build-digital-twin-triestes-energy-grid));
  Gridscale X connects planning and real-time operation in one grid model
  ([press release](https://press.siemens.com/global/en/pressrelease/siemens-gridscale-x-redefines-system-operations-and-agentic-transmission-planning)).
  A state estimator is not described by name (**not verified**).
- *Digital Twin Composer* (CES, 2026-01-06): photorealistic 3D twins on NVIDIA Omniverse libraries, connected to MES,
  QMS, PLC and IIoT data; Early Access
  ([press release](https://news.siemens.com/en-us/digital-twin-composer-ces-2026)). *Insights Hub* is the former
  MindSphere ([Siemens](https://www.siemens.com/in/en/products/software/mindsphere.html)).

**GE Vernova.** SmartSignal uses "AI/ML Digital Twins" based on *similarity-based modeling* with near real-time sensor
data; APM Performance Intelligence uses physics-based twins; GridOS twins simulate the grid over different time
horizons; the FAQ names what-if scenarios and behavior forecasts
([GE Vernova](https://www.gevernova.com/software/innovation/digital-twin-technology)). The "Digital Wind Farm" concept
dates from 2015 ([press release](https://www.ge.com/news/press-releases/ge-launches-next-evolution-wind-energy-making-renewables-more-efficient-economic));
the current status in the wind business is **not verified**.

**AVEVA and ETAP.**
- *AVEVA Process Simulation*: "Create a high-fidelity process model as a digital twin to monitor, simulate, and
  optimize plant performance in real-time by integrating with live data systems like AVEVA PI System"; KPIs that
  cannot be measured are monitored from the model; what-if over operating states, failures and feedstocks
  ([AVEVA](https://www.aveva.com/en/solutions/engineering/operator-training/)).
- *ETAP* (integration with Schneider's EcoStruxure Power Operation for operator training,
  [press release](https://www.se.com/ww/en/about-us/newsroom/news/press-releases/schneider-electric-and-etap-announce-new-digital-twin-integration-enabling-operator-training-and-simulations-greatly-reducing-risk-to-operations-6294ae4877bcea5360479e84)):
  "integrated Electrical Digital Twin platform", "unified engineering and real-time platform" with "State Estimation &
  Load Allocation", "Predictive Simulation", what-if ("Probe alternative actions") and "Sequence of Events Playback"
  ([ETAP](https://etap.com/products/etap-digital-twin)). **Functionally the closest counterpart to the three operating
  modes**, limited to electrical systems and proprietary.

**Emerson and AspenTech.** The Ovation Digital Twin is an embedded high-fidelity simulator that runs in parallel to the
control system and tests operating policies before they reach the plant
([Emerson](https://www.emerson.com/en-us/automation/control-and-safety-systems/distributed-control-systems-dcs/ovation-distributed-control-system/power-plant-simulator)).
In 2022, a simulation continuously synchronized with the plant was still considered a pilot and a "holy grail"
([Emerson blog](https://www.emersonautomationexperts.com/2022/industry/power-generation/ovation-users-conference-reimagining-a-more-sustainable-tomorrow/));
the status in 2026 is **not verified**. Emerson has fully owned AspenTech since 2025-03-12
([Emerson](https://www.emerson.com/en-us/news/2025/emerson-completes-acquisition-of-remaining-outstanding-shares-of-aspentech));
with OSI monarch, AspenTech offers SCADA, EMS, ADMS and a microgrid management system
([AspenTech](https://www.aspentech.com/en/resources/press-releases/aspen-technology-launches-microgrid-management-system)).
NI (LabVIEW) has also belonged to Emerson since 2023-10
([eeNews](https://www.eenewseurope.com/en/farewell-ni-as-emerson-acquisition-completes/)).

**ABB.** In the Boliden Aitik case, a Dymola process model runs against a copy of the 800xA control system via OPC
(about 35 signals); the APC optimization sits separately in Expert Optimizer; the model was re-parameterized after step
tests. ABB names model fidelity as the main difficulty
([ABB case study](https://new.abb.com/mining/digital-applications/advanced-process-control/case-study-the-use-of-a-digital-twin-with-advanced-process-control-at-boliden-aitik-mine)).

**Hitachi Energy.** HMAX Energy (2026-03) with the HVDC twin IdentiQ; vendor statement: up to 90 % faster response to
disturbances ([Hitachi Energy](https://www.hitachienergy.com/us/en/news-and-events/press-releases/2026/03/hitachi-launches-hmax-energy-a-pioneering-ai-powered-service-and-solution-suite-for-critical-energy-infrastructure)).
Details of the coupling **not verified**.

**Ansys Twin Builder (Synopsys).** Reduced order models from the Ansys solvers; "Calibrate and tune your simulation
model parameters by combining physical and virtual sensors"; deployment via Twin Deployer in the cloud, at the edge or
offline; FMI support ([Synopsys](https://ansys.synopsys.com/products/digital-twin/ansys-twin-builder)).

**Bentley Systems.** iTwin IoT collects and validates sensor data in infrastructure twins
([Bentley](https://www.bentley.com/software/itwin-iot/)). iTwin.js is under the MIT license; the native module is not
open source ([GitHub](https://github.com/iTwin/itwinjs-core)). Simulation on live data **not verified**.

**Dassault Systèmes.** The platform promises that real-time data from operation flows back into the "virtual twin"
([3DS blog on wind](https://blog.3ds.com/industries/infrastructure-energy-materials/how-virtual-twins-can-make-wind-farms-fit-for-the-future/)).
The energy example Red Eléctrica (2024-12) concerns design and collaboration among more than 1,500 participants, not a
documented live operation ([press release](https://www.3ds.com/newsroom/press-releases/electrical-grid-operator-red-electrica-transforms-its-design-process-and-collaboration-dassault-systemes-3dexperience-platform)).

**Octave (formerly Hexagon software).** Spin-off completed on 2026-05-28
([Hexagon](https://investors.hexagon.com/share-information/octave-separation)); HxGN SDx is now called InConcert and
manages engineering and operations information ([Octave](https://www.octave.com/products/engineering-information-management/inconcert)).
No link to simulation found.

**PTC ThingWorx.** Sale of Kepware and ThingWorx to TPG completed on 2026-03-16
([PTC](https://www.ptc.com/en/news/2026/ptc-completes-divestiture-of-kepware-and-thingworx-businesses)).

**IBM Maximo.** Monitor provides condition monitoring; Predict provides failure probabilities per failure mode
([IBM docs](https://www.ibm.com/docs/en/mhmpmh-and-p-u/cd?topic=overview-maximo-predict)). No physical simulation
documented.

**Cognite Data Fusion.** The *Simulator Integration* is generally available: simulations run automatically with near
real-time operating data, models and routines are versioned, and results are tracked over time; connectors for
PROSPER, GAP, Petro-SIM, Symmetry and DWSIM
([Cognite Hub](https://hub.cognite.com/simulator-integration-388/simulator-integration-service-now-generally-available-in-cognite-data-fusion-4304),
[Cognite docs](https://docs.cognite.com/cdf/integration/guides/simulators)). **The cleanest example of the pattern
"data platform drives third-party simulators"**, with a focus on oil and gas.

### 4.2 Wind

- **DNV WindGEMINI** (2017): SCADA signals as input, real-time analysis and forecast, aeroelastic modeling of fatigue
  to estimate the remaining useful life
  ([DNV](https://www.dnv.com/news/2017/new-windgemini-advances-wind-turbine-operations-104699/)).
  Current product status **not verified**.
- **Akselos**: structural twins based on reduced FE models (RB-FEA), updated with inspection and sensor data
  ([Akselos](https://akselos.com/digital-twins-a-comprehensive-guide/)); offshore focus.
- **Power Factors**: Unity REMS and Unity APM (2024-10) combine Drive and Greenbyte; vendor statement: more than 300 GW
  ([Power Factors](https://www.powerfactors.com/news/power-factors-launches-next-generation-ai-powered-asset-performance-management-application-on-unity-platform)).
  Asset performance analytics, no simulation twin found. Bazefield: **not checked**.
- **Envision EnOS Wind**: IoT operating system for wind farms with data acquisition, loss analysis, ML condition
  assessment, power forecast and AGC/AVC; twin at turbine level ([Envision](https://www.envision-group.com/en/windsoftware.html)).
- **Siemens Gamesa with NVIDIA** (2022): wake surrogate, vendor statement: up to 4,000 times faster
  ([NVIDIA](https://blogs.nvidia.com/blog/siemens-gamesa-wind-farms-digital-twins)). Production status **not
  verified**.
- **Sentient Science DigitalClone**: physics-based and ML prognostics for gearboxes; "DigitalClone Live" updates
  predictions with live data ([Windpower Engineering](https://www.windpowerengineering.com/extending-life-wind-turbines-prognostics-data-modeling/)).
  Older sources, status in 2026 **not verified**.
- **Vestas, Nordex**: no publicly documented twin products for operators in the sources checked
  (**not verified**).

### 4.3 Hydrogen and electrolysis

- **Siemens**: Hydrogen Performance Suite and gPROMS twins (section 4.1), the most extensively documented approach.
- **thyssenkrupp nucera**: names "Digital Twins" alongside *nucera evaluator* and *nucera load balancer*, as well as
  APC and *pulsewatch* in its service offering ([thyssenkrupp nucera](https://www.thyssenkrupp-nucera.com/technologies/alkaline-water-electrolysis)).
  Functional scope **not verified**.
- **Sunfire, ITM Power, Plug Power**: no published twin products found.
- **Research**: safety twin with a CFD dispersion calculation for the NREL ARIES facility
  ([OSTI](https://www.osti.gov/biblio/3011897)), platform architecture for an industrial electrolyzer
  ([ACM Energy Informatics Review](https://energy.acm.org/eir/poster-abstract-a-digital-twin-platform-applied-to-hydrogen-electrolyzers/)).

In the sources checked, no vendor explicitly describes the chain **offtaker → line pressure → electrolyzer → schedule →
marketing** as one model across the boundaries of the plant and its owners. The Siemens suite integrates grid operators
and markets as external sources; how far it computes offtakers and the marketing schedule as coupled building blocks
is **not verified**. Exactly this chain is scenario S1 of OpenTSx.

### 4.4 Grids and municipal utilities

- **DIgSILENT PowerFactory**: state estimation for balanced and unbalanced grids with bad data detection and
  observability analysis ([DIgSILENT](https://www.digsilent.de/en/state-estimation.html)); the OPC interface is used
  for real-time integration with SCADA, from online state estimation to the training simulator
  ([DIgSILENT IT projects](https://www.digsilent.de/en/it-projects.html)).
- **envelio Intelligent Grid Platform** (E.ON majority since 2021-12,
  [Startbase](https://www.startbase.de/news/exit-von-envelio/)): a grid twin that processes and corrects existing data;
  grid planning, grid operation with live data and Section 14a control on one data basis
  ([ZfK](https://www.zfk.de/digitalisierung/smart-city-energy/digitaler-zwilling-fuer-meister-und-monteure-envelio-startet-mobile-netzservices),
  [Presseportal](https://www.presseportal.de/pm/108695/6073125)).
- **VENIOS Energy Platform**: a cloud twin that uses state estimation to produce a high-resolution grid model despite
  few measured values; reference: Stadtwerke Schwäbisch Hall
  ([ZfK](https://www.zfk.de/digitalisierung/smart-city-energy/stadtwerke-schwaebisch-hall-machen-ihr-stromverteilnetz-transparent)).
- **PSIcontrol**: control system for monitoring, control and simulation; predictive grid simulation at N-ERGIE Netz
  ([PSI](https://www.psi.de/en/solutions/products/psicontrol),
  [Industry Press](https://industry-press.com/psi-software-se-erneuert-netzleitsystem-fuer-n-ergie-netz-gmbh/)).
- **DNV Synergi Electric**: planning and analysis tool with connections to GIS, SCADA and AMI; no live twin
  documented ([DNV](https://www.dnv.com/software/energy/synergi-electric)).
- **Tapestry (X, Alphabet)**: planning tool for long-term grid simulation at hourly resolution
  ([X](https://X.Company/projects/tapestry/)); in daily operation at Vector (New Zealand)
  ([Latitude Media](https://www.latitudemedia.com/news/exclusive-googles-grid-moonshot-is-now-tackling-distribution/)).

Grid twins compute load flow and grid state. OpenTSx, by contrast, computes the assets, contracts and operating
policies of a cluster. The two complement each other (Part 2, section 4.4).

### 4.5 VPP, flexibility and energy communities

- **Next Kraftwerke** (part of Shell since 2021): SaaS platform NEMOCS; forecasts from asset, weather, price and grid
  data ([Next Kraftwerke](https://www.next-kraftwerke.com/energy-blog/spotlight-nemocs-setting-up-a-vpp-with-ecotricity)).
- **gridX** (E.ON, fully owned since 2022-12): gridBox with local real-time optimization, cloud platform XENON
  ([gridX](https://www.gridx.ai/press-releases/e-on-acquires-majority-stake-in-leading-smart-grid-company-gridx)).
- **Entrix**: optimization and marketing of battery storage; 3 GW / 8.5 GWh under contract (2026-03)
  ([TaiyangNews](https://taiyangnews.info/technology/optimization-trading-platform-for-grid-scale-batteries-from-entrix)).
- **Kraken Flex** (Octopus Energy Group): ML and AI control of distributed assets; partnership with Schneider
  Electric (2026-06); more than 2 GW of flexibility from households ([Kraken](https://www.kraken.tech/press-releases/schneider-electric-and-kraken-partner-to-boost-grid-flexibility)).

These platforms forecast and optimize on live data (criterion c), but they do not simulate asset physics, and they
work centrally across the assets of many owners, not federated.

### 4.6 Hyperscalers and data platforms

- **Azure Digital Twins**: still generally available; twin graph with DTDL models; billing by operations, messages
  and Query Units ([Microsoft Learn](https://learn.microsoft.com/en-us/azure/digital-twins/overview),
  [pricing](https://azure.microsoft.com/en-us/pricing/details/digital-twins/)). Separate from this: **Fabric digital
  twin builder** (preview, ontology-based modeling in Real-Time Intelligence,
  [Microsoft Learn](https://learn.microsoft.com/en-us/fabric/real-time-intelligence/digital-twin-builder/overview));
  according to a third-party source, it has no simulation engine ([third-party source](https://fgarofalo56.github.io/Suppercharge_Microsoft_Fabric/features/digital-twin-builder/),
  **not verified**).
- **AWS IoT TwinMaker**: knowledge graph, 3D scenes and a Grafana plugin; usage-based pricing; according to the
  documentation, not intended for operating critical systems ([AWS docs](https://docs.aws.amazon.com/iot-twinmaker/latest/guide/what-is-twinmaker.html),
  [pricing](https://aws.amazon.com/iot-twinmaker/pricing/)). **Status:** TwinMaker is **not** listed in the AWS
  service availability announcements of October 2025 ([AWS](https://aws.amazon.com/about-aws/whats-new/2025/10/aws-service-availability/))
  and of March and June 2026 (summary: [Classmethod](https://dev.classmethod.jp/en/articles/aws-service-lifecycle-summary-20260630/));
  the pricing page contains no notice of an end of support. A third-party source claims a discontinuation
  ([innFactory](https://innfactory.de/en/cloud/aws/products/iot-twinmaker/)); this is **not verified**. Related
  services that are discontinued or in maintenance mode: IoT Analytics (end of support 2025-12-15), IoT Events (end of
  support 2026-05-20), SiteWise Monitor (maintenance mode since 2025-11-07), FleetWise (maintenance mode since
  2026-04-30), Greengrass v1 (end of support 2026-10-07)
  ([overview](https://thingshost.de/en/blog/aws-iot-service-retirements-2026)). For customers, this is an argument for
  open formats.
- **NVIDIA Omniverse and PhysicsNeMo**: PhysicsNeMo (formerly Modulus) is an open source PyTorch framework for physics
  ML under Apache 2.0 ([GitHub](https://github.com/NVIDIA/physicsnemo)); trained surrogates compute new behavior in
  real time ([NVIDIA](https://blogs.nvidia.com/blog/siemens-gamesa-wind-farms-digital-twins)).
- **Google**: Tapestry (section 4.4). **Databricks**: **not checked**.

### 4.7 Open source and standards

- **Eclipse Ditto** (EPL-2.0): twin as a state mirror of devices (reported, desired), connection via Kafka, MQTT,
  AMQP; no simulation ([GitHub](https://github.com/eclipse-ditto/ditto), [project](https://eclipse.dev/ditto/)).
- **Asset Administration Shell (AAS) and Eclipse BaSyx** (BaSyx Java SDK under MIT, [GitHub](https://github.com/eclipse-basyx/basyx-java-server-sdk)):
  the IDTA submodel 02005 *Provision of Simulation Models* describes simulation models such as FMUs on the Asset
  Administration Shell of the asset ([IDTA 02005](https://industrialdigitaltwin.org/wp-content/uploads/2023/01/IDTA-02005-1-0_Submodel_ProvisionOfSimulationModels.pdf)).
  It describes models; it does not execute them.
- **Catena-X**: twins as Asset Administration Shells in a Digital Twin Registry, sovereign exchange via the Eclipse
  Dataspace Connector ([CX-0002](https://catena-x.net/fileadmin/user_upload/Standard-Bibliothek/Update_Januar_2024/CX-0002-DigitalTwinsInCatenaX-v2.1.0.pdf)).
  The model to follow for data sovereignty across company boundaries.
- **FIWARE**: context broker based on NGSI-LD and Smart Data Models for twin classes, including energy
  ([arXiv 2309.12358](https://arxiv.org/pdf/2309.12358)). License of Orion-LD **not verified**.
- **mosaik 3** (OFFIS, LGPL-2.1): co-simulation for smart grids, combines time-stepped and discrete-event simulation;
  FMI and OPC UA ([arXiv 2410.16937](https://arxiv.org/html/2410.16937v1), [GitHub](https://github.com/OFFIS-mosaik)).
- **HELICS** (BSD-3-Clause): federates request time, and the core grants it only when no federate would have to
  compute into the past; target size more than 100,000 federates
  ([docs](https://docs.helics.org/en/main/user-guide/fundamental_topics/timing_configuration.html),
  [GitHub](https://github.com/GMLC-TDC/HELICS)).
- **FMI 3.0**: Co-Simulation, Model Exchange and Scheduled Execution, Clocks and Event Mode
  ([FMI FAQ](https://fmi-standard.org/faq/), [Modelica 2021](https://ecp.ep.liu.se/index.php/modelica/article/view/179)).
  SSP: **not checked**. OpenModelica: **not checked**.
- **pandapower** (BSD-3-Clause): WLS state estimation with chi-squared and residual tests; the docs warn that bad data
  removal is "not very robust" ([docs](https://pandapower.readthedocs.io/en/latest/estimation.html)).
- **PyPSA** (MIT): optimization and simulation of dispatch and expansion ([GitHub](https://github.com/PyPSA/PyPSA)).
  Planning, no live operation.
- **OpenEMS**: Edge and Backend under EPL-2.0, user interface under AGPL-3.0; simulated development environment
  ([GitHub](https://github.com/OpenEMS/openems)); reference implementation with Fraunhofer ISE and FENECON (2026-06,
  [OpenMUC](https://openmuc.org/news/jeebus-and-openems-open-source-solution/)).
- **Home Assistant with EMHASS** (EMHASS under MIT): linear optimization with PV and load forecasts, MPC operation
  ([docs](https://emhass.readthedocs.io/en/latest/), [GitHub](https://github.com/davidusb-geek/emhass)).
- **Victron VRM, Dynamic ESS**: hourly charge and discharge plan from prices, solar forecast and consumption patterns,
  apparently computed in the VRM cloud; without a connection, it falls back to normal ESS after 12 h
  ([Victron](https://www.victronenergy.com/live/drafts:dynamic_ess)). Relevant for the boat case.
- **OpenTwins** (University of Málaga, Apache 2.0): open platform for composite twins, FMI and ML integration;
  according to the project, not recommended for production use ([GitHub](https://github.com/ertis-research/OpenTwins)).

### 4.8 House and end customers

- **ENTER**: 3D model ("digital twin") of the house from on-site data, simulation of renovation scenarios, individual
  renovation roadmap ([ENTER](https://www.enter.de/blog/enter-vs-lokale-energieberater)). No ongoing comparison with
  consumption data documented.
- **Framework in Germany**: smart meter rate of 5.9 % of all metering locations in the first half of 2026
  ([ZfK](https://www.zfk.de/digitalisierung/smart-city-energy/smart-meter-fortschritt-im-ersten-halbjahr-weiter-viele-nachzuegler));
  Section 14a control since 2024-01-01 ([Finanztip](https://finanztip.de/stromtarife/steuerbare-verbrauchseinrichtungen-14a-enwg/));
  the Gebäudemodernisierungsgesetz (building modernization law) in force since 2026-07-29 (65 % rule removed,
  [Haufe](https://www.haufe.de/immobilien/wirtschaft-politik/gebaeudemodernisierungsgesetz-gmg_84342_683694.html)).
  Details in Part 2.
- **Heating apps (ViCare, tado and similar) and energy advisor software**: **not checked**.

Calibrated building models are common in building physics; the goodness-of-fit measures CV(RMSE) and NMBE that the
house twin uses ([08-HAUS-ZWILLING](../datamodel-2026/08-HAUS-ZWILLING.md)) follow this practice. We did not find a
product that combines a house model calibrated against bills with an identifiability report.

### 4.9 Market moves 2025/2026

| Date | Event | Source |
|------|-------|--------|
| 2025-03-12 | Emerson acquires the remaining AspenTech shares | [Emerson](https://www.emerson.com/en-us/news/2025/emerson-completes-acquisition-of-remaining-outstanding-shares-of-aspentech) |
| 2025-11-07 | AWS IoT SiteWise Monitor in maintenance mode; more IoT services follow in 2026 | [AWS](https://aws.amazon.com/about-aws/whats-new/2025/10/aws-service-availability/) |
| 2026-01-06 | Siemens Digital Twin Composer (Early Access) | [Siemens](https://news.siemens.com/en-us/digital-twin-composer-ces-2026) |
| 2026-03-16 | PTC sells ThingWorx and Kepware to TPG | [PTC](https://www.ptc.com/en/news/2026/ptc-completes-divestiture-of-kepware-and-thingworx-businesses) |
| about 2026-03-20 | Bosch announces the acquisition of Uptake (fleet maintenance) | [Bosch](https://www.bosch-presse.de/pressportal/us/en/press-release-30080.html) |
| 2026-03 | Hitachi Energy launches HMAX Energy | [Hitachi Energy](https://www.hitachienergy.com/us/en/news-and-events/press-releases/2026/03/hitachi-launches-hmax-energy-a-pioneering-ai-powered-service-and-solution-suite-for-critical-energy-infrastructure) |
| 2026-05-28 | Octave spun off from Hexagon | [Hexagon](https://investors.hexagon.com/share-information/octave-separation) |
| 2026-06 | Schneider Electric and Kraken: partnership for grid flexibility | [Kraken](https://www.kraken.tech/press-releases/schneider-electric-and-kraken-partner-to-boost-grid-flexibility) |
| 2026-10-08 | Siemens Hydrogen Performance Suite for the GalpH2Park | [Siemens](https://press.siemens.com/global/en/pressrelease/siemens-will-digitalize-100-mw-hydrogen-plant-sines-portugal) |

Large vendors bundle twin, control system and plant. Energy companies buy grid and flexibility software. Hyperscalers
are cleaning up their IoT portfolios. From this, one can infer a demand for **vendor-neutral, open** solutions that
customers can operate themselves. This is a hypothesis; the sources do not measure this demand.

---

## 5. Comparison matrix

Criteria a to h as in section 2.2; scale ● ◐ ○ ?. License and price according to the primary source; "on request"
means the source gives no prices. The rating is based on the linked sources as of 2026-10-12. Pure simulation and
planning tools get a "◐" for **b**: they compute scenarios, but not explicitly from the current live state.

### 5.1 Commercial industrial and energy offerings

| Vendor / product | a | b | c | d | e | f | g | h | License / price | Target market | Source |
|------------------|---|---|---|---|---|---|---|---|-----------------|---------------|--------|
| Siemens Simcenter xDT | ● | ? | ? | ? | ? | ○ | ◐ | ○ | commercial, on request | machines, components, OEM | [arXiv](https://arxiv.org/pdf/2210.17402), [Siemens](https://www.siemens.com/en-us/products/simcenter/engineering-data-science-ai/executable-digital-twin-gateway/) |
| Siemens Hydrogen Performance Suite (gPROMS) | ● | ? | ● | ? | ? | ○ | ○ | ◐ | commercial, on request | electrolysis plants | [Siemens](https://press.siemens.com/global/en/pressrelease/siemens-will-digitalize-100-mw-hydrogen-plant-sines-portugal) |
| Siemens Gridscale X LV Insights | ● | ? | ? | ? | ? | ○ | ○ | ○ | SaaS, on request | distribution system operators (DSOs) | [Siemens](https://www.siemens.com/en-us/products/gridscale-x/lv-insights-x/) |
| GE Vernova SmartSignal / APM / GridOS | ● | ● | ◐ | ? | ? | ○ | ○ | ○ | commercial, on request | generators, grid operators | [GE Vernova](https://www.gevernova.com/software/innovation/digital-twin-technology) |
| AVEVA Process Simulation with PI System | ● | ● | ◐ | ? | ? | ○ | ○ | ○ | commercial, on request | process industry | [AVEVA](https://www.aveva.com/en/solutions/engineering/operator-training/) |
| ETAP Digital Twin / Real-Time | ● | ● | ● | ? | ◐ | ○ | ◐ | ○ | commercial, on request | electrical grids, industry, data centers | [ETAP](https://etap.com/products/etap-digital-twin) |
| Emerson Ovation Digital Twin | ◐ | ● | ? | ? | ? | ○ | ○ | ○ | commercial, with the control system | power plants | [Emerson](https://www.emerson.com/en-us/automation/control-and-safety-systems/distributed-control-systems-dcs/ovation-distributed-control-system/power-plant-simulator) |
| ABB (Boliden case: Dymola + 800xA + APC) | ◐ | ● | ◐ | ? | ? | ◐ | ○ | ○ | project | mining, process | [ABB](https://new.abb.com/mining/digital-applications/advanced-process-control/case-study-the-use-of-a-digital-twin-with-advanced-process-control-at-boliden-aitik-mine) |
| Ansys Twin Builder (Synopsys) | ● | ◐ | ◐ | ? | ? | ◐ | ◐ | ○ | commercial, on request | machines, components | [Synopsys](https://ansys.synopsys.com/products/digital-twin/ansys-twin-builder) |
| Cognite Data Fusion, Simulator Integration | ● | ◐ | ? | ? | ◐ | ◐ | ○ | ○ | SaaS, on request | oil and gas, process | [Cognite](https://docs.cognite.com/cdf/integration/guides/simulators) |
| DIgSILENT PowerFactory | ● | ◐ | ◐ | ? | ? | ○ | ○ | ○ | commercial, on request | grid operators, planners | [DIgSILENT](https://www.digsilent.de/en/state-estimation.html) |
| envelio Intelligent Grid Platform | ● | ◐ | ? | ? | ? | ○ | ○ | ○ | SaaS, on request | distribution system operators | [ZfK](https://www.zfk.de/digitalisierung/smart-city-energy/digitaler-zwilling-fuer-meister-und-monteure-envelio-startet-mobile-netzservices) |
| VENIOS Energy Platform | ● | ◐ | ? | ? | ? | ○ | ○ | ○ | SaaS, on request | distribution system operators | [ZfK](https://www.zfk.de/digitalisierung/smart-city-energy/stadtwerke-schwaebisch-hall-machen-ihr-stromverteilnetz-transparent) |
| PSIcontrol | ◐ | ◐ | ◐ | ? | ? | ○ | ○ | ○ | commercial, on request | grid control systems | [PSI](https://www.psi.de/en/solutions/products/psicontrol) |
| DNV WindGEMINI (as of 2017) | ● | ? | ● | ? | ? | ○ | ○ | ○ | commercial, on request | wind | [DNV](https://www.dnv.com/news/2017/new-windgemini-advances-wind-turbine-operations-104699/) |
| Akselos | ◐ | ◐ | ◐ | ? | ? | ○ | ○ | ○ | commercial, on request | offshore structures | [Akselos](https://akselos.com/digital-twins-a-comprehensive-guide/) |
| Power Factors Unity | ◐ | ○ | ◐ | ? | ? | ○ | ○ | ○ | SaaS, on request | wind, PV and storage portfolios | [Power Factors](https://www.powerfactors.com/news/power-factors-launches-next-generation-ai-powered-asset-performance-management-application-on-unity-platform) |
| Envision EnOS Wind | ◐ | ? | ◐ | ? | ? | ○ | ○ | ○ | commercial, on request | wind farms | [Envision](https://www.envision-group.com/en/windsoftware.html) |
| Bentley iTwin IoT | ◐ | ? | ? | ? | ? | ◐ | ○ | ○ | commercial; iTwin.js MIT | infrastructure | [Bentley](https://www.bentley.com/software/itwin-iot/) |
| Dassault 3DEXPERIENCE | ? | ◐ | ? | ? | ? | ○ | ◐ | ◐ | commercial, on request | engineering, design | [3DS](https://www.3ds.com/newsroom/press-releases/electrical-grid-operator-red-electrica-transforms-its-design-process-and-collaboration-dassault-systemes-3dexperience-platform) |
| IBM Maximo Monitor / Predict | ◐ | ○ | ◐ | ? | ? | ○ | ○ | ○ | commercial, on request | maintenance | [IBM](https://www.ibm.com/docs/en/mhmpmh-and-p-u/cd?topic=overview-maximo-predict) |
| VPP and flexibility platforms (NEMOCS, gridX, Entrix, Kraken Flex) | ◐ | ○ | ● | ? | ? | ○ | ◐ | ◐ | SaaS, revenue share (model per vendor not verified) | aggregation, storage, households | [Next Kraftwerke](https://www.next-kraftwerke.com/energy-blog/spotlight-nemocs-setting-up-a-vpp-with-ecotricity), [gridX](https://www.gridx.ai/press-releases/e-on-acquires-majority-stake-in-leading-smart-grid-company-gridx) |

### 5.2 Hyperscalers and platforms

| Vendor / product | a | b | c | d | e | f | g | h | License / price | Target market | Source |
|------------------|---|---|---|---|---|---|---|---|-----------------|---------------|--------|
| Azure Digital Twins | ◐ | ○ | ○ | ? | ? | ◐ | ◐ | ○ | consumption-based (operations, messages, Query Units) | buildings, IoT | [Microsoft](https://learn.microsoft.com/en-us/azure/digital-twins/overview) |
| Microsoft Fabric digital twin builder (Preview) | ◐ | ? | ? | ? | ? | ○ | ◐ | ○ | Fabric capacity | industry, data | [Microsoft](https://learn.microsoft.com/en-us/fabric/real-time-intelligence/digital-twin-builder/overview) |
| AWS IoT TwinMaker | ◐ | ○ | ○ | ? | ? | ○ | ◐ | ○ | usage-based (entities, API calls, queries) | industry, buildings | [AWS](https://aws.amazon.com/iot-twinmaker/pricing/) |
| NVIDIA Omniverse + PhysicsNeMo | ◐ | ◐ | ◐ | ? | ? | ◐ | ◐ | ○ | PhysicsNeMo Apache 2.0; Omniverse commercial (not verified) | physics ML, 3D | [GitHub](https://github.com/NVIDIA/physicsnemo) |
| Siemens Digital Twin Composer | ◐ | ? | ? | ? | ? | ○ | ? | ○ | Early Access | factories | [Siemens](https://news.siemens.com/en-us/digital-twin-composer-ces-2026) |
| Tapestry (X, Alphabet) | ◐ | ◐ | ◐ | ? | ? | ○ | ○ | ○ | partnerships | transmission and distribution grids | [X](https://X.Company/projects/tapestry/) |
| PTC ThingWorx (TPG since 2026-03) | ◐ | ? | ? | ? | ? | ○ | ◐ | ○ | commercial | IIoT | [PTC](https://www.ptc.com/en/news/2026/ptc-completes-divestiture-of-kepware-and-thingworx-businesses) |

### 5.3 Open source, standards and research

| Project | a | b | c | d | e | f | g | h | License | Target market | Source |
|---------|---|---|---|---|---|---|---|---|---------|---------------|--------|
| Eclipse Ditto | ○ | ○ | ○ | ? | ? | ● | ◐ | ? | EPL-2.0 | device states (IoT) | [GitHub](https://github.com/eclipse-ditto/ditto) |
| AAS / Eclipse BaSyx + IDTA 02005 | ○ | ○ | ○ | ? | ○ | ● | ◐ | ◐ | MIT (BaSyx Java SDK); IDTA specification | Industrie 4.0 | [IDTA](https://industrialdigitaltwin.org/wp-content/uploads/2023/01/IDTA-02005-1-0_Submodel_ProvisionOfSimulationModels.pdf) |
| Catena-X (AAS + EDC) | ○ | ○ | ○ | ? | ? | ◐ | ○ | ● | open standards; operation via service providers | automotive supply chain | [Catena-X](https://catena-x.net/fileadmin/user_upload/Standard-Bibliothek/Update_Januar_2024/CX-0002-DigitalTwinsInCatenaX-v2.1.0.pdf) |
| FIWARE (NGSI-LD) | ○ | ○ | ○ | ? | ? | ● | ◐ | ◐ | open source (Orion-LD license not verified) | smart city, energy | [arXiv](https://arxiv.org/pdf/2309.12358) |
| mosaik 3 | ◐ | ◐ | ◐ | ○ | ◐ | ● | ◐ | ◐ | LGPL-2.1 | smart grid research | [arXiv](https://arxiv.org/html/2410.16937v1) |
| HELICS | ◐ | ◐ | ◐ | ○ | ◐ | ● | ◐ | ◐ | BSD-3-Clause | grid co-simulation | [HELICS](https://docs.helics.org/en/main/user-guide/co-simulation_overview.html) |
| FMI 3.0 (standard) | ◐ | ◐ | ◐ | ○ | ◐ | ● | ◐ | ○ | open standard | co-simulation | [FMI](https://fmi-standard.org/faq/) |
| pandapower | ◐ | ◐ | ◐ | ? | ? | ● | ○ | ○ | BSD-3-Clause | grid calculation | [docs](https://pandapower.readthedocs.io/en/latest/estimation.html) |
| PyPSA | ○ | ◐ | ◐ | ○ | ◐ | ● | ◐ | ○ | MIT | energy system planning | [GitHub](https://github.com/PyPSA/PyPSA) |
| OpenEMS | ◐ | ? | ◐ | ? | ? | ● | ◐ | ◐ | EPL-2.0 / AGPL-3.0 (UI) | storage, buildings, commercial | [GitHub](https://github.com/OpenEMS/openems) |
| Home Assistant + EMHASS | ◐ | ◐ | ● | ○ | ○ | ● | ○ | ○ | EMHASS MIT | households | [EMHASS](https://emhass.readthedocs.io/en/latest/) |
| Victron VRM Dynamic ESS | ◐ | ○ | ● | ○ | ○ | ◐ | ○ | ○ | part of the Victron ecosystem (terms not verified) | home, off-grid, boat | [Victron](https://www.victronenergy.com/live/drafts:dynamic_ess) |
| OpenTwins (Univ. Málaga) | ◐ | ◐ | ◐ | ? | ? | ● | ? | ○ | Apache 2.0 | research | [GitHub](https://github.com/ertis-research/OpenTwins) |
| TwinEU (Horizon Europe, until 2026-12) | ? | ? | ? | ? | ? | ◐ | ○ | ● | research project | transmission and distribution system operators | [TU Dortmund](https://ie3.etit.tu-dortmund.de/research/third-party-projects/distribution-grid-planning-operation/twineu/) |

### 5.4 House and reference

| Offering | a | b | c | d | e | f | g | h | License / price | Target market | Source |
|----------|---|---|---|---|---|---|---|---|-----------------|---------------|--------|
| ENTER (house twin, renovation roadmap) | ○ | ◐ | ○ | ? | ? | ○ | ○ | ○ | advisory service (subsidized by BAFA, the Federal Office for Economic Affairs and Export Control) | homeowners | [ENTER](https://www.enter.de/blog/enter-vs-lokale-energieberater) |
| **OpenTSx** (as of 2026-10-12, proof of concept) | ◐ | ● | ◐ | ● | ● | ● | ◐ | ◐ | Apache 2.0 | no production customer yet | [Repo](../../README.md), section 3 |

**How to read the matrix.** No vendor is documented in all columns. Column **d** (provenance per value) is "?" for
all commercial offerings: the primary sources do not address it. This does not mean that the function does not exist.
Before OpenTSx uses this in its advertising, the three closest competitors (ETAP, AVEVA, Cognite) should be asked about
it specifically in demos (section 8).

---

## 6. Research and standards

### 6.1 Definitions and maturity levels

- **ISO/IEC 30173:2023** defines the coupling via the "appropriate rate of synchronization"
  ([ISO](https://www.iso.org/standard/81442.html)); **ISO/IEC 30186:2025** provides a maturity model
  ([BSI](https://knowledge.bsigroup.com/products/bs-iso-iec-30186-digital-twin-maturity-model-and-guidance-for-a-maturity-assessment),
  content not seen); **ISO 23247** (parts 1 to 4, 2021) describes a framework for manufacturing
  ([ISO](https://committee.iso.org/standard/75066.html)).
- The **Digital Twin Capabilities Periodic Table** of the Digital Twin Consortium (v1.1, 2024-04) is a
  technology-neutral requirements grid for the capabilities of twins
  ([DTC User Guide](https://www.digitaltwinconsortium.org/wp-content/uploads/sites/3/2022/06/Digital-Twin-Capabilities-Periodic-Table-User-Guide.pdf)).
  It is suitable for mapping OpenTSx capabilities against vendors in tenders.
- **Kritzinger et al. (2018)**: by this classification, many systems called a "twin" are only models or shadows
  ([DOI](https://doi.org/10.1016/j.ifacol.2018.08.474)). The shadow mode of OpenTSx is a shadow; it becomes a twin
  only with the approved return path ([09-STUDIO-LABVIEW-STEUERPULT](../datamodel-2026/09-STUDIO-LABVIEW-STEUERPULT.md)).

### 6.2 Data assimilation, state estimation, shadow operation

- **Kalman filters for virtual sensors** have arrived in products (Siemens Smart Virtual Sensing, section 4.1).
- **State estimation** is standard in grid tools (PowerFactory, ETAP, VENIOS; pandapower as an open library).
- **Bayesian calibration** in operation is what AWS describes as level 4 ("Living Twin", section 2.1).
- **Ensemble Kalman filters** are being researched for twins in nuclear engineering and thermal hydraulics
  ([ScienceDirect](https://www.sciencedirect.com/science/article/abs/pii/S0149197026000703)). We did not find a
  current review specifically on energy systems.

**Consequence for OpenTSx:** the least-squares calibration (house, boat) and the residual of measurement − sensor view
are solid but simple forms of data assimilation. A sequential state estimation (Kalman) in shadow operation is missing
and is an obvious extension.

### 6.3 Co-simulation and synchronization: tight or loose

- **Jacobi and Gauss-Seidel coupling**: Jacobi computes in parallel but is usually less accurate
  ([Tutorial, arXiv 1809.08463](https://arxiv.org/pdf/1809.08463); overview [arXiv 1702.00686](https://arxiv.org/pdf/1702.00686)).
  Weak coupling necessarily introduces delays at the interfaces
  ([Modelica 2014](https://2014.international.conference.modelica.org/proceedings/html/submissions/ECP14096213_Viel.pdf)).
  The layered BSP of OpenTSx is a Gauss-Seidel-like order within the superstep; feedback paths cross the step boundary
  ([07, section 4.1](../datamodel-2026/07-DIGITAL-TWIN-BSP.md)).
- **Conservative time synchronization** as in HELICS (time is granted only when nobody would have to compute into the
  past) resembles variant L with staleness 0.
- **Bounded staleness (SSP)** comes from distributed machine learning
  ([Ho et al., NeurIPS 2013](https://proceedings.nips.cc/paper_files/paper/2013/file/b7bb35b9c6ca2aee2df08cf09d7016c2-Paper.pdf)).
- **FMI 3.0** brings Clocks and Scheduled Execution for event-based and near real-time coupling (section 4.7).

### 6.4 How our own measurement compares

In the work branch `opentsx/2026-p9-loose` (not yet in `master`), OpenTSx measures variant L against variant T on
simulated wall-clock time:

- With staleness 0, L is **bit-identical** to T for the H2 chain, the house and the boat.
- Throughput follows the formula "ticks per step = max over all loops Σ(1 + lag) / Σ(d + s)".
- The design rule is: *Feedback loops belong in one processor; between processors BSP is enough.*
  With it, L delivers the result of T at full pipeline throughput (at `lag = 2`, factor 5.9 for the H2 chain and
  6.0 for the house compared to BSP without colocation).

**Honest assessment:** the formula matches the **iteration bound** known from dataflow analysis: the maximum over all
loops of computation time divided by the number of delays
([York University, lecture](https://www.eecs.yorku.ca/course_archive/2011-12/W/4210/L4_it_bound.pdf)). This supports
the measurement. As theory, it is not new. The contribution lies in applying it to twins on a log: conformance down to
the bit, provenance propagation and operation across ownership boundaries. The paper "Digital Twins on Logs" should
cite the iteration bound explicitly.

### 6.5 Federation across ownership boundaries

- **TwinEU** (Horizon Europe, 2024-01 to 2026-12, coordinated by Fraunhofer, 77 partners from 15 countries): "a
  federated ecosystem of DT solutions". Each operator decides on its own implementation; standard interfaces and open
  APIs ensure interoperability. The German demonstrator with Amprion and Westnetz targets data exchange between
  transmission system operators (TSOs) and distribution system operators (DSOs)
  ([TU Dortmund](https://ie3.etit.tu-dortmund.de/research/third-party-projects/distribution-grid-planning-operation/twineu/)).
  **The most important link** for variant L.
- **Catena-X** shows how data sovereignty across company boundaries works technically and contractually (AAS,
  registry, Dataspace Connector, section 4.7).

### 6.6 Origin and provenance

- **OPC UA StatusCodes** include, among others, `Uncertain_SubstituteValue` (manually overwritten value) and
  `Good_LocalOverride` ([OPC Foundation](https://reference.opcfoundation.org/Core/Part8/v104/docs/?r=A.7&t=1)).
- **IEC 61850 quality** has `source = process | substituted`, `test` and `operatorBlocked` (documented here via the
  CIM model `Quality61850`, standard text not seen,
  [CIM](https://modellen.netbeheernederland.nl/tc57cim/cim17/schema/class/Quality61850.html)).
- **W3C PROV** models entities, activities and agents ([arXiv 2507.01075](https://arxiv.org/pdf/2507.01075)).
  We did not find a paper that applies PROV to the distinction "measured vs. simulated" in twins.

The provenance ladder of OpenTSx is therefore compatible: `MEASURED` and `REPORTED` can be derived from OPC UA and
IEC 61850 qualities. `FORECAST`, `ESTIMATED` and `SYNTHETIC`, as well as the propagation through the model chain, go
beyond them.

---

## 7. Honest assessment

### 7.1 Where vendors are stronger

| Area | Vendor strength | OpenTSx today |
|------|-----------------|---------------|
| **Maturity and operating experience** | installed base, for example PSIcontrol with systems of more than 20,000 telecontrol stations ([PSI](https://www.psi.de/en/solutions/products/psicontrol)); envelio with customers from 7,000 to 4.8 million withdrawal points ([ZfK](https://www.zfk.de/digitalisierung/smart-city-energy/digitaler-zwilling-fuer-meister-und-monteure-envelio-startet-mobile-netzservices)) | no production customer |
| **Depth of physics** | process models (gPROMS, AVEVA), reduced FE and structural models (Ansys, Akselos), aeroelastic lifetime (DNV), grid calculation with state estimation (PowerFactory) | simple atoms: characteristic curves, single-zone house (1R1C), ideal gas in the H2 line |
| **Security and attestations** | customers in areas close to critical infrastructure expect attestations, for example to IEC 62443 or ISO/IEC 27001 (which vendors hold which ones was not verified individually) | no certification, no security concept for the return path beyond the approval principle |
| **Integration into control systems** | DCS and SCADA vendors deliver twin and control system from a single source (Emerson, ABB, Siemens, PSI) | Modbus reader; IEC 104 and OPC UA planned |
| **Support and ecosystem** | service, SLA, training, partner networks, marketplaces | small project team, community being built |
| **User interface** | 3D and photorealistic scenes (Omniverse, iTwin), mature operator interfaces | static Studio prototype |
| **Financial strength and bundling** | Siemens in the H2 segment; E.ON with envelio and gridX | open source project |

### 7.2 Where OpenTSx differs

1. **One set of building blocks, three bindings.** The same atoms run as shadow, as what-if and as forecast. The
   operating policy (schedule, human, optimizer) is exchangeable via the same interface. A proposal can be played
   through in the twin before approval. ETAP comes closest to this; whether ETAP models operating policies so that they
   are exchangeable is not verified (section 8).
2. **Provenance per value down to every KPI.** Five classes with taint propagation; every series carries its
   provenance per step, and from this the measured share of every KPI can be reported (proposed as a percentage
   display, D21). No counterpart in the sources checked.
3. **Bit-identical replay on an open log.** Run id from the wiring plan, inputs, interventions and code version;
   episodes with checksums on KafScale. This makes it possible to recompute disputed cases and audits.
4. **Federable with a design rule.** Atoms can run at different owners; the measured rule says what must stay tightly
   coupled. So far shown only in the work branch on simulated wall-clock time.
5. **Evaluation principle as a product rule.** Coordination potential = coordinated alternative − reference
   operation, with the same boundary conditions and the same information. Modeled potential and proven benefit stay
   separate, including in the user interface.
6. **Start open and small.** Apache 2.0, Kafka protocol, Iceberg, OpenMetadata; stage A (data and potential)
   needs no simulation; the same method from the house to the cluster.
7. **Time series foundation.** Episodes, streaming statistics, data quality, pattern search and a wind farm simulator
   with ground truth exist and provide a test bench that twin vendors rarely include.

### 7.3 What is not a unique selling point

- Coupling live data and simulation (section 1, item 1).
- What-if scenarios and forecasts.
- State estimation and calibration.
- "Open" alone: open co-simulation and IoT building blocks are plentiful.

### 7.4 Consequences

- **Positioning** through the combination and the decision benefit, not through the coupling (Part 3).
- **Complement, do not replace:** grid twins, APM systems, electrolyzer suites and control systems deliver ports to
  the cluster twin. OpenTSx does not compete with their core.
- **Close the gaps that customers check first:** real data (stage A), security concept for the return path, IEC 104
  and OPC UA, Kalman-based shadow, scaling test.

---

## 8. Not verified and open points

- Provenance per value at ETAP, AVEVA, Cognite and Siemens (criterion d): ask specifically in demos.
- Whether ETAP's *Predictive Simulation* branches from the current live state and whether operating policies are
  exchangeable.
- Current status of DNV WindGEMINI, GE Digital Wind Farm, Sentient Science and Siemens Gamesa with NVIDIA.
- Twin offerings of Vestas, Nordex and Bazefield.
- Content of ISO/IEC 30186:2025 (requires payment), SSP standard, OpenModelica, Databricks.
- State estimator in Siemens Gridscale X (not described by name).
- Status of AWS IoT TwinMaker: no official announcement found; a third-party source says otherwise.
- Fabric digital twin builder without a simulation engine (third-party source only).
- License of FIWARE Orion-LD; terms of Omniverse and Victron Dynamic ESS.
- Completion of the Uptake acquisition by Bosch (announced, completion not confirmed).

---

## 9. Sources (retrieved 2026-10-12)

**Standards, definitions, research**
- ISO/IEC 30173:2023: https://www.iso.org/standard/81442.html
- ISO/IEC 30186:2025: https://knowledge.bsigroup.com/products/bs-iso-iec-30186-digital-twin-maturity-model-and-guidance-for-a-maturity-assessment
- ISO 23247: https://committee.iso.org/standard/75066.html
- DTC Capabilities Periodic Table: https://www.digitaltwinconsortium.org/wp-content/uploads/sites/3/2022/06/Digital-Twin-Capabilities-Periodic-Table-User-Guide.pdf
- Kritzinger et al. 2018: https://doi.org/10.1016/j.ifacol.2018.08.474
- National Academies: https://www.nationalacademies.org/news/2023/12/new-report-urges-multiagency-action-to-support-potentially-transformative-digital-twins-research
- AWS L4 Living Twin: https://aws.amazon.com/blogs/iot/l4-living-digital-twins/ · https://aws.amazon.com/blogs/hpc/deploying-level-4-digital-twin-self-calibrating-virtual-sensors-on-aws
- Executable Digital Twin: https://arxiv.org/pdf/2210.17402
- Co-simulation: https://arxiv.org/pdf/1702.00686 · https://arxiv.org/pdf/1809.08463 · https://2014.international.conference.modelica.org/proceedings/html/submissions/ECP14096213_Viel.pdf
- SSP: https://proceedings.nips.cc/paper_files/paper/2013/file/b7bb35b9c6ca2aee2df08cf09d7016c2-Paper.pdf
- Iteration bound: https://www.eecs.yorku.ca/course_archive/2011-12/W/4210/L4_it_bound.pdf
- FMI 3.0: https://fmi-standard.org/faq/ · https://ecp.ep.liu.se/index.php/modelica/article/view/179
- EnKF twin: https://www.sciencedirect.com/science/article/abs/pii/S0149197026000703
- OPC UA: https://reference.opcfoundation.org/Core/Part8/v104/docs/?r=A.7&t=1
- IEC 61850 via CIM: https://modellen.netbeheernederland.nl/tc57cim/cim17/schema/class/Quality61850.html
- W3C PROV: https://arxiv.org/pdf/2507.01075
- TwinEU: https://ie3.etit.tu-dortmund.de/research/third-party-projects/distribution-grid-planning-operation/twineu/
- Catena-X: https://catena-x.net/fileadmin/user_upload/Standard-Bibliothek/Update_Januar_2024/CX-0002-DigitalTwinsInCatenaX-v2.1.0.pdf

**Vendors**: all links in sections 4 and 5; in addition:
- Siemens Simcenter Smart Virtual Sensing: https://blogs.sw.siemens.com/simcenter/simcenter-3d-smart-virtual-sensing-unveiling-the-magic/
- Siemens Gridscale X (Trieste, planning + operation): https://press.siemens.com/global/en/pressrelease/acegasapsamga-leverages-siemens-gridscale-x-build-digital-twin-triestes-energy-grid · https://press.siemens.com/global/en/pressrelease/siemens-gridscale-x-redefines-system-operations-and-agentic-transmission-planning
- Siemens H2: https://resources.sw.siemens.com/en-US/green-hydrogen-digital-twin-for-hystar-electrolyzer-excellence/ · https://assets.new.siemens.com/siemens/assets/api/uuid:e2190682-94a3-4933-8f45-8d1faf717832/DI-PA-Simplifying-Green-H2-Production-Brief.pdf
- Cognite: https://hub.cognite.com/simulator-integration-388/simulator-integration-service-now-generally-available-in-cognite-data-fusion-4304
- AWS lifecycle: https://aws.amazon.com/about-aws/whats-new/2025/10/aws-service-availability/ · https://dev.classmethod.jp/en/articles/aws-service-lifecycle-summary-20260630/ · https://thingshost.de/en/blog/aws-iot-service-retirements-2026 · https://docs.aws.amazon.com/iot-twinmaker/latest/guide/what-is-twinmaker.html
