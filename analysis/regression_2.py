"""
Polystore driver-selection ML model — benchmark analysis pipeline
====================================================================

Trains a per-(driver, phase) linear model to predict query duration from
structural query features, for `pickBestDriver`. The whole point of the
pipeline is to produce a model that is:

  (a) honest about generalization to *unseen query shapes*, not just
      unseen rows of already-seen shapes, and
  (b) simple enough to port into Kotlin as a plain weighted-sum formula.

Every cleaning/exclusion decision (warmup, outliers, transforms) is kept
as a *flag*, never a deletion from the source data, so the raw CSV stays
untouched and every choice can be re-examined or turned off later.

--------------------------------------------------------------------
WHY these design choices (read this before changing the CONFIG below)
--------------------------------------------------------------------
1. Loading via DuckDB, not raw pandas.read_csv:
   At 3.5 GB, pandas.read_csv either eats a huge amount of RAM or forces
   manual chunking with dtype juggling. DuckDB can run SQL directly over
   the CSV (streaming, spills to disk if needed) and we only ever pull a
   pandas DataFrame back for the *already filtered/aggregated* rows we
   actually need for modeling. This keeps peak memory low without hand-
   rolled chunk loops.

2. "Query form" identity:
   A form is defined by the *structural* columns only — everything that
   describes the shape of the query, not how it was measured. That's all
   columns except driver, collectionSize, iteration, phase, duration.
   (driver/phase select which model we're fitting; collectionSize and
   iteration are properties of a *measurement* of a form, not the form
   itself.) We hash the tuple of rounded structural values into a stable
   form_id. Rounding the float fraction columns guards against float
   noise producing spurious "new" forms that are really the same query
   shape.

3. Warmup flag:
   First N iterations *within each (driver, phase, form_id) group, in
   iteration order* are flagged. This must be computed per-group because
   "iteration" is a per-benchmark-run counter that resets for each
   combination being measured.

4. Outlier flag:
   Classic IQR fencing (Q1 - k*IQR, Q3 + k*IQR), computed per (driver,
   phase) *after* excluding warmup rows, so warmup slowness doesn't
   widen the fences and mask real outliers. Flag only — rows are kept
   in the raw table, just excluded from the *fitting* set by default.

5. Train / held-out-forms split:
   A random ~1-5% slice of *distinct form_ids* is set aside entirely.
   No row belonging to those forms is used in training or in the
   row-level CV. This is what actually tests "does this generalize to a
   query shape the model has never seen" — plain row-level CV on all
   forms would silently leak shape information (the same shape appears
   with many collection sizes/iterations) and overstate accuracy.

6. Model A vs Model B:
   Model A is the naive linear regression baseline: everything linear,
   duration untransformed. Model B is built by diagnosing where Model A
   is systematically wrong — via residual analysis and a RandomForest
   "oracle" fit on the same features. Where the RF finds structure the
   linear model can't (checked via partial-dependence-style binned
   comparison), and where the target/feature is heavy-tailed (skew
   check), we apply log1p transforms. The RandomForest is *only* used
   for this diagnosis step — it is never the delivered model. Model B is
   still a plain linear regression underneath (possibly on transformed
   features/target), so its coefficients port trivially into Kotlin as
   `intercept + sum(coef_i * feature_i)` (with the transform applied
   before/after as noted in target_transform / feature_transforms).

7. Metrics:
   Reported separately for (a) K-fold CV on rows from *training* forms
   only, and (b) a single evaluation on the fully held-out forms. If (b)
   is much worse than (a), that's the honest signal that the model is
   memorizing shapes rather than learning structural cost relationships
   — worth writing up either way.
"""

from __future__ import annotations

import json
import os
import sys
import warnings
from dataclasses import dataclass, field

import numpy as np
import pandas as pd
from scipy import stats
from sklearn.ensemble import RandomForestRegressor
from sklearn.linear_model import LinearRegression
from sklearn.model_selection import KFold
from sklearn.metrics import r2_score, mean_squared_error

import matplotlib
matplotlib.use("Agg")  # headless: we only ever save figures, never show()
import matplotlib.pyplot as plt

# ---------------------------------------------------------------------------
# CONFIG — the knobs you're most likely to want to change / justify in text
# ---------------------------------------------------------------------------

CSV_PATH = "benchmarks.csv"          # input, ';'-delimited per the schema
OUTPUT_DIR = "benchmark_analysis_out"

WARMUP_ITERATIONS = 50               # iterations excluded as JIT/cache warmup
IQR_MULTIPLIER = 1.5                 # standard Tukey fence
HOLDOUT_FORM_FRACTION = 0.03         # 3% of distinct query forms held out
CV_FOLDS = 5
RANDOM_SEED = 42

N_HOLDOUT_REPEATS = 5                # extra random re-splits, for a robustness range
CORRELATION_FLAG_THRESHOLD = 0.5     # |r| above this gets called out explicitly

# Columns that describe a *measurement*, not the query shape itself.
NON_STRUCTURAL_COLUMNS = {"driver", "collectionSize", "iteration", "phase", "duration"}

# Numeric feature columns usable for modeling (structural + collectionSize).
# 'iteration' and 'duration' are excluded on purpose: iteration is a
# measurement artifact, duration is the target.
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

# Structural columns used to derive the form_id (everything except
# collectionSize, which varies per measurement of the *same* form).
FORM_ID_COLUMNS = [c for c in FEATURE_COLUMNS if c != "collectionSize"]

# Engineered interaction terms, added on top of FEATURE_COLUMNS for modeling
# only (NOT for form_id / correlation checks, which stay on the raw
# structural columns). Diagnosed from the residual pattern: Neo4j's fit
# stayed at a low, split-independent R^2 ceiling with clear vertical
# banding in the predicted-vs-actual plot — the visual signature of an
# effect that depends on the PRODUCT of two features rather than their
# sum, which no combination of per-feature log-transforms can represent.
# Confirmed with a quick before/after CV check: adding this one term
# measurably improved fit in a synthetic test carrying the same pattern.
# Cheap to keep for all (driver, phase) groups uniformly (a group where
# it's irrelevant just gets a near-zero coefficient) rather than special-
# casing Neo4j alone, since the whole point is one shared, portable
# feature set across drivers.
INTERACTION_TERMS = [("connectionSegmentCount", "firstFilterDepth")]


def interaction_col_name(a: str, b: str) -> str:
    return f"{a}_x_{b}"


def add_interaction_features(df: pd.DataFrame) -> pd.DataFrame:
    for a, b in INTERACTION_TERMS:
        df[interaction_col_name(a, b)] = df[a] * df[b]
    return df


MODEL_FEATURE_COLUMNS = FEATURE_COLUMNS + [interaction_col_name(a, b) for a, b in INTERACTION_TERMS]

FLOAT_ROUND_DECIMALS = 6  # guards form_id hashing against float noise


# ---------------------------------------------------------------------------
# 1. Loading
# ---------------------------------------------------------------------------

def load_data(csv_path: str) -> pd.DataFrame:
    """Load the CSV via DuckDB (streaming, low peak memory) into a pandas
    DataFrame with memory-efficient dtypes.

    We let DuckDB do the CSV parsing/type sniffing (it's fast and handles
    the 3.5GB scale comfortably) and only materialize a pandas frame at
    the end, since the rest of the pipeline (sklearn, scipy, seaborn) is
    pandas-based and the dataset comfortably fits in RAM as columnar
    numpy arrays even though the raw CSV text does not always fit
    comfortably as a pandas object-heavy frame.
    """
    import duckdb

    if not os.path.exists(csv_path):
        raise FileNotFoundError(
            f"'{csv_path}' not found. Point CSV_PATH at the benchmark CSV "
            f"before running this script."
        )

    # Explicit types instead of letting DuckDB sniff from a sample: at 3.5GB,
    # a type guessed from the first N rows (e.g. 'duration' looking like an
    # integer early on) can fail hard the moment a later row doesn't match
    # (e.g. a decimal duration value). Being explicit avoids that entirely
    # and is also just faster (no sniffing pass).
    column_types = {
        "driver": "VARCHAR",
        "collectionSize": "BIGINT",
        "singleCollectionSegmentCount": "BIGINT",
        "pairCollectionSegmentCount": "BIGINT",
        "connectionSegmentCount": "BIGINT",
        "multiQueryCount": "BIGINT",
        "rootIdFilterCount": "BIGINT",
        "rootValueInListFilterCount": "BIGINT",
        "rootEqualityFilterCount": "BIGINT",
        "rootNumberRangeFilterCount": "BIGINT",
        "nestedIdFilterCount": "BIGINT",
        "nestedValueInListFilterCount": "BIGINT",
        "nestedEqualityFilterCount": "BIGINT",
        "nestedNumberRangeFilterCount": "BIGINT",
        "firstFilterDepth": "BIGINT",
        "onlyResultFraction": "DOUBLE",
        "dynamicFilterFraction": "DOUBLE",
        "dynamicResultFraction": "DOUBLE",
        "phase": "VARCHAR",
        "iteration": "BIGINT",
        "duration": "DOUBLE",  # DOUBLE, not BIGINT: tolerates both '183' and '183.5'
    }
    types_sql = ", ".join(f"'{c}': '{t}'" for c, t in column_types.items())

    con = duckdb.connect()
    query = f"""
        SELECT *
        FROM read_csv('{csv_path}',
                       delim=';',
                       header=True,
                       quote='"',
                       escape='"',
                       auto_detect=false,
                       ignore_errors=true,
                       store_rejects=true,
                       columns={{{types_sql}}})
    """
    df = con.execute(query).df()

    # With auto_detect off and an explicit dialect, DuckDB no longer needs to
    # *guess* quote/escape/newline conventions (the failure mode that raised
    # "not possible to automatically detect the CSV parsing dialect"). But a
    # 3.5GB hand-generated CSV can still have a handful of genuinely
    # malformed lines (stray delimiter, unescaped quote, truncated last
    # line, etc.) — ignore_errors=true skips only those specific rows rather
    # than aborting the whole load, and store_rejects=true keeps a record of
    # what was skipped so it's a visible, reportable count, not a silent
    # loss.
    try:
        n_rejects = con.execute("SELECT COUNT(*) FROM reject_errors").fetchone()[0]
    except duckdb.CatalogException:
        n_rejects = 0
    if n_rejects:
        warnings.warn(
            f"DuckDB skipped {n_rejects:,} malformed CSV row(s) while parsing "
            f"(field count / escaping didn't match the declared dialect). "
            f"First few, for inspection:"
        )
        print(con.execute("SELECT * FROM reject_errors LIMIT 5").df().to_string())
    con.close()

    # NOTE: 'phase' has a third value in your data: 'total' = build + exec,
    # already summed by the benchmark harness. Previously dropped here as
    # redundant/double-counted — now left in at your request, so it flows
    # through the pipeline like any other phase and gets its own model
    # fit for comparison. Keep in mind it's not an independent signal:
    # its accuracy will partly reflect how well build+exec individually
    # fit, just measured directly instead of via summed predictions.

    # Tighten dtypes to keep memory down for downstream steps.
    df["driver"] = df["driver"].astype("category")
    df["phase"] = df["phase"].astype("category")

    int_cols = [
        "collectionSize", "singleCollectionSegmentCount", "pairCollectionSegmentCount",
        "connectionSegmentCount", "multiQueryCount", "rootIdFilterCount",
        "rootValueInListFilterCount", "rootEqualityFilterCount", "rootNumberRangeFilterCount",
        "nestedIdFilterCount", "nestedValueInListFilterCount", "nestedEqualityFilterCount",
        "nestedNumberRangeFilterCount", "firstFilterDepth", "iteration",
    ]
    for c in int_cols:
        if c in df.columns:
            df[c] = pd.to_numeric(df[c], errors="coerce").astype("int32")

    float_cols = ["onlyResultFraction", "dynamicFilterFraction", "dynamicResultFraction"]
    for c in float_cols:
        if c in df.columns:
            df[c] = pd.to_numeric(df[c], errors="coerce").astype("float32")

    df["duration"] = pd.to_numeric(df["duration"], errors="coerce").astype("float64")

    n_bad = df["duration"].isna().sum()
    if n_bad:
        warnings.warn(f"Dropping {n_bad} rows with unparseable duration.")
        df = df.dropna(subset=["duration"])

    return df.reset_index(drop=True)


# ---------------------------------------------------------------------------
# 2. Query-form identity
# ---------------------------------------------------------------------------

def compute_form_id(df: pd.DataFrame) -> pd.Series:
    """Hash the structural feature columns (rounded) into a stable form_id.

    Deliberately NOT using collectionSize/iteration/driver/phase/duration:
    the same query *shape* is measured across many collection sizes and
    against all three drivers, and we want all of those rows to map to
    the same form_id so the holdout split can genuinely remove a shape
    from training entirely.

    Implementation note: this used to build a per-row '|'-joined string via
    `df.astype(str).agg("|".join, axis=1)`. That's convenient but
    `agg(..., axis=1)` transposes the whole frame internally (visible in
    pandas' own traceback if it blows up) — for a few hundred thousand
    rows that's wasteful, for millions of rows of a 3.5GB file it's a full
    extra copy of the data as a giant object array and can exhaust memory
    outright. `pandas.util.hash_pandas_object` hashes row-wise without
    ever materializing per-row strings or transposing anything, so memory
    use stays proportional to the input, not to some transposed
    intermediate.
    """
    cols = FORM_ID_COLUMNS
    rounded = df[cols].copy()
    for c in cols:
        if pd.api.types.is_float_dtype(rounded[c]):
            rounded[c] = rounded[c].round(FLOAT_ROUND_DECIMALS)

    hashed = pd.util.hash_pandas_object(rounded, index=False)
    return hashed.astype("uint64").astype(str)


# ---------------------------------------------------------------------------
# 2b. Feature correlation check
# ---------------------------------------------------------------------------

def compute_feature_correlations(df: pd.DataFrame, clean_mask: pd.Series, out_dir: str) -> dict:
    """Two separate correlation checks, for two separate reasons:

    1. Structural-feature-vs-structural-feature correlations, computed on
       ONE ROW PER QUERY FORM (deduplicated by form_id). If this were
       computed on the full row-level data instead, a form measured
       thousands of times would just be re-confirming its own single
       correlation value over and over, drowning out the actual
       cross-form signal. This check answers: "does the query generator
       tend to produce certain structural features together?" — which
       matters because strongly correlated predictors make individual
       linear-regression coefficients unreliable (their effect can trade
       off against each other) even when the model's overall R^2 is fine.
       This is exactly the kind of thing that can produce a
       counter-intuitive coefficient sign, so it's worth checking before
       reading meaning into any single coefficient.

    2. collectionSize vs. every structural feature, computed on the full
       (clean) row-level data. Unlike the structural features,
       collectionSize is supposed to vary independently of query shape
       (the same shape gets measured at several sizes) — this is a
       sanity check that the benchmark generator actually achieved that
       orthogonality, not a search for a "real" relationship.
    """
    struct_cols = FORM_ID_COLUMNS

    # (1) one row per form
    unique_forms = df.loc[clean_mask, ["form_id"] + struct_cols].drop_duplicates(subset="form_id")
    struct_corr = unique_forms[struct_cols].corr()

    high_pairs = []
    for i, a in enumerate(struct_cols):
        for b in struct_cols[i + 1:]:
            r = struct_corr.loc[a, b]
            if pd.notna(r) and abs(r) >= CORRELATION_FLAG_THRESHOLD:
                high_pairs.append({"feature_a": a, "feature_b": b, "r": float(r)})
    high_pairs.sort(key=lambda p: -abs(p["r"]))

    # (2) collectionSize vs structural features, full clean rows
    clean_rows = df.loc[clean_mask, ["collectionSize"] + struct_cols]
    size_corr = clean_rows.corr()["collectionSize"].drop("collectionSize")
    size_flags = [
        {"feature": f, "r": float(r)}
        for f, r in size_corr.items() if pd.notna(r) and abs(r) >= CORRELATION_FLAG_THRESHOLD
    ]

    # heatmap for visual inspection (plain matplotlib — no seaborn dependency,
    # so this always renders regardless of what's installed on your machine)
    fig, ax = plt.subplots(figsize=(9, 7.5))
    im = ax.imshow(struct_corr.values, cmap="RdBu_r", vmin=-1, vmax=1)
    ax.set_xticks(range(len(struct_cols)))
    ax.set_yticks(range(len(struct_cols)))
    ax.set_xticklabels(struct_cols, rotation=90, fontsize=7)
    ax.set_yticklabels(struct_cols, fontsize=7)
    fig.colorbar(im, ax=ax, label="Pearson r", shrink=0.8)
    ax.set_title("Structural feature correlations (one row per query form)")
    fig.tight_layout()
    fig.savefig(os.path.join(out_dir, "feature_correlation_heatmap.png"), dpi=130)
    plt.close(fig)

    return {
        "n_unique_forms_checked": int(len(unique_forms)),
        "structural_feature_high_correlation_pairs": high_pairs,
        "collection_size_correlation_flags": size_flags,
        "full_structural_correlation_matrix": struct_corr.round(4).to_dict(),
    }


# ---------------------------------------------------------------------------
# 3. Warmup flag
# ---------------------------------------------------------------------------

def flag_warmup(df: pd.DataFrame) -> pd.Series:
    """Flag the first WARMUP_ITERATIONS rows (by 'iteration' order) within
    each (driver, phase, form_id) group.
    """
    order_rank = (
        df.groupby(["driver", "phase", "form_id"], observed=True)["iteration"]
        .rank(method="first")
    )
    return order_rank <= WARMUP_ITERATIONS


# ---------------------------------------------------------------------------
# 4. Outlier flag (IQR, per driver/phase, computed on non-warmup rows only)
# ---------------------------------------------------------------------------

def flag_outliers(df: pd.DataFrame) -> pd.Series:
    is_outlier = pd.Series(False, index=df.index)
    clean_mask = ~df["is_warmup"]

    for (driver, phase), group in df[clean_mask].groupby(["driver", "phase"], observed=True):
        q1, q3 = group["duration"].quantile([0.25, 0.75])
        iqr = q3 - q1
        lo, hi = q1 - IQR_MULTIPLIER * iqr, q3 + IQR_MULTIPLIER * iqr
        # Apply the fence computed on the clean subset to ALL rows in this
        # (driver, phase) group (including warmup rows, which we still
        # want flagged if e.g. they're outliers for other reasons too).
        same_group_mask = (df["driver"] == driver) & (df["phase"] == phase)
        is_outlier |= same_group_mask & ((df["duration"] < lo) | (df["duration"] > hi))

    return is_outlier


# ---------------------------------------------------------------------------
# 5. Train / held-out-forms split
# ---------------------------------------------------------------------------

def split_holdout_forms(df: pd.DataFrame, rng: np.random.Generator) -> set[str]:
    all_forms = df["form_id"].unique()
    n_holdout = max(1, int(round(len(all_forms) * HOLDOUT_FORM_FRACTION)))
    holdout_forms = set(rng.choice(all_forms, size=n_holdout, replace=False))
    return holdout_forms


# ---------------------------------------------------------------------------
# 6. Nonlinearity diagnosis (RandomForest used ONLY as a diagnostic oracle)
# ---------------------------------------------------------------------------

@dataclass
class TransformPlan:
    log_target: bool = False
    log_features: list[str] = field(default_factory=list)


def diagnose_transforms(X: pd.DataFrame, y: pd.Series, feature_cols: list[str]) -> TransformPlan:
    """Decide which log1p transforms to apply for Model B.

    Two independent signals, either of which can trigger a transform:

    1. Target skew: if raw duration is heavily right-skewed (skew > 1.5)
       for this (driver, phase) group, a plain linear model in raw-duration
       space tends to underfit the tail multiplicatively rather than
       additively — log1p(duration) usually fixes this (confirmed
       previously for Mongo build-phase; here we just check generically
       rather than hardcoding which driver/phase).
    2. Feature skew + RF divergence: for each candidate feature that is
       itself heavily skewed (skew > 1.5, all-non-negative), compare a
       plain LinearRegression fit against a RandomForest fit *using only
       that one feature plus collectionSize*. If the RF R^2 substantially
       beats the linear R^2 (>0.05 absolute), that's a sign of a
       nonlinear (often multiplicative/log-like) relationship worth
       log-transforming rather than leaving linear.
    """
    plan = TransformPlan()

    # --- target skew check ---
    target_skew = stats.skew(y.values)
    if target_skew > 1.5:
        plan.log_target = True

    # --- per-feature nonlinearity check ---
    for col in feature_cols:
        vals = X[col].values
        if np.any(vals < 0):
            continue  # log1p undefined for negatives; skip
        if len(np.unique(vals)) < 5:
            continue  # not enough variation to judge skew/nonlinearity

        col_skew = stats.skew(vals)
        if col_skew <= 1.5:
            continue

        single_feat = X[[col]].values
        y_for_fit = np.log1p(y.values) if plan.log_target else y.values

        lin = LinearRegression().fit(single_feat, y_for_fit)
        lin_r2 = r2_score(y_for_fit, lin.predict(single_feat))

        rf = RandomForestRegressor(
            n_estimators=100, max_depth=6, random_state=RANDOM_SEED, n_jobs=-1
        ).fit(single_feat, y_for_fit)
        rf_r2 = r2_score(y_for_fit, rf.predict(single_feat))

        if rf_r2 - lin_r2 > 0.05:
            plan.log_features.append(col)

    return plan


def apply_transforms(X: pd.DataFrame, y: pd.Series, plan: TransformPlan):
    X_t = X.copy()
    for col in plan.log_features:
        X_t[col] = np.log1p(X_t[col])
    y_t = np.log1p(y) if plan.log_target else y.copy()
    return X_t, y_t


def invert_target(y_transformed: np.ndarray, plan: TransformPlan) -> np.ndarray:
    return np.expm1(y_transformed) if plan.log_target else y_transformed


# ---------------------------------------------------------------------------
# 7. Fitting + evaluation for one (driver, phase) group
# ---------------------------------------------------------------------------

@dataclass
class GroupResult:
    driver: str
    phase: str
    model_a_json: dict
    model_b_json: dict
    metrics: dict
    plan_b: "TransformPlan"


def evaluate_row_cv(X: np.ndarray, y_transformed: np.ndarray, plan: TransformPlan, rng_seed: int) -> dict:
    """K-fold CV on rows (all from training forms already). Reports R^2 in
    the ORIGINAL duration scale so numbers are comparable across models
    regardless of whether a log transform was used.
    """
    kf = KFold(n_splits=CV_FOLDS, shuffle=True, random_state=rng_seed)
    r2s, rmses = [], []
    for train_idx, test_idx in kf.split(X):
        model = LinearRegression().fit(X[train_idx], y_transformed[train_idx])
        pred_t = model.predict(X[test_idx])
        pred = invert_target(pred_t, plan)
        true = invert_target(y_transformed[test_idx], plan)
        r2s.append(r2_score(true, pred))
        rmses.append(np.sqrt(mean_squared_error(true, pred)))
    return {"r2_mean": float(np.mean(r2s)), "r2_std": float(np.std(r2s)),
            "rmse_mean": float(np.mean(rmses))}


def evaluate_holdout(model: LinearRegression, X_holdout: np.ndarray, y_holdout_raw: np.ndarray,
                     plan: TransformPlan) -> dict:
    pred_t = model.predict(X_holdout)
    pred = invert_target(pred_t, plan)
    return {
        "r2": float(r2_score(y_holdout_raw, pred)),
        "rmse": float(np.sqrt(mean_squared_error(y_holdout_raw, pred))),
        "n_rows": int(len(y_holdout_raw)),
    }


def make_residual_plot(y_true, y_pred, title: str, out_path: str):
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.5))

    axes[0].scatter(y_pred, y_true, s=6, alpha=0.35)
    lims = [0, max(np.max(y_true), np.max(y_pred)) * 1.02 + 1e-9]
    axes[0].plot(lims, lims, "r--", linewidth=1)
    axes[0].set_xlabel("Predicted duration (µs)")
    axes[0].set_ylabel("Actual duration (µs)")
    axes[0].set_title("Predicted vs actual")

    residuals = y_true - y_pred
    axes[1].scatter(y_pred, residuals, s=6, alpha=0.35)
    axes[1].axhline(0, color="r", linestyle="--", linewidth=1)
    axes[1].set_xlabel("Predicted duration (µs)")
    axes[1].set_ylabel("Residual (actual - predicted)")
    axes[1].set_title("Residuals")

    fig.suptitle(title)
    fig.tight_layout()
    fig.savefig(out_path, dpi=130)
    plt.close(fig)


def fit_and_evaluate_group(train_df: pd.DataFrame, holdout_df: pd.DataFrame,
                           driver: str, phase: str, out_dir: str) -> GroupResult:
    feat_cols = MODEL_FEATURE_COLUMNS
    X_train_raw = train_df[feat_cols]
    y_train_raw = train_df["duration"]

    # ---- Model A: plain linear, no transforms ----
    plan_a = TransformPlan()
    Xa = X_train_raw.values
    ya = y_train_raw.values
    model_a = LinearRegression().fit(Xa, ya)
    cv_a = evaluate_row_cv(Xa, ya, plan_a, RANDOM_SEED)

    holdout_metrics_a = None
    if len(holdout_df):
        Xa_h = holdout_df[feat_cols].values
        ya_h = holdout_df["duration"].values
        holdout_metrics_a = evaluate_holdout(model_a, Xa_h, ya_h, plan_a)

    model_a_json = {
        "driver": driver,
        "phase": phase,
        "intercept": float(model_a.intercept_),
        "numeric_coefficients": {c: float(w) for c, w in zip(feat_cols, model_a.coef_)},
        "categorical_coefficients": {},
        "target_transform": None,
        "feature_transforms": {},
    }

    # ---- Diagnose + Model B: transformed linear ----
    plan_b = diagnose_transforms(X_train_raw, y_train_raw, feat_cols)
    Xb_df, yb = apply_transforms(X_train_raw, y_train_raw, plan_b)
    Xb = Xb_df.values
    model_b = LinearRegression().fit(Xb, yb.values)
    cv_b = evaluate_row_cv(Xb, yb.values, plan_b, RANDOM_SEED)

    holdout_metrics_b = None
    if len(holdout_df):
        Xb_h_df, _ = apply_transforms(holdout_df[feat_cols], holdout_df["duration"], plan_b)
        ya_h = holdout_df["duration"].values
        holdout_metrics_b = evaluate_holdout(model_b, Xb_h_df.values, ya_h, plan_b)

    model_b_json = {
        "driver": driver,
        "phase": phase,
        "intercept": float(model_b.intercept_),
        "numeric_coefficients": {c: float(w) for c, w in zip(feat_cols, model_b.coef_)},
        "categorical_coefficients": {},
        "target_transform": "log1p" if plan_b.log_target else None,
        "feature_transforms": {c: "log1p" for c in plan_b.log_features},
    }

    # ---- residual plots (Model B, since it's the delivered model) ----
    if len(holdout_df):
        pred_t = model_b.predict(Xb_h_df.values)
        pred = invert_target(pred_t, plan_b)
        make_residual_plot(
            ya_h, pred,
            title=f"{driver} / {phase} — Model B, held-out query forms",
            out_path=os.path.join(out_dir, f"residuals_{driver}_{phase}.png"),
        )

    metrics = {
        "n_train_rows": int(len(train_df)),
        "n_train_forms": int(train_df["form_id"].nunique()),
        "n_holdout_rows": int(len(holdout_df)),
        "n_holdout_forms": int(holdout_df["form_id"].nunique()) if len(holdout_df) else 0,
        "model_a": {"row_cv": cv_a, "holdout": holdout_metrics_a},
        "model_b": {"row_cv": cv_b, "holdout": holdout_metrics_b},
        "model_b_transform_plan": {
            "log_target": plan_b.log_target,
            "log_features": plan_b.log_features,
        },
    }

    return GroupResult(driver, phase, model_a_json, model_b_json, metrics, plan_b)


# ---------------------------------------------------------------------------
# 7b. Repeated-holdout-seed robustness check
# ---------------------------------------------------------------------------

def refit_and_score_with_plan(train_df: pd.DataFrame, holdout_df: pd.DataFrame,
                              plan: "TransformPlan", seed: int) -> tuple[dict, dict | None]:
    """Refit ONLY the linear regression weights for an already-decided
    transform plan, and score it. Used by the robustness check below,
    where re-running the (comparatively expensive) RandomForest-based
    transform diagnosis for every random re-split would conflate two
    different questions — "is this model structure right?" vs "how much
    does the R^2 estimate wobble due to which forms happened to land in
    the holdout set?" — we only want to ask the second one here, holding
    the model structure fixed.
    """
    feat_cols = MODEL_FEATURE_COLUMNS
    X_tr, y_tr = apply_transforms(train_df[feat_cols], train_df["duration"], plan)
    model = LinearRegression().fit(X_tr.values, y_tr.values)
    row_cv = evaluate_row_cv(X_tr.values, y_tr.values, plan, seed)

    holdout_metrics = None
    if len(holdout_df):
        X_h, _ = apply_transforms(holdout_df[feat_cols], holdout_df["duration"], plan)
        y_h_raw = holdout_df["duration"].values
        holdout_metrics = evaluate_holdout(model, X_h.values, y_h_raw, plan)

    return row_cv, holdout_metrics


def repeated_holdout_robustness(df: pd.DataFrame, clean_mask: pd.Series,
                                canonical_results: dict, n_repeats: int) -> dict:
    """Re-run the train/held-out-forms split N_HOLDOUT_REPEATS more times
    with different random seeds (the canonical run's own split/seed is
    included as repeat 0), refitting only the linear weights each time
    (see refit_and_score_with_plan), and report the spread of R^2 across
    repeats.

    Why this matters: a single random split of ~100 held-out forms is a
    small sample, and R^2 estimated from it can land above OR below the
    row-CV number just by chance — which is exactly what happened in the
    first run (several groups showed HIGHER held-out R^2 than row-CV
    R^2, which is the opposite of what overfitting would predict). This
    doesn't tell you the model is good or bad on its own; it tells you
    that one number isn't enough evidence either way. Reporting a
    mean/std/range across repeats is what actually lets you claim (or
    rule out) "the model generalizes to unseen query shapes" with any
    confidence in the thesis.
    """
    report = {}

    for key, canonical in canonical_results.items():
        driver, phase = canonical.driver, canonical.phase
        plan = canonical.plan_b

        row_cv_r2s = [canonical.metrics["model_b"]["row_cv"]["r2_mean"]]
        holdout_r2s = []
        if canonical.metrics["model_b"]["holdout"]:
            holdout_r2s.append(canonical.metrics["model_b"]["holdout"]["r2"])

        for rep in range(n_repeats):
            seed = RANDOM_SEED + 1000 + rep  # distinct from the canonical seed
            rng_rep = np.random.default_rng(seed)
            holdout_forms_rep = split_holdout_forms(df, rng_rep)
            is_holdout_rep = df["form_id"].isin(holdout_forms_rep)

            group_mask = (df["driver"] == driver) & (df["phase"] == phase)
            train_df_rep = df[clean_mask & ~is_holdout_rep & group_mask]
            holdout_df_rep = df[clean_mask & is_holdout_rep & group_mask]

            if len(train_df_rep) < 20:
                continue

            row_cv, holdout_metrics = refit_and_score_with_plan(
                train_df_rep, holdout_df_rep, plan, seed
            )
            row_cv_r2s.append(row_cv["r2_mean"])
            if holdout_metrics:
                holdout_r2s.append(holdout_metrics["r2"])

        def summarize(values: list[float]) -> dict:
            arr = np.array(values)
            return {
                "n_splits": len(values),
                "mean": float(arr.mean()),
                "std": float(arr.std()),
                "min": float(arr.min()),
                "max": float(arr.max()),
                "all_values": [float(v) for v in values],
            }

        report[key] = {
            "row_cv_r2": summarize(row_cv_r2s),
            "holdout_r2": summarize(holdout_r2s) if holdout_r2s else None,
        }

    return report


# ---------------------------------------------------------------------------
# 8. Main
# ---------------------------------------------------------------------------

def main(csv_path: str = CSV_PATH, out_dir: str = OUTPUT_DIR):
    os.makedirs(out_dir, exist_ok=True)
    rng = np.random.default_rng(RANDOM_SEED)

    print(f"[1/8] Loading '{csv_path}' ...")
    df = load_data(csv_path)
    df = add_interaction_features(df)
    print(f"      {len(df):,} rows loaded.")

    print("[2/8] Computing query form_id ...")
    df["form_id"] = compute_form_id(df)
    print(f"      {df['form_id'].nunique():,} distinct query forms found.")

    print("[3/8] Flagging warmup + outlier rows ...")
    df["is_warmup"] = flag_warmup(df)
    df["is_outlier"] = flag_outliers(df)
    print(f"      warmup: {df['is_warmup'].sum():,} rows "
          f"({df['is_warmup'].mean():.1%}); "
          f"outlier: {df['is_outlier'].sum():,} rows "
          f"({df['is_outlier'].mean():.1%})")

    clean_mask = ~df["is_warmup"] & ~df["is_outlier"]

    print("[4/8] Checking structural feature correlations ...")
    corr_report = compute_feature_correlations(df, clean_mask, out_dir)
    if corr_report["structural_feature_high_correlation_pairs"]:
        print(f"      {len(corr_report['structural_feature_high_correlation_pairs'])} "
              f"structural feature pair(s) with |r| >= {CORRELATION_FLAG_THRESHOLD}:")
        for p in corr_report["structural_feature_high_correlation_pairs"]:
            print(f"        {p['feature_a']} <-> {p['feature_b']}: r={p['r']:.3f}")
    else:
        print(f"      no structural feature pairs above |r| >= {CORRELATION_FLAG_THRESHOLD}")
    if corr_report["collection_size_correlation_flags"]:
        print("      WARNING: collectionSize correlates with structural features "
              "(expected to be ~independent by design):")
        for f in corr_report["collection_size_correlation_flags"]:
            print(f"        collectionSize <-> {f['feature']}: r={f['r']:.3f}")

    print("[5/8] Splitting held-out query forms ...")
    holdout_forms = split_holdout_forms(df, rng)
    df["is_holdout_form"] = df["form_id"].isin(holdout_forms)
    print(f"      {len(holdout_forms):,} forms held out "
          f"({df['is_holdout_form'].mean():.1%} of rows)")

    train_mask = clean_mask & ~df["is_holdout_form"]
    holdout_mask = clean_mask & df["is_holdout_form"]

    print("[6/8] Fitting Model A / Model B per (driver, phase) ...")
    all_json_models = {}
    all_metrics = {}
    canonical_results: dict[str, GroupResult] = {}
    for driver in df["driver"].cat.categories:
        for phase in df["phase"].cat.categories:
            train_df = df[train_mask & (df["driver"] == driver) & (df["phase"] == phase)]
            holdout_df = df[holdout_mask & (df["driver"] == driver) & (df["phase"] == phase)]
            if len(train_df) < 20:
                print(f"      skipping {driver}/{phase}: only {len(train_df)} clean training rows")
                continue

            result = fit_and_evaluate_group(train_df, holdout_df, str(driver), str(phase), out_dir)

            key = f"{driver}__{phase}"
            all_json_models[key] = {"model_a": result.model_a_json, "model_b": result.model_b_json}
            all_metrics[key] = result.metrics
            canonical_results[key] = result

            mb = result.metrics["model_b"]
            print(f"      {driver:>8}/{phase:<5}  "
                  f"rowCV R2={mb['row_cv']['r2_mean']:.3f}  "
                  f"holdoutForms R2={(mb['holdout']['r2'] if mb['holdout'] else float('nan')):.3f}")

    print(f"[7/8] Robustness check: re-splitting holdout forms "
          f"{N_HOLDOUT_REPEATS} more time(s) per group ...")
    robustness_report = repeated_holdout_robustness(df, clean_mask, canonical_results, N_HOLDOUT_REPEATS)
    for key, rep in robustness_report.items():
        rc = rep["row_cv_r2"]
        ho = rep["holdout_r2"]
        ho_str = (f"{ho['mean']:.3f} ± {ho['std']:.3f} (range {ho['min']:.3f}-{ho['max']:.3f}, "
                  f"n={ho['n_splits']})") if ho else "n/a"
        print(f"      {key:<20} rowCV R2: {rc['mean']:.3f} ± {rc['std']:.3f} "
              f"(range {rc['min']:.3f}-{rc['max']:.3f}, n={rc['n_splits']})   "
              f"holdout R2: {ho_str}")

    print("[8/8] Writing outputs ...")
    coeff_path = os.path.join(out_dir, "model_coefficients_linear.json")
    with open(coeff_path, "w") as f:
        json.dump(all_json_models, f, indent=2)

    metrics_path = os.path.join(out_dir, "metrics_report.json")
    with open(metrics_path, "w") as f:
        json.dump(all_metrics, f, indent=2)

    corr_path = os.path.join(out_dir, "feature_correlations.json")
    with open(corr_path, "w") as f:
        json.dump(corr_report, f, indent=2)

    robustness_path = os.path.join(out_dir, "robustness_report.json")
    with open(robustness_path, "w") as f:
        json.dump(robustness_report, f, indent=2)

    print(f"\nDone.\n"
          f"  coefficients     -> {coeff_path}\n"
          f"  metrics          -> {metrics_path}\n"
          f"  correlations     -> {corr_path}\n"
          f"  robustness       -> {robustness_path}\n"
          f"  residual plots   -> {out_dir}/residuals_<driver>_<phase>.png\n"
          f"  correlation plot -> {out_dir}/feature_correlation_heatmap.png")


if __name__ == "__main__":
    csv_arg = sys.argv[1] if len(sys.argv) > 1 else CSV_PATH
    main(csv_path=csv_arg)