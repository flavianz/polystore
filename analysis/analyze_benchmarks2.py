"""
Analysis + runtime-estimator fitting script for Polystore driver benchmark data.

Expected CSV columns (';'-delimited):
    run_id;query_shape;driver;collection_size;depth;filter_count;filter_type;
    bench_result_type;dynamic_data;phase;iteration;duration

Usage:
    python analyze_benchmarks.py path/to/measurements.csv

Outputs:
    - summary_stats.csv, driver_comparison_<phase>.csv, scaling_correlation.csv
    - model_metrics.csv                  -> R^2 / CV scores per driver x phase x model type
    - model_coefficients_linear.json     -> fitted linear model coefficients, ready for Kotlin
    - residual_<driver>_<phase>.png      -> residual plots per model
    - ./plots/ boxplots and scaling plots
"""

import sys
import json
from pathlib import Path

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from scipy import stats
from sklearn.linear_model import LinearRegression
from sklearn.ensemble import RandomForestRegressor
from sklearn.model_selection import KFold, GroupKFold, cross_val_score
from sklearn.metrics import r2_score
from sklearn.preprocessing import OneHotEncoder
from sklearn.compose import ColumnTransformer
from sklearn.pipeline import Pipeline

# ---------------------------------------------------------------------------
# 0. Config
# ---------------------------------------------------------------------------

WARMUP_ITERATIONS = 50
OUTLIER_METHOD = "iqr"
IQR_MULTIPLIER = 1.5
ZSCORE_THRESHOLD = 3.0

PLOTS_DIR = Path("plots")
PLOTS_DIR.mkdir(exist_ok=True)

sns.set_theme(style="whitegrid")

NUMERIC_FEATURES = [
    "collection_size", "depth", "filter_count",
    "multi_query_depth", "multi_query_unpushed_filter",
    "first_filtered_segment_index", "unfiltered_fanout_log",
]
CATEGORICAL_FEATURES = ["filter_type", "bench_result_type", "dynamic_data", "requires_multi_query"]
ALL_FEATURES = NUMERIC_FEATURES + CATEGORICAL_FEATURES

# Phases fit on log1p(duration) instead of raw duration. Build-phase durations span two+
# orders of magnitude (typical few-hundred us vs up to 70,000us for unfiltered deep fan-out
# shapes) - fitting on the raw scale lets a handful of extreme rows dominate the squared-error
# loss and prevents a linear model from representing what's mechanically a MULTIPLICATIVE
# effect (each unfiltered hop multiplies the working set by roughly the branching factor, not
# a fixed additive cost). log1p compresses this and turns a multiplicative relationship into
# something closer to linear in log-space, which pairs with the unfiltered_fanout_log feature
# below. Predictions must be inverted with expm1() - both in this script's own scoring/plots
# and in the ported Kotlin estimator.
LOG_TARGET_PHASES = ("build",)

# query_shape prefixes that indicate Mongo has to run multiple queries and stitch results
# together client-side (deep hierarchies), rather than resolving everything server-side in
# one round trip. Identified from residual analysis (residual_mongo_total.png showed a
# cluster of severely underpredicted durations not explained by depth/filter_count alone).
MULTI_QUERY_SHAPE_PREFIXES = ("deep", "very deep")

# filter_type values that can normally be pushed to the server (and are cheap), but were
# confirmed (via build-phase duration split by requires_multi_query) to fall back to
# expensive client-side filtering specifically when multi-query stitching is happening.
# requires_multi_query=False: mean 154us, p99 876us. requires_multi_query=True: mean
# 3717us, p99 42370us - a ~24x gap concentrated almost entirely in these two filter types.
UNPUSHABLE_WHEN_MULTI_QUERY_FILTER_TYPES = ("equality", "numRange")

# Index (0-based) of the first path segment that carries a pushable filter, per query_shape.
# Captures the real mechanism behind Mongo's worst build-phase spikes: in the multi-segment
# stitching code path (MongoDriver.get, path.size > 2 branch / fetchTwoCollectionSegments),
# when a Collection segment has no condition, ALL parent docs with ALL embedded children are
# fetched unfiltered and walked in Kotlin before any downstream filter narrows anything down -
# this walk happens outside any measureTimedValue block, so it's counted entirely as "build"
# time, and scales with how many upstream segments are unfiltered, not with filter_count or
# filter_type alone (a query can have filter_count=1 but still fan out hugely if that one
# filter is on the LAST segment rather than the first).
#
# For a connection segment, the far-node ("collectionCondition") and edge ("connectionCondition")
# filters resolve in the SAME find() call as the connection segment itself (see
# fetchConnectionSegment) - so a far-node filter counts at the connection segment's own index,
# not a separate one.
#
# Value = depth (i.e. "never filtered") when no segment in the path has a condition at all.
# Default 0 for any query_shape not listed here (filtered at the very first segment).
FIRST_FILTERED_SEGMENT_INDEX_OVERRIDES = {
    "connection all": 2,
    "connection all only": 2,
    "connection filter on edge property": 1,
    "connection filter on far node": 1,
    "deep sub collection collect all": 3,
    "deep sub collection collect all only": 3,
    "deep sub collection grand child range filter": 2,
    "deep sub collection child range filter": 1,
    "dynamic collection all": 1,
    "dynamic collection all only": 1,
    "collection all": 1,
    "collection all only": 1,
    "sub collection collect all": 2,
    "sub collection collect all only": 2,
    "sub collection child range filter": 2,
    "very deep sub collection all only": 5,
    "very deep sub collection all": 5,
    "very deep sub collection int equality middle only": 2,
}


# ---------------------------------------------------------------------------
# 1. Load & flag data
# ---------------------------------------------------------------------------

def load_data(csv_path: str) -> pd.DataFrame:
    df = pd.read_csv(csv_path, sep=";", encoding="utf-8-sig")

    expected_cols = {
        "run_id", "query_shape", "driver", "collection_size", "depth", "filter_count",
        "filter_type", "bench_result_type", "dynamic_data", "phase", "iteration", "duration"
    }
    missing = expected_cols - set(df.columns)
    if missing:
        raise ValueError(f"CSV is missing expected columns: {missing}")

    if df["dynamic_data"].dtype == object:
        df["dynamic_data"] = df["dynamic_data"].astype(str).str.lower().map(
            {"true": True, "false": False}
        )

    df["requires_multi_query"] = df["query_shape"].str.lower().str.startswith(
        MULTI_QUERY_SHAPE_PREFIXES
    )

    # Interaction feature: lets the model learn a per-hop cost that ONLY applies when
    # multi-query stitching happens, rather than one flat additive penalty regardless of
    # how deep the stitched query goes (flat penalty was found to overcorrect for
    # shallow "deep" shapes and undercorrect for "very deep" ones - see residual analysis).
    df["multi_query_depth"] = df["requires_multi_query"].astype(int) * df["depth"]

    # Interaction feature capturing the confirmed mechanism behind Mongo's build-phase
    # duration spikes: equality/numRange filters are normally pushed to the server (cheap),
    # but fall back to client-side filtering (expensive, scales with unfiltered fetch size)
    # specifically when multi-query stitching is happening.
    df["multi_query_unpushed_filter"] = (
            df["requires_multi_query"] & df["filter_type"].isin(UNPUSHABLE_WHEN_MULTI_QUERY_FILTER_TYPES)
    ).astype(int)

    df["first_filtered_segment_index"] = (
        df["query_shape"].map(FIRST_FILTERED_SEGMENT_INDEX_OVERRIDES).fillna(0).astype(int)
    )

    # Multiplicative fan-out proxy: each unfiltered hop roughly MULTIPLIES the working set
    # size by the branching factor rather than adding a fixed cost, so log(fanout) is
    # approximately first_filtered_segment_index * log(collection_size). Pairs with fitting
    # build-phase duration in log-space (see LOG_TARGET_PHASES) so a linear model can
    # represent what is mechanically an exponential relationship.
    df["unfiltered_fanout_log"] = df["first_filtered_segment_index"] * np.log1p(df["collection_size"])

    return df


def flag_warmup(df: pd.DataFrame, warmup_iterations: int = WARMUP_ITERATIONS) -> pd.DataFrame:
    df = df.copy()
    df["iteration_rank"] = df.groupby(["driver", "query_shape", "phase"])["iteration"].rank(method="first")
    df["is_warmup"] = df["iteration_rank"] <= warmup_iterations
    return df


def flag_outliers(df: pd.DataFrame, method: str = OUTLIER_METHOD) -> pd.DataFrame:
    df = df.copy()
    df["is_outlier"] = False

    group_cols = ["driver", "query_shape", "phase"]
    for _, idx in df[~df["is_warmup"]].groupby(group_cols).groups.items():
        sub = df.loc[idx, "duration"]

        if method == "iqr":
            q1, q3 = sub.quantile(0.25), sub.quantile(0.75)
            iqr = q3 - q1
            lower, upper = q1 - IQR_MULTIPLIER * iqr, q3 + IQR_MULTIPLIER * iqr
            outlier_idx = sub[(sub < lower) | (sub > upper)].index
        elif method == "zscore":
            z = np.abs(stats.zscore(sub))
            outlier_idx = sub.index[z > ZSCORE_THRESHOLD]
        else:
            raise ValueError(f"Unknown outlier method: {method}")

        df.loc[outlier_idx, "is_outlier"] = True

    return df


# ---------------------------------------------------------------------------
# 2. Descriptive statistics
# ---------------------------------------------------------------------------

def summary_table(df: pd.DataFrame) -> pd.DataFrame:
    clean = df[~df["is_warmup"] & ~df["is_outlier"]]

    def agg(group):
        d = group["duration"]
        return pd.Series({
            "n": len(d), "mean": d.mean(), "median": d.median(), "std": d.std(),
            "cv": d.std() / d.mean() if d.mean() else np.nan,
            "min": d.min(), "p90": d.quantile(0.90), "p95": d.quantile(0.95),
            "p99": d.quantile(0.99), "max": d.max(),
        })

    return (
        clean.groupby(["driver", "query_shape", "phase"])
        .apply(agg, include_groups=False)
        .reset_index()
        .sort_values(["query_shape", "phase", "driver"])
    )


def compare_drivers(df: pd.DataFrame, phase: str) -> pd.DataFrame:
    clean = df[~df["is_warmup"] & ~df["is_outlier"] & (df["phase"] == phase)]
    drivers = sorted(clean["driver"].unique())
    rows = []

    for shape, group in clean.groupby("query_shape"):
        for i in range(len(drivers)):
            for j in range(i + 1, len(drivers)):
                a = group.loc[group["driver"] == drivers[i], "duration"]
                b = group.loc[group["driver"] == drivers[j], "duration"]
                if len(a) < 2 or len(b) < 2:
                    continue
                u_stat, p_value = stats.mannwhitneyu(a, b, alternative="two-sided")
                delta = cliffs_delta(a.to_numpy(), b.to_numpy())
                rows.append({
                    "query_shape": shape, "phase": phase,
                    "driver_a": drivers[i], "driver_b": drivers[j],
                    "median_a": a.median(), "median_b": b.median(),
                    "p_value": p_value, "cliffs_delta": delta,
                })

    return pd.DataFrame(rows)


def cliffs_delta(a: np.ndarray, b: np.ndarray) -> float:
    gt = sum((x > y) for x in a for y in b)
    lt = sum((x < y) for x in a for y in b)
    return (gt - lt) / (len(a) * len(b))


def scaling_correlation(df: pd.DataFrame) -> pd.DataFrame:
    clean = df[~df["is_warmup"] & ~df["is_outlier"]]
    rows = []
    # raw structural parameters only - NOT derived/interaction features like
    # multi_query_depth, multi_query_unpushed_filter, first_filtered_segment_index,
    # which are model inputs, not something we want a standalone scaling report for.
    raw_params = ["collection_size", "depth", "filter_count"]
    for (driver, phase), group in clean.groupby(["driver", "phase"]):
        for param in raw_params:
            if group[param].nunique() < 2:
                continue
            rho, p_value = stats.spearmanr(group[param], group["duration"])
            rows.append({"driver": driver, "phase": phase, "parameter": param,
                         "spearman_rho": rho, "p_value": p_value})
    return pd.DataFrame(rows)


# ---------------------------------------------------------------------------
# 3. Regression models: per driver x phase, predicting duration from query features
# ---------------------------------------------------------------------------

def build_pipeline(model_type: str) -> Pipeline:
    """
    ColumnTransformer one-hot encodes the categorical features (filter_type, bench_result_type,
    dynamic_data) and passes numeric features through untouched, then feeds into either a
    linear regression (interpretable, good baseline) or a random forest (captures non-linearity
    / interactions, if residuals from linear look structured).
    """
    preprocessor = ColumnTransformer(transformers=[
        ("num", "passthrough", NUMERIC_FEATURES),
        ("cat", OneHotEncoder(handle_unknown="ignore"), CATEGORICAL_FEATURES),
    ])

    if model_type == "linear":
        model = LinearRegression()
    elif model_type == "forest":
        model = RandomForestRegressor(n_estimators=200, max_depth=8, random_state=42)
    else:
        raise ValueError(f"Unknown model_type: {model_type}")

    return Pipeline([("preprocess", preprocessor), ("model", model)])


def fit_and_validate_models(df: pd.DataFrame):
    """
    Fits linear and random-forest models per (driver, phase). For phases in LOG_TARGET_PHASES
    (currently just "build"), fits on log1p(duration) instead of raw duration - build-phase
    spans two+ orders of magnitude (typical few-hundred us vs up to 70,000us for unfiltered
    deep fan-out), and the underlying mechanism is multiplicative (each unfiltered hop
    multiplies working set size), which log-space linear regression can represent directly.
    All R2 scores are computed on the ORIGINAL duration scale (predictions inverted via
    expm1 before scoring) so numbers stay comparable across phases regardless of which
    scale was used internally to fit.

    Validates two ways:
      - k-fold CV over rows (how well the model fits this data distribution generally)
      - GroupKFold by query_shape (how well it generalizes to UNSEEN query shapes -
        the actually relevant test, since at runtime queries won't match your fixed
        benchmark shapes exactly)
    Returns a metrics table and a dict of fitted linear models (driver, phase) -> Pipeline,
    used afterward for coefficient export. Fitted linear models are ALWAYS fit on
    log1p(duration) if the phase uses it - the export/prediction-formula step must apply
    expm1() to the raw linear formula output.
    """
    clean = df[~df["is_warmup"] & ~df["is_outlier"]].copy()
    metrics_rows = []
    fitted_linear_models = {}

    for (driver, phase), group in clean.groupby(["driver", "phase"]):
        X = group[ALL_FEATURES]
        y = group["duration"]
        shapes = group["query_shape"]
        use_log_target = phase in LOG_TARGET_PHASES
        y_fit = np.log1p(y) if use_log_target else y

        if len(group) < 20 or shapes.nunique() < 2:
            print(f"Skipping {driver}/{phase}: not enough data/variety "
                  f"({len(group)} rows, {shapes.nunique()} query shapes) to validate meaningfully.")
            continue

        for model_type in ["linear", "forest"]:
            pipeline = build_pipeline(model_type)

            def r2_on_original_scale(estimator, X_test, y_test_fit):
                pred_fit = estimator.predict(X_test)
                pred = np.expm1(pred_fit) if use_log_target else pred_fit
                y_test = np.expm1(y_test_fit) if use_log_target else y_test_fit
                return r2_score(y_test, pred)

            kf = KFold(n_splits=5, shuffle=True, random_state=42)
            kfold_scores = cross_val_score(pipeline, X, y_fit, cv=kf, scoring=r2_on_original_scale)

            n_groups = shapes.nunique()
            gkf_splits = min(5, n_groups)
            group_scores = np.array([np.nan])
            if gkf_splits >= 2:
                gkf = GroupKFold(n_splits=gkf_splits)
                group_scores = cross_val_score(
                    pipeline, X, y_fit, cv=gkf, groups=shapes, scoring=r2_on_original_scale
                )

            pipeline.fit(X, y_fit)
            y_pred_fit = pipeline.predict(X)
            y_pred = np.expm1(y_pred_fit) if use_log_target else y_pred_fit
            in_sample_r2 = r2_score(y, y_pred)

            metrics_rows.append({
                "driver": driver, "phase": phase, "model_type": model_type,
                "log_target": use_log_target,
                "n_rows": len(group), "n_query_shapes": n_groups,
                "in_sample_r2": in_sample_r2,
                "kfold_r2_mean": kfold_scores.mean(), "kfold_r2_std": kfold_scores.std(),
                "group_kfold_r2_mean": np.nanmean(group_scores),
                "group_kfold_r2_std": np.nanstd(group_scores),
            })

            if model_type == "linear":
                fitted_linear_models[(driver, phase)] = (pipeline, use_log_target)
                # residual plot on the ORIGINAL scale - what actually matters for judging fit
                plot_residuals(driver, phase, y, y_pred)

    return pd.DataFrame(metrics_rows), fitted_linear_models


def plot_residuals(driver: str, phase: str, y_true: pd.Series, y_pred: np.ndarray):
    residuals = y_true.to_numpy() - y_pred
    fig, axes = plt.subplots(1, 2, figsize=(10, 4))

    axes[0].scatter(y_pred, residuals, alpha=0.3, s=10)
    axes[0].axhline(0, color="black", linewidth=1)
    axes[0].set_xlabel("predicted duration")
    axes[0].set_ylabel("residual (actual - predicted)")
    axes[0].set_title("Residuals vs. predicted")

    sns.histplot(residuals, kde=True, ax=axes[1])
    axes[1].set_title("Residual distribution")

    fig.suptitle(f"Linear model residuals - {driver} / {phase}")
    plt.tight_layout()
    plt.savefig(PLOTS_DIR / f"residual_{driver}_{phase}.png", dpi=150)
    plt.close()


def export_linear_coefficients(fitted_linear_models: dict, path: str = "model_coefficients_linear.json"):
    """
    Exports fitted linear model parameters in a driver/phase-keyed structure that's easy to
    port into Kotlin: intercept + one coefficient per numeric feature + one coefficient per
    one-hot category level. Prediction in Kotlin becomes:

        rawFormula = intercept
                 + coef_collection_size * collectionSize
                 + coef_depth * depth
                 + coef_filter_count * filterCount
                 + coef_filter_type[filterType]      // 0 if category unseen in training
                 + coef_bench_result_type[resultType]
                 + coef_dynamic_data[dynamicData]
                 + ... (remaining numeric/categorical coefficients)

        duration = if (target_transform == "log1p") exp(rawFormula) - 1 else rawFormula

    IMPORTANT: check "target_transform" per driver/phase. Currently "build" phase entries
    are fit on log1p(duration) (see LOG_TARGET_PHASES) because build-phase cost is
    multiplicative (unfiltered fan-out) rather than additive - the Kotlin side MUST apply
    expm1 (exp(x) - 1) to the raw formula output for these, or predictions will be wildly
    wrong (log-scale values, not microseconds).

    NOTE: all category levels get explicit coefficients (no drop='first'), so there's no
    implicit "baseline" category to track separately in Kotlin - simpler port.
    """
    export = {}

    for (driver, phase), (pipeline, use_log_target) in fitted_linear_models.items():
        preprocessor: ColumnTransformer = pipeline.named_steps["preprocess"]
        model: LinearRegression = pipeline.named_steps["model"]

        numeric_coefs = dict(zip(NUMERIC_FEATURES, model.coef_[:len(NUMERIC_FEATURES)]))

        cat_encoder: OneHotEncoder = preprocessor.named_transformers_["cat"]
        cat_feature_names = cat_encoder.get_feature_names_out(CATEGORICAL_FEATURES)
        cat_coefs_flat = model.coef_[len(NUMERIC_FEATURES):]

        categorical_coefs = {}
        for full_name, coef in zip(cat_feature_names, cat_coefs_flat):
            for feature in CATEGORICAL_FEATURES:
                prefix = f"{feature}_"
                if full_name.startswith(prefix):
                    level = full_name[len(prefix):]
                    categorical_coefs.setdefault(feature, {})[level] = coef
                    break

        export.setdefault(driver, {})[phase] = {
            "intercept": model.intercept_,
            "numeric_coefficients": numeric_coefs,
            "categorical_coefficients": categorical_coefs,
            "target_transform": "log1p" if use_log_target else "none",
        }

    with open(path, "w") as f:
        json.dump(export, f, indent=2, default=float)

    print(f"\nExported linear model coefficients to {path}")


# ---------------------------------------------------------------------------
# 4. Plots
# ---------------------------------------------------------------------------

def plot_boxplots_by_shape(df: pd.DataFrame, phase: str):
    clean = df[~df["is_warmup"] & ~df["is_outlier"] & (df["phase"] == phase)]
    for shape, group in clean.groupby("query_shape"):
        plt.figure(figsize=(6, 4))
        order = group.groupby("driver")["duration"].median().sort_values().index
        sns.boxplot(data=group, x="driver", y="duration", order=order)
        plt.title(f"{phase} duration - {shape}")
        plt.ylabel("duration (us)")
        plt.tight_layout()
        plt.savefig(PLOTS_DIR / f"box_{phase}_{shape.replace(' ', '_')}.png", dpi=150)
        plt.close()


def plot_scaling(df: pd.DataFrame, phase: str, parameter: str):
    clean = df[~df["is_warmup"] & ~df["is_outlier"] & (df["phase"] == phase)]
    if clean[parameter].nunique() < 2:
        print(f"Skipping scaling plot for '{parameter}' ({phase}): not varied in this dataset.")
        return

    agg = (
        clean.groupby(["driver", parameter])["duration"]
        .agg(median="median", p25=lambda x: x.quantile(0.25), p75=lambda x: x.quantile(0.75))
        .reset_index()
    )

    plt.figure(figsize=(7, 4))
    for driver, group in agg.groupby("driver"):
        group = group.sort_values(parameter)
        plt.plot(group[parameter], group["median"], marker="o", label=driver)
        plt.fill_between(group[parameter], group["p25"], group["p75"], alpha=0.15)
    plt.title(f"{phase} duration vs. {parameter}")
    plt.xlabel(parameter)
    plt.ylabel("duration (median, IQR band)")
    plt.legend()
    plt.tight_layout()
    plt.savefig(PLOTS_DIR / f"scaling_{phase}_{parameter}.png", dpi=150)
    plt.close()


# ---------------------------------------------------------------------------
# 5. Main
# ---------------------------------------------------------------------------

def main(csv_path: str):
    df = load_data(csv_path)
    df = flag_warmup(df)
    df = flag_outliers(df)

    print(f"Loaded {len(df)} rows: {df['driver'].nunique()} drivers, "
          f"{df['query_shape'].nunique()} query shapes, "
          f"{df['is_warmup'].sum()} flagged warmup, {df['is_outlier'].sum()} flagged outliers.")

    summary = summary_table(df)
    summary.to_csv("summary_stats.csv", index=False)
    print("\n=== Summary statistics (clean subset) ===")
    print(summary.to_string(index=False))

    for phase in df["phase"].unique():
        comparison = compare_drivers(df, phase)
        if not comparison.empty:
            comparison.to_csv(f"driver_comparison_{phase}.csv", index=False)

    scaling = scaling_correlation(df)
    if not scaling.empty:
        scaling.to_csv("scaling_correlation.csv", index=False)
        print("\n=== Scaling correlation (Spearman rho vs. duration) ===")
        print(scaling.to_string(index=False))

    # for phase in df["phase"].unique():
    #     plot_boxplots_by_shape(df, phase)
    #     for param in ["collection_size", "depth", "filter_count"]:
    #         plot_scaling(df, phase, param)

    print("\n=== Fitting per-driver, per-phase regression models ===")
    metrics, fitted_linear_models = fit_and_validate_models(df)
    if not metrics.empty:
        metrics.to_csv("model_metrics.csv", index=False)
        print(metrics.to_string(index=False))
        print(
            "\nRead this as: 'kfold_r2_mean' = in-distribution fit quality; "
            "'group_kfold_r2_mean' = generalization to UNSEEN query shapes (the more "
            "important number for judging whether the estimator will work on real queries). "
            "If linear R^2 is much lower than forest R^2, that's evidence of non-linearity "
            "worth discussing/handling (e.g. log-transform collection_size)."
        )
        export_linear_coefficients(fitted_linear_models)
    else:
        print("Not enough data variety yet to fit/validate models "
              "(need multiple query shapes and enough rows per driver/phase).")

    print(f"\nPlots written to {PLOTS_DIR.resolve()}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python analyze_benchmarks.py path/to/measurements.csv")
        sys.exit(1)

    main(sys.argv[1])