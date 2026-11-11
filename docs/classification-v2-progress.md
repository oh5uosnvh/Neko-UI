# Approximate group-name clustering progress

- Generalized `GroupClassification` normalization uses Unicode NFKC, case folding, punctuation/emoji-insensitive compact forms, and repeated Latin-letter folding.
- Family matching is bounded prefix matching (maximum four extra normalized characters) with a minimum meaningful prefix, plus first-token family matching for numeric/descriptor suffixes. Matching is anchored to each family’s first source item, avoiding transitive overmerge and unrelated one-character matches.
- All IDs/cards are retained and member order remains source order. `namedClusters` and `previewText` expose named clusters with counts and an `Other` remainder.
- `GroupFragment` preview now uses named clusters/counts; confirm and undo persistence flow remains unchanged.
- Added positive coverage for ik/iku/ikuuu variants, 良心 descriptors, 黑石 variants, 守候网络, and negative coverage for one-character overlap and transitive overmerge.
- Added `assertTrue` import required by the new tests. No Gradle commands were run after the correction; caller is expected to run the unified test task.
