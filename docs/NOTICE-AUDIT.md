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
- `Containerfile` copies both files and the resolved dependency inventory into the API image; `Containerfile.frontend` copies them into the frontend image so both independently distributed images carry the license and attribution notices.
- Derived Java files contain brief provenance/modification notices; detailed attribution and license terms are in `NOTICE` and `LICENSE`.

## Dependency license inventory (reviewed 2026-10-27)

The resolved project dependency inventory is [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md). It was generated from the resolved Maven POM license metadata for packaged `compile`/`runtime` scopes (172 artifacts), all declared Maven project dependency scopes (267 artifacts, including tests), and every package/license field in `frontend/package-lock.json` (292 packages, including development tooling). No Maven report entries or npm lockfile package entries were missing license declarations. Maven build-plugin dependencies are excluded because they are build tooling, not project/runtime dependencies distributed by this application.

The reviewed runtime exceptions to the predominant Apache-2.0/MIT terms include MariaDB Connector/J 3.5.10 (LGPL-2.1-or-later) and Mozilla Rhino 1.9.1 (MPL-2.0); Eclipse components declare EPL and/or EDL options, in some cases alongside GPL with the Classpath Exception. The inventory preserves coordinates, versions, package source/project links, and declared terms. The container copies the inventory alongside `LICENSE` and `NOTICE`. This is a metadata and attribution review, not legal advice; before a specific redistribution, check the exact applicable license text, notices and any source/relinking obligations for the versions actually shipped.

To refresh after changing dependencies, run `org.codehaus.mojo:license-maven-plugin:2.7.1:add-third-party` with `-Dlicense.includedScopes=compile,runtime -Dlicense.outputDirectory=target/license-audit`, then run it again with `-Dlicense.outputDirectory=target/license-audit/all`; finally run `python scripts/third-party-notices.py`. Review and commit the resulting inventory diff. The npm audit reads the lockfile directly.
