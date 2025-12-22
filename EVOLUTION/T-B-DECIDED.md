# To Be Decided (T-B-DECIDED)

Collection of open questions, pending decisions, and unassigned tasks from the EVOLUTION roadmap.

## TSx Track Polyglot Notebooks (TASK-007)

From `DECISION-TSx-Track-Polyglot-Notebooks.md` and `TASK-007-POC-TSx-Polyglot-Notebooks-Week1.md`:

- [ ] **Approval**: Decision document requires signatures from Product Owner, Tech Lead, and Budget Owner.
- [ ] **Go/No-Go Decision**: Scheduled for 2025-12-26 (Day 5 of POC).
    - Criteria: User ratings, Setup time, Technical feasibility.
- [ ] **Kernel Selection fallback**: If IJava fails, what is the definitive fallback? (JShell vs BeakerX vs Separate Notebooks).
- [ ] **Hosting**: Will we provide a Docker image or cloud-based environment (Binder/Colab) to mitigate setup issues?

## Application Tools

- [ ] **Renaming**: Module `application-tools` seems to be outside the `opentsx-*` naming convention.
    - Status: User recalls a renaming. Currently it is `application-tools`.
    - Action: Needs to be renamed to `opentsx-app-tools` (or similar) and added to root POM.

## General Evolution

- [ ] **Roadmap Visibility**: Should high-level roadmap items be visible in the main `README.md`?
