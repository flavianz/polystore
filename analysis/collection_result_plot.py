import pandas as pd
import matplotlib.pyplot as plt
from fontTools.misc.cython import returns

# Load data
df = pd.read_csv("regression/data/result-size11.csv", sep=";")

collection_sizes = sorted(df["collection_size"].unique())

fig, axes = plt.subplots(2, 3, figsize=(15, 8), sharey=False)
axes = axes.flatten()

for ax, size in zip(axes, collection_sizes):
    subset = df[df["collection_size"] == size]["result_size"]
    ax.hist(subset, bins=20, color="steelblue", edgecolor="black")
    ax.set_title(f"collection_size = {size}")
    ax.set_xlabel("result_size")
    ax.set_ylabel("count")

# Hide unused subplots if fewer than 6 sizes
for ax in axes[len(collection_sizes):]:
    ax.axis("off")

plt.tight_layout()
plt.savefig("./regression/plots/result_size_distribution11.png", dpi=150)
plt.show()