# OpenTSx: Business documents on market, customers and campaign

> As of: **2026-10-12** (retrieval date of all web sources). Language: English. These documents answer a question from
> the owner and are a working basis, not texts ready for publication. Before any publication, the
> [claims guide](03-MARKETING-KAMPAGNE.md) applies.

## 1. The question

> "Do deep research on vendors of digital twins: is there a direct coupling of live data and simulated data as in our
> model? Turn this into a marketing campaign. Who are the customers for it? What benefit do we offer them?"

## 2. Short answers

1. **Do others offer the coupling of live and simulation data?** Yes. It is widespread and often more mature than in
   OpenTSx. Examples are state estimation in grid tools (DIgSILENT, ETAP, VENIOS, envelio, Siemens Gridscale X),
   process twins with live data (AVEVA with PI System, Cognite Simulator Integration, Siemens Hydrogen Performance
   Suite since 2026-10-08) and component twins with a Kalman filter or calibration (Siemens xDT, Ansys Twin Builder).
   We did **not find** the combination OpenTSx pursues: the same building blocks in three operating modes with an
   exchangeable operating policy, provenance per value down to the KPIs, bit-identical replay on an open log, federable
   execution across ownership boundaries, open under Apache 2.0.
   → [Part 1: Market research](01-MARKTRECHERCHE-DIGITAL-TWINS.md)
2. **Who are the customers?** Priority goes to (1) clusters of wind farm, electrolyzer, storage, offtaker and
   marketing, (2) municipal utilities, and through them energy communities, (3) research, labs and engineering
   consultancies. The house and the boat are narrative anchors.
   → [Part 2: Customers and benefits](02-KUNDEN-UND-NUTZEN.md)
3. **What benefit do we offer?** The coordination potential is quantified (coordinated alternative minus reference
   operation, same information), decisions are played through before anyone acts, every number carries its
   provenance, runs are reproducible bit for bit, data sovereignty stays with the asset owner and the first step is
   small (stage A without simulation). → [Part 2](02-KUNDEN-UND-NUTZEN.md)
4. **The campaign:** "Compute first, then switch." Three phases (credibility, lighthouses, scaling), a 90-day plan from
   2026-10-19 with a weekly calendar, a message house, a claims guide, three sample posts and an outline of the landing
   page. → [Part 3: Marketing campaign](03-MARKETING-KAMPAGNE.md)

## 3. Documents

| Document | Content |
|----------|--------|
| [01-MARKTRECHERCHE-DIGITAL-TWINS](01-MARKTRECHERCHE-DIGITAL-TWINS.md) | Assessment criteria a to h, OpenTSx status, vendor profiles, comparison matrix, research and standards, honest assessment, items not verified, sources |
| [02-KUNDEN-UND-NUTZEN](02-KUNDEN-UND-NUTZEN.md) | Benefit building blocks, sourced market figures and rules, eleven segments with personas, objections, willingness to pay and buying decision makers, prioritization, business models |
| [03-MARKETING-KAMPAGNE](03-MARKETING-KAMPAGNE.md) | The campaign on one page, positioning, category name, taglines, message house, pitches, claims guide, phases, 90-day plan, assets, channels with checked trade fair dates, funnel and KPIs, budget, partners, risks, three LinkedIn posts, landing page |
| [kampagnen-briefing.html](kampagnen-briefing.html) | Campaign briefing as a page (as of 2026-10-12): honesty bar, vendor matrix, differences, target groups, message house, claims, 90-day plan, trade fairs, first post, open decisions; open in a browser |

## 4. Method

- **Project status** from the repository: README, concepts [05](../datamodel-2026/05-SMART-TOPICS.md) to
  [11](../datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md), [DevOps guide](../devops/README.md),
  [TASK-011](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md) and the code of `opentsx-twin`. Work branches that
  are not yet in `master` (loose coupling with measurement, DWD collector, asset explorer) are marked as such.
- **Web research** on 2026-10-12: web search and retrieval of primary sources (product pages, documentation,
  press releases, standards, technical articles, official and association statistics). Where only secondary sources
  were available, this is noted.
- **Rating** of each vendor against eight criteria (a to h) on the scale ● documented, ◐ partial, ○ not found,
  ? not verified ([Part 1, section 2](01-MARKTRECHERCHE-DIGITAL-TWINS.md)).
- **Market figures** only with a source and a reference date; own calculations are marked.

## 5. Limits and caveats

- **Self-reported information:** The rating of the vendors is based on their own publications. There were no tests,
  demos or conversations. "Not found" means "not found in the sources checked".
- **OpenTSx maturity:** Proof of concept on synthetic and simulated data, **no production customer**. A project
  setting with a wind farm, an electrolyzer and direct marketing of the power is in preparation; partners appear only
  as roles.
- **Addendum 2026-10-12, afternoon:** The parts these documents name as work branches (loose coupling with
  measurement and Avro schemas, DWD collector, asset explorer, repository hygiene) are now in `master`
  (integration #116). So are the Modbus collector (#115), the operations concept with probe run (#137) and the
  dependency maintenance (#131). The ratings remain valid: everything still runs on synthetic and simulated data.
  This completes the first work package of phase 0 ("merge of the work branches").
- **Hypotheses:** Pain points, willingness to pay, buying decision makers, KPI targets and budget amounts are
  assumptions. They are to be checked through conversations or quotes.
- **Time reference:** Rules and trade fair dates change. The GModG (building modernization law) has applied since
  2026-07-29, energy sharing since 2026-06-01 and Section 14a EnWG (Energy Industry Act) since 2024-01-01. Check them
  again before use.
- **No legal advice:** Statements on laws, comparative advertising and terms are pointers, not a legal review. A
  trademark and name search was not done.

## 6. Decisions for the owner

1. Category name: "decision twin" (market) and "Digital Twins on Logs" (technology)? A trademark search comes first.
2. Budget tier for the 90 days ("low" or "medium", Part 3, section 10).
3. Outreach to the pilot partners (cluster, municipal utility, lab) and the scope of the stage A offer.
4. Merge of the work branches before the start, so that demos and posts refer to `master`.
5. Approval of the white paper, the preprint and the three sample posts.
