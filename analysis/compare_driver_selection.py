"""
Driver-selection accuracy comparison
=====================================

Answers the actual question that matters for `pickBestDriver`: not "how
close is the duration estimate", but "did we pick the driver that was
actually fastest". This is evaluated ONLY on held-out query forms (never
seen during model fitting), so it's an honest test of generalization to
new query shapes, not a replay of training data.

Two things get scored against the same ground truth:
  1. The ML model (via model_coefficients_linear.json), using the 'total'
     phase models directly, since 'total' duration is what actually
     matters for picking a driver end-to-end.
  2. Your existing `chooseDriverSimple` heuristic, via a `simpleChoice`
     column you export from Kotlin (see load_simple_choices() below for
     the expected format) — left as a clearly-marked slot to fill in.

Ground truth "fastest driver" for a given (query form, collectionSize) is
the driver with the lowest MEAN clean 'total' duration across iterations
for that exact form+size combination — mean rather than a single
iteration, since a single iteration's noise could easily flip which
driver "wins" by a few percent even when one is genuinely faster.
"""

from __future__ import annotations

import json
import os
import warnings
from dataclasses import dataclass

import numpy as np
import pandas as pd

from regression_2 import (
    load_data, add_interaction_features, compute_form_id, flag_warmup,
    flag_outliers, MODEL_FEATURE_COLUMNS,
)

CSV_PATH = "benchmarks.csv"
COEFFICIENTS_PATH = "benchmark_analysis_out/model_coefficients_linear.json"
SIMPLE_CHOICE_PATH = "simple_choices.csv"   # see load_simple_choices() below
OUT_DIR = "benchmark_analysis_out"
PHASE_FOR_DECISION = "total"   # what actually matters for picking a driver


# ---------------------------------------------------------------------------
# Ground truth: actually-fastest driver per (form, collectionSize)
# ---------------------------------------------------------------------------

def compute_ground_truth(df: pd.DataFrame, clean_mask: pd.Series) -> pd.DataFrame:
    """One row per (form_id, collectionSize): the driver with the lowest
    mean 'total' duration, plus how many drivers had data for that
    combination (a 2-driver comparison is a much easier "pick the
    winner" problem than a genuine 3-way one — worth knowing which
    you're actually being scored on).
    """
    d = df[clean_mask & (df["phase"] == PHASE_FOR_DECISION)]
    means = (
        d.groupby(["form_id", "collectionSize", "driver"], observed=True)["duration"]
        .mean()
        .reset_index()
    )
    winners = (
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
    return winners.merge(n_drivers, on=["form_id", "collectionSize"])


# ---------------------------------------------------------------------------
# ML model: predicted-fastest driver per (form, collectionSize)
# ---------------------------------------------------------------------------

def predict_duration(model_json: dict, row: pd.Series) -> float:
    """Apply one (driver, phase) model's coefficients to a single feature
    row: same arithmetic the ported Kotlin formula will do, so this also
    doubles as a reference implementation for that port.
    """
    total = model_json["intercept"]
    for feat, coef in model_json["numeric_coefficients"].items():
        val = row[feat]
        if model_json["feature_transforms"].get(feat) == "log1p":
            val = np.log1p(val)
        total += coef * val
    if model_json["target_transform"] == "log1p":
        total = np.expm1(total)
    return total


def compute_ml_predictions(df: pd.DataFrame, coefficients: dict, drivers: list[str]) -> pd.DataFrame:
    """One row per (form_id, collectionSize): the driver Model B predicts
    will be fastest for the PHASE_FOR_DECISION phase.

    Uses one representative row per (form_id, collectionSize) — since all
    structural features are identical for a given form, and collectionSize
    is fixed per group, every row in the group has the same feature
    values, so a single row is exactly as good as averaging them.
    """
    rep_rows = df.drop_duplicates(subset=["form_id", "collectionSize"])[
        list(dict.fromkeys(["form_id", "collectionSize"] + MODEL_FEATURE_COLUMNS))
    ]

    records = []
    for _, row in rep_rows.iterrows():
        preds = {}
        for driver in drivers:
            key = f"{driver}__{PHASE_FOR_DECISION}"
            if key not in coefficients:
                continue
            preds[driver] = predict_duration(coefficients[key]["model_b"], row)
        if not preds:
            continue
        predicted_fastest = min(preds, key=preds.get)
        records.append({
            "form_id": row["form_id"],
            "collectionSize": row["collectionSize"],
            "ml_predicted_fastest": predicted_fastest,
            **{f"ml_pred_duration_{d}": v for d, v in preds.items()},
        })

    return pd.DataFrame.from_records(records)


# ---------------------------------------------------------------------------
# Simple heuristic: load Kotlin-computed picks
# ---------------------------------------------------------------------------

def load_simple_choices(path: str, form_id_columns: list[str]) -> pd.DataFrame | None:
    """Expected CSV format (';'-delimited, same convention as the main
    benchmark file): the same structural columns used for form_id
    (everything in FORM_ID_COLUMNS from analyze_benchmarks_v2.py — no
    need for collectionSize or duration here, since chooseDriverSimple's
    decision doesn't depend on measured timing) plus one new column:

        simpleChoice   — 'postgres' / 'mongo' / 'neo4j'

    One row per distinct query form (NOT per benchmark measurement) is
    enough, since the decision only depends on query structure. This
    function recomputes the SAME form_id hash used everywhere else in
    the pipeline, so it joins cleanly against the benchmark data as long
    as the column names and values match exactly.
    """
    if not os.path.exists(path):
        warnings.warn(
            f"'{path}' not found — skipping the simple-heuristic comparison. "
            f"See load_simple_choices()'s docstring for the expected format."
        )
        return None

    raw = pd.read_csv(path, sep=";")

    # Accept either column name — 'simpleChoice' was my originally-suggested
    # name, 'simpleDriverChoice' is what actually got exported. No need to
    # rename the export to match docs; the docs should match reality.
    choice_col = "simpleDriverChoice" if "simpleDriverChoice" in raw.columns else "simpleChoice"
    if choice_col not in raw.columns:
        raise ValueError(
            f"'{path}' has neither 'simpleDriverChoice' nor 'simpleChoice' column."
        )

    missing = set(form_id_columns) - set(raw.columns)
    if missing:
        raise ValueError(f"'{path}' is missing expected columns: {missing}")

    # CRITICAL: hash_pandas_object hashes differ across dtypes even for
    # identical logical values (e.g. int64 vs int32, float64 vs float32).
    # load_data() in the main pipeline casts count columns to int32 and
    # fraction columns to float32 before computing form_id — a plain
    # pd.read_csv here defaults to int64/float64, which silently produces
    # a DIFFERENT hash for the same query shape and makes every join fail
    # with zero matches (no error, just an empty-looking result — this bit
    # me during testing). Match dtypes exactly before hashing.
    float_cols = {"onlyResultFraction", "dynamicFilterFraction", "dynamicResultFraction"}
    for c in form_id_columns:
        if c in float_cols:
            raw[c] = raw[c].astype("float32")
        else:
            raw[c] = raw[c].astype("int32")

    raw["form_id"] = compute_form_id(raw)
    return raw[["form_id", choice_col]].rename(columns={choice_col: "simple_fastest"})


# ---------------------------------------------------------------------------
# Scoring
# ---------------------------------------------------------------------------

def score_accuracy(comparison: pd.DataFrame, predicted_col: str, actual_col: str = "actual_fastest") -> dict:
    valid = comparison.dropna(subset=[predicted_col, actual_col])
    correct = (valid[predicted_col] == valid[actual_col])
    overall = {
        "n_comparisons": int(len(valid)),
        "accuracy": float(correct.mean()) if len(valid) else None,
    }
    # accuracy broken down by how many drivers were actually in contention —
    # a 2-way pick is a much easier baseline (50% by chance) than a 3-way
    # one (33% by chance), so lumping them together can be misleading.
    by_n = {}
    for n, group in valid.groupby("n_drivers_compared"):
        by_n[int(n)] = {
            "n_comparisons": int(len(group)),
            "accuracy": float((group[predicted_col] == group[actual_col]).mean()),
        }
    overall["by_n_drivers_compared"] = by_n
    return overall


def main(csv_path: str = CSV_PATH, coeff_path: str = COEFFICIENTS_PATH,
         simple_choice_path: str = SIMPLE_CHOICE_PATH, out_dir: str = OUT_DIR):
    from regression_2 import FORM_ID_COLUMNS

    print("[1/5] Loading benchmark data + recomputing form_id/flags "
          "(same as the main pipeline, needed for ground truth) ...")
    df = load_data(csv_path)
    df = add_interaction_features(df)
    df["form_id"] = compute_form_id(df)
    df["is_warmup"] = flag_warmup(df)
    df["is_outlier"] = flag_outliers(df)
    clean_mask = ~df["is_warmup"] & ~df["is_outlier"]

    print("[2/5] Computing ground-truth fastest driver per (form, size) ...")
    ground_truth = compute_ground_truth(df, clean_mask)
    print(f"      {len(ground_truth):,} (form, collectionSize) combinations")

    print("[3/5] Computing ML-predicted fastest driver ...")
    with open(coeff_path) as f:
        coefficients = json.load(f)
    drivers = sorted({k.split("__")[0] for k in coefficients.keys()})
    ml_preds = compute_ml_predictions(df, coefficients, drivers)

    print("[4/5] Loading simple-heuristic picks (if available) ...")
    simple_choices = load_simple_choices(simple_choice_path, FORM_ID_COLUMNS)

    comparison = ground_truth.merge(ml_preds, on=["form_id", "collectionSize"], how="left")
    if simple_choices is not None:
        comparison = comparison.merge(simple_choices, on="form_id", how="left")

    print("[5/5] Scoring ...")
    results = {"ml_model": score_accuracy(comparison, "ml_predicted_fastest")}
    if "simple_fastest" in comparison.columns:
        results["simple_heuristic"] = score_accuracy(comparison, "simple_fastest")

    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "driver_selection_accuracy_2.json")
    with open(out_path, "w") as f:
        json.dump(results, f, indent=2)

    comparison_path = os.path.join(out_dir, "driver_selection_comparison_rows.csv")
    comparison.to_csv(comparison_path, sep=";", index=False)

    print(f"\nML model accuracy:       {results['ml_model']['accuracy']:.3f} "
          f"({results['ml_model']['n_comparisons']} comparisons)")
    if "simple_heuristic" in results:
        sh = results["simple_heuristic"]
        print(f"Simple heuristic accuracy: {sh['accuracy']:.3f} ({sh['n_comparisons']} comparisons)")
    else:
        print(f"(simple heuristic not scored — provide '{simple_choice_path}' to include it)")

    print(f"\nDetailed results -> {out_path}\nRow-level comparison -> {comparison_path}")


if __name__ == "__main__":
    import sys
    main(csv_path=sys.argv[1] if len(sys.argv) > 1 else CSV_PATH)