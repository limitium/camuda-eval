# Camunda DMN Evaluator

This project provides a complete web application for evaluating Camunda DMN decision tables with a Vue.js frontend and Java backend, containerized using Jib.

## Architecture

- **Frontend**: Vue.js application with DMN editor and test runner
- **Backend**: Java application serving both API and static frontend assets
- **Containerization**: Single container using Jib (no Docker files needed)

## Quick Start

### Build and Run

```sh
# Build complete application (frontend + backend container)
make build-all

# Run the containerized application
make run

# View logs
make logs

# Stop the application
make stop
```

### Custom Base Path Configuration

Deploy the app under a path behind the same domain using a single runtime variable `BASE_URL` (no slashes). For example, `BASE_URL=myapp` serves the app under `http://domain/myapp/` and the API under `http://domain/myapp/api/...`.

```sh
# Build once (no BASE_URL needed at build time)
make build-all

# Run with different base paths at runtime
make run BASE_URL=myapp
make run BASE_URL=another-app

# Or use the example deployment script
./example-deploy.sh
```

**Environment Variables:**
- `BASE_URL`: Base path after domain for both frontend and backend (e.g., `myapp`). This is a **runtime-only** configuration.

The backend automatically:
1. Sets `server.servlet.context-path=/${BASE_URL}` 
2. Injects the base URL into the frontend HTML at runtime
3. The frontend reads the base URL from `window.BASE_URL` and configures axios accordingly

### Development

```sh
# Start development environment
make dev

# This will show instructions for running frontend and backend separately
```

## Build Commands

```sh
# Build frontend only
make build-frontend

# Build backend container only
make build-backend

# Build complete application
make build-all

# Clean up build artifacts
make clean
```

## API Endpoints

The backend serves both the API and static frontend:

- `GET /` - Frontend application
- `GET /dmn` - List DMN files
- `POST /dmn` - Upload DMN file
- `GET /yaml` - List YAML test files
- `POST /runs` - Run tests
- `GET /runs/{id}/status` - Get test run status
- `GET /api/history` - Commits that changed a domain, table, rule, column or cell (see History below)

## Directory Structure

- `evaluator/` - Core library
- `evaluator-test/` - Tests and test resources
- `build.gradle` - Root build config (minimal)
- `.gitignore` - Standard Java/Gradle ignores

## Usage Examples

### DmnEvaluator - Core Evaluation

The `DmnEvaluator` class provides methods to load and evaluate DMN decision tables with support for typed outputs.

#### Basic Usage

```java
import limitium.art.camunda.evaluator.DmnEvaluator;
import org.camunda.bpm.engine.variable.Variables;
import java.nio.file.Paths;

// Load DMN from file
DmnEvaluator evaluator = new DmnEvaluator();
DmnEvaluator.DmnDecisionEvaluator decision = evaluator.loadRule(
    Paths.get("src/main/resources/my-decision.dmn"), 
    "MyDecision"
);

// Evaluate with variables
VariableMap vars = Variables.createVariables()
    .putValue("age", 25)
    .putValue("country", "US");

// Get raw result
DmnDecisionResult result = decision.evaluate(vars);
String output = result.getSingleResult().getSingleEntry();
```

#### Typed Evaluation Methods

```java
// String output
String stringResult = decision.evaluateToString(vars);

// Boolean output (supports boolean values and "true"/"false" strings)
Boolean boolResult = decision.evaluateToBoolean(vars);

// Number output (supports numeric values and numeric strings)
Number numResult = decision.evaluateToNumber(vars);

// Using Map instead of VariableMap
Map<String, Object> inputMap = Map.of("age", 25, "country", "US");
String result = decision.evaluateToString(inputMap);
```

#### String-based DMN Loading

```java
String dmnXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
    "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\">" +
    "  <decision id=\"MyDecision\">" +
    "    <decisionTable hitPolicy=\"FIRST\">" +
    "      <input id=\"age\">" +
    "        <inputExpression typeRef=\"number\"><text>age</text></inputExpression>" +
    "      </input>" +
    "      <output id=\"result\" typeRef=\"string\"/>" +
    "      <rule id=\"rule1\">" +
    "        <inputEntry><text>age >= 18</text></inputEntry>" +
    "        <outputEntry><text>\"Adult\"</text></outputEntry>" +
    "      </rule>" +
    "    </decisionTable>" +
    "  </decision>" +
    "</definitions>";

DmnEvaluator.DmnDecisionEvaluator decision = evaluator.loadRuleFromString(dmnXml, "MyDecision");
```

#### Coverage Collection

```java
// Enable coverage collection
evaluator.setCollectCoverage(true);

// Run evaluations...
decision.evaluate(vars);

// Get coverage events
List<DmnEvaluator.CoverageEvent> events = evaluator.getCoverageEvents();
for (DmnEvaluator.CoverageEvent event : events) {
    System.out.println("Decision: " + event.decisionKey + 
                      ", Rule: " + event.ruleId + 
                      ", Parameters: " + event.parameters);
}
```

### DmnYamlTestFactory - YAML-based Testing

The `DmnYamlTestFactory` creates JUnit 5 dynamic tests from YAML test specifications.

#### YAML Test Format

Create a YAML file (e.g., `my-decision.yaml`) alongside your DMN file:

```yaml
tests:
  - description: "Adult user should get adult recommendation"
    decision: "AgeDecision"
    in:
      age: 25
      country: "US"
    out: "Adult"
    
  - description: "Minor user should get minor recommendation"
    decision: "AgeDecision"
    in:
      age: 16
      country: "US"
    out: "Minor"
    
  - description: "Boolean decision test"
    decision: "EligibleDecision"
    in:
      age: 21
      hasLicense: true
    out: true
    
  - description: "Numeric decision test"
    decision: "ScoreDecision"
    in:
      points: 85
    out: 100
```

#### JUnit 5 Integration

```java
import limitium.art.camunda.evaluator.junit.DmnYamlTestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.nio.file.Paths;
import java.util.stream.Stream;

public class MyDmnTests {
    
    @TestFactory
    Stream<DynamicTest> dmnTests() throws Exception {
        return DmnYamlTestFactory.from(Paths.get("src/test/resources"));
    }
    
    // Or with separate DMN and YAML directories
    @TestFactory
    Stream<DynamicTest> dmnTestsSeparate() throws Exception {
        return DmnYamlTestFactory.from(
            Paths.get("src/test/resources/dmn"),
            Paths.get("src/test/resources/yaml")
        );
    }
}
```

#### Supported Output Types

The factory supports multiple output types in YAML:

```yaml
tests:
  # String output
  - decision: "StringDecision"
    in: { input: "value" }
    out: "result"
    
  # Boolean output
  - decision: "BooleanDecision"
    in: { flag: true }
    out: true
    
  # Number output (integer)
  - decision: "NumberDecision"
    in: { value: 42 }
    out: 100
    
  # Number output (decimal)
  - decision: "DecimalDecision"
    in: { value: 1.5 }
    out: 3.14
```

#### Coverage Reports

When using `DmnYamlTestFactory`, coverage reports are automatically generated at JVM shutdown:

```
=== DMN Test Coverage Report (YAML) ===
src/test/resources/my-decision.dmn:
  AgeDecision:
    totalRules: 2
    coveredRules: 2
    coverage: 1.0
    uncoveredRules: []
  _summary:
    totalRules: 2
    coveredRules: 2
    coverage: 1.0
=== End of Coverage Report ===
```

## Adding DMN or YAML Tests

Place DMN and YAML files in `evaluator-test/src/test/resources/` and test classes in `evaluator-test/src/test/java/limitium/art/camunda/evaluator/`.

## Requirements
- Java 8+
- Gradle 7+

## Dependencies

- Camunda DMN Engine 7.17.0
- JUnit 5.10.0
- SnakeYAML 2.2

---

For more examples, see the test files in the `evaluator-test` module. 
## Demo with Docker

`docker compose up --build` starts dev and prod, which meet only in one git remote. dev authors,
tests, tags releases and tries rules. prod changes nothing: it shows what runs and any commit, runs
tests and tries values, and deploys release tags — every other write is refused with
403 (`DeployOnlyGuard`), and it only reads the git remote.

| | dev | prod |
|---|---|---|
| UI | http://localhost:7080 (as alice) | http://localhost:7081 (as carol, deploy only) |
| brokers | `kafka-dev:9092` (host: `localhost:19092`) | `kafka-prod:9092` (host: `localhost:29092`) |
| topics | `dmn-emea-street-dev`, `dmn-amer-street-dev` | `dmn-emea-street-prod`, `dmn-amer-street-prod` |
| drafts | can be tried here | not allowed |

Each instance is configured by plain environment variables (see `docker/entrypoint.sh`):
`RULES_ENV` (tier), `RULES_ROLE` (author | deployer), `RULES_ALLOW_DRAFT`, `KAFKA_BOOTSTRAP`,
`RULES_APPS` (`name=file,file[@topic] …`), `RULES_TOPIC_TEMPLATE` (default `dmn-{app}-{env}`),
`KAFKA_PROPERTIES` (extra client settings), `GIT_REMOTE_URL`, `GIT_USER`, `GIT_TOKEN`, `RULES_USER`.
Both applications read one domain, `fee`, with two tables: Fee Code (country, market and amount
give a fee code and the calculator to use) and Fee Calculator (the fee amount from the calculator and
the amount), plus their test cases. The shared git repository is seeded with it on first start from
`docker/seed` (`docker/seed.sh`); put other `dmn/*.dmn` and `yaml/*.yaml` files there to start
with different rules. The topics are created compacted with one partition by `docker/create-topics.sh`. Set `RULES_APPS`
in a `.env` file to change the applications for both tiers; `docker compose down -v` starts afresh.
The host ports are variables too (`DEV_PORT`, `PROD_PORT`, `KAFKA_DEV_PORT`, `KAFKA_PROD_PORT`, and
`RULES_IMAGE` for the image's name), so a second stack can run beside this one under another `-p`.

## End-to-end check before a release

`e2e/run.sh` builds the image from the working tree and starts a fresh dev + prod stack beside the
demo (project `camunda-e2e`, UIs on :7180 and :7181, its own git remote, brokers and volumes — the
demo is not touched). It then drives the life of a rule through the UI in headless Chrome
(`e2e/lifecycle.mjs`): start a work space, change a result and add a rule, see a test fail and fix
it, save, ask History for the cell (and check that clicking other cells reads nothing), commit, publish a draft, merge into main, create `release/1.0`,
tag `v1.0.0`, deploy it on dev, then deploy it on prod and open its history there. Each step is
checked where it lands — the remote repository, the topics, the API — and any page error, console
error or failed request fails the run. `e2e/layout.mjs` then opens every screen at 1440, 1024 and
768 px wide and fails on a page that scrolls sideways, text cut off without an ellipsis, a control
covered by another or without a name, or an action whose label wraps.

```bash
e2e/run.sh                 # screenshots and results in e2e/out; the stack is removed afterwards
KEEP=1 e2e/run.sh          # leaves it up to look around
```

Needs Docker, Node and Google Chrome (`CHROME=/path/to/chrome` for another location).

## History, at any grain

**History** on a table (in a work space, or opened at any commit) shows beside the grid the commits
that changed one thing, newest first, each with its author, date and the value before and after:

| level | what it shows | shown per commit |
|---|---|---|
| Cell | one rule's one condition or result | `"US-MAIN"` → `"US-NMS"` |
| Rule | every cell of that rule, its note, its place among the rules | the cells that changed |
| Column | the column's heading and its cell in every rule | each rule's cell that changed |
| Table | everything in the table | each rule that changed, hit policy, columns |
| Domain | every table and the test cases | the same, per table, and whether the tests changed |

It is read only on request: opening it shows the cell picked in the grid (or the table, with none
picked), and after that only a level pressed or **Show the history of …** for a newly picked cell
reads again — clicking about the grid to edit it reads nothing, and the panel keeps what it shows,
outlined in the grid. In a work space, saved but uncommitted changes come first. Identity is the DMN id, which the editor keeps, so a rule is the
same rule however it is edited or moved; a rule that only shifted because another came or went did
not move. A merge that only brought a change in — a landing, a pull — is not listed: the commit that
made the change is, under its own author. A merge is listed only when it produced something neither
side had.

```
GET /api/history?domain=fee&ws=feature/alice/x&decision=FeeCode&rule=FeeCode_r2&column=FeeCode_out_feeCode
GET /api/history?domain=fee&rev=v1.1.0&decision=FeeCode          # a table, up to a version
```

## Assistant (Qwen)

The **Assistant** button in the top band opens a side panel about the screen that is open. It
answers questions (how to deploy here, where a commit goes, tag or release branch, what a rule does),
and does things for the person — **one action at a time, each shown as a card the person confirms**;
nothing happens until they press its button, which runs the same code as the screen's own button:

| screen | actions |
|---|---|
| every screen | press one of its bottom-bar buttons (Save, Run the tests, Merge…); open another screen |
| an application | deploy a tag; redeploy what runs; put the last release back; start a work space from what runs |
| Releases | tag a release branch's newest commit; create a release branch from main or a release branch |
| Commit changes | commit, with a message (or only put the message in the box) |
| Try in dev | publish the work space to an application |
| a domain's tables | add a rule; add a test case; change one cell; remove one rule (on screen, unsaved, in blue) |

Also:
- **Review my changes** (work space, commit, test, merge screens): the app reviews the uncommitted
  changes — or, with none, the commits not yet merged — rule by rule: what changed, test cases that
  fail, new or changed rules no test case reaches, and new or changed rules that never apply (an
  earlier rule takes their cases under first-match) or overlap another (under unique). The findings
  are shown as they are; the model only sums them up.
- **Write it for me** next to the commit message: the model writes the first line from what changed;
  the app lists the exact changes and the test result under it, and keeps what the person had written
  as the why.
- **DMN syntax** on a table: the cheat sheet — 153 entries in four tabs with a filter, every example
  run on this engine (Camunda 7.17, FEEL 1.13), and what it refuses or quietly gets wrong.

It talks to any server with the OpenAI chat-completions API (vLLM, llama.cpp, Ollama, LM Studio)
running Qwen; without a URL there is no assistant and no button.

| setting | env (docker) | |
|---|---|---|
| `dmn.assistant.url` | `ASSISTANT_URL` | e.g. `http://qwen:8000/v1` (`/chat/completions` is added) |
| `dmn.assistant.model` | `ASSISTANT_MODEL` | the model's name on that server, default `qwen2.5:7b` |
| `dmn.assistant.api-key` | `ASSISTANT_KEY` | sent as a bearer token, if the server wants one |
| `dmn.assistant.timeout-seconds` | | default 120 |
| `dmn.assistant.instructions-dir` | | a folder whose files replace the built-in instructions of the same name, re-read on every question |

How the model is kept to its job (`backend/.../assistant`, `controller/AssistantController`):

- **Instructions live on the server** (`backend/src/main/resources/assistant`): `system.md` (what the
  app is, how to answer), one guide per screen in `pages/`, `format.md` (the reply), `actions.md`
  (one section per action — a screen's prompt holds only the actions that screen allows), and on a
  table's screen `table-proposals.md` and `feel.md` (verified FEEL). A screen sends facts, never
  instructions; its one-sentence situation (`now`) and the lines of any rule the question names go
  with the question.
- **Actions are enforced, not only asked for** (`Actions`, `ProposalCheck`): an action the screen does
  not have is dropped; every action is checked against what the screen shows (the tag exists, the
  version is free, the button can be pressed, the branch's newest commit is the one shown). Every
  cell of a rule is evaluated by the engine, names that do not exist are refused (the engine reads
  `JP-LRG` as JP minus LRG and quietly gives nothing), and a new rule is placed in the table and run:
  under first-match one an earlier rule shadows is refused, under unique one that overlaps another. A
  test case is run and says what it gives today and which rule it reaches. What is refused goes back
  to the model with the reasons, up to twice.
- **The person confirms**: the card says what will happen in the app's words; destructive ones
  (deploy, tag, publish, put back) are marked. When confirmed, the screen checks once more that
  nothing moved since (the cell is still what the card showed, the branch's newest commit is the same).

To see exactly what the model is told and says:
`logging.level.limitium.art.camunda.backend.controller.AssistantController=DEBUG` and
`logging.level.limitium.art.camunda.backend.assistant.ChatModel=DEBUG`.

In the demo, with Ollama on the host (`ollama pull qwen2.5:7b`, served with `OLLAMA_CONTEXT_LENGTH=16384`
— the table screen's instructions are about 5,000 tokens):

    ASSISTANT_URL=http://host.docker.internal:11434/v1 docker compose up -d
