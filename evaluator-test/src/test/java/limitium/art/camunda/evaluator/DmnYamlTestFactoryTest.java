package limitium.art.camunda.evaluator;

import limitium.art.camunda.evaluator.junit.DmnYamlTestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DmnYamlTestFactoryTest {
    @TestFactory
    Stream<DynamicTest> dmnTests() throws Exception {
        Stream<DynamicTest> testStream = DmnYamlTestFactory.from(
                Paths.get("src/test/resources"),
                Paths.get("src/test/resources"));

        List<DynamicTest> testStreamList = testStream.collect(Collectors.toList());
        assertEquals(4, testStreamList.size(), "Should be 4 dynamic tests");

        return testStreamList.stream();
    }
}
