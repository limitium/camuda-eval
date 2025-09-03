# Camunda DMN Evaluator (Multi-Module)

This project provides a modular Java framework for evaluating Camunda DMN decision tables and collecting test coverage, with YAML-based test definitions.

## Modules

- **evaluator**: Core DMN evaluation logic, coverage collection, and YAML reporting utilities.
- **evaluator-test**: All tests and test resources (DMN and YAML files). Depends on `evaluator`.

## Build & Test

```sh
./gradlew build
./gradlew :evaluator-test:test
```

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