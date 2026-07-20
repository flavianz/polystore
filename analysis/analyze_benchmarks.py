import sys
from pathlib import Path

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from scipy import stats

# ---------------------------------------------------------------------------
# 0. Config
# ---------------------------------------------------------------------------

WARMUP_ITERATIONS = 50  # first N iterations per (driver, query_shape, phase) treated as warmup
OUTLIER_METHOD = "iqr"  # "iqr" or "zscore"
IQR_MULTIPLIER = 1.5
ZSCORE_THRESHOLD = 3.0

PLOTS_DIR = Path("plots")
PLOTS_DIR.mkdir(exist_ok=True)

sns.set_theme(style="whitegrid")


# ---------------------------------------------------------------------------
# 1. Load & flag data (raw data stays untouched; exclusions are flags)
# ---------------------------------------------------------------------------

def load_data(csv_path: str) -> pd.DataFrame:
    df = pd.read_csv(csv_path, delimiter=";")

    expected_cols = {
        "run_id", "query_shape", "driver", "collection_size",
        "depth", "filter_count", "phase", "iteration", "duration"
    }
    missing = expected_cols - set(df.columns)
    if missing:
        raise ValueError(f"CSV is missing expected columns: {missing}")

    return df


def flag_warmup(df: pd.DataFrame, warmup_iterations: int = WARMUP_ITERATIONS) -> pd.DataFrame:
    """
    Mark the first N iterations of each (driver, query_shape, phase) group as warmup.
    Iteration numbering is assumed to restart per group; if it's global instead,
    this still works since we rank within each group.
    """
    df = df.copy()
    df["iteration_rank"] = df.groupby(["driver", "query_shape", "phase"])["iteration"].rank(method="first")
    df["is_warmup"] = df["iteration_rank"] <= warmup_iterations
    return df


def flag_outliers(df: pd.DataFrame, method: str = OUTLIER_METHOD) -> pd.DataFrame:
    """
    Flag outliers per (driver, query_shape, phase) group, computed only on non-warmup rows
    so a slow warmup phase doesn't distort the "steady state" outlier bounds.
    """
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
    """
    Mean/median/percentiles/std/CV per driver x query_shape x phase, computed on the
    'clean' subset (no warmup, no outliers). Percentiles matter more than mean here
    since latency distributions are right-skewed.
    """
    clean = df[~df["is_warmup"] & ~df["is_outlier"]]

    def agg(group):
        d = group["duration"]
        return pd.Series({
            "n": len(d),
            "mean": d.mean(),
            "median": d.median(),
            "std": d.std(),
            "cv": d.std() / d.mean() if d.mean() else np.nan,
            "min": d.min(),
            "p90": d.quantile(0.90),
            "p95": d.quantile(0.95),
            "p99": d.quantile(0.99),
            "max": d.max(),
        })

    return (
        clean.groupby(["driver", "query_shape", "phase"])
        .apply(agg, include_groups=False)
        .reset_index()
        .sort_values(["query_shape", "phase", "driver"])
    )


def warmup_effect_table(df: pd.DataFrame) -> pd.DataFrame:
    """Compare warmup vs. steady-state duration, to justify the warmup cutoff in the thesis."""
    return (
        df.groupby(["driver", "phase", "is_warmup"])["duration"]
        .agg(["count", "mean", "median"])
        .reset_index()
        .sort_values(["driver", "phase", "is_warmup"])
    )


def outlier_summary(df: pd.DataFrame) -> pd.DataFrame:
    """How many outliers were flagged per group, as a sanity check they're not too aggressive."""
    return (
        df[~df["is_warmup"]]
        .groupby(["driver", "query_shape", "phase"])["is_outlier"]
        .agg(["sum", "count"])
        .rename(columns={"sum": "n_outliers", "count": "n_total"})
        .assign(outlier_pct=lambda x: 100 * x["n_outliers"] / x["n_total"])
        .reset_index()
    )


# ---------------------------------------------------------------------------
# 3. Statistical comparison between drivers
# ---------------------------------------------------------------------------

def compare_drivers(df: pd.DataFrame, phase: str) -> pd.DataFrame:
    """
    Pairwise Mann-Whitney U tests between drivers, per query_shape, for a given phase.
    Non-parametric because latency distributions are not normal (right-skewed, heavy tail).
    Also reports Cliff's delta as an effect size, since with n~1000 almost every
    comparison will be "significant" - effect size tells you if it's practically meaningful.
    """
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
                    "query_shape": shape,
                    "phase": phase,
                    "driver_a": drivers[i],
                    "driver_b": drivers[j],
                    "median_a": a.median(),
                    "median_b": b.median(),
                    "p_value": p_value,
                    "cliffs_delta": delta,
                })

    return pd.DataFrame(rows)


def cliffs_delta(a: np.ndarray, b: np.ndarray) -> float:
    """
    Cliff's delta effect size: fraction of pairs favoring a minus fraction favoring b.
    Range [-1, 1]; magnitude ~0.11/0.28/0.43 conventionally read as small/medium/large.
    O(n*m) - fine at n=1000 per group, would need a faster method for much larger n.
    """
    gt = sum((x > y) for x in a for y in b)
    lt = sum((x < y) for x in a for y in b)
    return (gt - lt) / (len(a) * len(b))


# ---------------------------------------------------------------------------
# 4. Scaling behaviour: duration vs. collectionSize / depth / filterCount
# ---------------------------------------------------------------------------

def scaling_correlation(df: pd.DataFrame) -> pd.DataFrame:
    """
    Spearman correlation (monotonic, not necessarily linear - appropriate given we don't
    know the true functional form yet) between duration and each structural parameter,
    per driver and phase. A first, cheap signal before fitting any regression model.
    """
    clean = df[~df["is_warmup"] & ~df["is_outlier"]]
    rows = []

    for (driver, phase), group in clean.groupby(["driver", "phase"]):
        for param in ["collection_size", "depth", "filter_count"]:
            if group[param].nunique() < 2:
                continue  # correlation undefined if the parameter doesn't vary
            rho, p_value = stats.spearmanr(group[param], group["duration"])
            rows.append({
                "driver": driver,
                "phase": phase,
                "parameter": param,
                "spearman_rho": rho,
                "p_value": p_value,
            })

    return pd.DataFrame(rows)


# ---------------------------------------------------------------------------
# 5. Plots
# ---------------------------------------------------------------------------

def plot_boxplots_by_shape(df: pd.DataFrame, phase: str):
    """One boxplot figure per query_shape, comparing drivers. Good overview figure."""
    clean = df[~df["is_warmup"] & ~df["is_outlier"] & (df["phase"] == phase)]

    for shape, group in clean.groupby("query_shape"):
        plt.figure(figsize=(6, 4))
        order = group.groupby("driver")["duration"].median().sort_values().index
        sns.boxplot(data=group, x="driver", y="duration", order=order)
        plt.title(f"{phase} duration - {shape}")
        plt.ylabel("duration μ")
        plt.tight_layout()
        safe_name = shape.replace(" ", "_")
        plt.savefig(PLOTS_DIR / f"box_{phase}_{safe_name}.png", dpi=150)
        plt.close()


def plot_ecdf_by_driver(df: pd.DataFrame, phase: str, query_shape: str):
    """
    ECDF instead of histogram: shows the full distribution shape including the tail,
    without binning artifacts. Good for illustrating e.g. p95/p99 differences visually.
    """
    clean = df[~df["is_warmup"] & ~df["is_outlier"] & (df["phase"] == phase) & (df["query_shape"] == query_shape)]
    if clean.empty:
        return

    plt.figure(figsize=(6, 4))
    for driver, group in clean.groupby("driver"):
        sns.ecdfplot(data=group, x="duration", label=driver)
    plt.title(f"ECDF of {phase} duration - {query_shape}")
    plt.xlabel("duration")
    plt.legend()
    plt.tight_layout()
    safe_name = query_shape.replace(" ", "_")
    plt.savefig(PLOTS_DIR / f"ecdf_{phase}_{safe_name}.png", dpi=150)
    plt.close()


def plot_warmup_curve(df: pd.DataFrame, phase: str, query_shape: str):
    """
    Duration per iteration index, to visually justify the warmup cutoff.
    Includes both warmup and steady-state points (no outlier filtering here on purpose -
    we want to see the raw trajectory).
    """
    sub = df[(df["phase"] == phase) & (df["query_shape"] == query_shape)]
    if sub.empty:
        return

    plt.figure(figsize=(7, 4))
    for driver, group in sub.groupby("driver"):
        group = group.sort_values("iteration_rank")
        plt.plot(group["iteration_rank"], group["duration"], marker=".", linestyle="none",
                 alpha=0.4, label=driver)
    plt.axvline(WARMUP_ITERATIONS, color="black", linestyle="--", linewidth=1,
                label=f"warmup cutoff ({WARMUP_ITERATIONS})")
    plt.title(f"Duration per iteration - {phase} - {query_shape}")
    plt.xlabel("iteration index")
    plt.ylabel("duration")
    plt.legend()
    plt.tight_layout()
    safe_name = query_shape.replace(" ", "_")
    plt.savefig(PLOTS_DIR / f"warmup_{phase}_{safe_name}.png", dpi=150)
    plt.close()


def plot_scaling(df: pd.DataFrame, phase: str, parameter: str):
    """
    Duration vs. a structural parameter (collectionSize, depth, filterCount), one line
    per driver, median with an IQR band. Directly feeds into thinking about the eventual
    estimator function.
    """
    clean = df[~df["is_warmup"] & ~df["is_outlier"] & (df["phase"] == phase)]
    if clean[parameter].nunique() < 2:
        print(f"Skipping scaling plot for '{parameter}': not varied in this dataset yet.")
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
# 6. Main
# ---------------------------------------------------------------------------

def main(csv_path: str):
    df = load_data(csv_path)
    df = flag_warmup(df)
    df = flag_outliers(df)

    print(f"Loaded {len(df)} rows: {df['driver'].nunique()} drivers, "
          f"{df['query_shape'].nunique()} query shapes, "
          f"{df['is_warmup'].sum()} flagged as warmup, "
          f"{df['is_outlier'].sum()} flagged as outliers.")

    # --- descriptive stats ---
    summary = summary_table(df)
    summary.to_csv("summary_stats.csv", index=False)
    print("\n=== Summary statistics (clean subset) ===")
    print(summary.to_string(index=False))

    warmup_tab = warmup_effect_table(df)
    print("\n=== Warmup vs. steady-state ===")
    print(warmup_tab.to_string(index=False))

    outliers_tab = outlier_summary(df)
    print("\n=== Outlier rate per group ===")
    print(outliers_tab.to_string(index=False))

    # --- statistical comparison ---
    for phase in df["phase"].unique():
        comparison = compare_drivers(df, phase)
        if not comparison.empty:
            comparison.to_csv(f"driver_comparison_{phase}.csv", index=False)
            print(f"\n=== Driver comparison ({phase}) ===")
            print(comparison.to_string(index=False))

    # --- scaling correlations ---
    scaling = scaling_correlation(df)
    if not scaling.empty:
        scaling.to_csv("scaling_correlation.csv", index=False)
        print("\n=== Scaling correlation (Spearman rho vs. duration) ===")
        print(scaling.to_string(index=False))
    else:
        print("\nNo scaling correlation computed - collectionSize/depth/filterCount "
              "don't vary yet in this run (expected for a first single-collectionSize run).")

    # --- plots ---
    for phase in df["phase"].unique():
        plot_boxplots_by_shape(df, phase)
        for shape in df["query_shape"].unique():
            plot_ecdf_by_driver(df, phase, shape)
            plot_warmup_curve(df, phase, shape)
        for param in ["collection_size", "depth", "filter_count"]:
            plot_scaling(df, phase, param)

    print(f"\nPlots written to {PLOTS_DIR.resolve()}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python analyze_benchmarks.py path/to/measurements.csv")
        sys.exit(1)

    main(sys.argv[1])