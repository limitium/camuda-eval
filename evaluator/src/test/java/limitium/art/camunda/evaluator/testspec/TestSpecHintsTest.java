package limitium.art.camunda.evaluator.testspec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSpecHintsTest {

    /** Genre takes preferredGenre; Movie takes userAge plus Genre's output, so it chains. */
    private static final String CHAINED =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions id=\"Defs\" name=\"Defs\" namespace=\"http://camunda.org/schema/1.0/dmn\"\n"
            + "  xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\">\n"
            + "  <decision id=\"GenreDecision\" name=\"Genre\">\n"
            + "    <decisionTable id=\"GT\" hitPolicy=\"FIRST\">\n"
            + "      <input id=\"g_in\"><inputExpression id=\"g_ie\" typeRef=\"string\">"
            + "<text>preferredGenre</text></inputExpression></input>\n"
            + "      <output id=\"g_out\" name=\"genreRecommendation\" typeRef=\"string\"/>\n"
            + "      <rule id=\"g_r\"><inputEntry id=\"g_i1\"><text>-</text></inputEntry>"
            + "<outputEntry id=\"g_o1\"><text>\"Drama\"</text></outputEntry></rule>\n"
            + "    </decisionTable>\n"
            + "  </decision>\n"
            + "  <decision id=\"MovieDecision\" name=\"Movie\">\n"
            + "    <informationRequirement><requiredDecision href=\"#GenreDecision\"/></informationRequirement>\n"
            + "    <decisionTable id=\"MT\" hitPolicy=\"FIRST\">\n"
            + "      <input id=\"m_in1\"><inputExpression id=\"m_ie1\" typeRef=\"number\">"
            + "<text>userAge</text></inputExpression></input>\n"
            + "      <input id=\"m_in2\"><inputExpression id=\"m_ie2\" typeRef=\"string\">"
            + "<text>genreRecommendation</text></inputExpression></input>\n"
            + "      <output id=\"m_out\" name=\"movieRecommendation\" typeRef=\"string\"/>\n"
            + "      <rule id=\"m_r\"><inputEntry id=\"m_i1\"><text>-</text></inputEntry>"
            + "<inputEntry id=\"m_i2\"><text>-</text></inputEntry>"
            + "<outputEntry id=\"m_o1\"><text>\"Some Film\"</text></outputEntry></rule>\n"
            + "    </decisionTable>\n"
            + "  </decision>\n"
            + "</definitions>\n";

    @Test
    @DisplayName("a chained decision asks for its upstream inputs, not the value it declares")
    void chainedDecisionResolvesToUpstreamInputs() {
        List<TestSpecHints.DecisionHint> hints = TestSpecHints.of(CHAINED);
        assertEquals(2, hints.size());

        TestSpecHints.DecisionHint movie = byId(hints, "MovieDecision");
        assertTrue(movie.suppliedVariables.contains("userAge"), movie.suppliedVariables.toString());
        assertTrue(movie.suppliedVariables.contains("preferredGenre"),
                "the upstream decision's input is what a test actually supplies: "
                        + movie.suppliedVariables);
        assertTrue(!movie.suppliedVariables.contains("genreRecommendation"),
                "that value is computed on the way, not supplied: " + movie.suppliedVariables);
        assertEquals(java.util.Arrays.asList("GenreDecision"), movie.requires);
    }

    @Test
    @DisplayName("a chain test may also expect what the tables on the way give")
    void chainTestsCanExpectUpstreamResults() {
        TestSpecHints.DecisionHint movie = byId(TestSpecHints.of(CHAINED), "MovieDecision");
        assertEquals("GenreDecision", movie.chainOutputs.get("genreRecommendation"));
        assertTrue(movie.canExpect("genreRecommendation"));
        assertTrue(movie.canExpect("movieRecommendation"));
        assertTrue(!movie.canExpect("somethingElse"));

        String spec = "tests:\n"
                + "  - decision: MovieDecision\n"
                + "    columns: [scenario, userAge, preferredGenre, \"=> genreRecommendation\", \"=> movieRecommendation\"]\n"
                + "    rows:\n"
                + "      - [adult drama fan, 30, Drama, Drama, Some Film]\n";
        assertTrue(TestSpecValidator.validate(CHAINED, spec).stream()
                        .noneMatch(p -> p.severity == TestSpecValidator.Problem.Severity.ERROR),
                "expecting a result on the way is valid");
    }

    @Test
    void anUnchainedDecisionIsJustItsOwnInputs() {
        TestSpecHints.DecisionHint genre = byId(TestSpecHints.of(CHAINED), "GenreDecision");
        assertEquals(java.util.Arrays.asList("preferredGenre"), genre.suppliedVariables);
        assertEquals(java.util.Arrays.asList("genreRecommendation"), genre.outputs);
        assertTrue(genre.requires.isEmpty(),
                "requires lists what is evaluated on the way, which excludes the decision itself: "
                        + genre.requires);
    }

    @Test
    @DisplayName("the generated skeleton uses the variables a test supplies")
    void skeletonUsesSuppliedVariables() {
        String written = TestSpecWriter.write(TestSpecHints.skeleton(CHAINED));

        assertTrue(written.contains("- decision: MovieDecision"), written);
        assertTrue(written.contains("preferredGenre"),
                "the skeleton must be usable as written:\n" + written);
        assertTrue(written.contains("\"=> movieRecommendation\""), written);

        // And it is a valid spec, not just plausible text.
        TestSpec reread = TestSpecReader.read(written);
        assertEquals(2, reread.decisions.size());
        assertEquals(0, reread.caseCount());
    }

    @Test
    @DisplayName("a requirement cycle terminates instead of hanging the editor")
    void cyclesDoNotHang() {
        String cyclic = CHAINED.replace(
                "  <decision id=\"GenreDecision\" name=\"Genre\">\n",
                "  <decision id=\"GenreDecision\" name=\"Genre\">\n"
                        + "    <informationRequirement><requiredDecision href=\"#MovieDecision\"/></informationRequirement>\n");

        List<TestSpecHints.DecisionHint> hints = TestSpecHints.of(cyclic);
        assertEquals(2, hints.size());
    }

    private static TestSpecHints.DecisionHint byId(List<TestSpecHints.DecisionHint> hints, String id) {
        for (TestSpecHints.DecisionHint hint : hints) {
            if (id.equals(hint.decision)) {
                return hint;
            }
        }
        throw new AssertionError("no hint for " + id);
    }
}
