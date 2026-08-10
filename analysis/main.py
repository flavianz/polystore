import pandas as pd

df = pd.read_csv("./data/bench-data-raw-10.csv", sep=";", encoding="utf-8-sig")
df["requires_multi_query"] = df["query_shape"].str.lower().str.startswith(("deep", "very deep"))

# how many distinct shapes/depths are actually multi-query?
mq = df[df["requires_multi_query"]]
print("distinct multi-query shapes:", mq["query_shape"].nunique())
print("distinct depths among multi-query shapes:")
print(mq.groupby("query_shape")["depth"].first().value_counts().sort_index())

# and for comparison, non-multi-query
non_mq = df[~df["requires_multi_query"]]
print("distinct non-multi-query shapes:", non_mq["query_shape"].nunique())

