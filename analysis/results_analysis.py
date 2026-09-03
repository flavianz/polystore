"""
Resultate-Analyse — Polystore Benchmark & Routing Auswertung
==============================================================

Selbststaendiges Skript fuer Kapitel 5 (Resultate). Liest NUR die rohe
Benchmark-CSV (dieselbe 45-Mio-Zeilen-Datei aus der Datenbeschaffung,
Kapitel 4.3.1) und produziert alle Diagramme/Kennzahlen fuer:

  5.2  Performance des Wrappers
    1. Haeufigkeit "schnellste DB" (global + nach Tiefe/Connections/Filtern)
    2. Skalierungsverhalten ueber collectionSize
    3. Varianz/Verteilung pro Query-Typ und Driver
    4. Build- vs. Exec-Zeit-Anteil pro Driver
    5. Wrapper (Build+Exec) vs. reines Exec ("lohnt sich der Wrapper")
    6. Wie knapp/deutlich die Entscheidungen zwischen den DBs ausfallen

  5.3  Genauigkeit des Abfrage-Routings
    7. Regressionsguete (R^2, RMSE) pro (Driver, Phase)
    8. Klassifikations-Accuracy: ML vs. Heuristik vs. Majority-Baseline
    9. Durchschnittlicher Zeitgewinn/-verlust ML vs. Heuristik vs. Majority
    10. Verteilung der Kosten von Fehlentscheidungen

Bewusst eigenstaendig gehalten (kein Import aus regression.py /
regression_2.py / compare_driver_selection.py), damit dieses Skript
unabhaengig von deren genauem Zustand lauffaehig ist. Dafuer werden
form_id, Holdout-Split und die linearen Modelle hier noch einmal (in
vereinfachter Form: nur Modell A, kein log1p/Interaction-Diagnoseschritt)
selbst berechnet. Falls du stattdessen exakt die bereits trainierten
Modell-B-Koeffizienten aus model_coefficients_linear.json verwenden
willst, kannst du COEFFICIENTS_PATH unten setzen (siehe Abschnitt 7/8).

Erwartetes CSV-Format: ';'-getrennt, mit mindestens den Spalten
  driver, collectionSize, iteration, phase, duration,
  plus den strukturellen QueryProperties-Spalten (siehe FEATURE_COLUMNS).
phase enthaelt (mindestens) die Werte 'build', 'exec', 'total'.

Aufruf:
    python3 results_analysis.py [benchmarks.csv] [out_dir]
"""

from __future__ import annotations

import json
import os
import sys
import warnings

import numpy as np
import pandas as pd

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import seaborn as sns

sns.set_theme(style="whitegrid")

# ---------------------------------------------------------------------------
# CONFIG
# ---------------------------------------------------------------------------

CSV_PATH = "benchmarks.csv"
OUT_DIR = "results_analysis_out"

# Optional: falls vorhanden, werden fuer 7./8./9./10. die bereits trainierten
# Modell-B-Koeffizienten (aus regression_2.py) statt einer im Skript neu
# gefitteten einfachen linearen Regression verwendet. Auf None lassen, um
# immer die eigenstaendige, in diesem Skript trainierte Variante zu nutzen.
COEFFICIENTS_PATH: str | None = "benchmark_analysis_out/model_coefficients_linear.json"  # z.B. "benchmark_analysis_out/model_coefficients_linear.json"

# Optional: CSV mit den Entscheidungen der simplen Heuristik
# (';'-getrennt, Spalten: die FORM_ID_COLUMNS + 'simpleDriverChoice').
SIMPLE_CHOICE_PATH: str | None = "simple_choices.csv"

WARMUP_ITERATIONS = 50
IQR_MULTIPLIER = 1.5
HOLDOUT_FORM_FRACTION = 0.03
RANDOM_SEED = 42
PHASE_FOR_DECISION = "total"

DRIVERS = ["postgres", "mongo", "neo4j"]  # erwartete Werte der 'driver'-Spalte

FEATURE_COLUMNS = [
    "collectionSize",
    "singleCollectionSegmentCount",
    "pairCollectionSegmentCount",
    "connectionSegmentCount",
    "multiQueryCount",
    "rootIdFilterCount",
    "rootValueInListFilterCount",
    "rootEqualityFilterCount",
    "rootNumberRangeFilterCount",
    "nestedIdFilterCount",
    "nestedValueInListFilterCount",
    "nestedEqualityFilterCount",
    "nestedNumberRangeFilterCount",
    "firstFilterDepth",
    "onlyResultFraction",
    "dynamicFilterFraction",
    "dynamicResultFraction",
]
FORM_ID_COLUMNS = [c for c in FEATURE_COLUMNS if c != "collectionSize"]
FLOAT_ROUND_DECIMALS = 6


# ---------------------------------------------------------------------------
# 0. Laden & Grundaufbereitung (identisch im Prinzip zu Kapitel 4.3.2)
# ---------------------------------------------------------------------------

def load_data(csv_path: str) -> pd.DataFrame:
    df = pd.read_csv(csv_path, sep=";")
    df["driver"] = df["driver"].astype("category")
    df["phase"] = df["phase"].astype("category")
    return df


def compute_form_id(df: pd.DataFrame) -> pd.Series:
    rounded = df[FORM_ID_COLUMNS].round(FLOAT_ROUND_DECIMALS)
    return pd.util.hash_pandas_object(rounded, index=False).astype("int64")


def flag_warmup(df: pd.DataFrame) -> pd.Series:
    order = df.sort_values("iteration").groupby(
        ["driver", "phase", "form_id"], observed=True
    ).cumcount()
    return (order < WARMUP_ITERATIONS).reindex(df.index).fillna(False)


def flag_outliers(df: pd.DataFrame, warmup_mask: pd.Series) -> pd.Series:
    out = pd.Series(False, index=df.index)
    clean = df[~warmup_mask]
    for (drv, ph), group in clean.groupby(["driver", "phase"], observed=True):
        q1, q3 = group["duration"].quantile([0.25, 0.75])
        iqr = q3 - q1
        low, high = q1 - IQR_MULTIPLIER * iqr, q3 + IQR_MULTIPLIER * iqr
        mask = (df["driver"] == drv) & (df["phase"] == ph) & (
                (df["duration"] < low) | (df["duration"] > high)
        )
        out |= mask
    return out


def split_holdout_forms(df: pd.DataFrame, seed: int = RANDOM_SEED) -> set:
    rng = np.random.default_rng(seed)
    forms = df["form_id"].unique()
    n_holdout = max(1, int(len(forms) * HOLDOUT_FORM_FRACTION))
    return set(rng.choice(forms, size=n_holdout, replace=False))


def prepare(csv_path: str) -> pd.DataFrame:
    df = load_data(csv_path)
    df["form_id"] = compute_form_id(df)
    df["is_warmup"] = flag_warmup(df)
    df["is_outlier"] = flag_outliers(df, df["is_warmup"])
    df["is_clean"] = ~df["is_warmup"] & ~df["is_outlier"]
    holdout_forms = split_holdout_forms(df)
    df["is_holdout_form"] = df["form_id"].isin(holdout_forms)
    return df


# ---------------------------------------------------------------------------
# Hilfsfunktionen
# ---------------------------------------------------------------------------

def query_depth_bucket(row: pd.Series) -> str:
    """Grobe Tiefen-Kategorie einer Abfrageform, fuer die Aufschluesselung
    in 1./6. Bewusst simpel gehalten (Anzahl involvierter Collection-
    Segmente als Proxy fuer Tiefe/Komplexitaet)."""
    n = (
            row["singleCollectionSegmentCount"]
            + row["pairCollectionSegmentCount"]
            + row["connectionSegmentCount"]
    )
    if n <= 1:
        return "flach (<=1 Segment)"
    elif n <= 3:
        return "mittel (2-3 Segmente)"
    else:
        return "tief (4+ Segmente)"


def save_json(obj: dict, path: str):
    with open(path, "w") as f:
        json.dump(obj, f, indent=2, default=str)


# ---------------------------------------------------------------------------
# 1. Haeufigkeit "schnellste DB"
# ---------------------------------------------------------------------------

def analysis_fastest_db(df: pd.DataFrame, out_dir: str) -> pd.DataFrame:
    d = df[df["is_clean"] & (df["phase"] == PHASE_FOR_DECISION)]
    means = (
        d.groupby(["form_id", "collectionSize", "driver"], observed=True)["duration"]
        .mean()
        .reset_index()
    )
    winners = (
        means.sort_values("duration")
        .groupby(["form_id", "collectionSize"], observed=True)
        .first()
        .reset_index()
        .rename(columns={"driver": "fastest_driver"})
    )

    # globale Haeufigkeit
    global_counts = winners["fastest_driver"].value_counts(normalize=True)
    fig, ax = plt.subplots(figsize=(6, 4.5))
    global_counts.reindex(DRIVERS).plot.bar(ax=ax, color=["#4C72B0", "#55A868", "#C44E52"])
    ax.set_ylabel("Anteil, an dem Driver am schnellsten war")
    ax.set_title("Haeufigkeit der schnellsten Datenbank (gesamt)")
    fig.tight_layout()
    fig.savefig(os.path.join(out_dir, "01_fastest_db_global.png"), dpi=130)
    plt.close(fig)

    # nach Tiefe aufgeschluesselt
    props = df.drop_duplicates("form_id").set_index("form_id")
    winners["depth_bucket"] = winners["form_id"].map(lambda f: query_depth_bucket(props.loc[f]))
    pivot_depth = (
        winners.groupby(["depth_bucket", "fastest_driver"], observed=True)
        .size()
        .unstack(fill_value=0)
    )
    pivot_depth = pivot_depth.div(pivot_depth.sum(axis=1), axis=0)
    fig, ax = plt.subplots(figsize=(7, 4.5))
    pivot_depth.reindex(columns=DRIVERS).plot.bar(stacked=True, ax=ax,
                                                  color=["#4C72B0", "#55A868", "#C44E52"])
    ax.set_ylabel("Anteil")
    ax.set_title("Schnellste Datenbank nach Abfragetiefe")
    ax.legend(title="Driver")
    fig.tight_layout()
    fig.savefig(os.path.join(out_dir, "01_fastest_db_by_depth.png"), dpi=130)
    plt.close(fig)

    # nach Connection-Anzahl
    winners["connection_count"] = winners["form_id"].map(
        lambda f: props.loc[f, "connectionSegmentCount"]
    )
    conn_bucket = pd.cut(winners["connection_count"], bins=[-1, 0, 1, 2, np.inf],
                         labels=["0", "1", "2", "3+"])
    pivot_conn = (
        winners.groupby([conn_bucket, "fastest_driver"], observed=True)
        .size()
        .unstack(fill_value=0)
    )
    pivot_conn = pivot_conn.div(pivot_conn.sum(axis=1), axis=0)
    fig, ax = plt.subplots(figsize=(7, 4.5))
    pivot_conn.reindex(columns=DRIVERS).plot.bar(stacked=True, ax=ax,
                                                 color=["#4C72B0", "#55A868", "#C44E52"])
    ax.set_xlabel("Anzahl Connections in der Abfrage")
    ax.set_ylabel("Anteil")
    ax.set_title("Schnellste Datenbank nach Anzahl Connections")
    ax.legend(title="Driver")
    fig.tight_layout()
    fig.savefig(os.path.join(out_dir, "01_fastest_db_by_connections.png"), dpi=130)
    plt.close(fig)

    save_json(
        {
            "global": global_counts.to_dict(),
            "by_depth": pivot_depth.to_dict(orient="index"),
            "by_connections": {str(k): v for k, v in pivot_conn.to_dict(orient="index").items()},
        },
        os.path.join(out_dir, "01_fastest_db.json"),
    )
    return winners


# ---------------------------------------------------------------------------
# 2. Skalierungsverhalten ueber collectionSize
# ---------------------------------------------------------------------------

def analysis_scaling(df: pd.DataFrame, out_dir: str):
    d = df[df["is_clean"] & (df["phase"] == PHASE_FOR_DECISION)]
    agg = (
        d.groupby(["driver", "collectionSize"], observed=True)["duration"]
        .median()
        .reset_index()
    )
    fig, ax = plt.subplots(figsize=(7, 4.5))
    for drv in DRIVERS:
        sub = agg[agg["driver"] == drv].sort_values("collectionSize")
        if len(sub):
            ax.plot(sub["collectionSize"], sub["duration"], marker="o", label=drv)
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlabel("Datensatzgroesse (collectionSize)")
    ax.set_ylabel("Median-Dauer (µs)")
    ax.set_title("Skalierungsverhalten ueber die Datensatzgroesse")
    ax.legend(title="Driver")
    fig.tight_layout()
    fig.savefig(os.path.join(out_dir, "02_scaling_by_collectionsize.png"), dpi=130)
    plt.close(fig)
    agg.to_csv(os.path.join(out_dir, "02_scaling_by_collectionsize.csv"), sep=";", index=False)


# ---------------------------------------------------------------------------
# 3. Varianz / Verteilung pro Query-Typ und Driver
# ---------------------------------------------------------------------------

def analysis_variance(df: pd.DataFrame, out_dir: str):
    d = df[df["is_clean"] & (df["phase"] == PHASE_FOR_DECISION)].copy()
    props = df.drop_duplicates("form_id").set_index("form_id")
    d["depth_bucket"] = d["form_id"].map(lambda f: query_depth_bucket(props.loc[f]))

    fig, ax = plt.subplots(figsize=(8, 5))
    sns.boxplot(data=d, x="depth_bucket", y="duration", hue="driver",
                order=["flach (<=1 Segment)", "mittel (2-3 Segmente)", "tief (4+ Segmente)"],
                hue_order=DRIVERS, ax=ax, showfliers=False)
    ax.set_yscale("log")
    ax.set_xlabel("Abfragetiefe")
    ax.set_ylabel("Dauer (µs)")
    ax.set_title("Verteilung der Laufzeiten nach Tiefe und Driver")
    fig.tight_layout()
    fig.savefig(os.path.join(out_dir, "03_variance_by_depth.png"), dpi=130)
    plt.close(fig)

    # Variationskoeffizient (std/mean) pro (driver, form) als Streuungsmass
    cv = (
        d.groupby(["driver", "form_id"], observed=True)["duration"]
        .agg(["mean", "std"])
        .reset_index()
    )
    cv["cv"] = cv["std"] / cv["mean"]
    cv_summary = cv.groupby("driver", observed=True)["cv"].describe()
    cv_summary.to_csv(os.path.join(out_dir, "03_coefficient_of_variation.csv"), sep=";")


# ---------------------------------------------------------------------------
# 4. Build- vs. Exec-Zeit-Anteil & 5. Wrapper vs. reines Exec
# ---------------------------------------------------------------------------

def analysis_build_vs_exec(df: pd.DataFrame, out_dir: str):
    d = df[df["is_clean"] & df["phase"].isin(["build", "exec", "total"])]
    med = (
        d.groupby(["driver", "phase"], observed=True)["duration"]
        .median()
        .unstack()
    )
    med["build_share_of_total"] = med["build"] / med["total"]
    med.to_csv(os.path.join(out_dir, "04_build_vs_exec.csv"), sep=";")

    fig, ax = plt.subplots(figsize=(6, 4.5))
    med[["build", "exec"]].reindex(DRIVERS).plot.bar(stacked=True, ax=ax,
                                                     color=["#8172B2", "#CCB974"])
    ax.set_ylabel("Median-Dauer (µs)")
    ax.set_title("Anteil Build- vs. Exec-Zeit pro Driver")
    fig.tight_layout()
    fig.savefig(os.path.join(out_dir, "04_build_vs_exec.png"), dpi=130)
    plt.close(fig)

    # 5. "Lohnt sich der Wrapper": Anteil an Faellen, in denen
    # Wrapper-Gesamtzeit (total) trotz Build-Overhead schneller ist als
    # das langsamste reine exec einer anderen Datenbank -> Vergleich pro
    # Query-Form zwischen "total" (inkl. eigenem Build) der gewaehlten DB
    # und "exec" (ohne jeglichen Wrapper-Overhead) der jeweils anderen DBs.
    exec_only = d[d["phase"] == "exec"].groupby(
        ["form_id", "collectionSize", "driver"], observed=True
    )["duration"].mean().reset_index()
    total_only = d[d["phase"] == "total"].groupby(
        ["form_id", "collectionSize", "driver"], observed=True
    )["duration"].mean().reset_index()

    rows = []
    for (form_id, size), group_total in total_only.groupby(["form_id", "collectionSize"]):
        exec_group = exec_only[
            (exec_only["form_id"] == form_id) & (exec_only["collectionSize"] == size)
            ]
        for _, trow in group_total.iterrows():
            other_exec = exec_group[exec_group["driver"] != trow["driver"]]
            if other_exec.empty:
                continue
            beats_all_other_exec = (trow["duration"] < other_exec["duration"]).all()
            beats_fastest_other_exec = trow["duration"] < other_exec["duration"].min()
            rows.append({
                "form_id": form_id, "collectionSize": size, "driver": trow["driver"],
                "total_duration": trow["duration"],
                "beats_all_other_exec": beats_all_other_exec,
                "beats_fastest_other_exec": beats_fastest_other_exec,
            })
    wrapper_df = pd.DataFrame(rows)
    summary = {
        "share_where_own_total_beats_fastest_other_pure_exec":
            float(wrapper_df["beats_fastest_other_exec"].mean()) if len(wrapper_df) else None,
        "share_where_own_total_beats_all_other_pure_exec":
            float(wrapper_df["beats_all_other_exec"].mean()) if len(wrapper_df) else None,
        "n_comparisons": int(len(wrapper_df)),
    }
    save_json(summary, os.path.join(out_dir, "05_wrapper_vs_pure_exec.json"))
    wrapper_df.to_csv(os.path.join(out_dir, "05_wrapper_vs_pure_exec_rows.csv"), sep=";", index=False)


# ---------------------------------------------------------------------------
# 6. Wie knapp/deutlich die Entscheidungen ausfallen
# ---------------------------------------------------------------------------

def analysis_margins(winners: pd.DataFrame, df: pd.DataFrame, out_dir: str):
    d = df[df["is_clean"] & (df["phase"] == PHASE_FOR_DECISION)]
    means = (
        d.groupby(["form_id", "collectionSize", "driver"], observed=True)["duration"]
        .mean()
        .reset_index()
    )
    margins = []
    for (form_id, size), group in means.groupby(["form_id", "collectionSize"]):
        sorted_group = group.sort_values("duration")
        if len(sorted_group) < 2:
            continue
        fastest, second = sorted_group.iloc[0]["duration"], sorted_group.iloc[1]["duration"]
        margins.append({
            "form_id": form_id, "collectionSize": size,
            "relative_margin": (second - fastest) / fastest if fastest > 0 else np.nan,
        })
    margin_df = pd.DataFrame(margins)

    fig, ax = plt.subplots(figsize=(7, 4.5))
    ax.hist(margin_df["relative_margin"].clip(upper=5), bins=50)
    ax.set_xlabel("relativer Abstand schnellste zu zweitschnellster DB")
    ax.set_ylabel("Anzahl (Form, CollectionSize)-Kombinationen")
    ax.set_title("Wie knapp/deutlich die schnellste DB gewinnt")
    fig.tight_layout()
    fig.savefig(os.path.join(out_dir, "06_decision_margins.png"), dpi=130)
    plt.close(fig)

    save_json(
        {
            "median_relative_margin": float(margin_df["relative_margin"].median()),
            "share_margin_below_10pct": float((margin_df["relative_margin"] < 0.10).mean()),
            "share_margin_above_100pct": float((margin_df["relative_margin"] > 1.0).mean()),
        },
        os.path.join(out_dir, "06_decision_margins.json"),
    )


# ---------------------------------------------------------------------------
# 7.-10. ML-Guete, Klassifikations-Accuracy, Zeitgewinn, Fehlerkosten
# ---------------------------------------------------------------------------

def fit_simple_linear_models(df: pd.DataFrame):
    """Einfaches Modell A (unveraendert, keine Transforms) pro (driver,
    phase) -- fuer 7./9./10. Fuer Kapitel 5 bewusst das simplere Modell A
    statt der vollen Modell-B-Diagnosepipeline, ausser COEFFICIENTS_PATH
    ist gesetzt (siehe unten)."""
    from sklearn.linear_model import LinearRegression
    from sklearn.model_selection import KFold
    from sklearn.metrics import r2_score, mean_squared_error

    train = df[df["is_clean"] & ~df["is_holdout_form"]]
    holdout = df[df["is_clean"] & df["is_holdout_form"]]

    models, metrics = {}, {}
    for drv in DRIVERS:
        for phase in df["phase"].cat.categories:
            tr = train[(train["driver"] == drv) & (train["phase"] == phase)]
            ho = holdout[(holdout["driver"] == drv) & (holdout["phase"] == phase)]
            if len(tr) < 20:
                continue
            X, y = tr[FEATURE_COLUMNS].values, tr["duration"].values
            model = LinearRegression().fit(X, y)

            kf = KFold(n_splits=5, shuffle=True, random_state=RANDOM_SEED)
            r2s, rmses = [], []
            for tr_idx, te_idx in kf.split(X):
                m = LinearRegression().fit(X[tr_idx], y[tr_idx])
                pred = m.predict(X[te_idx])
                r2s.append(r2_score(y[te_idx], pred))
                rmses.append(np.sqrt(mean_squared_error(y[te_idx], pred)))

            key = f"{drv}__{phase}"
            models[key] = model
            ho_r2 = ho_rmse = None
            if len(ho):
                pred_ho = model.predict(ho[FEATURE_COLUMNS].values)
                ho_r2 = float(r2_score(ho["duration"].values, pred_ho))
                ho_rmse = float(np.sqrt(mean_squared_error(ho["duration"].values, pred_ho)))
            metrics[key] = {
                "row_cv_r2_mean": float(np.mean(r2s)),
                "row_cv_rmse_mean": float(np.mean(rmses)),
                "holdout_r2": ho_r2,
                "holdout_rmse": ho_rmse,
                "n_train_rows": int(len(tr)),
                "n_holdout_rows": int(len(ho)),
            }
    return models, metrics


def load_external_coefficients(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def predict_with_json_model(model_json: dict, row: pd.Series) -> float:
    total = model_json["intercept"]
    for feat, coef in model_json["numeric_coefficients"].items():
        val = row[feat]
        if model_json.get("feature_transforms", {}).get(feat) == "log1p":
            val = np.log1p(val)
        total += coef * val
    if model_json.get("target_transform") == "log1p":
        total = np.expm1(total)
    return total


def analysis_ml_quality_and_routing(df: pd.DataFrame, out_dir: str):
    # --- 7. Regressionsguete ---
    if COEFFICIENTS_PATH and os.path.exists(COEFFICIENTS_PATH):
        external = load_external_coefficients(COEFFICIENTS_PATH)
        models_for_prediction = ("external", external)
        metrics = {k: v["metrics"] if "metrics" in v else None for k, v in external.items()}
        # externe metrics_report.json separat einlesen, falls vorhanden
        ext_metrics_path = os.path.join(os.path.dirname(COEFFICIENTS_PATH), "metrics_report.json")
        if os.path.exists(ext_metrics_path):
            with open(ext_metrics_path) as f:
                metrics = json.load(f)
    else:
        fitted_models, metrics = fit_simple_linear_models(df)
        models_for_prediction = ("fitted", fitted_models)

    save_json(metrics, os.path.join(out_dir, "07_regression_quality.json"))

    rows = []
    for key, m in metrics.items():
        if m is None:
            continue
        drv, phase = key.split("__")
        r2 = m.get("holdout_r2") if "holdout_r2" in m else m.get("model_b", {}).get("holdout", {}).get("r2")
        rows.append({"driver": drv, "phase": phase, "holdout_r2": r2})
    r2_df = pd.DataFrame(rows)
    if len(r2_df):
        pivot = r2_df.pivot(index="phase", columns="driver", values="holdout_r2")
        fig, ax = plt.subplots(figsize=(6, 4.5))
        pivot.reindex(columns=DRIVERS).plot.bar(ax=ax, color=["#4C72B0", "#55A868", "#C44E52"])
        ax.set_ylabel("Holdout R^2")
        ax.set_title("Regressionsguete pro Driver und Phase")
        fig.tight_layout()
        fig.savefig(os.path.join(out_dir, "07_regression_quality.png"), dpi=130)
        plt.close(fig)

    # --- Ground truth + ML-Vorhersagen fuer 8./9./10. ---
    d = df[df["is_clean"] & (df["phase"] == PHASE_FOR_DECISION)]
    means = (
        d.groupby(["form_id", "collectionSize", "driver"], observed=True)["duration"]
        .mean()
        .reset_index()
    )
    ground_truth = (
        means.sort_values("duration")
        .groupby(["form_id", "collectionSize"], observed=True)
        .first()
        .rename(columns={"driver": "actual_fastest", "duration": "actual_fastest_duration"})
        .reset_index()
    )
    n_drivers = (
        means.groupby(["form_id", "collectionSize"], observed=True)["driver"]
        .nunique()
        .rename("n_drivers_compared")
        .reset_index()
    )
    ground_truth = ground_truth.merge(n_drivers, on=["form_id", "collectionSize"])
    ground_truth = ground_truth[ground_truth["form_id"].isin(
        df.loc[df["is_holdout_form"], "form_id"].unique()
    )]

    rep_rows = df.drop_duplicates(subset=["form_id", "collectionSize"])
    rep_rows = rep_rows[rep_rows["form_id"].isin(ground_truth["form_id"])]

    kind, model_store = models_for_prediction
    ml_records = []
    for _, row in rep_rows.iterrows():
        preds = {}
        for drv in DRIVERS:
            key = f"{drv}__{PHASE_FOR_DECISION}"
            if kind == "fitted":
                if key not in model_store:
                    continue
                preds[drv] = float(model_store[key].predict(row[FEATURE_COLUMNS].values.reshape(1, -1))[0])
            else:
                if key not in model_store:
                    continue
                model_json = model_store[key].get("model_b", model_store[key])
                preds[drv] = predict_with_json_model(model_json, row)
        if not preds:
            continue
        ml_records.append({
            "form_id": row["form_id"], "collectionSize": row["collectionSize"],
            "ml_predicted_fastest": min(preds, key=preds.get),
            **{f"ml_pred_duration_{d_}": v for d_, v in preds.items()},
        })
    ml_df = pd.DataFrame(ml_records)

    comparison = ground_truth.merge(ml_df, on=["form_id", "collectionSize"], how="left")

    # Majority-Baseline
    majority_driver = comparison["actual_fastest"].value_counts().idxmax()
    comparison["majority_predicted"] = majority_driver

    # Simple Heuristik (optional)
    if SIMPLE_CHOICE_PATH and os.path.exists(SIMPLE_CHOICE_PATH):
        raw = pd.read_csv(SIMPLE_CHOICE_PATH, sep=";")
        choice_col = "simpleDriverChoice" if "simpleDriverChoice" in raw.columns else "simpleChoice"
        float_cols = {"onlyResultFraction", "dynamicFilterFraction", "dynamicResultFraction"}
        for c in FORM_ID_COLUMNS:
            raw[c] = raw[c].astype("float64" if c in float_cols else "int64")
        rounded = raw[FORM_ID_COLUMNS].round(FLOAT_ROUND_DECIMALS)
        raw["form_id"] = pd.util.hash_pandas_object(rounded, index=False).astype("int64")
        comparison = comparison.merge(
            raw[["form_id", choice_col]].rename(columns={choice_col: "simple_predicted"}),
            on="form_id", how="left",
        )

    comparison.to_csv(os.path.join(out_dir, "08_comparison_rows.csv"), sep=";", index=False)

    # --- 8. Klassifikations-Accuracy ---
    def accuracy(col):
        valid = comparison.dropna(subset=[col, "actual_fastest"])
        if not len(valid):
            return None
        overall = float((valid[col] == valid["actual_fastest"]).mean())
        by_n = {
            int(n): float((g[col] == g["actual_fastest"]).mean())
            for n, g in valid.groupby("n_drivers_compared")
        }
        return {"overall": overall, "by_n_drivers_compared": by_n, "n": int(len(valid))}

    accuracy_results = {
        "ml_model": accuracy("ml_predicted_fastest"),
        "majority_baseline": accuracy("majority_predicted"),
    }
    if "simple_predicted" in comparison.columns:
        accuracy_results["simple_heuristic"] = accuracy("simple_predicted")
    save_json(accuracy_results, os.path.join(out_dir, "08_classification_accuracy.json"))

    fig, ax = plt.subplots(figsize=(6, 4.5))
    labels, values = [], []
    for name, res in accuracy_results.items():
        if res:
            labels.append(name)
            values.append(res["overall"])
    ax.bar(labels, values, color=["#4C72B0", "#55A868", "#C44E52"][: len(labels)])
    ax.set_ylabel("Accuracy")
    ax.set_ylim(0, 1)
    ax.set_title("Klassifikations-Accuracy: ML vs. Heuristik vs. Majority-Baseline")
    fig.tight_layout()
    fig.savefig(os.path.join(out_dir, "08_classification_accuracy.png"), dpi=130)
    plt.close(fig)

    # --- 9. Zeitgewinn/-verlust & 10. Fehlerkosten ---
    duration_lookup = means.set_index(["form_id", "collectionSize", "driver"])["duration"]

    def resolve_duration(form_id, size, drv):
        try:
            return duration_lookup.loc[(form_id, size, drv)]
        except KeyError:
            return np.nan

    for col, out_col in [
                            ("ml_predicted_fastest", "ml_chosen_duration"),
                            ("majority_predicted", "majority_chosen_duration"),
                        ] + ([("simple_predicted", "simple_chosen_duration")] if "simple_predicted" in comparison.columns else []):
        comparison[out_col] = comparison.apply(
            lambda r: resolve_duration(r["form_id"], r["collectionSize"], r[col])
            if pd.notna(r[col]) else np.nan, axis=1,
        )
        comparison[f"{out_col}_overhead"] = comparison[out_col] - comparison["actual_fastest_duration"]

    time_summary = {}
    for out_col in ["ml_chosen_duration", "majority_chosen_duration"] + (
            ["simple_chosen_duration"] if "simple_predicted" in comparison.columns else []
    ):
        overhead_col = f"{out_col}_overhead"
        valid = comparison.dropna(subset=[overhead_col])
        time_summary[out_col] = {
            "mean_absolute_overhead_us": float(valid[overhead_col].mean()),
            "median_absolute_overhead_us": float(valid[overhead_col].median()),
            "mean_relative_overhead": float(
                (valid[overhead_col] / valid["actual_fastest_duration"]).mean()
            ),
            "share_correct_zero_overhead": float((valid[overhead_col] <= 1e-9).mean()),
        }
    save_json(time_summary, os.path.join(out_dir, "09_time_gain_loss.json"))

    fig, ax = plt.subplots(figsize=(7, 4.5))
    for out_col, label in [
                              ("ml_chosen_duration_overhead", "ML-Modell"),
                              ("majority_chosen_duration_overhead", "Majority-Baseline"),
                          ] + ([("simple_chosen_duration_overhead", "Simple Heuristik")]
    if "simple_predicted" in comparison.columns else []):
        vals = comparison[out_col].dropna()
        wrong_only = vals[vals > 1e-9]  # Verteilung der Fehlerkosten, nur falsche Entscheidungen
        if len(wrong_only):
            ax.hist(wrong_only.clip(upper=wrong_only.quantile(0.95)), bins=40, alpha=0.5, label=label)
    ax.set_xlabel("Zeitverlust bei Fehlentscheidung (µs)")
    ax.set_ylabel("Anzahl Faelle")
    ax.set_title("Verteilung der Kosten von Fehlentscheidungen")
    ax.legend()
    fig.tight_layout()
    fig.savefig(os.path.join(out_dir, "10_error_cost_distribution.png"), dpi=130)
    plt.close(fig)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main(csv_path: str = CSV_PATH, out_dir: str = OUT_DIR):
    os.makedirs(out_dir, exist_ok=True)
    warnings.filterwarnings("ignore")

    print(f"[1/4] Lade und bereite '{csv_path}' auf ...")
    df = prepare(csv_path)
    print(f"      {len(df):,} Zeilen, {df['form_id'].nunique():,} Abfrageformen, "
          f"{df['is_clean'].mean():.1%} nach Warmup/Outlier-Filter sauber, "
          f"{df['is_holdout_form'].sum():,} Zeilen aus zurueckgehaltenen Formen.")

    print("[2/4] Performance-Analysen (1-6) ...")
    winners = analysis_fastest_db(df, out_dir)
    analysis_scaling(df, out_dir)
    analysis_variance(df, out_dir)
    analysis_build_vs_exec(df, out_dir)
    analysis_margins(winners, df, out_dir)

    print("[3/4] ML-Guete & Routing-Analysen (7-10) ...")
    analysis_ml_quality_and_routing(df, out_dir)

    print(f"[4/4] Fertig. Alle Diagramme/Kennzahlen liegen in '{out_dir}'.")


if __name__ == "__main__":
    csv_arg = sys.argv[1] if len(sys.argv) > 1 else CSV_PATH
    out_arg = sys.argv[2] if len(sys.argv) > 2 else OUT_DIR
    main(csv_path=csv_arg, out_dir=out_arg)