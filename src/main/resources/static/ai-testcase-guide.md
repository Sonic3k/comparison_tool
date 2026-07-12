# JSON Test Case Guide for AI Generation

This document describes the JSON format for creating test cases that can be imported into the API Comparison Tool. Each JSON file represents a single **TestGroup** — equivalent to one TC sheet in Excel.

---

## File Structure

```json
{
  "name": "Group Name",
  "description": "What this group tests",
  "owner": "tester@company.com",
  "enabled": true,
  "testCaseDefs": [
    { "id": "TC-U01", "name": "Create user – full flow", "description": "..." }
  ],
  "testRequests": [ ...array of test requests... ]
}
```

> **One file = one group.** To import multiple groups, create one JSON file per group and import them separately.

### Test Case vs Test Request

- A **Test Request** is one executable HTTP call (the objects in `testRequests`).
- A **Test Case** is a logical case — it maps 1-1 to a manual regression test case and consists of **one or more** test requests. Requests reference their test case via `testCaseId`.
- `testCaseDefs` is the registry of test cases in the group (`id`, `name`, `description`).
- Both `testCaseDefs` and `testCaseId` are **optional**: if omitted, every request becomes its own test case (`testCaseId` = request `id`) and defs are auto-created on import.
- Requests sharing the same `testCaseId` **always execute sequentially in declared order** (so `extractVariables` chains inside a test case work), while different test cases can run in parallel.
- Legacy files using the old key `testCases` (JSON) or `<testCase>` elements (XML) still import fine.

Example — one test case made of two requests:

```json
"testCaseDefs": [
  { "id": "SO-01", "name": "Submit order end-to-end", "description": "Create then verify" }
],
"testRequests": [
  { "id": "SO-01_1", "testCaseId": "SO-01", "name": "Create order", "method": "POST",
    "endpoint": "/orders", "verificationMode": "none", "phase": "test",
    "jsonBody": "{\"item\":\"abc\"}", "extractVariables": "orderId=$.id", "enabled": true, "result": null },
  { "id": "SO-01_2", "testCaseId": "SO-01", "name": "Verify order", "method": "GET",
    "endpoint": "/orders/{{orderId}}", "verificationMode": "comparison", "phase": "test",
    "enabled": true, "result": null }
]
```

---

## Test Request Fields

### Required Fields

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique within the group. Convention: `TC-U01`, `SO-01_1`, `GS-01` |
| `name` | string | Short display name |
| `enabled` | boolean | `true` to run, `false` to skip |
| `verificationMode` | string | See table below |
| `phase` | string | `setup` / `test` / `teardown` |
| `method` | string | `GET` `POST` `PUT` `PATCH` `DELETE` |
| `endpoint` | string | Path only — e.g. `/users/1`. Base URL comes from the environment config |

### Optional Fields

| Field | Type | Default | Description |
|---|---|---|---|
| `testCaseId` | string | request `id` | Logical test case this request belongs to. Give several requests the same value to form a multi-request test case |
| `description` | string | `""` | Purpose of the request |
| `queryParams` | array | `[]` | URL query parameters — array of `{key, value}` objects |
| `formParams` | array | `[]` | Form-encoded body params — array of `{key, value}` objects |
| `jsonBody` | string | `""` | Raw JSON string for request body |
| `headers` | string | `""` | Custom headers, one per line: `Key: Value` |
| `author` | string | `""` | Author email |
| `extractVariables` | string | `""` | Extract values from response — see Variable Extraction section |
| `comparisonConfig` | object | `null` | Per-TC comparison overrides — see Comparison Config section |
| `automationConfig` | object | `null` | Automation assertions — required when `verificationMode` is `automation` or `both` |
| `result` | object | `null` | Leave as `null` — tool populates this after execution |

---

## verificationMode Values

| Value | Behavior |
|---|---|
| `comparison` | Call both source and target, diff the responses |
| `automation` | Call target only, run assertions defined in `automationConfig` |
| `both` | Call both environments, diff responses AND run assertions on target |
| `none` | Call target only, no verification — used for setup/teardown TCs that just need to run |

---

## phase Values

| Value | Execution Order | Concurrency |
|---|---|---|
| `setup` | Runs first within the group | Always sequential |
| `test` | Runs after all setup TCs | Parallel or sequential (per suite config) |
| `teardown` | Runs last within the group | Always sequential, always runs even if tests fail |

> **Global groups:** Groups named starting with `Global Setup` or `Global Teardown` run before/after all other groups, and their extracted variables are available suite-wide.

---

## Examples

### 1. Simple Comparison TC

Calls both source and target, diffs the JSON response.

```json
{
  "id": "TC-U01",
  "name": "Get User by ID",
  "description": "Retrieve user profile — verify field parity between legacy and modernized",
  "enabled": true,
  "verificationMode": "comparison",
  "phase": "test",
  "method": "GET",
  "endpoint": "/users/1",
  "queryParams": [],
  "formParams": [],
  "jsonBody": "",
  "headers": "",
  "author": "tester@company.com",
  "extractVariables": "",
  "comparisonConfig": null,
  "automationConfig": null,
  "result": null
}
```

### 2. TC with Query Parameters

```json
{
  "id": "TC-U06",
  "name": "List Users with Pagination",
  "description": "Paginated user list — check pagination key differences",
  "enabled": true,
  "verificationMode": "comparison",
  "phase": "test",
  "method": "GET",
  "endpoint": "/users",
  "queryParams": [
    { "key": "page",  "value": "1" },
    { "key": "limit", "value": "5" }
  ],
  "formParams": [],
  "jsonBody": "",
  "headers": "",
  "author": "tester@company.com",
  "extractVariables": "",
  "comparisonConfig": null,
  "automationConfig": null,
  "result": null
}
```

### 3. TC with JSON Body (POST)

```json
{
  "id": "TC-U02",
  "name": "Create User",
  "description": "Create a new user and compare the response structure",
  "enabled": true,
  "verificationMode": "comparison",
  "phase": "test",
  "method": "POST",
  "endpoint": "/users",
  "queryParams": [],
  "formParams": [],
  "jsonBody": "{\"name\": \"John Doe\", \"email\": \"john.doe@example.com\", \"role\": \"user\"}",
  "headers": "",
  "author": "tester@company.com",
  "extractVariables": "",
  "comparisonConfig": null,
  "automationConfig": null,
  "result": null
}
```

### 4. Automation TC (target only, with assertions)

Calls only the target environment and validates the response against explicit assertions.

```json
{
  "id": "TC-U09",
  "name": "Update Status — Not Implemented",
  "description": "Modernized endpoint returns 404 NOT_IMPLEMENTED for this operation",
  "enabled": true,
  "verificationMode": "automation",
  "phase": "test",
  "method": "PATCH",
  "endpoint": "/users/1/status",
  "queryParams": [],
  "formParams": [],
  "jsonBody": "{\"status\": \"inactive\"}",
  "headers": "",
  "author": "tester@company.com",
  "extractVariables": "",
  "comparisonConfig": null,
  "automationConfig": {
    "expectedStatus": "404",
    "expectedBody": "$.error exists\n$.code == \"NOT_IMPLEMENTED\"",
    "expectedHeaders": "",
    "maxResponseTime": 0
  },
  "result": null
}
```

### 5. Both Mode TC (comparison + assertions)

Compares source vs target AND runs assertions on the target response.

```json
{
  "id": "TC-A03",
  "name": "Login Unknown User",
  "description": "Both environments must return 401 with INVALID_CREDENTIALS code",
  "enabled": true,
  "verificationMode": "both",
  "phase": "test",
  "method": "POST",
  "endpoint": "/auth/login",
  "queryParams": [],
  "formParams": [],
  "jsonBody": "{\"email\": \"nobody@unknown.com\", \"password\": \"wrong\"}",
  "headers": "",
  "author": "tester@company.com",
  "extractVariables": "",
  "comparisonConfig": null,
  "automationConfig": {
    "expectedStatus": "401",
    "expectedBody": "$.error exists\n$.code == \"INVALID_CREDENTIALS\"",
    "expectedHeaders": "",
    "maxResponseTime": 0
  },
  "result": null
}
```

### 6. Comparison Override TC

Per-TC comparison config that overrides the suite-level settings.

```json
{
  "id": "TC-A02",
  "name": "Login Suspended User",
  "description": "Legacy returns 403, modernized returns 500 — compare error responses",
  "enabled": true,
  "verificationMode": "comparison",
  "phase": "test",
  "method": "POST",
  "endpoint": "/auth/login",
  "queryParams": [],
  "formParams": [],
  "jsonBody": "{\"email\": \"fiona.davis@example.com\", \"password\": \"any\"}",
  "headers": "",
  "author": "tester@company.com",
  "extractVariables": "",
  "comparisonConfig": {
    "ignoreFieldsRaw": "",
    "caseSensitive": false,
    "ignoreArrayOrder": false,
    "numericTolerance": 0.0,
    "compareErrorResponses": true
  },
  "automationConfig": null,
  "result": null
}
```

### 7. Setup TC with Variable Extraction

Runs first in the group, extracts a value from the response for later TCs to use.

```json
{
  "id": "SU-01",
  "name": "[SETUP] Create Test User",
  "description": "Create a user to use in subsequent test cases",
  "enabled": true,
  "verificationMode": "none",
  "phase": "setup",
  "method": "POST",
  "endpoint": "/users",
  "queryParams": [],
  "formParams": [],
  "jsonBody": "{\"name\": \"Test User\", \"email\": \"testuser@example.com\", \"role\": \"user\"}",
  "headers": "",
  "author": "tester@company.com",
  "extractVariables": "setupUserId=$.user_id",
  "comparisonConfig": null,
  "automationConfig": null,
  "result": null
}
```

### 8. TC using an Extracted Variable

References a variable extracted in a previous TC using `{{variableName}}` syntax.

```json
{
  "id": "TC-U03",
  "name": "Get Created User",
  "description": "Fetch the user created in setup — uses extracted ID",
  "enabled": true,
  "verificationMode": "comparison",
  "phase": "test",
  "method": "GET",
  "endpoint": "/users/{{setupUserId}}",
  "queryParams": [],
  "formParams": [],
  "jsonBody": "",
  "headers": "",
  "author": "tester@company.com",
  "extractVariables": "",
  "comparisonConfig": null,
  "automationConfig": null,
  "result": null
}
```

### 9. Teardown TC

Runs last in the group, cleans up test data. Always runs even if test TCs failed.

```json
{
  "id": "TU-01",
  "name": "[TEARDOWN] Delete Test User",
  "description": "Clean up the user created in setup",
  "enabled": true,
  "verificationMode": "none",
  "phase": "teardown",
  "method": "DELETE",
  "endpoint": "/users/{{setupUserId}}",
  "queryParams": [],
  "formParams": [],
  "jsonBody": "",
  "headers": "",
  "author": "tester@company.com",
  "extractVariables": "",
  "comparisonConfig": null,
  "automationConfig": null,
  "result": null
}
```

---

## Variable Extraction DSL

`extractVariables` extracts values from the response body and stores them as variables for subsequent TCs.

**Format:** `varName=$.jsonPath` — comma-separated for multiple extractions.

```
setupUserId=$.user_id
setupOrderId=$.order_id, setupTotal=$.total_amount
globalToken=$.access_token
```

**JsonPath support:** dot notation and array index access.

```
$.user_id              → top-level field
$.address.city         → nested field
$.items[0].product_id  → array element field
```

**Variable scope:**
- Variables extracted in a `test` or `teardown` phase TC are available within the same group.
- Variables extracted in a **Global Setup** group (group name starts with `Global Setup`) are available across all groups.

---

## Assertion DSL (`automationConfig.expectedBody`)

One assertion per line. All assertions must pass for the TC to pass.

### Supported operators

| Syntax | Meaning | Example |
|---|---|---|
| `$.field exists` | Field is present in response | `$.user_id exists` |
| `$.field == value` | Field equals exact value | `$.status == "active"` |
| `$.field != value` | Field does not equal value | `$.role != "admin"` |
| `$.field > value` | Numeric greater than | `$.total > 0` |
| `$.field < value` | Numeric less than | `$.price < 1000` |
| `$.field >= value` | Numeric greater than or equal | `$.count >= 1` |
| `$.field contains value` | String contains substring | `$.message contains "success"` |
| `$.array[*] contains value` | Any element in array contains value | `$.tags[*] contains "electronics"` |
| `$.array[key=val].field == x` | Filter array by key, assert field | `$.items[status=active].count == 5` |

### Examples

```
$.status == 200
$.data.user_id exists
$.data.email == "john@example.com"
$.pagination.total > 0
$.items[*] contains "laptop"
$.error == "NOT_FOUND"
$.code == "INVALID_CREDENTIALS"
```

---

## comparisonConfig Fields

All fields are optional — `null` means inherit from suite-level settings.

| Field | Type | Description |
|---|---|---|
| `ignoreFieldsRaw` | string | Comma-separated field names to ignore during comparison. Added on top of suite-level ignore list |
| `caseSensitive` | boolean | Whether string comparison is case-sensitive |
| `ignoreArrayOrder` | boolean | Whether array element order matters |
| `numericTolerance` | number | Tolerance for floating point comparison (e.g. `0.01`) |
| `compareErrorResponses` | boolean | `true` = compare 5xx responses normally. `false` = mark as ERROR and skip diff |

---

## Common Mistakes

**Wrong — `result` has data (AI should leave it null):**
```json
"result": { "status": "passed", ... }
```
✅ Correct: `"result": null`

**Wrong — endpoint includes base URL:**
```json
"endpoint": "https://api.company.com/users/1"
```
✅ Correct: `"endpoint": "/users/1"`

**Wrong — `jsonBody` is an object:**
```json
"jsonBody": { "name": "John" }
```
✅ Correct: `"jsonBody": "{\"name\": \"John\"}"` (must be a JSON string)

**Wrong — `queryParams` as a string:**
```json
"queryParams": "page=1&limit=10"
```
✅ Correct:
```json
"queryParams": [
  { "key": "page",  "value": "1" },
  { "key": "limit", "value": "10" }
]
```

**Wrong — using `automationConfig` with `verificationMode: "comparison"`:**
```json
"verificationMode": "comparison",
"automationConfig": { "expectedStatus": "200", ... }
```
✅ Correct: use `"verificationMode": "automation"` or `"both"` when specifying `automationConfig`

**Wrong — `extractVariables` on a `comparison` TC (variables only populated from target call in `none`/`automation` mode):**

Use `verificationMode: "none"` for setup TCs that need to extract variables.

---

## Prompt Template for AI

Use this as a system prompt or prefix when asking AI to generate test cases:

```
Generate a JSON TestGroup for the API Comparison Tool. The output must be a single valid JSON object with this structure:

{
  "name": "<group name>",
  "description": "<description>",
  "owner": "<email>",
  "enabled": true,
  "testCaseDefs": [ ...array of {id, name, description} test case entries... ],
  "testRequests": [ ...array of test request objects... ]
}

Rules:
- Always set "result": null
- A test request may set "testCaseId" to group several requests into ONE logical test case; list that id in "testCaseDefs". Requests of the same test case run sequentially in declared order
- If a request has no "testCaseId", it is its own test case (defs auto-created)
- "endpoint" is a path only, never a full URL
- "jsonBody" must be a JSON string (escaped), not an object
- "queryParams" and "formParams" are arrays of {"key": "...", "value": "..."} objects
- Use "verificationMode": "none" for setup/teardown TCs
- Use "verificationMode": "comparison" to diff source vs target
- Use "verificationMode": "automation" for assertion-only TCs (target only)
- Use "verificationMode": "both" to diff AND assert
- "automationConfig" is required when verificationMode is "automation" or "both"
- "comparisonConfig" is null unless you need per-TC overrides
- "extractVariables" format: "varName=$.jsonPath" (comma-separated for multiple)
- Variable substitution in endpoint/body: {{variableName}}
- Phase "setup" runs first (sequential), "test" runs in the middle, "teardown" runs last (always runs)
```
