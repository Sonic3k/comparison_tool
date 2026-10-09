# Comparison Tool 2

API test suites organised by test case. A test case is a short flow of HTTP steps with checks; folders group test
cases and can have their own setup and teardown. A run can also call a second environment (the baseline) and compare
every response.

One jar, no database, no other server. Suites and results are plain files.

## Run

```
java -jar comparison-tool.jar                       # UI on http://localhost:8080 (opens the browser on Windows/macOS)
java -jar comparison-tool.jar my.suite.json         # UI with this suite; Save writes back, results go to results/
java -jar comparison-tool.jar run my.suite.json --env dev            # no UI, like JMeter non-GUI
java -jar comparison-tool.jar run my.suite.json --env dev --baseline uat --junit --excel
java -jar comparison-tool.jar convert old-suite.json                 # 1.x suite -> .suite.json
java -jar comparison-tool.jar help
```

`run` writes `results/<suite>_<env>_<time>.run.jsonl` next to the suite, one line per test case as it finishes.
Open that file in the UI (drag and drop) to triage it. Exit code: 0 all passed, 1 some did not, 2 the run could not start.

UI options: `--port 9090`, `--no-browser`. On a shared server set `APP_PASSWORD` to require a password, and
`CT_CI_API_KEY` to protect `POST /api/ci/run`.

## Files

- `*.suite.json` - the suite (schema `comparison-tool/suite@2`). Excel (`.xlsx`) can be opened and saved too.
- `*.run.jsonl` - a run result: header, setup/teardown results, one line per test case, summary.
- Export: Excel, Postman collection, Bruno collection (from the UI or `export --format`).

## Build

```
mvn clean package            # target/comparison-tool.jar
```

The UI is a React app in `ui/`. Its built files are committed in `src/main/resources/static`, so Maven alone builds the
jar. After changing the UI: `cd ui && npm install && npm run build`.
