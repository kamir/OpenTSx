# OpenTSx – Business-Unterlagen: Markt, Kunden, Kampagne

> Stand: **12.10.2026** (Abrufdatum aller Webquellen). Sprache: Deutsch. Diese Unterlagen beantworten eine Frage des
> Owners und sind Arbeitsgrundlage, keine veröffentlichungsfertigen Texte. Vor jeder Veröffentlichung gilt der
> [Claims-Leitfaden](03-MARKETING-KAMPAGNE.md).

## 1. Die Frage

> „Mache eine tiefe Recherche nach Anbietern von Digital Twins, gibt es da eine direkte Ankopplung von Live-Daten und
> simulierten Daten wie in unserem Modell? Mache daraus eine Marketing-Kampagne. Wer sind die Kunden dafür? Welchen
> Nutzen bieten wir denen?“

## 2. Kurzantworten

1. **Gibt es die Kopplung von Live- und Simulationsdaten bei anderen?** Ja, verbreitet und oft reifer als bei OpenTSx.
   Beispiele sind Zustandsschätzung in Netzwerkzeugen (DIgSILENT, ETAP, VENIOS, envelio, Siemens Gridscale X),
   Prozesszwillinge mit Live-Daten (AVEVA mit PI System, Cognite Simulator Integration, Siemens Hydrogen Performance
   Suite seit 08.10.2026) und Komponentenzwillinge mit Kalman-Filter oder Kalibrierung (Siemens xDT, Ansys Twin Builder).
   **Nicht gefunden** haben wir die Kombination, die OpenTSx verfolgt: dieselben Bausteine in drei Betriebsarten mit
   austauschbarer Fahrweise, Herkunft je Wert bis in die Kennzahlen, bitgleiches Replay auf einem offenen Log,
   föderierbare Ausführung über Eigentümergrenzen, offen unter Apache 2.0.
   → [Teil 1: Marktrecherche](01-MARKTRECHERCHE-DIGITAL-TWINS.md)
2. **Wer sind die Kunden?** Vorrang haben (1) Verbünde aus Windpark, Elektrolyseur, Speicher, Abnehmer und
   Vermarktung, (2) Stadtwerke und über sie Energiegemeinschaften, (3) Forschung, Labore und Ingenieurbüros. Haus und
   Boot sind Erzählanker. → [Teil 2: Kunden und Nutzen](02-KUNDEN-UND-NUTZEN.md)
3. **Welchen Nutzen bieten wir?** Das Koordinationspotenzial wird beziffert (koordinierte Alternative minus
   Referenzbetrieb, gleiche Information), Entscheidungen werden vor dem Handeln durchgerechnet, jede Zahl trägt ihre
   Herkunft, Läufe sind bitgleich nachvollziehbar, die Datenhoheit bleibt beim Eigentümer und der Einstieg ist klein
   (Stufe A ohne Simulation). → [Teil 2](02-KUNDEN-UND-NUTZEN.md)
4. **Die Kampagne:** „Erst rechnen, dann schalten.“ Drei Phasen (Glaubwürdigkeit, Leuchttürme, Skalierung), ein
   90-Tage-Plan ab 19.10.2026 mit Wochenkalender, Botschaftshaus, Claims-Leitfaden, drei Beispielbeiträge und eine
   Gliederung der Landingpage. → [Teil 3: Marketing-Kampagne](03-MARKETING-KAMPAGNE.md)

## 3. Dokumente

| Dokument | Inhalt |
|----------|--------|
| [01-MARKTRECHERCHE-DIGITAL-TWINS](01-MARKTRECHERCHE-DIGITAL-TWINS.md) | Prüfkriterien a–h, OpenTSx-Stand, Anbieterprofile, Vergleichsmatrix, Forschung und Standards, ehrliche Einordnung, nicht Verifiziertes, Quellen |
| [02-KUNDEN-UND-NUTZEN](02-KUNDEN-UND-NUTZEN.md) | Nutzenbausteine, belegte Marktzahlen und Regeln, elf Segmente mit Personas, Einwänden, Zahlungsbereitschaft und Einkaufsentscheidern, Priorisierung, Geschäftsmodelle |
| [03-MARKETING-KAMPAGNE](03-MARKETING-KAMPAGNE.md) | Kampagne auf einer Seite, Positionierung, Kategoriename, Taglines, Botschaftshaus, Pitches, Claims-Leitfaden, Phasen, 90-Tage-Plan, Assets, Kanäle mit geprüften Messeterminen, Funnel und KPIs, Budget, Partner, Risiken, drei LinkedIn-Beiträge, Landingpage |
| [kampagnen-briefing.html](kampagnen-briefing.html) | Kampagnen-Briefing als Seite (Stand 12.10.2026): Ehrlichkeitsleiste, Anbietermatrix, Unterschiede, Zielgruppen, Botschaftshaus, Claims, 90-Tage-Plan, Messen, erster Beitrag, offene Entscheidungen; im Browser öffnen |

## 4. Methode

- **Projektstand** aus dem Repository: README, Konzepte [05](../datamodel-2026/05-SMART-TOPICS.md) bis
  [11](../datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md), [DevOps-Leitfaden](../devops/README.md),
  [TASK-011](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md) und Code von `opentsx-twin`. Arbeitszweige, die
  noch nicht in `master` sind (lose Kopplung mit Messung, DWD-Sammler, Anlagen-Explorer), sind als solche
  gekennzeichnet.
- **Webrecherche** am 12.10.2026: Websuche und Abruf von Primärquellen (Produktseiten, Dokumentation,
  Pressemitteilungen, Standards, Fachartikel, amtliche und Verbandsstatistiken). Wo nur Sekundärquellen vorlagen, ist
  das vermerkt.
- **Bewertung** je Anbieter nach acht Kriterien (a–h) mit der Skala ● belegt, ◐ teilweise, ○ nicht gefunden,
  ? nicht verifiziert ([Teil 1 §2](01-MARKTRECHERCHE-DIGITAL-TWINS.md)).
- **Marktzahlen** nur mit Quelle und Stichtag; eigene Rechnungen sind markiert.

## 5. Grenzen und Vorbehalte

- **Selbstauskünfte:** Die Bewertung der Anbieter beruht auf deren eigenen Veröffentlichungen. Es gab keine Tests,
  Demos oder Gespräche. „Nicht gefunden“ heißt „in den geprüften Quellen nicht gefunden“.
- **OpenTSx-Reifegrad:** Proof of Concept auf synthetischen und simulierten Daten, **kein Produktivkunde**. Ein
  Projektumfeld mit Windpark, Elektrolyseur und Direktvermarktung ist in Vorbereitung; Partner erscheinen nur als
  Rollen.
- **Nachtrag 12.10.2026, nachmittags:** Die in den Unterlagen als Arbeitszweige genannten Teile (lose Kopplung mit
  Messung und Avro-Schemas, DWD-Sammler, Anlagen-Explorer, Repository-Hygiene) sind inzwischen in `master`
  (Integration #116), ebenso der Modbus-Sammler (#115), das Betriebskonzept mit Probelauf (#137) und die
  Abhängigkeitspflege (#131). Die Bewertungen bleiben gültig: alles läuft weiterhin auf synthetischen und simulierten
  Daten. Damit ist das erste Arbeitspaket von Phase 0 („Merge der Arbeitszweige“) erledigt.
- **Hypothesen:** Schmerzpunkte, Zahlungsbereitschaft, Einkaufsentscheider, KPI-Ziele und Budgetbeträge sind Annahmen
  und mit Gesprächen bzw. Angeboten zu prüfen.
- **Zeitbezug:** Regeln (GModG seit 29.07.2026, Energy Sharing seit 01.06.2026, § 14a seit 01.01.2024) und
  Messetermine ändern sich; vor Verwendung erneut prüfen.
- **Keine Rechtsberatung:** Aussagen zu Gesetzen, vergleichender Werbung und Begriffen sind Hinweise, keine
  rechtliche Prüfung. Eine Marken- und Namensrecherche wurde nicht durchgeführt.

## 6. Entscheidungen beim Owner

1. Kategoriename: „Entscheidungszwilling“ (Markt) und „Digital Twins on Logs“ (Technik)? Vorher Markenrecherche.
2. Budgetstufe für die 90 Tage („niedrig“ oder „mittel“, Teil 3 §10).
3. Ansprache der Pilotpartner (Verbund, Stadtwerk, Labor) und Umfang des Stufe-A-Angebots.
4. Merge der Arbeitszweige vor dem Start, damit Demos und Beiträge auf `master` verweisen.
5. Freigabe von Whitepaper, Preprint und den drei Beispielbeiträgen.
