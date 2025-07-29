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

## Adding DMN or YAML Tests

Place DMN and YAML files in `evaluator-test/src/test/resources/` and test classes in `evaluator-test/src/test/java/limitium/art/camunda/evaluator/`.

## Requirements
- Java 17+
- Gradle 7+

---

For more, see the code in each module. 