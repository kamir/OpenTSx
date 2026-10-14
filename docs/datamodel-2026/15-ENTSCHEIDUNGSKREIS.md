# OpenTSx: Entscheidung, Streaming-Vertrag und Wirkungskreis (Überarbeitung des Plans)

> Stand: 2026-10-13. **Entschieden am 2026-10-13** mit zwei Änderungen (D57, D62; Abschnitt 11). Dieses Dokument verarbeitet ein externes Review-Briefing (Engineering
> und komplexe Systeme) zum Methodik-Leitfaden und zum Plan aus
> [14](14-ENGINEERING-FLOW.md). Es ändert den Plan ab der Lieferstufe nach L3 und schärft die Verträge. Umgesetzt
> ist davon: die Korrektur des H2-Beispiels; Rolle, Bedeutung und Betriebsbereich im Systemdokument und in den
> Details eines Signals; eine Kennung für bereinigte Episoden; zwei Schema-Entwürfe mit Beispiel. Alles andere
> folgt in den Stufen, in der Reihenfolge aus D62: erst L4 und L5, dann E1 bis E3. Ein zweites Briefing (das
> Jahresmodell eines Windparks mit Wasserstoff) ist in Abschnitt 13 verarbeitet und am selben Tag entschieden (D64
> bis D66): Der Systemplan und das reale Jahr 2025 kommen vor E1. Arbeitspaket:
> [TASK-014](../../EVOLUTION/TASK-014-engineering-flow.md).

## 1. Kurzfassung

Das Briefing sagt: Die Grundlage trägt (erwartete Signale, sichtbare Lücken, nachvollziehbare Aufbereitung), aber
für eine Workbench, mit der man ein System **führt**, fehlen drei Dinge: der verbindliche Bezug zu einer
Entscheidung, eine belastbare Streaming-Semantik und der geschlossene Kreis von der Empfehlung über die Handlung zur
Wirkung. Der wichtigste Satz: **Die zentrale Einheit ist eine überprüfbare Entscheidung.** Signale, Flows, Modelle
und Topics liefern ihr die Grundlage.

Ich halte das Briefing in der Hauptsache für richtig. Es hat den Code nicht gesehen; an drei Stellen ist der Stand
weiter, als es annimmt, an einer hat es einen fachlichen Fehler im Beispiel gefunden, den niemand von uns bemerkt
hatte. Abschnitt 2 geht die Befunde einzeln durch.

Was sich ändert:

1. **Die Entscheidungskarte** wird das Arbeitsobjekt, an dem Fachseite und Technik dasselbe pflegen (Abschnitt 3).
2. **Das Signal bekommt Bedeutung:** eine Rolle (Beobachtung, Schätzung, Kommando, Grenze), zwei getrennte
   Wertebereiche (was der Sensor messen kann, was im Betrieb normal ist) und einen Zustand in mehreren Dimensionen
   statt eines einzigen Wortes (Abschnitt 4).
3. **`clean` heißt nicht mehr "erfüllt die Erwartung".** Bereinigen entfernt keine auffälligen Werte, es
   kennzeichnet sie. Rohwerte bleiben (Abschnitt 5).
4. **Ein Streaming-Vertrag** legt Zeit, Endgültigkeit, Verspätung, Doppelte, Wiederanlauf und Revision fest
   (Abschnitt 6, ausgeführt in [11](11-SMART-TOPIC-PROZESSOR-DEVOPS.md), Abschnitt 7a).
5. **Ein Modell wird für einen Zweck freigegeben,** nicht dadurch, dass seine Eingänge sauber sind (Abschnitt 7).
6. **Empfehlung, Freigabe, Maßnahme und Wirkung** werden eigene Datensätze (Abschnitt 8).
7. **Der Plan** nach L3 wird neu geordnet: erst Entscheidungskarte und fachliche Verträge, dann ein durchgängiger
   Streaming- und Replay-Pfad, dann der Handlungs- und Lernkreis (Abschnitt 10).

## 2. Die Befunde des Briefings, einzeln

"Stand" sagt, was im Repository heute gilt. Das Briefing konnte das nicht wissen.

| Nr. | Befund | Einschätzung | Stand heute | Folge |
|---|---|---|---|---|
| B1 | Der Einstieg ist das System; die Geschäftsentscheidung fehlt | stimmt | die Hülle beginnt bei System und Signals; eine Entscheidung gibt es als Objekt nicht | Entscheidungskarte (Abschnitt 3), neuer erster Schritt der Methode |
| B2 | "Clean meets the expectation" kann echte Ereignisse wegputzen | stimmt, und L3 tut genau das | `dq.plausible-range` macht Werte außerhalb des Bereichs zur Lücke oder begrenzt sie; das Qualitätsbyte der v2-Episode bleibt ungenutzt, obwohl der Kanal-Katalog `IMPLAUSIBLE` als "Wert bleibt" definiert | `clean` neu definiert, Modus `flag`, zwei Wertebereiche (Abschnitte 4 und 5) |
| B3 | Ein exklusiver Signalstatus verdeckt Information | stimmt | ein Zustand je Signal, daneben nur das Kennzeichen "out of range" | Zustand in fünf Dimensionen, Verwendbarkeit je Zweck (Abschnitt 4.3) |
| B4 | "Worst origin" ist keine Rangfolge | stimmt | `Origin.worst` im Zwilling, `Systems.origin` rechnet danach | Herkunft als Ableitungsgraph mit Anteilen; `worst` bleibt nur als grobe Marke "enthält Synthetisches" (Abschnitt 4.4) |
| B5 | Plausible Einzelwerte ergeben kein plausibles System | stimmt | der geplante Generator `gen.ou` erzeugt eine Reihe für sich, aus Bereich und Takt | Demo-Generator und Szenariomodell werden getrennte Dinge (Abschnitt 5.3) |
| B6 | "Follows the topic" lässt Verspätung, Doppelte, Neustart, Korrektur offen | stimmt teilweise | für Vektoren und bereinigte Reihen ist festgelegt: Abschluss eines Ticks, `lateness`, Reihenfolge je Reihe, Checkpoint, Wiederanlauf mit möglichen Doppelten; **nicht** festgelegt sind Ereignisidentität, Revision und der damalige Informationsstand | Streaming-Vertrag (Abschnitt 6). Der Lauf auf master2 vom selben Tag hat den Befund bestätigt: einem bereinigten Signal fehlte eine Episode, weil ein verweigerter Abruf Datensätze verschluckte |
| B7 | Saubere Eingänge beweisen keine Modellgüte | stimmt | der Ort "Models" sagt nur, welche Signale bereinigt vorliegen | Freigabe je Analyse mit Gültigkeitsbereich, Nachweis und Unsicherheit (Abschnitt 7) |
| B8 | Ergebnisse neben Messungen zu zeigen schließt keinen Kreis | stimmt | Zwilling rechnet Was-wäre-wenn; es gibt keine Empfehlung, Freigabe oder Maßnahme als Objekt | vier Datensätze (Abschnitt 8) |
| B9 | H2-Beispiel: Summe der gemessenen Turbinenleistung ist nicht die "verfügbare Leistung" | **stimmt, fachlicher Fehler** | das Systemdokument band `park.p_avail_kw` an die Summe der gemessenen Leistung | korrigiert (Abschnitt 9) |
| B10 | "Ein Writer pro Topic" ist zu grob | schon anders | im Topic der bereinigten Reihen gilt "ein Signal, ein Schreiber"; geprüft wird das nur im Frontend | Zuständigkeit je logischem Ergebnis in den Vertrag; Prüfung in den Server (Abschnitt 6) |
| B11 | Das Beispiel zeigt fortlaufende Aufbereitung, die es nicht gibt | traf zu, als der Leitfaden entstand | seit L3 läuft die Kette aus Bereich, Einheit und Resampling für eine Reihe; die Summe über Turbinen und alles mit mehreren Eingängen läuft nicht | Leitfaden nennt je Schritt des Beispiels, was läuft |
| B12 | "Sechs externe Eingaben" gegen "zwei offene von acht" | stimmt, und die Zahl war falsch | der Schaltplan hat **neun** Eingänge von außen; sechs haben ein Signal, drei keines (`offtake.valve`, `grid.p_self_kw`, `grid.p_grid_meas_kw`) | Leitfaden und Beispiel korrigiert |
| B13 | Kein universeller Graph-Editor zuerst | schon so | der Ort System ist eine Tabelle; der Graph der Workbench gilt einem Flow, nicht dem System | bleibt Regel |
| B14 | Bedienung ohne YAML und Topic-Namen | stimmt als Ziel | Bindungen stehen von Hand im Systemdokument; Veröffentlichen aus der Workbench geht ohne beides | Abnahmekriterium der ersten Version (Abschnitt 10) |

Zwei Stellen übernehme ich mit Vorbehalt:

* Das Briefing verweist für die Zweckbindung der Glaubwürdigkeit auf eine NIST-Veröffentlichung und für Watermarks
  und Trigger auf die Dokumentation von Apache Beam. Beide Verweise sind hier **nicht nachgeprüft**. Die Aussagen,
  die daraus folgen, stehen in diesem Dokument als eigene Festlegungen, nicht als Zitat.
* "Eine bessere Entwicklung nach einer Empfehlung beweist nicht deren Wirkung." Das ist richtig und hat eine
  unbequeme Folge: Mit den Daten, die wir haben (ein Simulator-Szenario von sechs Stunden), lässt sich keine
  Wirksamkeit zeigen, nur der Mechanismus. Abschnitt 8.3 sagt, was eine Vergleichsbasis wäre.

## 3. Die Entscheidung als zentrale Einheit

### 3.1 Die Entscheidungskarte

Eine Entscheidung ist eine wiederkehrende Frage mit jemandem, der sie beantwortet. Beispiel: "Welchen Fahrplan
fahren Elektrolyseur und Batterie in der nächsten Stunde?"

Schema: [`schemas/opentsx.decision.v1.schema.json`](schemas/opentsx.decision.v1.schema.json), Beispiel:
[`systems/decisions/h2-dispatch.decision.yaml`](../../systems/decisions/h2-dispatch.decision.yaml).

| Teil | Inhalt |
|---|---|
| Frage | was entschieden wird, in einem Satz |
| Verantwortung | wer entscheidet, wer freigibt, wer ausführt |
| Takt und Horizont | wie oft entschieden wird, für welchen Zeitraum, bis wann die Entscheidung stehen muss |
| Ziele | was besser werden soll, mit Kennzahl und Richtung |
| Stellgrößen | die Ports des Systems, über die gehandelt wird |
| Grenzen | was nie verletzt werden darf (Druck, Ladezustand, Lieferpflicht) |
| Bedarf | welche Signale die Entscheidung braucht, je Signal mit Güte, höchstem Alter und zulässiger Herkunft |
| Analysen | welche Modelle oder Regeln eine Empfehlung liefern, je mit Gültigkeitsbereich und Nachweis (Abschnitt 7) |
| Alternativen | die Strategien, die verglichen werden, darunter immer eine einfache Referenz |

Die Karte ist der Maßstab für alles Weitere. Ob ein Signal "gut genug" ist, beantwortet nicht das Signal, sondern
der Bedarf der Entscheidung: Der Ladezustand der Batterie darf für eine Trendanalyse eine Stunde alt sein, für den
Fahrplan der nächsten Viertelstunde nicht.

### 3.2 Drei Ansichten auf dasselbe Objekt

| Ansicht | Zeigt | Für wen |
|---|---|---|
| **Entscheidung** | Frage, Ziele, Grenzen, Alternativen mit Unsicherheit, Empfehlung, Freigabe | Fachseite |
| **System** | Bausteine, Zustände, Flüsse, Stellgrößen, Verzögerungen | beide |
| **Nachweis** | Quellen, Datenstand, Aufbereitung, Modellversion, Annahmen, Laufzeitzustand | Technik |

Der Weg zwischen ihnen ist die Funktion: Von "Lieferziel gefährdet" führt ein Klick zum betroffenen Speicher, zur
Annahme und zur Datenquelle. Umgekehrt zeigt eine technische Änderung, welche Entscheidungen sie berührt. Die Orte
der Hülle bleiben; sie ordnen sich darunter ein: System und Signals sind die Ansicht "System" und ein Teil von
"Nachweis", Flows ist "Nachweis", Models wird "Entscheidung".

### 3.3 Vier Arten von Beziehungen

Ein Graph, der alles mit Linien verbindet, behauptet mehr, als er weiß. Die Arten bleiben getrennt, in den Daten
und im Bild:

| Art | Bedeutet | Steht in |
|---|---|---|
| physisch | Energie oder Material fließt von A nach B | Schaltplan (`wiring`) |
| Datenherkunft | B wurde aus A berechnet | Flows, Provenienz der Episoden |
| Wirkannahme | ein Eingriff an A ändert B, mit Verzögerung | Modell (Dynamik der Bausteine), als Annahme gekennzeichnet |
| Verantwortung | wer entscheidet, gibt frei, führt aus | Entscheidungskarte |

Eine Datenabhängigkeit beweist keine Wirkung. Dass die Prognose den Windmesser liest, heißt nicht, dass der Wind
die Erzeugung bestimmt, solange abgeregelt wird.

## 4. Das Signal, geschärft

### 4.1 Rolle

Gleiche Einheit heißt nicht gleiche Größe. Jedes Signal bekommt eine Rolle:

| `role` | Bedeutung | Beispiel |
|---|---|---|
| `observation` | was war: gemessen oder gezählt | tatsächliche Erzeugung |
| `estimate` | was sein könnte oder vermutet wird: aus anderen Größen berechnet | verfügbares Erzeugungspotenzial |
| `command` | was verlangt wurde: Sollwert, Fahrplan, Abregelung | Sollwert des Elektrolyseurs |
| `limit` | was nicht überschritten werden darf | Netzgrenze, Höchstdruck |
| `exogenous` | was von außen kommt und nicht beeinflusst wird | Wind, Preis, Bedarf |

Dazu ein Satz `meaning`, der sagt, was die Größe ist. Vorschläge für eine Bindung dürfen Metrik und Einheit nutzen,
verbunden wird erst, wenn ein Mensch die Bedeutung bestätigt hat.

### 4.2 Zwei Wertebereiche

Die erste Fassung hatte einen Bereich je Signal und nannte ihn "erwartet". Es sind zwei verschiedene Fragen:

| Bereich | Frage | Verletzt heißt | Quelle |
|---|---|---|---|
| `min`, `max` (Plausibilität) | Kann der Sensor diesen Wert liefern? | die **Messung** ist fraglich | Kanal-Katalog |
| `operating` (Betrieb) | Ist dieser Wert im normalen Betrieb zu erwarten? | das **System** ist in einem besonderen Zustand; die Messung kann völlig richtig sein | Systemdokument |

Eine Böe von 28 m/s liegt im plausiblen Bereich des Anemometers (0 bis 60) und außerhalb des Betriebsbereichs.
Sie ist die wertvollste Information des Tages und darf keine Lücke werden.

### 4.3 Zustand in Dimensionen

Statt eines Wortes fünf Angaben, jede für sich berechnet:

| Dimension | Werte | Aus |
|---|---|---|
| Verfügbarkeit | offen, gebunden ohne Daten, Daten da | Systemdokument, TS-Inventar |
| Gültigkeit der Messung | Anteil fraglicher Werte (außerhalb der Plausibilität, vom Sensor markiert) | Qualitätsbyte der Episoden |
| Betriebszustand | im Betriebsbereich, außerhalb seit wann | Wertebereich `operating` |
| Aktualität | Alter des letzten Werts gegen den Takt | TS-Inventar, laufende Flows |
| Aufbereitung | roh, bereinigt (läuft oder steht), Merkmale | Topics `clean`, `features`, Zustand der Flows |

Was ein Mensch sieht, ist ein Satz je Zweck, abgeleitet aus dem Bedarf einer Entscheidung: "Für die Trendanalyse
verwendbar. Für den Fahrplan gesperrt: letzter Ladezustand 40 Minuten alt, erlaubt sind 5." Die Liste Signals zeigt
weiter einen nächsten Schritt je Zeile; er folgt aus der Dimension, die als erste nicht passt.

### 4.4 Herkunft als Ableitung

`Origin.worst` macht aus einer Summe von neun Messungen und einer Schätzung eine "Schätzung" und aus einer
fehlerhaften Messung eine "Messung". Künftig:

* Die Herkunft eines abgeleiteten Signals ist der **Weg**: welche Signale, welcher Flow in welcher Fassung, welche
  Modelle. Die Episoden tragen dafür schon `flow` und `specHash`; es fehlen die Eingänge.
* Angezeigt werden drei getrennte Angaben: **Anteil nach Herkunft** der Eingänge (zum Beispiel "9 von 10 Eingängen
  gemessen, 1 geschätzt"), **Qualität** (Dimensionen aus 4.3) und **Unsicherheit**, wo ein Modell eine angibt.
* Eine Marke bleibt, weil sie nie fehlen darf: "enthält Synthetisches", sobald irgendein Eingang erzeugt ist.
  Sie ist eine Warnung, keine Bewertung.

## 5. Was "clean" heißt, und was Simulation leistet

### 5.1 Bereinigen kennzeichnet, es entfernt nicht

Neue Festlegung:

> Eine bereinigte Reihe liegt auf dem Takt und in der Einheit des Systems, und jeder Wert trägt ein Urteil über die
> Messung. Sie ist nicht dadurch bereinigt, dass sie in den erwarteten Bereich passt.

Folgen:

* Der Operator für den Plausibilitätsbereich bekommt den Modus `flag` und der wird Vorgabe: Der Wert bleibt, das
  Qualitätsbyte wird `IMPLAUSIBLE` (so steht es im Kanal-Katalog seit jeher). `gap` und `clip` bleiben wählbar und
  heißen in der Oberfläche, was sie tun: "Werte verwerfen", "Werte begrenzen".
* Resampling zählt je Fenster, wie viele Werte fraglich waren, und gibt das im Qualitätsbyte weiter. Ein
  Minutenmittel aus drei guten und 57 fraglichen Werten ist kein gutes Minutenmittel.
* Der Betriebsbereich löst nie eine Korrektur aus, nur ein Ereignis.
* Die Rohreihe bleibt unverändert im Rohtopic. Das gilt schon heute.

### 5.2 Ein Takt ist eine Sicht, kein Naturgesetz

Minutenmittel, Zählerstände, Preisintervalle und Schaltvorgänge brauchen verschiedene Aufbereitung. Der Takt gehört
deshalb zur Güte `clean` **eines Signals für einen Zweck**, und die Art des Signals (`ANALOG`, `STATUS`, `COUNTER`)
bestimmt, welche Aggregation erlaubt ist: Mittel für analoge Werte, letzter Stand oder Dauer je Zustand für
Zustände, Differenz für Zähler. Der Prozessor lehnt ein Mittel über einen Zähler ab.

### 5.3 Demo-Generator und Szenariomodell

| | Demo-Generator | Szenariomodell |
|---|---|---|
| Erzeugt | eine Reihe für sich, aus Bereich und Takt | mehrere Größen gemeinsam, aus Annahmen und Abhängigkeiten |
| Kennt | nichts vom Rest des Systems | Kopplungen (Wind, Preis, Bedarf), Speicherdynamik, Bilanzgrenzen |
| Taugt für | Oberfläche erproben, Leitungen prüfen | Strategien vergleichen |
| Aussage | "so sähen Daten aus" | "unter diesen Annahmen verhält sich das System so" |

Ein Startwert beweist Wiederholbarkeit, keine Realitätsnähe. Der Zwilling ist bereits ein Szenariomodell für die
Anlagenseite (Bilanzen, Rampen, Druck). Für die äußeren Einflüsse fehlt eines; `gen.ou` für den Preis ist ein
Demo-Generator und wird so benannt. Ein Ergebnis, das auf einem Demo-Generator steht, wird für den Vergleich von
Strategien gesperrt.

## 6. Der Streaming-Vertrag

Die Festlegungen stehen im Einzelnen in [11](11-SMART-TOPIC-PROZESSOR-DEVOPS.md), Abschnitt 7a, mit dem Stand je
Punkt. Hier die Grundsätze:

1. **Vier Zeiten.** Ereigniszeit (wann es war), Eingangszeit (wann es ankam), bei Prognosen Erstellungszeit und
   Zielzeit. Eine historische Entscheidung sieht nur, was damals angekommen war.
2. **Vorläufig und endgültig.** Jedes Ergebnis sagt, ob es endgültig ist. Kommen Daten nach dem Abschluss, entsteht
   eine **Revision** mit Verweis auf das frühere Ergebnis. Nichts wird still überschrieben.
3. **Identität.** Jedes Ergebnis hat eine Kennung aus dem, was es logisch ist (Signal, Güte, Zeitraum, Fassung des
   Flows). Wird nach einem Neustart dasselbe noch einmal geschrieben, erkennt es der Leser an der Kennung.
4. **Zuständigkeit je logischem Ergebnis,** nicht je Topic: Ein Signal in einer Güte hat einen Flow, der es
   schreibt. Der Server prüft das beim Start, nicht nur die Oberfläche.
5. **Replay ist vollständig beschrieben:** Eingangsdaten mit Offsets, Anfangszustand, Parameter, Fassung von Flow
   und System, damaliger Informationsstand. Startwert und Versionsnummer allein reichen nicht.
6. **Eine neue Fassung läuft erst nebenher.** Sie schreibt in den Schatten, wird verglichen, dann umgeschaltet;
   der Rückweg bleibt.
7. **Gleiche Bedeutung in Vorschau, Replay und Betrieb.** Workbench und Prozessor dürfen verschieden gebaut sein;
   ihre Ergebnisse stimmen unter gleichen Bedingungen innerhalb einer genannten Toleranz überein. Für
   `align.vector` und die drei Bereinigungs-Operatoren ist das mit gemeinsamen Testvektoren belegt.

## 7. Freigabe eines Modells für einen Zweck

Eine Analyse (Modell, Regel, Optimierer) deklariert auf der Entscheidungskarte:

| Angabe | Inhalt |
|---|---|
| Eingänge | Signale mit Güte, höchstem Alter, zulässiger Herkunft |
| Gültigkeitsbereich | in welchem Bereich der Eingänge sie geprüft ist (zum Beispiel Wind 3 bis 25 m/s, keine Abregelung) |
| Nachweis | womit geprüft: Zeitraum, Kennzahl, Vergleich mit der Referenz, Datum |
| Unsicherheit | wie sie angegeben wird, und ab welcher Breite ein Ergebnis nicht mehr trägt |
| Fassung | Version und Hash |

Daraus folgt ein **Verwendbarkeitsstatus** je Ergebnis: verwendbar, eingeschränkt (mit Grund), gesperrt. Gesperrt
wird gezielt: Fehlt der Wasserstoffbedarf, ist die Lieferplanung gesperrt, die Analyse der Windleistung nicht.
Ein Ergebnis außerhalb des Gültigkeitsbereichs wird angezeigt und als nicht gestützt gekennzeichnet, nicht
verschwiegen.

Vollständige Herkunft der Eingänge ist dafür notwendig, aber kein Beleg für Güte.

## 8. Der Kreis: Empfehlung, Freigabe, Maßnahme, Wirkung

### 8.1 Vier Datensätze

Schema: [`schemas/opentsx.decision-record.v1.schema.json`](schemas/opentsx.decision-record.v1.schema.json). Jeder
Datensatz ist ein Ereignis in einem Topic je Entscheidung und verweist auf seinen Vorgänger.

| `kind` | Sagt | Trägt |
|---|---|---|
| `recommendation` | was die Analyse vorschlägt | Alternativen mit Kennzahlen, gewählte Alternative, Gültigkeitszeitraum, Informationsstand, Unsicherheit, Verwendbarkeit, Fassungen |
| `approval` | was ein Mensch daraus gemacht hat | angenommen, geändert oder abgelehnt, von wem, mit Begründung |
| `action` | was tatsächlich ausgeführt wurde | Stellgröße, Wert, Zeitpunkt, wer oder was ausführte; kann von der Freigabe abweichen |
| `outcome` | was danach beobachtet wurde | Kennzahlen im Gültigkeitszeitraum, gegen Erwartung und gegen Referenz |

Eine von Hand bestätigte Maßnahme schließt den Kreis. Eine Steuerung der Anlage braucht es dafür nicht; OpenTSx
führt nichts aus. Schnelle Regelungen und Schutzfunktionen bleiben, wo sie sind.

### 8.2 Drei Ursachen auseinanderhalten

Weicht die Wirkung von der Erwartung ab, kann das Modell falsch gewesen sein, die Maßnahme anders ausgeführt worden
sein als empfohlen, oder die Umwelt hat sich geändert. Die Datensätze erlauben, das zu trennen: `action` gegen
`approval` zeigt die Abweichung der Ausführung, die äußeren Signale (Rolle `exogenous`) gegen ihre Prognose zeigen
die Umwelt, der Rest ist Modellfehler.

### 8.3 Was als Nachweis gilt

"Nach der Empfehlung war es besser" ist kein Nachweis. Eine Aussage über Nutzen braucht eine Vergleichsbasis:
dieselbe Lage mit der Referenzstrategie. Im Zwilling geht das (dieselben Eingänge, zwei Strategien). Am echten
System geht es nur über viele Perioden und mit Vorsicht. Mit synthetischen Daten lässt sich zeigen, dass der Kreis
funktioniert, **nicht**, dass eine Strategie wirkt. Die Oberfläche sagt das an der Stelle, an der die Zahl steht.

### 8.4 Schattenbetrieb

Bevor eine Empfehlung übernommen wird, läuft die Analyse im Betrieb mit, ohne dass jemand danach handelt. Die
Datensätze `recommendation` entstehen, `approval` und `action` nicht; `outcome` vergleicht die Empfehlung mit dem,
was ohne sie geschah. Erst wenn das über eine vereinbarte Zeit trägt, wird die Freigabe angeboten.

## 9. Die Korrektur am H2-Beispiel

Das Systemdokument band `park.p_avail_kw` (verfügbare Leistung des Parks) an die Summe der gemessenen Wirkleistung
der Turbinen. Bei Abregelung ist das falsch: Senkt das Kommando die Einspeisung, sinkt die gemessene Leistung, das
Potenzial aber nicht. Ein Optimierer, der seine eigene Abregelung als nachlassenden Wind liest, regelt sich selbst
herunter.

Im Schaltplan sind die Größen schon getrennt: Der Baustein `park` nimmt `p_avail_kw` und `limit_kw` und gibt
`p_gen_kw`. Falsch war nur die Bindung. Jetzt:

| Signal | Rolle | Quelle |
|---|---|---|
| `park.p_gen_kw` | Beobachtung: tatsächliche Erzeugung | Summe der bereinigten Wirkleistung der Turbinen (Flow `park-power`) |
| `park.p_avail_kw` | Schätzung: Erzeugungspotenzial | aus der bereinigten Windgeschwindigkeit über die Leistungskurve (Flow `park-potential`), Herkunft `ESTIMATED` |
| `rules.limit_kw` | Kommando: Abregelung | im Schaltplan verdrahtet, kein Signal von außen |
| `park/T02/WTUR.TurSt` | Beobachtung: Betriebszustand der Turbine | an das Topic gebunden, roh; nötig für die technische Verfügbarkeit, von keinem Flow genutzt |

Die tatsächliche Erzeugung dient damit dem Vergleich mit dem, was der Zwilling rechnet, nicht als sein Eingang. Der
Prozessor für die Leistungskurve (`model.power-curve`) und der für die Summe (`agg.sum`) sind zu bauen; beide
Flows stehen im Dokument und laufen nicht. Die Netzgrenze fehlt im Schaltplan ganz und ist als offener Punkt
vermerkt.

## 10. Der überarbeitete Plan

L1 bis L3 aus [14](14-ENGINEERING-FLOW.md) sind umgesetzt. **Entschieden ist (D62): Zuerst werden L4 (Generatoren,
Bind und Simulate) und L5 (Models) fertig gebaut. Danach wird neu geplant, und es geht mit E1, E2 und E3 weiter.**
Die Tabelle beschreibt die Stufen danach; was L4 und L5 davon schon liefern (Binden in der Oberfläche,
Demo-Generatoren, ein erstes Modell mit Gültigkeitsbereich), entfällt dort. Ihre Reihenfolge folgt der Priorität
des Briefings: **ein vollständiger Durchstich für eine Entscheidung** statt weiterer Breite.

| Stufe | Inhalt | Abgenommen, wenn |
|---|---|---|
| **E1 Entscheidungskarte und fachliche Verträge** | Entscheidungskarte lesen und anzeigen (drei Ansichten, zuerst nur lesend); Rolle, Bedeutung und Betriebsbereich am Signal; Zustand in Dimensionen; Verwendbarkeit je Entscheidung; Signale in der Oberfläche binden statt in YAML; der geführte Modus folgt den acht Schritten | eine Fachperson sieht für die Beispielentscheidung, welche Aussagen gesperrt sind und warum, und kommt von dort zur Quelle; eine Bindung entsteht ohne Datei |
| **E2 Durchgängiger Streaming- und Replay-Pfad** | Modus `flag` und Qualitätsbyte durch die Kette; Ereigniskennung und Kennzeichen vorläufig, endgültig, revidiert in den Ausgaben; Zuständigkeit im Server geprüft; Replay-Beschreibung; neue Fassung im Schatten; Mehrfach-Eingang (`agg.sum`), Leistungskurve; Szenariomodell für die äußeren Einflüsse | Doppelte, verspätete Daten und Neustarts verursachen weder Doppelzählung noch Verlust, belegt durch Tests, die genau das einspielen; ein Lauf lässt sich mit dem damaligen Informationsstand wiederholen; eine echte Grenzverletzung bleibt in der bereinigten Reihe sichtbar |
| **E3 Handlungs- und Lernkreis** | die vier Datensätze; zwei Alternativen gegen die Referenz im Zwilling vergleichen; Empfehlung bestätigen; Maßnahme erfassen; Wirkung gegen Erwartung und Referenz; Schattenbetrieb | der Durchstich aus Abschnitt 10.1 läuft von Anfang bis Ende |

### 10.1 Der Durchstich

> Eine Fachperson verbindet einen Beispieldatenstrom, beschreibt ein Betriebsziel, vergleicht zwei
> Handlungsalternativen, bestätigt eine Empfehlung und kann anschließend deren Umsetzung und Wirkung nachvollziehen.

Abnahmekriterien der ersten Version, je mit der Stufe, die sie erfüllt:

| Kriterium | Stufe |
|---|---|
| Bedienung ohne YAML und ohne Topic-Namen | E1 |
| Jede wesentliche Ergebniszahl ist bis zu Eingaben und Annahmen zurückverfolgbar | E1 (Nachweis), E3 (Ergebniszahlen) |
| Fehlende Eingaben sperren genau die davon abhängigen Aussagen | E1 |
| Doppelte, verspätete Ereignisse und Neustarts ohne unbemerkte Doppelzählung oder Verlust | E2 |
| Historische Entscheidungen mit dem damaligen Wissensstand rekonstruierbar | E2 |
| Echte Grenzverletzungen bleiben sichtbar | E2 |
| Abregelung, Speicherbilanzen und Ausführungsverzögerung korrekt behandelt | E2 (Abregelung in den Signalen), E3 (Verzögerung zwischen Freigabe und Maßnahme) |
| Eine neue Strategie wird unter gleichen Szenarien gegen eine einfache Referenz verglichen | E3 |

### 10.2 Was ausdrücklich nicht gebaut wird

* Kein universeller Graph-Editor für Systeme.
* Keine Korrektur, die Auffälliges entfernt, damit etwas grün wird.
* Keine Verbindung von Signal und Reihe allein nach Name und Einheit.
* Kein universeller Qualitätswert, keine Rangfolge der Herkunft.
* Keine Aussage über Wirksamkeit auf synthetischen Daten.
* Keine Steuerung der Anlage. OpenTSx empfiehlt und protokolliert.
* Keine Vollständigkeit des Systems als Voraussetzung: Was fehlt, sperrt nur, was davon abhängt.
* Nichts Geplantes wird als heute durchspielbar gezeigt.

### 10.3 Stand nach L4 und L5, Neuplanung vor E1

Stand 2026-10-13. L4 und L5 sind gebaut (14, Abschnitt 11). Damit ist ein Teil von E1 und ein Stück von E2 schon
da. Die Tabelle sagt je Inhalt der Stufen, was steht und was bleibt. **Die Schnitte darunter sind ein Vorschlag;
entschieden ist davon nichts.**

| Stufe | Schon da durch L4 und L5 | Bleibt |
|---|---|---|
| E1 | Entscheidungskarte wird gelesen und am Ort Models gezeigt (eine Ansicht, nur lesend); Rolle, Bedeutung und Betriebsbereich am Signal; Verwendbarkeit je Analyse mit Gründen und Sprung zum Signal; Binden in der Oberfläche als Arbeitskopie | Zustand eines Signals in fünf Dimensionen (D55); die zwei weiteren Ansichten der Karte; Ableitungsweg mit Anteilen statt nur `madeOf` (D56); der geführte Modus mit acht Schritten; Schema der Karte im Build geprüft; "ein Signal, ein Schreiber" im Server geprüft; Arbeitskopie in die Datei schreiben |
| E2 | Leistungskurve; Kennung der Episoden, die einen Neustart übersteht | zwei Varianten von `clean` (D57) mit Qualitätsbyte durch die Kette; Kennzeichen vorläufig, endgültig, revidiert; Replay mit damaligem Stand; neue Fassung im Schatten; Mehrfach-Eingang (`agg.sum`); Szenariomodell; Generator, der fortlaufend schreibt; der Nachweis der Korrektur am Lesen nach einer Ablehnung auf dem Labor-Broker |
| E3 | nichts | alles; dazu: eine Analyse, hinter der ein Modell läuft (`price-following` ist heute nur eine Karte), und die Prüfung des Gültigkeitsbereichs je Zeitpunkt statt über die ganze Reihe |

Vorgeschlagene Schnitte, je ein Pull-Request gegen `master`, nacheinander:

| Schnitt | Inhalt | Abgenommen, wenn |
|---|---|---|
| E1a | Zustand in fünf Dimensionen im Server und auf der Seite Signals; Schema der Karte im Build; Zuständigkeit im Server geprüft | die Seite Signals zeigt je Signal, woran es fehlt, getrennt nach Dimension; ein zweiter Schreiber wird vom Server abgelehnt, belegt durch einen Test |
| E1b | Karte in drei Ansichten; geführter Modus mit acht Schritten; Ableitungsweg | das erste Abnahmekriterium von E1 aus der Tabelle oben |
| E2a | `clean` in zwei Varianten mit Qualitätsbyte; `agg.sum` | eine echte Grenzverletzung bleibt in `marked` sichtbar und fehlt in `filtered`; `park.p_gen_kw` entsteht aus den Turbinen |
| E2b | Kennzeichen und Replay; Schatten | die Abnahme von E2 aus der Tabelle oben |
| E2c | Szenariomodell für Wind, Preis und Bedarf | die drei äußeren Signale hängen im Szenario zusammen; Ergebnisse darauf sind als Szenario benannt, nicht als Demo |
| E3a | die vier Datensätze als Ereignisse; `price-following` als laufendes Modell im Zwilling | eine Empfehlung entsteht, wird bestätigt und steht im Protokoll |
| E3b | Vergleich zweier Alternativen gegen die Referenz; Wirkung gegen Erwartung | der Durchstich aus 10.1 |

Offen für den Auftraggeber:

1. Reihenfolge. Vorschlag: E1a, E2a, dann E1b. Grund: Die Dimensionen und die zwei Varianten ändern, was in den
   Topics steht und was der Server meldet; die Ansichten bauen darauf auf und müssten sonst zweimal angefasst werden.
2. Szenariomodell (E2c) vor oder nach E3a. Ohne es bleibt jeder Vergleich in E3 gesperrt (D61), weil Preis und
   Bedarf nur als Demo vorliegen. Vorschlag: E2c vor E3b, E3a darf vorher laufen.
3. Echte Daten. Solange nur Simulator-Daten da sind, kann keine Analyse den Status `usable` erreichen. Das ist
   gewollt (10.2), heißt aber: Der Durchstich endet bei `restricted`, bis Messdaten angebunden sind.

## 11. Entscheidungen

Entschieden vom Auftraggeber am 2026-10-13.

| Nr. | Thema | Entscheidung | Stand |
|---|---|---|---|
| D52 | Zentrale Einheit | die Entscheidung; die Entscheidungskarte (`opentsx.decision.v1`) ist das Arbeitsobjekt, die Orte der Hülle ordnen sich als drei Ansichten darunter ein | wie vorgeschlagen |
| D53 | Rolle und Bedeutung | jedes Signal hat `role` und `meaning`; verbunden wird erst nach Bestätigung der Bedeutung | wie vorgeschlagen |
| D54 | Zwei Wertebereiche | Plausibilität (Messung) und Betrieb (System) getrennt; nur die Plausibilität betrifft die Messgültigkeit | wie vorgeschlagen |
| D55 | Zustand | fünf Dimensionen statt eines Zustands; angezeigt wird die Verwendbarkeit je Entscheidung | wie vorgeschlagen |
| D56 | Herkunft | Ableitungsweg mit Anteilen; `worst` entfällt als Bewertung, es bleibt die Marke "enthält Synthetisches" | wie vorgeschlagen |
| D57 | Bedeutung von `clean` | **beides wird geschrieben:** eine Güte `clean` in zwei Varianten im selben Topic, unterschieden durch das Tag `variant`. `marked`: die Werte bleiben, jeder mit Urteil. `filtered`: fragliche Werte sind entfernt. Vorgabe für Leser ist `marked`. Keine vierte Güte | **geändert** (Vorschlag war: Kennzeichnen als Vorgabe, Entfernen wählbar) |
| D58 | Streaming-Vertrag | die sieben Grundsätze aus Abschnitt 6, ausgeführt in 11, Abschnitt 7a | wie vorgeschlagen |
| D59 | Modellfreigabe | je Analyse Eingänge, Gültigkeitsbereich, Nachweis, Unsicherheit; daraus ein Verwendbarkeitsstatus je Ergebnis | wie vorgeschlagen |
| D60 | Kreis | vier Datensätze (`opentsx.decision-record.v1`); OpenTSx führt nichts aus | wie vorgeschlagen |
| D61 | Simulation | L4 baut Demo-Generatoren je Signal und nennt sie so; Ergebnisse darauf sind für Strategievergleiche gesperrt. Ein Szenariomodell folgt in E2 | wie vorgeschlagen |
| D62 | Plan | **erst L4 und L5 fertig bauen, dann neu planen und mit E1, E2 und E3 weitermachen.** L4 und L5 entfallen also nicht; Abschnitt 10 gilt für die Zeit danach und wird vor E1 noch einmal durchgesehen | **geändert** (Vorschlag war: E1 bis E3 ersetzen L4 und L5) |
| D63 | Beispielentscheidung | "Fahrplan für Elektrolyseur und Batterie in der nächsten Stunde" am System `h2-chain` | wie vorgeschlagen |

**Folgen der beiden Änderungen.**

* D57: Abschnitt 5.1 gilt mit einer Ergänzung. Der Prozessor schreibt je bereinigtem Signal zwei Reihen. Die
  Regel für die Reihe (14, Abschnitt 6.1) bekommt das Tag `variant`; die Kennung einer Episode schließt es ein. Die
  Seite Signals zeigt die Variante `marked`; ein Flow oder ein Modell, das die gefilterte Reihe will, sagt es. Das
  kostet den doppelten Platz im Topic der bereinigten Reihen. Gebaut wird es mit E2, nicht mit L4 oder L5.
* D62: L4 und L5 werden mit dem gebaut, was schon entschieden ist. Das heißt für L4: Demo-Generatoren, so benannt
  (D61), Binden erst nach bestätigter Bedeutung (D53). Für L5: Ein Modell bekommt seinen Gültigkeitsbereich und
  Nachweis von Anfang an (D59), auch wenn die Entscheidungskarte als Oberfläche erst mit E1 kommt.

## 12. Was offen bleibt

* **Die Daten.** Sechs Stunden eines Simulator-Szenarios tragen den Mechanismus, keine Aussage über Nutzen.
* **Die Netzgrenze** und die technische Verfügbarkeit fehlen im Schaltplan von `h2-chain`.
* **Der geführte Modus** der Oberfläche zeigt bis E1 die sieben Schritte der früheren Fassung des Leitfadens.
* **Die Ursache der verlorenen Episode** ist aus dem Verhalten erschlossen, nicht aus dem Quelltext des
  Kafka-Clients, und der Nachweis der Korrektur auf master2 steht aus.
* **Wer eine Entscheidungskarte ändern darf,** und wie sie versioniert wird, folgt D42 (Datei im Repository,
  Arbeitskopie je Konto) und ist dort so offen wie beim Systemdokument.

## 13. Zweites Briefing: das Jahresmodell

Ein zweites externes Briefing (2026-10-13) beschreibt das Ziel als gekoppeltes Betriebsmodell eines Windparks, das
für ein ganzes Jahr rechnet, wie Windstrom, Batterie, Elektrolyse, Wasserstoffspeicher und Vermarktung
zusammenarbeiten. Geprüft ist es gegen den Stand des Repositorys. Die Quellen, die es nennt (PyPSA, windpowerlib,
DWD, KNE), sind **nicht nachgeschlagen**; was hier über sie steht, ist die Aussage des Briefings.

### 13.1 Was sich deckt

| Aussage des Briefings | Stand hier |
|---|---|
| Die Aufbereitung ist ein DAG; Rückkopplung gehört in den Simulator | so gebaut: Der Prozessor rechnet DAGs, der Zwilling hat die Schleife zwischen Elektrolyseur und Leitung |
| Jede Zahl sagt, ob sie gemessen, vorgegeben, geschätzt oder simuliert ist; Simulation überschreibt keine Messkanäle | D56; Generiertes hat ein eigenes Topic; `origin` und `madeOf` an jeder Reihe |
| Was fehlt, sperrt nur, was davon abhängt | seit L5 gebaut (Abschnitt 7) |
| Rollierende Planung ist nur ohne Zukunftswissen ehrlich, wenn ihre Eingaben es sind | Abschnitt 6 und E2: Replay mit dem damaligen Stand |
| Historie und Livebetrieb über denselben Kanalvertrag, ohne Lücke und Doppelzählung | Abschnitt 6; die Kennung der Episoden übersteht einen Neustart |
| Eine neue Strategie gegen eine einfache Referenz | D63, E3 |

Schon vorhanden, was das Briefing nicht kennt: `opentsx-market` (Day-Ahead-Preise, reBAP, Marktwerte, Archiv mit
Manifest und Qualitätsbericht, Zwillinge auf realer Historie), `opentsx-dwd` (Stationsdaten und MOSMIX), der
Zwilling `h2-chain` mit neun Bausteinen, darunter Betriebsregeln und Direktvermarktung.

### 13.2 Was übernommen wird

* **Drei Leistungsgrößen.** Wetterbedingtes Potenzial vor Einschränkungen, zulässig verfügbare Leistung nach
  technischen und umweltbedingten Einschränkungen, tatsächlich gefahrene Leistung. Heute gibt es die zweite
  (`park.p_avail_kw`) und die dritte (`park.p_gen_kw`); die erste kommt dazu. Erst mit allen dreien lässt sich
  sagen, warum Energie nicht erzeugt wurde.
* **Topologie der Wasserstoffseite.** Puffer, Verdichter und Übergabestation als eigene Bausteine; `p_bar` wird in
  zugeordnete Ein- und Ausgangsdrücke geteilt; `headroom_kg` gehört zu einem benannten Speicher; der Abnehmer hängt
  physisch an der Übergabe, sein Bedarf ist eine eigene Informationsverbindung; Marktvertrag und physische
  Netzabgabe sind getrennt. Die Reihenfolge von Verdichter, Puffer und Leitung ist eine Eigenschaft der Anlage,
  keine der Darstellung. Solange keine echte Anlage vorliegt, ist die gewählte Reihenfolge eine Annahme und steht
  als solche im Plan.
* **Überlappende Abschaltungen** werden nicht mehrfach als Verlust gezählt: eine ausgewiesene Zuordnungsregel und
  die Überschneidung getrennt sichtbar.
* **Kennungen eines Laufs.** Jedes Ergebnis trägt Lauf und Szenario neben Signal, Einheit, Fassung und Prüfstatus.
  Heute stehen Flow, Herkunft und Hash der Spezifikation an der Reihe; `run` und `scenario` kommen mit E3.
* **Faire Anfangs- und Endbestände** beim Vergleich von Läufen: Ein Lauf darf nicht besser aussehen, weil er
  Batterie und Speicher am Ende leert.
* **Einheiten für Wasserstoff.** kg und kg/h im Fachmodell; die Umrechnung auf eine Energiebasis geschieht an einer
  Stelle, mit benannter Heizwertbasis.

### 13.3 Einwände

1. **Der Umfang.** Fünf Entscheidungen, ein Optimierer, Druckphysik, Regeln für Fledermäuse und Bewirtschaftung
   sind ein Programm, keine Lieferstufe. Der Durchstich für eine Entscheidung bleibt deshalb das Maß (D64).
2. **Der Optimierer.** Startzeiten, Mindestlaufzeiten und Stand-by des Elektrolyseurs machen die Aufgabe
   ganzzahlig; über 35.040 Schritte mit voller Voraussicht ist das schwer. Ein Teillastwirkungsgrad braucht eine
   stückweise Näherung. Es wäre ein zweiter Rechenkern in Python neben dem Zwilling in Java.
3. **Die Schleife** aus Fahrplan, Druckprüfung und Neuberechnung hat im Briefing keine Abbruchregel. Sie braucht
   eine Obergrenze der Durchläufe und eine feste Regel, wie Grenzen verschärft werden; sonst kann sie pendeln.
4. **Das Jahr 2025 hat einen Bruch.** Der Day-Ahead-Preis ist bis zum Liefertag 2025-09-30 stündlich und danach
   viertelstündlich. Das Archiv hält die Originalauflösung; ein Stundenwert wird durch Wiederholung keine
   Viertelstundenmessung, und die Herkunft sagt das.
5. **Historische Prognosen.** Die rollierende Planung braucht die Prognosen von damals. Ob MOSMIX für 2025
   rückwirkend zu bekommen ist, ist nicht geprüft. Sonst bleiben simulierte Prognosen mit benanntem Fehlerverhalten.
6. **Die Leistungskurve von L5 ist ein Demo-Modell.** Sie rechnet aus der Windgeschwindigkeit an der Gondel, ohne
   Nabenhöhe, Luftdichte und Parkverluste. Für Wind einer DWD-Station reicht sie nicht.
7. **Ohne Daten der Anlage** bleibt jedes Ergebnis `restricted`. Reales Wetter und reale Preise mit angenommenen
   Anlagenwerten ergeben eine wettergetriebene Simulation, keine Rekonstruktion des Betriebs. Bewirtschaftung und
   Fledermausschutz wären reine Szenarien.
8. **Der Broker im Labor** trägt ein Jahr in Viertelstunden (35.040 Werte je Signal), kein Jahr in Sekunden.

### 13.4 Entscheidungen

Entschieden vom Auftraggeber am 2026-10-13.

| Nr. | Thema | Entscheidung | Stand |
|---|---|---|---|
| D64 | Ziel | **beides:** Der Durchstich für eine Entscheidung bleibt (D52, D62). Das reale Jahr 2025 wird als Daten vorgezogen und kommt vor E1, damit die weiteren Stufen auf echten äußeren Daten laufen | **geändert** (Vorschlag war: das Jahr als Szenariomodell in E2c) |
| D65 | Optimierer | später, als eine weitere Analyse der Entscheidungskarte nach E3, mit Export des Systems (Netz als NetCDF, dazu ein Manifest mit Zuordnung, Einheiten, Datenständen und Fassungen). Bis dahin rechnet der Zwilling. Die Druckprüfung bleibt im Zwilling | wie vorgeschlagen |
| D66 | Reihenfolge | **der Systemplan zuerst:** Topologie und drittes Leistungssignal aus 13.2 vor allem anderen; danach E1a | **geändert** (Vorschlag war: E1a, E2a, E1b) |

**Folgen.** D64 und D66 stellen zwei Stufen vor E1. Ihre Reihenfolge untereinander ist nicht eigens entschieden;
gewählt ist: erst der Plan, dann die Daten, weil der Plan festlegt, welche Signale es gibt und woran das Jahr
gebunden wird.

Für S1 hat der Auftraggeber am selben Tag drei Dinge entschieden:

| Nr. | Thema | Entscheidung |
|---|---|---|
| D67 | Reihenfolge auf der Wasserstoffseite | Elektrolyseur (30 bar), Verdichter, Puffer (50 bis 200 bar), Leitung (20 bis 40 bar), Übergabestation (mindestens 20 bar), Abnehmer. Eine Annahme, solange keine echte Anlage vorliegt; sie steht als solche im Plan |
| D68 | Tiefe der Physik in S1 | einfach und benannt: Massenbilanz und ideales Gas für Puffer und Leitung, Verdichterleistung aus Druckverhältnis und Wirkungsgrad, Druckverlust als einfache Funktion des Durchflusses. Jede Vereinfachung steht im Baustein. Realgas und Kennfelder folgen mit dem Prüfmodell nach E3 |
| D69 | Der bisherige Plan | wird ersetzt und bleibt als Datei für die Tests des Zwillings erhalten |

| Stufe | Inhalt | Abgenommen, wenn |
|---|---|---|
| **S1 Systemplan** (**umgesetzt** 2026-10-13, mit Grenzen: [TASK-014](../../EVOLUTION/TASK-014-engineering-flow.md), 014.13) | Zwilling und Systemdokument `h2-chain` in neuer Fassung: Puffer, Verdichter, Übergabestation; getrennte Drücke; `headroom_kg` am Speicher; Bedarf als Informationsverbindung; Marktvertrag getrennt von der Netzabgabe; Signal für das wetterbedingte Potenzial; die Annahme zur Reihenfolge im Plan vermerkt | der Plan hat keinen Eingang von außen ohne Signal; der Zwilling läuft mit dem neuen Plan durch seine Tests; das Systembild zeigt die neuen Bausteine |
| **J1 Das Jahr 2025 als Daten** | Preise und Wetter des Jahres 2025 aus dem Archiv als Reihen im Topic, an die Signale des Systems gebunden, mit Herkunft `REPORTED` und `MEASURED`; der Bruch der Auflösung im Preis und die Tage der Zeitumstellung geprüft; die Quelle des Windes benannt (Station, Höhe) und als nicht auf Nabenhöhe übertragen gekennzeichnet | `market.price_eur_mwh` hat Daten für 2025 mit Herkunft `REPORTED`; die Seite Signals zeigt Abdeckung und Lücken des Jahres; kein Wert ist erfunden, um eine Lücke zu schließen |
| danach | E1a, E2a, E1b, E2b, E2c, E3a, E3b aus 10.3. E2c schrumpft auf das, was J1 nicht liefert (Bedarf des Abnehmers, Zusammenhang der äußeren Größen) | wie in 10.3 |

### 13.5 J1, erster Schritt: das Jahr 2025 im Archiv

Am 2026-10-13 geladen, mit den Werkzeugen, die es gibt (`bin/opentsx market weather-backfill` und `backfill`), in
ein Archiv außerhalb des Repositorys. Nichts davon liegt im Repository und nichts im Broker.

| Reihe | Station | Werte | Abdeckung | Lücken | Herkunft |
|---|---|---|---|---|---|
| Windgeschwindigkeit, Stundenmittel | Greifswalder Oie 01759 (Windpark, 23 km, Messhöhe 10 m) | 8727 | 99,62 % | 2 (33 h) | `MEASURED` |
| Lufttemperatur, stündlich | Greifswalder Oie 01759 | 8726 | 99,61 % | 3 (34 h) | `MEASURED` |
| Globalstrahlung, 10 Minuten | Arkona 00183 (42 km) | 52546 | 99,97 % | 2 (2,3 h) | `MEASURED` |
| Wind und Temperatur | Marienberg 03166 (Haus) | je 8753 | 99,92 % | 1 (7 h) | `MEASURED` |
| Globalstrahlung | Fichtelberg 01358 (Haus) | 52493 | 99,87 % | 4 (11,2 h) | `MEASURED` |
| Wind und Temperatur | Kiel-Holtenau 02564 (Boot) | 8757 und 8756 | 99,97 % und 99,95 % | 1 (3 h) und 2 (4 h) | `MEASURED` |
| Globalstrahlung | Dörnick 06163 (Boot) | 52541 | 99,96 % | 3 (3,2 h) | `MEASURED` |
| Day-Ahead-Preis DE-LU (SMARD) | n/a | 15390 | 100 % | 0 | `REPORTED` |

Die Lücken sind ausgewiesen und nicht gefüllt. Der Preis hat den erwarteten Bruch: 6550 Stundenwerte bis zum
Liefertag 2025-09-30, danach 8840 Viertelstundenwerte. Beide Tage der Zeitumstellung sind vollständig.

**Ein Lauf über das Jahr** ist mit dem Zwilling auf Historie möglich
(`bin/opentsx twin --history <archiv> --year 2025 --wind dwd`): 365 Tage im Viertelstundenschritt, Wind der Station
01759 über das Windmodell von `opentsx-market` (Höhenübertragung von 10 m auf 120 m mit dem Exponenten des
Standorts, dann Leistungskurve), Preis aus dem Archiv. Der Park kommt auf 36.078 MWh, das sind 34 Prozent seiner
Nennleistung im Jahresmittel. Der Lauf rechnet mit dem Plan in der **ersten** Fassung (ohne Verdichter, Puffer und
Übergabestation) und einer Fahrregel nach Preisgrenze; er trägt selbst die Zeile "Modelled, not proven".

**Was zu J1 noch fehlt.**

1. **Die Reihen im Broker und an den Signalen.** **Erledigt (D76):** `bin/opentsx market publish` schreibt
   archivierte Reihen als rohe Episoden in das Episoden-Topic einer Domäne, in Paketen mit Pause, und hält beim
   ersten Paket an, das der Broker nicht bestätigt. Am 2026-10-13 gingen 1463 Episoden des Jahres 2025 in den
   Broker des Labors (Wind in 10 Minuten, Temperatur und Luftdruck der Station 01759, Day-Ahead-Preis); zwei
   Pakete brauchten einen zweiten Anlauf, alle wurden bestätigt. Das Systemdokument (Fassung 4) bindet den Preis
   an diese Reihe (Herkunft `REPORTED`) und führt die drei Wetterreihen als Signale des Standorts. Die sechs
   weiteren Wettergrößen liegen im Archiv und nicht im Broker; sie kommen, wenn eine Regel sie liest.
2. **Der Zeitraum.** **Erledigt (D75):** Das Szenario spielt am 2025-10-01, dem ersten Liefertag mit
   viertelstündlichem Preis. Die Reihen der Turbinen im Broker sind weiter die des Simulators vom 2026-10-01. Damit
   das nicht jede Entscheidung veralten lässt, misst die Freigabe das Alter eines Eingangs jetzt gegen die
   neuesten Daten dessen, was die Entscheidung braucht, und nicht mehr gegen alle Reihen des Systems. Die Eingänge
   des Szenarios selbst sind weiter erfunden; an die Reihen der Signale bindet ein Lauf noch nicht.
3. **Die Größen.** Geladen waren zunächst drei: Temperatur, Windgeschwindigkeit als Stundenmittel,
   Globalstrahlung. **Nachgezogen (D77):** Der Abruf kennt jetzt auch Windrichtung, Luftdruck, Niederschlag,
   Feuchte, Böen (höchste Böe der Stunde) und Wind in 10 Minuten. Für die Station des Windparks (01759) sind sie
   für 2025 geladen, Abdeckung 99,60 bis 99,74 Prozent, 2 bis 4 Lücken je Reihe. Die täglichen Abrufe im Cluster
   holen weiter nur die drei ersten Größen; die neuen werden mit `--stations <id>:<größe>` angefordert.
4. **Der Plan.** Der Jahreslauf rechnet mit der ersten Fassung des Plans. Die zweite Fassung (S1) rechnet in
   Minuten über sechs Stunden; ein Jahr in Minuten sind 525.600 Schritte. **Entschieden (D78), noch nicht
   gebaut:** die zweite Fassung im Viertelstundenschritt, nach einer Prüfung von Regler und Rampen.
6. **Das Potenzial aus dem Wind der Station.** **Gebaut (2026-10-14):** Der Operator `model.wind-shear` überträgt
   den Wind einer Messhöhe nach dem Potenzgesetz auf die Nabenhöhe, im Prozessor und in der Workbench. Auf dem
   Laborsystem laufen zwei Flows: der bereinigte Wind der Station (52.360 Werte) und daraus das Potenzial des Parks
   (Höhenübertragung von 10 m auf 120 m mit dem Exponenten 0,11, dann die Leistungskurve von vier Anlagen mit je
   3 MW). Das Potenzial des Jahres 2025 reicht von 0 bis 12.000 kW und heißt geschätzt, gemacht aus gemessenen
   Daten.
7. **Eine Reihe, zwei Herkünfte.** Der frühere Flow für das Potenzial (aus dem simulierten Wind einer Turbine vom
   2026-10-01) und der neue schreiben dieselbe Reihe, weil die Identität einer abgeleiteten Reihe nur aus System
   und Signal folgt. Die 350 Werte des alten Flows bleiben im Log und hängen am Ende des Jahres 2025; `madeOf` nennt
   beide Herkünfte. Folge: Das Potenzial endet am 2026-10-01, der Preis am 2025-12-31, und die Freigabe meldet den
   Preis als veraltet. Abhilfe ist zu entscheiden: die Identität einer abgeleiteten Reihe um den Flow erweitern,
   oder die Freigabe auf den Zeitraum einer Entscheidung beziehen (etwa die Nacht des Szenarios).
5. **Historische Prognosen gibt es nicht.** Der DWD hält auf seinem Server nur die jeweils neuesten
   MOSMIX-Läufe; das Archiv der Rohdateien beginnt am 2026-10-12. Eine rollierende Planung für 2025 kann deshalb
   nur mit simulierten Prognosen rechnen, deren Fehlerverhalten benannt ist.

**Entschieden vom Auftraggeber am 2026-10-13.**

| Nr. | Thema | Entscheidung | Stand |
|---|---|---|---|
| D75 | Zeitraum | das Szenario der Wasserstoffkette wird auf einen Tag im Jahr 2025 gelegt, damit Demo, Wetter und Preis zusammenpassen | wie vorgeschlagen |
| D76 | Weg der Daten | die Reihen des Jahres 2025 werden **in den Broker des Labors geschrieben** und an die Signale gebunden | **geändert** (Vorschlag war: Läufe direkt aus dem Archiv, der Broker bleibt unberührt) |
| D77 | Wettergrößen | die sechs fehlenden Größen werden **jetzt** nachgezogen | **geändert** (Vorschlag war: mit den Regeln, die sie brauchen) |
| D78 | Plan für das Jahr | die zweite Fassung des Plans (S1) rechnet das Jahr im Viertelstundenschritt; vorher wird geprüft, ob Regler und Rampen bei diesem Schritt stabil bleiben | wie vorgeschlagen |
| D79 | Sprache | die Oberflächen bleiben, wie sie sind: Web-Anwendung Englisch, Studio Deutsch | entschieden |

Offen bleibt nach diesem Briefing: Standort und Koordinaten, Kennwerte der Anlagen, Druckbereiche der
Wasserstoffseite, das Abnahmeprofil, die Bedingungen für Import, Export und Vermarktung. Ohne sie sind die Werte
des Plans Annahmen und als solche geführt.
