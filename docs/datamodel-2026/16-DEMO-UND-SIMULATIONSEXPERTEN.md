# 16. Demo der Wasserstoffkette und Simulations-Experten als Agenten

> Stand: 2026-10-13. **Entschieden am 2026-10-13 sind D70 bis D73 (Abschnitt 6); D74 ist offen.** Dieses Dokument plant eine Demo am System
> `h2-chain`, beschreibt die Rollen von Simulations-Experten als Agenten und listet, was für beides fehlt. Es baut
> auf [14](14-ENGINEERING-FLOW.md) und [15](15-ENTSCHEIDUNGSKREIS.md) auf. Arbeitspaket:
> [TASK-014](../../EVOLUTION/TASK-014-engineering-flow.md), 014.15.
>
> Der Name eines Kunden steht hier nicht: Das Repository ist öffentlich. "Der Betreiber" meint einen Kunden, der
> einen Windpark mit Elektrolyse betreibt; "Fachperson" meint dessen Leute aus Betriebsführung, Vermarktung und
> Anlagentechnik.

## 1. Worum es geht

Fachpersonen eines Betreibers sollen das System mit Hilfe von Assistenten simulieren können, ohne Fachleute für
Optimierung oder für Datenströme zu sein. Der Anbieter liefert dafür ein Produkt aus drei Teilen: KafScale für die
Datenströme, OpenTSx für Signale, Aufbereitung und Entscheidungskarte, und den Zwilling als Simulator. Das Produkt
steht stellvertretend für die Dienstleistung dahinter.

Ein Satz dazu, was "in die Obhut der KI geben" hier heißt und was nicht:

> Die Assistenten bedienen den Simulator. Sie bedienen nicht die Anlage. Eine Empfehlung wird von einem Menschen
> freigegeben, und eine Maßnahme wird von einem Menschen ausgeführt (D60; 15, Abschnitt 10.2).

Das ist keine Vorsicht aus Prinzip. Solange die Daten simuliert sind, trägt kein Ergebnis eine Aussage über Nutzen
(D61), und ein Assistent, der das verschweigt, wäre das größte Risiko des Produkts.

## 2. Das Demo-Szenario

**Titel:** "Eine Nacht an der Wasserstoffkette: eine Störung, eine Entscheidung, ein Protokoll."
**Dauer:** 20 Minuten. **Publikum:** Fachpersonen eines Betreibers. **Frage der Demo:**

> Um 01:00 schließt die Übergabestation. Bis wann muss sie wieder offen sein, was tun wir bis dahin mit dem
> Elektrolyseur, und wann erfährt es der Direktvermarkter?

Die Demo folgt dem Leitfaden ([Handbuch, Kapitel 16](../user-manual/16-engineering-method.md)): von der Entscheidung
zu den Signalen, zur Simulation, zur Empfehlung, zum Protokoll. Jeder Akt sagt, was heute steht.

| Akt | Was die Fachperson tut | Was sie sieht | Stand heute |
|---|---|---|---|
| 1 Die Anlage | öffnet das Systembild | zwölf Bausteine, Strom und Wasserstoff als Verbindungen, die Annahme zur Reihenfolge auf der Wasserstoffseite; an jedem Eingang von außen ein Signal mit Zustand | **da** (Ort System, seit S1) |
| 2 Die Daten | öffnet die Signale | je Signal: Bedeutung, Herkunft, Abdeckung; drei Signale sind offen; der Wind ist als simuliert gekennzeichnet | **da** (Ort Signals) |
| 3 Die Entscheidung | öffnet die Entscheidungskarte | welche Signale die Entscheidung braucht und welche Analyse heute gesperrt ist, mit Grund | **da** (Ort Models); die Karte ist ein Beispiel mit erfundenen Nachweisen |
| 4 Die Frage | fragt den Assistenten: "Was passiert, wenn die Übergabe um 01:00 schließt?" | der Assistent startet einen Lauf und antwortet mit Zeiten: Warnung 01:29, Druckgrenze 02:29, Abschaltung, Mehreinspeisung; jede Zahl mit Lauf-Kennung | **Rechnung da** (Zwilling, als Kommandozeile und vorgerechnet im Studio). **Fehlt:** der Assistent, ein Lauf auf Anfrage |
| 5 Die Alternativen | fragt: "Und wenn sie um 02:00 wieder öffnet? Oder der Elektrolyseur früher drosselt?" | zwei Alternativen gegen die Referenz: Abschaltungen, erzeugter und gelieferter Wasserstoff, Fahrplanabweichung | **Rechnung da** (Was-wäre-wenn, Fahrweise `pressure-aware`). **Fehlt:** der Vergleich als Ansicht und als Werkzeug (E3) |
| 6 Der Grund | fragt: "Warum wurde Energie nicht erzeugt?" | Potenzial, verfügbare und erzeugte Leistung; die Differenz getrennt nach Auflage und Fahrbefehl | **Rechnung da** (seit S1). **Fehlt:** die Ansicht |
| 7 Die Empfehlung | lässt sich eine Empfehlung geben | "Ventil bis 02:20 öffnen, sonst ab 01:30 drosseln; Vermarkter um 01:30 informieren", mit dem Status `restricted` und dem Satz, dass die Daten simuliert sind | **fehlt** (E3); der Status je Analyse ist da |
| 8 Das Protokoll | gibt frei oder lehnt ab | Empfehlung, Freigabe, Maßnahme, später Wirkung, als vier Einträge | **fehlt** (E3); das Format liegt als Entwurf vor |

### 2.1 Was die Demo nicht behauptet

* Keine Aussage über Geld. Der Erlös im Zwilling rechnet Einspeisung mal Preis, ohne Ausgleichsenergie, ohne
  Wasserstofferlös, ohne Kosten.
* Keine Aussage über eine echte Anlage. Größen, Drücke und die Reihenfolge von Verdichter, Puffer und Leitung sind
  Annahmen.
* Keine Optimierung. Verglichen werden Fahrweisen, die jemand vorgibt; ein Optimierer folgt später (D65).

### 2.2 Zwei Ausbaustufen der Demo

| Stufe | Inhalt | Braucht |
|---|---|---|
| **Demo A, mit dem, was da ist** | Akte 1 bis 3 in der Web-Anwendung, Akte 4 bis 6 im Studio mit den vorgerechneten Läufen, Akte 7 und 8 als Ausblick auf Papier | S1 auf dem Laborsystem ausgerollt, das Studio-Image neu gebaut; sonst nichts |
| **Demo B, mit Assistent** | alle acht Akte in einem Fenster; der Assistent rechnet auf Anfrage | Abschnitt 4, Punkte 1 bis 5 |

## 3. Simulations-Experten als Agenten

Ein Simulations-Experte ist hier eine Rolle mit vier Dingen: einem Auftrag, den Werkzeugen, die sie benutzen darf,
dem, was sie verweigert, und der Form ihrer Antwort. Fünf Rollen, weil fünf verschiedene Fehler zu verhindern sind.

| Rolle | Auftrag | Darf | Verweigert | Verhindert |
|---|---|---|---|---|
| **Szenario-Assistent** | übersetzt die Frage einer Fachperson in einen Lauf: Plan, Eingänge, Eingriffe, Fahrweisen, Zeitraum | Systeme, Signale und Pläne lesen; einen Lauf beschreiben und starten | einen Wert zu erfinden, der fehlt. Fehlt ein Parameter, fragt er oder nennt die Annahme beim Namen | die Frage, die anders gerechnet wird, als sie gemeint war |
| **Datenprüfer** | prüft vor jedem Lauf die Eingänge: Abdeckung, Herkunft, Alter, Wertebereich, Verwendbarkeit für diese Entscheidung | Signalzustand und Entscheidungsstatus lesen; Reihen lesen | einen Lauf freizugeben, dessen Eingänge für die Frage gesperrt sind | die Rechnung auf falschen oder fehlenden Daten |
| **Modellprüfer** | prüft nach jedem Lauf die Physik: Bilanzen für Strom und Wasserstoff, Grenzen, Gültigkeitsbereich, benannte Vereinfachungen | Ergebnisse eines Laufs lesen; Prüfungen rechnen | ein Ergebnis als ausführbar zu bezeichnen, das eine Grenze verletzt oder den Gültigkeitsbereich verlässt | das plausibel aussehende, falsche Ergebnis |
| **Betriebsstratege** | schlägt Alternativen vor und vergleicht sie gegen eine einfache Referenz, unter gleichen Eingängen | Läufe starten und vergleichen | eine Alternative ohne Referenz zu bewerten; einen Vergleich auf Demo-Daten als Wirkung auszugeben | die Strategie, die nur gut aussieht |
| **Berichter** | erklärt das Ergebnis in der Sprache der Fachperson, führt jede Zahl auf Lauf, Eingänge und Annahmen zurück, schreibt die Empfehlung ins Protokoll | Läufe und Vergleiche lesen; einen Protokolleintrag vorschlagen | eine Zahl zu nennen, die aus keinem Lauf stammt; den Status wegzulassen | die Zahl ohne Herkunft |

Für den Betrieb der Plattform gibt es die Rolle schon: den Agenten für KafScale (Diagnose und Runbooks am Broker).
Sie gehört nicht zu den Simulations-Experten und bleibt beim Anbieter.

### 3.1 Regeln, die für alle fünf gelten

1. **Zahlen kommen aus Läufen.** Jede Zahl in einer Antwort trägt die Kennung des Laufs, aus dem sie stammt. Ein
   Assistent rechnet nicht im Kopf.
2. **Der Status steht in jeder Antwort.** `usable`, `restricted` oder `blocked`, mit dem Grund. Bei `blocked`
   gibt es keine Empfehlung, sondern den Weg zur fehlenden Quelle.
3. **Simulation heißt Simulation.** Gemessen, gemeldet, geschätzt, simuliert: Der Assistent benutzt das Wort, das
   an der Reihe steht.
4. **Annahmen werden genannt, nicht versteckt.** Die Vereinfachungen eines Bausteins gehören in die Antwort, wenn
   das Ergebnis von ihnen abhängt.
5. **Der Mensch gibt frei.** Der Assistent empfiehlt und protokolliert. Er schreibt keine Stellgröße an eine
   Anlage, und es gibt kein Werkzeug, mit dem er es könnte.
6. **Alles wird protokolliert.** Frage, Lauf, Antwort, Freigabe: nachvollziehbar, wer was wann wissen konnte.
7. **Im Zweifel fragen.** Eine Rückfrage ist billiger als ein Lauf auf der falschen Annahme.

### 3.2 Die Werkzeuge, auf denen die Rollen stehen

Ein Agent ist so gut wie seine Werkzeuge. Die Tabelle ist der Werkzeugvertrag und zugleich die Lückenliste.

| Werkzeug | Zweck | Stand |
|---|---|---|
| Systeme, Signale, Plan lesen | wissen, was es gibt und in welchem Zustand | **da** (`GET /v1/systems`, `/signals`, `/plan`) |
| Entscheidungsstatus lesen | wissen, was gesperrt ist und warum | **da** (`GET /v1/systems/<id>/decisions`) |
| Reihen lesen | Eingänge und Ergebnisse ansehen | **da** (`/v1/inventory`, `/v1/episodes`) |
| Signal binden, Generator laufen lassen | eine Quelle anschließen | **da**, als Arbeitskopie je Konto; Generatoren nur für Administratoren |
| **Lauf starten** | Schatten, Was-wäre-wenn, Prognose mit Eingriffen und Fahrweisen | **fehlt** als Dienst; es gibt die Kommandozeile und Java |
| **Lauf lesen** | Reihen, Ereignisse, Kennzahlen eines Laufs nach Kennung | **halb:** Ereignisse stehen im Topic; Reihen und Kennzahlen eines Laufs sind nicht abrufbar |
| **Läufe vergleichen** | Alternative gegen Referenz, unter gleichen Eingängen | **fehlt** als Dienst; der Zwilling rechnet es |
| **Bilanzen prüfen** | Strom und Wasserstoff, Grenzen, Gültigkeit | **fehlt** als Werkzeug; die Prüfungen stehen in den Tests |
| **Protokolleintrag schreiben** | Empfehlung, Freigabe, Maßnahme, Wirkung | **fehlt** (E3); Format als Entwurf |

### 3.3 Zwei Orte für die Rollen

| Ort | Für wen | Form | Wann |
|---|---|---|---|
| **beim Anbieter** | die eigenen Leute, die das System für einen Betreiber einrichten und Fragen vorab durchspielen | Agenten-Definitionen im Repository, die auf die Kommandozeile und die Schnittstellen zugreifen | sofort möglich, mit den Werkzeugen, die da sind |
| **im Produkt** | die Fachpersonen des Betreibers | Assistent in der Web-Anwendung neben Systembild und Entscheidungskarte; dieselben Rollen hinter einer Schnittstelle | nach Abschnitt 4, Punkte 1 bis 5 |

Der erste Ort ist der Prüfstand für den zweiten: Was die Rollen beim Anbieter an Werkzeugen und Regeln brauchen,
braucht der Assistent im Produkt auch.

## 4. Was fehlt

Geordnet nach dem, was die Demo B und das Produkt am stärksten aufhält.

| Nr. | Was fehlt | Warum es zählt | Größe |
|---|---|---|---|
| 1 | **Der Simulator als Dienst:** ein Lauf auf Anfrage über eine Schnittstelle, mit Plan, Eingängen, Eingriffen und Fahrweisen; Antwort mit Lauf-Kennung | ohne ihn kann kein Assistent rechnen; heute rechnet nur die Kommandozeile und das Studio zeigt Vorgerechnetes | mittel |
| 2 | **Ein Register der Läufe:** Lauf, Szenario, Datenstand der Eingänge, Fassung von Plan und Bausteinen; Reihen, Ereignisse und Kennzahlen abrufbar; Vergleich zweier Läufe | ohne es ist keine Zahl rückverfolgbar, und der Vergleich fehlt (E3) | mittel |
| 3 | **Der Werkzeugvertrag für Assistenten:** die Werkzeuge aus 3.2 als eine beschriebene Schnittstelle, mit Rechten je Rolle | ohne ihn ist ein Agent ein Textgenerator mit Meinung | klein, wenn 1 und 2 stehen |
| 4 | **Die Leitplanken, geprüft:** ein Satz von Fragen mit erwarteten Antworten und Läufen; Tests, dass ein Assistent keine Zahl ohne Lauf nennt, bei `blocked` nicht empfiehlt und den Status nennt | ohne Tests ist jede Regel aus 3.1 eine Hoffnung | mittel |
| 5 | **Der Assistent in der Oberfläche:** ein Feld neben Systembild und Karte; die Antwort zeigt Lauf, Status und Annahmen | die Fachperson soll kein zweites Werkzeug öffnen | mittel |
| 6 | **Echte Daten:** das Jahr 2025 für Preis und Wetter (J1); vom Betreiber Standort, Kennwerte der Anlagen, Topologie und Druckbereiche der Wasserstoffseite, Abnahmeprofil, Bedingungen für Netz und Vermarktung | bis dahin bleibt jedes Ergebnis `restricted`; die Demo zeigt den Mechanismus, nicht den Nutzen | groß, und nicht allein in unserer Hand |
| 7 | **Das Wettermodell:** vom Wind einer Station zum Potenzial des Parks (Nabenhöhe, Luftdichte, Leistungskurve, Parkverluste) | die Leistungskurve von L5 ist ein Demo-Modell | mittel |
| 8 | **Der Vergleich als Ansicht** und die vier Protokolleinträge (E3) | Akte 5, 7 und 8 der Demo | groß |
| 9 | **Wirtschaftliche Bewertung:** Ausgleichsenergie, Wasserstofferlös, Kosten, faire Anfangs- und Endbestände | ohne sie ist "besser" nur technisch gemeint | mittel |
| 10 | **Der Optimierer** (D65) | eine Fachperson will irgendwann den Vorschlag, nicht nur den Vergleich | groß, bewusst später |
| 11 | **Der Betrieb als Produkt:** Mandanten getrennt, echte Anmeldung statt des Testkontos, Installation je Betreiber, Sicherung, Überwachung | heute läuft ein Laborsystem mit einem Testkonto | groß |
| 12 | **KafScale unter Last:** Der Broker im Labor verweigert zeitweise Lesen und Schreiben; Replay, Aufbewahrung und Zeitabfragen sind gegen die eingesetzte Fassung zu prüfen | ein Assistent, der auf einen Lauf wartet, der nie ankommt, ist keine Demo | offen |
| 13 | **Wo das Sprachmodell läuft:** Anbieter, Vertrag, welche Daten des Betreibers das Haus verlassen dürfen, Kosten je Frage | eine Frage des Betreibers, die vor der ersten Demo B beantwortet sein muss | Entscheidung |
| 14 | **Lizenzen der Datenquellen:** Intraday-Preise sind lizenziert und werden nicht gesammelt; Marktdaten dürfen nicht ohne Prüfung weitergegeben werden | sonst liefert das Produkt Daten aus, die es nicht ausliefern darf | Prüfung |
| 15 | **Die erste echte Frage** einer Fachperson, mit Abnahmekriterium | ohne sie optimiert die Demo auf uns selbst | Gespräch |

## 5. Vorschlag für die Reihenfolge

1. **Demo A herrichten:** S1 und das Studio auf dem Laborsystem ausrollen, den Ablauf aus Abschnitt 2 einmal
   durchspielen und festhalten, wo er hakt.
2. **Die fünf Rollen beim Anbieter anlegen** (3.3, erster Ort) und an Demo A erproben: Wo fehlt ein Werkzeug, wo
   eine Regel?
3. **Lauf-Dienst und Lauf-Register** (Punkte 1 und 2). Das ist zugleich der Kern von E3.
4. **Werkzeugvertrag und Leitplanken-Tests** (Punkte 3 und 4).
5. **Assistent in der Oberfläche** (Punkt 5): Demo B.
6. J1 und die Daten des Betreibers laufen daneben, sobald sie da sind.

Das verschiebt die Reihenfolge aus [15](15-ENTSCHEIDUNGSKREIS.md), Abschnitt 13.4: Dort folgt auf S1 das Jahr 2025
(J1) und dann E1a. Ob Lauf-Dienst und Rollen vor J1 kommen, ist zu entscheiden.

## 6. Entscheidungen

Entschieden vom Auftraggeber am 2026-10-13.

| Nr. | Frage | Entscheidung | Stand |
|---|---|---|---|
| D70 | Womit beginnt die Demo? | **Demo A zuerst:** mit dem, was da ist | wie vorgeschlagen |
| D71 | Wo entstehen die Rollen zuerst? | beim Anbieter, als Agenten-Definitionen im Repository (`.claude/agents/sim-*.md`) | wie vorgeschlagen |
| D72 | Wie viele Rollen? | fünf: Szenario-Assistent, Datenprüfer, Modellprüfer, Betriebsstratege, Berichter. Im Produkt später ein Assistent als Gesicht, die Prüfer dahinter | wie vorgeschlagen |
| D73 | Was kommt nach S1? | **erst Demo A ausrollen** und durchspielen; die nächste Baustufe wird danach entschieden | **geändert** (Vorschlag war: Lauf-Dienst, J1 daneben) |
| D74 | Wo läuft das Sprachmodell, und was darf das Haus des Betreibers verlassen? | offen; vor Demo B mit dem Betreiber zu klären, bis dahin nur Beispieldaten | offen |

Die fünf Definitionen liegen unter `.claude/agents/`. Sie arbeiten mit der Kommandozeile und den Schnittstellen,
die es gibt, und sagen, wenn eine Frage ein Werkzeug braucht, das fehlt (Abschnitt 3.2).

## 7. Erster Durchlauf von Demo A

Am 2026-10-13 auf dem Laborsystem, nach dem Ausrollen von S1 (Retrieval-Server und Studio mit dem neuen Plan).
Durchgespielt sind die Akte 1 bis 4; die Akte 5 und 6 nur so weit, wie das Studio sie vorgerechnet zeigt.

**Was trägt.** Das Systembild zeigt zwölf Bausteine und meldet "9 inputs from outside, each with a signal". Die
Seite Signals zeigt dreizehn Signale mit Herkunft und nächstem Schritt. Die Entscheidungskarte nennt je Analyse,
woran es fehlt. Das Studio erzählt die neue Kette: Ventil zu um 01:00, Warnung 01:29, Druckgrenze im Puffer 02:29,
Schutzabschaltung, Mehreinspeisung.

**Wo es hakt.**

| Nr. | Befund | Folge für die Demo |
|---|---|---|
| 1 | Das Systembild der Web-Anwendung ist mit zwölf Bausteinen schwer zu lesen: Der Weg des Wasserstoffs ist nicht als Kette zu erkennen, Beschriftungen überlappen, Strom, Gas und Information sehen gleich aus. Die Annahme zur Reihenfolge steht nur im Plan, nicht im Bild | Akt 1 im Studio zeigen (dort sind die Spalten die Phasen und die Flüsse farbig), bis das Bild der Web-Anwendung nachgezogen ist |
| 2 | Das Studio ist von außen nicht erreichbar (Dienst nur im Cluster) und aus der Web-Anwendung nicht verlinkt. Zwei Fenster, zwei Adressen | vor der Demo eine Weiterleitung einrichten; auf Dauer ein Weg vom Ort System ins Studio |
| 3 | Die Web-Anwendung spricht Englisch, das Studio Deutsch | für ein deutsches Publikum im Ablauf ansagen; eine Sprache je Publikum ist eine Entscheidung |
| 4 | Beide Analysen stehen auf `blocked`: Der Preis ist gebunden, aber nicht erzeugt; der Bedarf hat nur Rohdaten; das Potenzial des Parks ist nicht veröffentlicht | vor der Demo drei Schritte: Preis erzeugen, Bedarf bereinigen, Potenzial aus der Windgeschwindigkeit veröffentlichen. Danach steht `restricted`, und genau das ist die Aussage der Demo |
| 5 | Die Reihe, die vor S1 als `park.p_avail_kw` veröffentlicht wurde, zählt nicht mehr: Das Signal heißt jetzt `park.p_potential_kw`, und der laufende Flow schreibt weiter unter dem alten Namen | den Flow auf das neue Signal umstellen und neu starten; ein umbenanntes Signal braucht einen beschriebenen Weg |
| 6 | Der Lauf des Studios hat mit dem neuen Plan eine neue Kennung. Das Ereignis-Topic dieses Laufs ist nicht veröffentlicht | den Lauf einmal ins Topic veröffentlichen, sonst bleibt die Ansicht "Ereignis-Topic" leer |
| 7 | Das Studio zeigt nur vorgerechnete Alternativen (Ventil wieder offen zu neun festen Zeiten, zwei Fahrweisen). Eine Frage dazwischen lässt sich nicht stellen | die Fragen der Demo auf die vorgerechneten legen; das ist die Lücke 1 aus Abschnitt 4 |
| 8 | Akt 6 hat nichts zu zeigen: Im Lauf der Demo wird nichts abgeregelt, Potenzial, verfügbare und erzeugte Leistung sind gleich | dem Szenario ein Fenster mit einer Auflage und einen Fahrbefehl geben, damit die drei Leistungen auseinanderlaufen |
| 9 | Der Abnehmer steht im Systembild der Web-Anwendung auf "to do", obwohl sein Bedarf gebunden ist: Die Marke unterscheidet nicht zwischen "Rohdaten da" und "offen" | die Marke am Baustein auf den Zustand des Signals abbilden, nicht nur auf "bereinigt oder nicht" |

Die Befunde 4, 5 und 6 sind Vorbereitung und brauchen keinen Code. Die Befunde 1, 2, 8 und 9 sind kleine Arbeiten
an Oberfläche und Szenario. Befund 7 ist der Lauf-Dienst.
