# 17. Notebook-Arbeitsplatz: Reihen laden, ansehen, verproben

> Stand: 2026-10-14. **Entschieden am 2026-10-13 sind D80 bis D82 (Abschnitt 7); Stufe N1 ist gebaut und im Stack
> der Demo eingehängt (Abschnitt 8).** Dieses Dokument plant
> ein Subsystem: Notebooks als weitere OpenTSx-Anwendung, in denen jemand die Reihen des Systems direkt lädt und von
> Hand damit arbeitet. Es baut auf [16](16-DEMO-UND-SIMULATIONSEXPERTEN.md) auf. Arbeitspaket:
> [TASK-014](../../EVOLUTION/TASK-014-engineering-flow.md), 014.18.
>
> Der Name eines Kunden, Rechner und Adressen stehen hier nicht: Das Repository ist öffentlich. "Der Stack" meint die
> Installation, in der OpenTSx neben dem Broker läuft und die ein Notebook mitbringt.

## 1. Wozu

Die Web-Anwendung zeigt, was vorgesehen ist: Signale, Flows, Läufe. Wer einen Betreiber gewinnen will, braucht
daneben einen Ort für das, was nicht vorgesehen ist: eine Reihe des Betreibers hochladen, neben das Erwartete legen,
eine Frage stellen, die noch niemand als Ansicht gebaut hat. Das ist kundenspezifische Arbeit, und sie ist der
Moment, in dem ein Betreiber seine eigenen Daten im System sieht.

Drei Tätigkeiten, in dieser Reihenfolge wichtig:

1. **Ansehen.** Was liegt vor? Das Zeitreihen-Inventar als Tabelle, eine Reihe als Kurve, Abdeckung und Lücken.
2. **Verproben.** Passt eine neue Reihe zu dem, was das System erwartet? Einheit, Takt, Wertebereich, Lücken,
   Vergleich mit einer verwandten Reihe. Das Ergebnis ist ein Urteil und ein Vorschlag, woran die Reihe zu binden
   wäre.
3. **Rechnen.** Den Zwilling eine Frage rechnen lassen und das Ergebnis selbst weiterverarbeiten, etwa über viele
   Varianten.

## 2. Was es gibt

Am 2026-10-13 nachgesehen, nur lesend.

| Baustein | Stand |
|---|---|
| Notebook im Stack | JupyterLab 4 mit Python 3.13, pandas, numpy, scipy, matplotlib, plotly und pyarrow; zwei Kafka-Klienten; ein eigenes Verzeichnis je Installation, das Neustarts übersteht; Beispiele kommen beim Start aus einem eigenen Image dazu. Das Notebook gehört zum Stack, nicht zu OpenTSx |
| Weg zum Broker | **offen:** Das Notebook darf über den Proxy des Stacks lesen und schreiben; die Adresse steht in seiner Umgebung |
| Weg zum Retrieval-Server | **zu:** Der Namensraum des Notebooks lässt nur freigegebene Ziele zu, und OpenTSx gehört nicht dazu. Weder der Dienst noch die Web-Anwendung sind von dort erreichbar |
| Python-Paket `opentsx` | liest und schreibt das v2-Modell byte-gleich zu Java, hat einen Klienten für den Retrieval-Server (Episoden, Mustersuche, Segmente) und wandelt Episoden in pandas. **Es fehlt:** Inventar, Systeme und Signale, Läufe des Zwillings; im Notebook des Stacks ist das Paket nicht installiert |
| Daten | Inventar mit über 150 Reihen, seit heute darunter das Jahr 2025 für Wind, Temperatur, Luftdruck (DWD, gemessen) und Preis (gemeldet) |
| Läufe | Lauf-Dienst mit Register (16, Abschnitt 4, Punkte 1 und 2) |

## 3. Das Subsystem

```
                 +--------------------+        liest         +---------------------+
  Fachperson --> | Notebook des Stacks| -------------------> | Retrieval-Server    |  Inventar, Episoden,
                 |  + Paket opentsx   |   (Weg A, REST)      | (Systeme, Läufe)    |  Signale, Läufe
                 |  + OpenTSx-Hefte   |                      +---------------------+
                 |                    | -------------------> +---------------------+
                 +--------------------+   (Weg B, Topic)     | Broker (Proxy)      |  Episoden roh
                          |                                  +---------------------+
                          | schreibt nur in sein eigenes Verzeichnis (Stufe N1 und N2)
                          v
                 Hefte, Auswertungen, Bindungsvorschläge als Dateien
```

Zwei Wege zu den Daten, mit verschiedenem Zweck:

| Weg | Wozu | Stand | Preis |
|---|---|---|---|
| **A, über den Retrieval-Server** | alles, was OpenTSx schon geordnet hat: Inventar, Signale mit Zustand, Entscheidungsstatus, Läufe | zu: braucht eine Freigabe im Namensraum des Notebooks | eine Regel im Stack, die dessen Betreiber setzt; der Server hat keine Konten, die Freigabe gilt also für jeden, der das Notebook öffnen kann |
| **B, direkt aus dem Topic** | die rohen Episoden, so wie die Flows sie lesen; unabhängig davon, was der Server gerade kennt | offen | das Paket `opentsx` muss ins Notebook; ein Leser, der ein Topic von vorn liest, belastet den Broker |

Beide werden gebraucht. A ist der Normalfall und der schonende. B ist der direkte Zugriff, den die Aufgabe nennt,
und der Beweis, dass die Daten dem Betreiber gehören und nicht hinter einer Oberfläche liegen.

### 3.1 Was OpenTSx dazu liefert

| Teil | Inhalt |
|---|---|
| **Paket** | `opentsx` wird um drei Dinge erweitert: `inventory()` (das Inventar als Tabelle), `systems()`, `signals(system)` und `decisions(system)`, und `runs` (Szenario lesen, Lauf starten, Lauf und Vergleich als Tabellen). Dazu ein Leser für Weg B: Episoden eines Topics nach Metrik und Zeitraum, mit einer Obergrenze, die er nicht stillschweigend überschreitet |
| **Hefte** | vier Notebooks, die laufen, wie sie geliefert werden (Abschnitt 4) |
| **Auslieferung** | die Hefte und das Paket als ein kleines Image, das der Stack beim Start des Notebooks einhängt, so wie er es mit seinen eigenen Beispielen tut. OpenTSx ändert das Notebook des Stacks nicht |
| **Verweis** | in der Web-Anwendung am Ort Signals und am Ort Simulate: "Im Notebook öffnen", mit dem Namen der Reihe oder des Laufs |

### 3.2 Regeln

1. **Lesen ist frei, Schreiben nicht.** In den Stufen N1 und N2 schreibt ein Notebook nichts in den Broker und
   nichts in das System. Was es erzeugt, bleibt in seinem Verzeichnis.
2. **Herkunft bleibt sichtbar.** Jede Tabelle, die das Paket liefert, trägt die Herkunft der Reihe als Spalte.
   Simuliertes heißt im Notebook simuliert.
3. **Zahlen aus Läufen tragen die Kennung des Laufs** (16, Abschnitt 3.1). Eine Tabelle aus einem Lauf hat sie als
   Spalte.
4. **Lücken werden nicht gefüllt.** Eine Lücke ist `NaN` und eine Zeile in der Lückenliste.
5. **Der Broker wird geschont.** Der Leser für Weg B nimmt einen Zeitraum und eine Obergrenze; "alles von vorn" ist
   eine ausdrückliche Angabe und keine Vorgabe.
6. **Daten des Betreibers bleiben im Stack.** Ein Heft lädt nichts nach außen; das Paket hat keinen Aufruf dafür.

## 4. Die Hefte

| Heft | Frage | Zeigt | Weg |
|---|---|---|---|
| **1 Inventar** | Was liegt vor? | das Inventar als Tabelle (Metrik, Tags, Zeitraum, Werte, Lücken, Herkunft), filterbar; eine gewählte Reihe als Kurve; Abdeckung je Tag als Karte; die Lücken als Liste | A; als Ersatz B, solange A zu ist |
| **2 Ein Signal** | Was erwartet das System, und was ist da? | ein Signal von `h2-chain` mit Erwartung und Betriebsbereich, roh und bereinigt übereinander, die Werte außerhalb des Bereichs markiert | A |
| **3 Verproben** | Passt meine Reihe? | eine CSV-Datei des Betreibers gegen die Erwartung eines Signals: Einheit, Takt, Bereich, Lücken, Zeitzone; daneben eine verwandte Reihe (etwa der Wind der Station gegen den Wind an der Gondel); am Ende ein Urteil und die Zeilen für die Bindung | A, Datei aus dem Verzeichnis |
| **4 Den Zwilling fragen** | Was wäre, wenn? | das Szenario lesen, eine Frage als Lauf rechnen, Kennzahlen und Verläufe gegen die Referenz; dann dieselbe Frage über zwanzig Uhrzeiten als Tabelle | A |

Heft 1 ist das erste, das gebaut wird: Es braucht am wenigsten, und es ist das, was jemand zuerst sehen will.

## 5. Was fehlt

| Nr. | Was fehlt | Größe | Wer |
|---|---|---|---|
| 1 | Paket `opentsx`: Inventar, Systeme, Signale, Entscheidungen, Läufe; Leser für Weg B mit Obergrenze | mittel | OpenTSx |
| 2 | Die vier Hefte, mit Tests, die sie gegen einen Server im Speicher durchlaufen lassen | mittel | OpenTSx |
| 3 | Das Image mit Paket und Heften, und die Zeilen, mit denen ein Stack es einhängt | klein | OpenTSx, eingehängt vom Betreiber des Stacks |
| 4 | **Freigabe für Weg A** im Namensraum des Notebooks | eine Regel | Betreiber des Stacks; nicht in diesem Repository |
| 5 | **Wer darf was sehen.** Der Retrieval-Server hat keine Konten. Solange das so ist, sieht jeder, der das Notebook öffnen kann, alle Reihen des Servers | Entscheidung, später Bau | Auftraggeber |
| 6 | Verweis aus der Web-Anwendung ins Notebook | klein | OpenTSx |
| 7 | Schreiben aus dem Notebook: eine verprobte Reihe als Kandidat ins System, mit Konto und Herkunft | mittel, bewusst später | OpenTSx |
| 8 | Mehrere Personen: heute ein Notebook, ein Verzeichnis, ein Zugang | Entscheidung | Betreiber des Stacks |
| 9 | Last am Broker durch Leser, die von vorn lesen | Prüfung vor Stufe N2 | Betrieb des Brokers |

## 6. Stufen

| Stufe | Inhalt | Abgenommen, wenn |
|---|---|---|
| **N1 Inventar im Notebook** | Paket mit `inventory()` und dem Leser für Weg B; Heft 1; Image; im Stack der Demo eingehängt | eine Person öffnet das Notebook des Stacks, lässt Heft 1 laufen und sieht das Inventar als Tabelle und eine Reihe des Jahres 2025 als Kurve, mit Lücken und Herkunft; nichts wurde geschrieben |
| **N2 Signal und Verproben** | Paket mit Systemen und Signalen; Hefte 2 und 3; Freigabe für Weg A ist gesetzt | eine CSV-Datei wird gegen ein Signal geprüft, und das Heft sagt, woran sie zu binden wäre und warum nicht |
| **N3 Zwilling und Rückweg** | Paket mit Läufen; Heft 4; Verweis aus der Web-Anwendung; Schreiben eines Kandidaten mit Konto | eine Frage wird im Notebook über viele Varianten gerechnet; eine verprobte Reihe erscheint in der Web-Anwendung als Kandidat |

N1 kommt ohne Änderung am Stack aus, wenn Heft 1 über Weg B liest. Das ist der Grund, es zuerst zu bauen.

## 7. Entscheidungen

| Nr. | Frage | Entscheidung | Stand |
|---|---|---|---|
| D80 | Womit beginnt das Subsystem? | mit N1: Inventar im Notebook | wie vorgeschlagen |
| D81 | Wie kommen Paket und Hefte ins Notebook? | **gleich als eigenes Image**, das der Stack beim Start einhängt | **geändert** (Vorschlag war: für die erste Demo von Hand ins Verzeichnis gelegt) |
| D82 | Wird Weg A freigegeben, obwohl der Server keine Konten hat? | **jetzt**, mit einer Netzwerkregel im Namensraum des Notebooks | **geändert** (Vorschlag war: mit N2) |
| D83 | Darf ein Notebook schreiben? | offen; Vorschlag: in N1 und N2 nein, in N3 nur über die Web-Anwendung mit Konto, nie direkt ins Topic | offen |

## 8. Stand von N1

Gebaut und am 2026-10-14 im Stack der Demo eingehängt.

* **Paket `opentsx`:** `RetrievalClient` hat `inventory()`, `systems()`, `signals()`, `decisions()`,
  `scenario()`, `run()`, `runs()`, `run_series()` und `compare()`. `TopicReader` liest die Episoden eines Topics
  einmal von vorn bis zum Ende, ohne Konsumentengruppe und ohne Commit, filtert nach Metrik, Tags und Zeitraum,
  behält jede Episode einmal und hält vor dem Lesen an, wenn das Topic mehr Datensätze hat, als er lesen darf.
* **Heft 1** (`python-package/notebooks/01-zeitreihen-inventar.ipynb`): Inventar als Tabelle, die gemessenen und
  gemeldeten Reihen, eine Reihe als Kurve mit Tagesmittel, Abdeckung je Tag als Karte, Lückenliste, der Preis 2025
  mit dem Wechsel der Auflösung. Wählt Weg A und fällt auf Weg B zurück.
* **Image** `opentsx/notebook-content` (`deploy/docker/notebook-content.Dockerfile`): Paket mit fastavro und
  xxhash für Python 3.13 und die Hefte; ein Init-Container kopiert sie bei jedem Start nach `~/opentsx`.
* **Im Stack** (`deploy/k3s/notebook/`): Patch der Notebook-Bereitstellung und eine Netzwerkregel, die nur den
  Retrieval-Server zusätzlich erlaubt. Die vorherige Bereitstellung ist gesichert; zurück geht es mit
  `rollout undo` und dem Löschen der Regel.
* **Geprüft im Notebook des Stacks:** Heft 1 läuft über Weg A durch (154 Reihen; Wind der Station 2025 mit 52.360
  Werten und zwei Lücken im Juni, zusammen 200 fehlende Werte; 724 Intervalle mit negativem Preis). Weg B erreicht
  den Broker und hält bei einer Grenze von 10 Datensätzen an, ohne zu lesen.

**Grenzen.** Der Retrieval-Server hat keine Konten: Wer das Notebook öffnen kann, liest jede Reihe. Die Hefte 2
bis 4 fehlen. Das Verzeichnis `~/opentsx` wird bei jedem Start ersetzt.
