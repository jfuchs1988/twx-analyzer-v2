# Provenance and NOTICE Audit

**Review date:** 2026-10-27  
**Reviewed upstream:** [dosvak/twx-code-analyzer](https://github.com/dosvak/twx-code-analyzer)  
**Reference commit used for comparison:** `303bf265280d07532972bf7eeca96dc100fd8d4c` (2026-09-19)

## Code comparison results

- The 31 Java files in `src/main/java/io/twxav2/analysis/ref` that have same-named upstream files were compared by file name and normalized code. Package/import lines and comments were excluded from normalization. Twenty code sequences were identical; eleven showed substantial overlap plus local extensions or changes.
- `RefModel.java` also contains the `TwxLoader.nameEmbeddedProcesses` method. This file now carries a provenance/modification header as well.
- The 32 identified derived/modified files under `analysis/ref` have a header at the top of the file. It names Dosvak LLC, the upstream project, Apache-2.0, and the modifications for TWX Analyzer v2.
- The four remaining files without such a header (`CatalogRule.java`, `RefRuleCatalog.java`, `RefRulesProducer.java`, `RuleTextsDe.java`) are project-specific adapters, CDI registration, or German text localization. No corresponding same-named upstream files were found, so they were not blanket-labeled as upstream code.
- Same-named candidates outside `analysis/ref` (`analysis/RuleContext.java`, `analysis/RuleSettings.java`, `model/Finding.java`, `model/TwxObject.java`) showed no meaningful reuse in the normalized line comparison (maximum similarity 0.146). This is a search/comparison result, not a legal determination of independent authorship.

The reviewed commit is a comparison reference, not a verified original import commit. `NOTICE` now identifies the repository, rights holder, and reference commit. The upstream-specific IBM/Dosvak trademark wording was not copied verbatim, to avoid suggesting affiliation or endorsement of this product.

The root `LICENSE` is the Apache-2.0 license text with this project's copyright holder in place of the template placeholder. The applicable Dosvak attribution from the upstream `NOTICE` has been included in this project's `NOTICE`; product- or publisher-specific wording was not copied.

## Distribution locations

- The root `LICENSE` and `NOTICE` remain part of the source repository.
- `Containerfile` copies both files into the application image (`/app/LICENSE`, `/app/NOTICE`) so the documented container distribution carries the license and attribution.
- Derived Java files contain brief provenance/modification notices; detailed attribution and license terms are in `NOTICE` and `LICENSE`.

## Remaining review

The third-party list in `NOTICE` is explicitly not exhaustive. Before publishing or redistributing, complete a license inventory of all direct and transitive Maven/npm dependencies, including any required license texts or other obligations for the components actually distributed. This source comparison is not a legal review.
