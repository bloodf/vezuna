# Gates: shared coverage reporting

OWNS: shared/build.gradle.kts

Scope: Built-in Kover package line coverage reporting with existing 80% bound and exclusions.

- [x] G1: Package line coverage measured by official Cortex execution.
  EVIDENCE: Tested36cc605; KoverLog/XML/HTML exit0, data92.2353%, domain99.2516%; report.
- [x] G2: Existing bound/exclusions preserved; only owned configuration changes.
  EVIDENCE: Eight added log lines; diff --check passed; composition.json proves unchanged covered production inputs.
- [x] G3: Existing unit tests and affected build/report verification pass.
  EVIDENCE: JVM962 in123suites; Androidhost914 in106suites, each zero failures/errors/skips; KoverVerify PASS. Executed at36cc605, not newer combined tests.
- [x] G4: Independent approval, live gates and normal merge.
  EVIDENCE: Source review APPROVED36cc605; no protection/rules/unresolved threads. Normal merge1be6d0a at19:12:43Z, actual tree matches composed tree. Optional hosted checks not claimed green; existing brand-document failure preserved in failed-android.log.
