# Polystore Source Code

In diesem Repository befindet sich der Source Code für den Datenbank-Wrapper `Polystore`, die verschiedenen
Python-Scripts zur Auswertung der Benchmarks sowie der Source Code für `Polybrowse`, die Web-Anwendung zur grafischen
Verwaltung von Daten in Polystore (wird erst auf Maturaarbeits-Präsentation hin fertiggestellt).

Ordner:

* Wrapper: `server`
* Python-Scripts: `analysis`
* Polybrowse: `polybrowse`

Zudem finden sich in diesem Repository auch alle rohen Benchmark-Dateien und die detaillierten Auswertungen dieser.
Tendenziell sind diese Daten im `analysis`-Ordern, gewisse Rohdaten können aber auch noch im `server`-Ordner liegen. Nur
die Dateien des ML-Benchmarks fehlen, da diese zu gross für Git und GitHub sind.

Im Wrapper-Ordner befinden sich auch die Kotlin-Scripte / Programme, die zur Generierung der verschiedenen Benchmarks
verwendet wurden. Da diese immer wieder für andere Zwecke verwendet und umgeschrieben wurden, entsprechen diese im
jetzigen Zustand wohl nicht allen gewonnenen Resultaten, enthalten eventuell auskommentierte Zeilen oder sind
unvollständig. Die verwendeten Zustände können in der Git Commit-History betrachtet werden.