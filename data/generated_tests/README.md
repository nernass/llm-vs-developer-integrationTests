# Generated test suites

```
generated_tests/
  selected_run1/<project>/<LLM>/Run1.java      the 72 suites used with PIT (Run 1 of each project-LLM pair)
  all_runs/<project>/<LLM>/run_<n>/Run<n>.java  all 360 outputs of the consistency analysis (n = 1..5)
  selection.csv                                 which run was used for every project-LLM pair (72 rows)
```

* `<project>` = folder names used in `../pit_reports/`; `<LLM>` = `Claude`, `GPT`, `Gemini`, `DeepSeek`.
* Each output is stored as `Run<n>.java`; the package declaration and the original class names are inside
  the file. In the PIT runs the test classes carried suite-specific names (for example `claudeIT_1` or
  `IntegrationTest_gemini`); these names are visible as killing tests in the PIT reports.
* `selected_run1/<project>/<LLM>/Run1.java` is identical to `all_runs/<project>/<LLM>/run_1/Run1.java`.
* The developer-written tests are the tests of the original projects and are not copied here.
