# OpenTSx lokal ausprobieren: Testinstallation mit k3s und KafScale

*Anleitung zum Ausdrucken · Stand 12.10.2026 (Version 2, mit Probelauf) · gilt für `master` ab diesem Datum*

> **Worum es geht.** Auf **einem** Linux-Rechner laufen danach: ein lokales KafScale (Log-Speicher, Kafka-Protokoll,
> per docker compose), ein k3s-Cluster mit dem Statistik-Prozessor, dem Retrieval-Server und dem Smart Topics Studio,
> dazu als Jobs der Windpark-Simulator und die drei Zwillinge (H2-Kette, Haus, Boot).
>
> **Zeitbedarf:** 60–90 Minuten, davon 10–20 Minuten Bauzeit. **Rechner:** 4 CPU-Kerne, 8 GB RAM (16 GB angenehmer),
> 20 GB freier Speicher, Ubuntu 24.04 LTS (andere Linux-Systeme gehen sinngemäß), Internetzugang für die Downloads.
>
> **Ehrlicher Stand:** Skripte und Manifeste sind geprüft (Syntax, Manifeste gegen die Kubernetes-Schemas, alle
> Befehle im Image). Ein vollständiger Lauf auf einem echten k3s mit KafScale steht noch aus. **Ihr Lauf ist der
> erste.** Bitte die Prüfliste (Teil E) ausfüllen und Auffälligkeiten notieren.

| Notizen | |
|---|---|
| Datum | &nbsp; |
| Rechner / Betriebssystem | &nbsp; |
| LAN-IP des Rechners (`HOST_IP`, Schritt A5) | &nbsp; |
| Ergebnis (alles grün / Abbruch bei Schritt …) | &nbsp; |

## Überblick

```text
+---------------------- ein Linux-Rechner -----------------------+
|                                                                |
|  docker compose (Teil B)            k3s (Teil C)               |
|  +----------------------+  9092    +------------------------+  |
|  | KafScale-Broker      |<-------->| Namespace opentsx      |  |
|  | etcd, MinIO (S3)     | HOST_IP  | Statistik, Retrieval,  |  |
|  +----------------------+          | Studio, Demo-Jobs      |  |
|                                    +------------------------+  |
|                                                                |
+----------------------------------------------------------------+
```

Wichtigster Punkt: KafScale muss sich mit der **LAN-IP** des Rechners melden (nicht mit `localhost`). Die Pods in k3s
verbinden sich zuerst mit der Bootstrap-Adresse und danach mit der Adresse, die der Broker „ankündigt“. Beide müssen
aus den Pods erreichbar sein.

## Teil A: Vorbereitung (einmalig)

**A1 – Grundpakete** (Java 21, Python, Docker mit compose):

```bash
sudo apt-get update
sudo apt-get install -y git curl netcat-openbsd openjdk-21-jdk python3 docker.io docker-compose-v2
sudo usermod -aG docker "$USER"
```

Danach **ab- und wieder anmelden**, damit die Gruppe `docker` wirkt. Prüfen: `docker run --rm hello-world`.

**A2 – Maven 3.9** (Ubuntu liefert 3.8, gebraucht wird 3.9 oder neuer):

```bash
MVN=3.9.9
URL=https://archive.apache.org/dist/maven/maven-3/$MVN/binaries
curl -fsSL $URL/apache-maven-$MVN-bin.tar.gz | sudo tar -xz -C /opt
echo "export PATH=/opt/apache-maven-$MVN/bin:\$PATH" >> ~/.bashrc
source ~/.bashrc
mvn -v            # erwartet: Apache Maven 3.9.9, Java version: 21
```

**A3 – k3s** (Kubernetes für einen Rechner):

```bash
curl -sfL https://get.k3s.io | sh -
mkdir -p ~/.kube
sudo k3s kubectl config view --raw > ~/.kube/config
chmod 600 ~/.kube/config
echo 'export KUBECONFIG=$HOME/.kube/config' >> ~/.bashrc
source ~/.bashrc
kubectl get nodes   # erwartet: eine Zeile mit STATUS Ready
```

**A4 – OpenTSx holen:**

```bash
git clone https://github.com/kamir/OpenTSx.git
cd OpenTSx
```

Alle weiteren Befehle laufen in diesem Verzeichnis.

**A5 – LAN-IP ermitteln** und oben in die Notizen eintragen:

```bash
HOST_IP=$(hostname -I | awk '{print $1}')
echo "$HOST_IP"     # z. B. 192.168.1.20 – nicht 127.0.0.1 und nicht 172.17.x.x (Docker)
```

Wenn die erste Adresse nicht die LAN-Adresse ist: `ip -4 addr` ansehen und `HOST_IP=…` von Hand setzen. In jedem
neuen Terminal muss `HOST_IP` erneut gesetzt werden.

## Schnellweg: Teil B bis E mit einem Befehl

Nach Teil A kann ein einziges Skript den Rest erledigen: KafScale lokal starten (mit der LAN-IP als angekündigter
Adresse), bauen, importieren, konfigurieren, ausrollen, alle Demos ausführen und jede Station prüfen. Am Ende steht
ein Prüfprotokoll mit BESTANDEN/FEHLER je Schritt und Hinweisen zur Abhilfe.

```bash
deploy/k3s/60-probe.sh --dry-run --local-kafscale   # nur den Plan zeigen
deploy/k3s/60-probe.sh --local-kafscale             # Probelauf, 30–60 min
```

| Option | Wirkung |
|---|---|
| `--skip-build` | Bauen und Import überspringen (Images schon in k3s) |
| `--online` | DWD-Schritt holt echte Vorhersagen (sonst Beispieldatei, kein Internet nötig) |
| `--keep-going` | nach einem Fehler in Vorbereitung/Bau/Ausrollen trotzdem weitermachen |
| `--only kafscale-check,twin` | nur bestimmte Schritte |
| `--kafscale-host 192.168.1.20` | IP selbst vorgeben, falls die automatische Wahl nicht passt |

Ergebnis: `probe-report-<Zeit>.md` (Übersicht) und `probe-logs-<Zeit>/` (Ausgabe je Schritt). Exit-Code 0 heißt:
alles bestanden. Wer lieber Schritt für Schritt vorgeht oder einen Fehler eingrenzen will, folgt Teil B bis E.

## Teil B: Lokales KafScale starten

```bash
KAFSCALE_ADVERTISED_HOST=$HOST_IP docker compose -f docker-compose.kafscale.yml up -d
docker compose -f docker-compose.kafscale.yml ps   # broker, etcd, minio laufen
nc -vz "$HOST_IP" 9092                              # erwartet: succeeded / open
```

Nur wenn eine Firewall (ufw) aktiv ist, den Pods den Weg zum Broker freigeben:

```bash
sudo ufw allow from 10.42.0.0/16 to any port 9092 proto tcp
```

## Teil C: OpenTSx in k3s installieren

| Schritt | Befehl | Erwartet |
|---|---|---|
| C1 Voraussetzungen prüfen | `KAFSCALE_BOOTSTRAP=$HOST_IP:9092 deploy/k3s/00-prereqs.sh` | Ende: `all required checks passed` (Warnungen erlaubt) |
| C2 Bauen (10–20 min) | `deploy/k3s/10-build.sh` | zwei Images `opentsx/tools` und `opentsx/studio` |
| C3 Images an k3s geben | `deploy/k3s/11-import-images.sh` | fragt nach dem sudo-Passwort, importiert beide Images |
| C4 Konfigurieren | `deploy/k3s/20-configure.sh KAFSCALE_BOOTSTRAP=$HOST_IP:9092` | schreibt `deploy/k3s/.env` |
| C5 Ausrollen | `deploy/k3s/30-deploy.sh` | Namespace `opentsx`, drei Deployments |
| C6 Zustand ansehen | `deploy/k3s/50-status.sh` | alle Pods `Running`, keine Fehler |
| C7 Verbindung prüfen | `deploy/k3s/40-demo.sh kafscale-check` | siehe unten |

Erwartete Ausgabe von C7 (gekürzt):

```text
bootstrap 192.168.1.20:9092
  bootstrap 192.168.1.20:9092: reachable
advertised broker addresses (what clients really connect to):
  advertised 192.168.1.20:9092: reachable
domain wind:
  tsx.wind.episodes.v2                  6 partitions             0 records
  ...
OK
```

Steht dort `advertised localhost:9092: NOT reachable`, kündigt KafScale die falsche Adresse an: Teil B mit
gesetztem `KAFSCALE_ADVERTISED_HOST` wiederholen (`docker compose … down`, dann `up -d`).

## Teil D: Demos

Alles in einem Durchgang (etwa 5 Minuten):

```bash
deploy/k3s/40-demo.sh all
```

Oder einzeln:

| Befehl | Was passiert | Woran man Erfolg erkennt |
|---|---|---|
| `deploy/k3s/40-demo.sh windpark-sim` | Windpark-Simulator schreibt Episoden nach KafScale | Job `Complete`; `kafscale-check` zeigt danach Records in `tsx.wind.episodes.v2` |
| `deploy/k3s/40-demo.sh stats` | Statistik-Prozessor fasst die Episoden zusammen | Records in `tsx.wind.series-stats.v2` |
| `deploy/k3s/40-demo.sh twin` | H2-Kette: Schatten, Was-wäre-wenn, Prognose | Ereignisse wie `protective_shutdown`, je Lauf eine Lauf-Id und ein Hash |
| `deploy/k3s/40-demo.sh house` | Einfamilienhaus: Referenzjahr und Stellhebel | Kennzahlen (Öl, Holz, Strom) und Differenzen je Hebel |
| `deploy/k3s/40-demo.sh boat` | Etap 22: zwei Wochen Törn, Hebel Solar/Speicher/Starlink | Autonomie und ungedeckter Bedarf je Variante |
| `deploy/k3s/40-demo.sh retrieval` | Retrieval-API antwortet | `/v1/health` und eine Episode als JSON |
| `deploy/k3s/40-demo.sh studio` | Studio im Browser | **http://localhost:8088** zeigt H2-Kette, Haus und Boot in drei Ansichten (Ctrl-C beendet) |

Die Ergebnisse sind reproduzierbar: Gleicher Code und gleiche Eingaben ergeben dieselbe Lauf-Id und denselben Hash.

## Teil E: Prüfliste zum Abhaken

| ✓ | Prüfpunkt | Notiz |
|---|---|---|
| ☐ | A: `mvn -v` zeigt Maven 3.9 und Java 21 | |
| ☐ | A: `kubectl get nodes` zeigt `Ready` | |
| ☐ | B: `nc -vz $HOST_IP 9092` erfolgreich | |
| ☐ | C1: `all required checks passed` | |
| ☐ | C2/C3: beide Images gebaut und importiert | |
| ☐ | C6: alle Pods `Running` | |
| ☐ | C7: bootstrap **und** advertised `reachable`, `OK` | |
| ☐ | D: Windpark-Episoden und Statistik in KafScale | |
| ☐ | D: Zwilling, Haus, Boot laufen durch | |
| ☐ | D: Retrieval antwortet, Studio im Browser sichtbar | |
| ☐ | Schnellweg: `60-probe.sh` ohne FEHLER, Prüfprotokoll beigelegt | |

## Teil F: Wenn etwas hakt

| Symptom | Ursache | Abhilfe |
|---|---|---|
| `ErrImageNeverPull` / `ImagePullBackOff` | Images nicht in k3s (Docker-Images sieht k3s nicht) | `deploy/k3s/11-import-images.sh`, danach `deploy/k3s/30-deploy.sh --restart` |
| `bootstrap … NOT reachable` | Firewall oder falsche IP | `nc -vz $HOST_IP 9092`; ufw-Regel aus Teil B; IP in C4 prüfen |
| `advertised localhost:9092: NOT reachable` | KafScale kündigt `localhost` an | Teil B mit `KAFSCALE_ADVERTISED_HOST=$HOST_IP` neu starten |
| Statistik bleibt bei 0 Records | Prozessor startete vor den Topics | `kubectl -n opentsx rollout restart deploy/opentsx-stats` |
| Pod `OOMKilled` | zu wenig Speicher | andere Programme schließen; Grenzen siehe `docs/devops/sizing-plan.md` |
| `port-forward`: Adresse belegt | Port 8088 schon benutzt | `kubectl -n opentsx port-forward svc/opentsx-studio 9099:80` → http://localhost:9099 |
| alter Stand nach Neubau | gleiches Image-Tag | `deploy/k3s/30-deploy.sh --restart` |

Mehr: `docs/devops/troubleshooting.md`. Zustand und Logs auf einen Blick: `deploy/k3s/50-status.sh --logs`.

## Teil G: Aufräumen

```bash
deploy/k3s/90-teardown.sh                              # OpenTSx aus k3s entfernen (fragt nach)
docker compose -f docker-compose.kafscale.yml down -v  # lokales KafScale samt Daten entfernen
/usr/local/bin/k3s-uninstall.sh                        # nur wenn k3s ganz weg soll
```

## Plan B: ohne Cluster, nur die Zwillinge

Wer nur die Modelle sehen will, braucht weder Docker noch k3s (nur Teil A1, A2, A4):

```bash
mvn -q install -DskipTests
bin/opentsx twin                 # H2-Kette in drei Betriebsarten
bin/opentsx house --simulated    # Haus mit simulierten Abrechnungen und Kalibrierung
bin/opentsx boat --simulated     # Boot mit simulierten Logger-Daten
```

Das Studio lässt sich direkt im Browser öffnen: `docs/prototypes/smart-topics-studio.html`.

## Betrieb nach dem Test

Das Betriebskonzept ([operations.md](operations.md)) beschreibt Überwachung, Sicherung, Updates und
Störungsabläufe. Sicherung und Wiederherstellung: `deploy/k3s/70-backup.sh` und `deploy/k3s/71-restore.sh`.

*Weiterführend:* [DevOps-Leitfaden](README.md) · [Betriebskonzept](operations.md) · [Setup-Plan](setup-plan.md) · [Sizing-Plan](sizing-plan.md) ·
[Demos](demos.md) · [Fehlersuche](troubleshooting.md)
