# Generated test suites

```
generated_tests/
  pit_suites/<project>/<LLM>/...                       the 72 test suites evaluated with PIT
                                                       (main generation experiment, one suite per project and model)
  consistency_runs/<project>/<LLM>/run_<n>/Run<n>.java the 360 outputs of the separate consistency experiment
                                                       (five repeated generations, n = 1..5)
```

* `<project>` = folder names used in `../pit_reports/`; `<LLM>` = `Claude`, `GPT`, `Gemini`, `DeepSeek`.
* **The two folders come from two different experiments.** The PIT reports in `../pit_reports/` were produced from
  the suites in `pit_suites/` and from the developer-written tests of each project. The files in `consistency_runs/`
  were generated separately, to measure how much the generated tests vary between repeated generations; they were
  not evaluated with PIT.
* `pit_suites/<project>/<LLM>/` contains the test files that were run with PIT for that suite, with their original
  names and package folders (relative to `src/test/java`); `pit_suites/<project>/Human/` contains the
  developer-written integration test of the project (copied from the original project). The class names appear as
  killing tests in the PIT reports (for example `claudeIT_1` or `IntegrationTest_gemini`).
* `consistency_runs/`: each output is stored as `Run<n>.java`; the package declaration and the original class names
  are inside the file.
* Only the integration-test file of each suite is stored. The other tests of the original projects are not copied.
* **vaulttec-sonar-auth-oidc:** the PIT runs of this project also executed the project's pre-existing unit tests
  (`OidcConfigurationTest`, `UserIdentityFactoryTest`, `AutoLoginFilterTest`, ...), and some reports also contain
  test classes of other suites. Most killing tests recorded in its reports therefore belong to those classes, not
  to the suite's own file.
* Every empty folder contains an empty `.gitkeep` file only so that Git keeps the folder. Delete it once files are added.
