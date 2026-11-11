# Group search classification progress (pre22)

- Added `GroupClassification.stableClusterOrder`: a pure, stable permutation helper. It only clusters repeated conservative name keys (trailing numeric instance suffixes such as `Tokyo-01`/`Tokyo-02`) and never mutates, merges, or deletes groups.
- Search toolbar now exposes **一键归类** immediately when opened with an empty query, directly left of the fixed close/tick controls. Non-empty queries retain the existing `归类` gate (at least two matches) and custom-selection flow.
- Empty-query classification shows the proposed card order in a confirmation preview. Confirm persists ID-only order through `GroupOrderStore`; the snackbar provides **还原** via the original ID order. Cancel makes no change.
- Added separate `group_classification.xml` resources (default and zh-rCN) and expanded algorithm/toolbar tests.
- Existing unrelated working-tree edits were not touched.

## Targeted verification

```sh
./gradlew :app:testDebugUnitTest --tests io.nekohasekai.sagernet.group.GroupClassificationTest --tests io.nekohasekai.sagernet.group.GroupSearchBarTest
```

Do not run Gradle concurrently with another agent. This task did not commit or push.
