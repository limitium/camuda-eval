package limitium.art.camunda.backend.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the assistant may propose on a screen, checked against what that screen shows. */
class ActionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ProposalCheck.Outcome check(String context, String proposal) throws Exception {
        return new Actions(JSON.readTree(context), "alice").check(JSON.readTree(proposal));
    }

    private static final String DEPLOY = "{\"application\":\"emea-street\",\"runsNow\":\"nothing deployed yet\",\"tags\":[\"v1.0.0\",\"v1.0.1\"],"
            + "\"runsSomething\":true,\"runsRelease\":true,\"draftRuns\":false,\"canStartWorkSpace\":true,\"buttons\":[\"Deploy a tag\"],\"places\":[\"Releases\"]}";

    @Test
    @DisplayName("each screen allows its own actions, and every screen its buttons and places")
    void allowedPerScreen() {
        assertTrue(Actions.allowed("application").containsAll(java.util.List.of("deploy", "redeploy", "restore_release", "start_work_space", "press", "go")));
        assertTrue(Actions.allowed("table").contains("change_cell"));
        assertFalse(Actions.allowed("releases").contains("deploy"));
        assertFalse(Actions.allowed("applications").contains("add_rule"));
        assertEquals(java.util.List.of("press", "go"), Actions.allowed(null));
    }

    @Test
    @DisplayName("a deploy names a tag the screen lists, in any spelling, and says what it changes")
    void deploy() throws Exception {
        ProposalCheck.Outcome ok = check(DEPLOY, "{\"type\":\"deploy\",\"tag\":\"1.0.1\"}");
        assertTrue(ok.ok(), ok.problems.toString());
        assertEquals("v1.0.1", ok.proposal.path("tag").asText());
        JsonNode card = ok.proposal.path("card");
        assertEquals("Deploy v1.0.1 to emea-street", card.path("confirm").asText());
        assertTrue(card.path("loud").asBoolean());
        assertTrue(card.path("lines").toString().contains("Every test runs at v1.0.1 first"));

        String unknown = check(DEPLOY, "{\"type\":\"deploy\",\"tag\":\"v2.0.0\"}").problems.toString();
        assertTrue(unknown.contains("`v1.0.0`, `v1.0.1`"), unknown);
        String none = check("{\"tags\":[]}", "{\"type\":\"deploy\",\"tag\":\"v1.0.0\"}").problems.toString();
        assertTrue(none.contains("no tag to deploy here yet"), none);
    }

    @Test
    @DisplayName("redeploy, put back and start a work space only when what runs allows them")
    void runningOnly() throws Exception {
        assertTrue(check(DEPLOY, "{\"type\":\"redeploy\"}").ok());
        assertFalse(check(DEPLOY, "{\"type\":\"restore_release\"}").ok());
        ProposalCheck.Outcome start = check(DEPLOY, "{\"type\":\"start_work_space\",\"name\":\"XETRA fees!\"}");
        assertEquals("xetra-fees", start.proposal.path("name").asText());
        assertTrue(start.proposal.path("card").path("lines").toString().contains("hotfix/alice/xetra-fees"), start.proposal.toString());
        assertFalse(check("{\"canStartWorkSpace\":false}", "{\"type\":\"start_work_space\",\"name\":\"x\"}").ok());
    }

    private static final String RELEASES = "{\"branchTips\":{\"main\":{\"tip\":\"4b91586aaaa\",\"nextBranch\":\"release/1.1\",\"tags\":[]},"
            + "\"release/1.0\":{\"tip\":\"bb1bb8cbbbb\",\"tipMessage\":\"Merge x\",\"nextVersion\":\"v1.0.0\",\"nextBranch\":\"release/1.1\",\"tags\":[\"v0.9.0\"]}}}";

    @Test
    @DisplayName("a tag goes on a release branch's newest commit, as a version not taken; never on main")
    void tag() throws Exception {
        ProposalCheck.Outcome ok = check(RELEASES, "{\"type\":\"tag\",\"branch\":\"release/1.0\",\"version\":\"1.0.0\"}");
        assertTrue(ok.ok(), ok.problems.toString());
        assertEquals("bb1bb8cbbbb", ok.proposal.path("commit").asText());
        assertEquals("Tag as v1.0.0", ok.proposal.path("card").path("confirm").asText());
        assertFalse(check(RELEASES, "{\"type\":\"tag\",\"branch\":\"main\",\"version\":\"1.0.0\"}").ok());
        assertFalse(check(RELEASES, "{\"type\":\"tag\",\"branch\":\"release/1.0\",\"version\":\"one\"}").ok());
        String taken = check(RELEASES, "{\"type\":\"tag\",\"branch\":\"release/1.0\",\"version\":\"v0.9.0\"}").problems.toString();
        assertTrue(taken.contains("taken already") && taken.contains("v1.0.0"), taken);
    }

    @Test
    @DisplayName("a release branch is created from main or a release branch, by its number alone")
    void createBranch() throws Exception {
        ProposalCheck.Outcome ok = check(RELEASES, "{\"type\":\"create_branch\",\"from\":\"main\",\"number\":\"release/1.1\"}");
        assertTrue(ok.ok(), ok.problems.toString());
        assertEquals("1.1", ok.proposal.path("number").asText());
        assertEquals("4b91586aaaa", ok.proposal.path("commit").asText());
        assertFalse(check(RELEASES, "{\"type\":\"create_branch\",\"from\":\"feature/alice/x\",\"number\":\"1.1\"}").ok());
        assertFalse(check(RELEASES, "{\"type\":\"create_branch\",\"from\":\"main\",\"number\":\"next\"}").ok());
    }

    @Test
    @DisplayName("a commit needs changes and a message; a publish an application of the screen")
    void commitAndPublish() throws Exception {
        assertTrue(check("{\"hasChanges\":true,\"commitIsMadeOnBranch\":\"feature/alice/x\"}", "{\"type\":\"commit\",\"message\":\"Fee Code: x\"}").ok());
        assertFalse(check("{\"hasChanges\":false}", "{\"type\":\"commit\",\"message\":\"Fee Code: x\"}").ok());
        assertFalse(check("{\"hasChanges\":true}", "{\"type\":\"commit\",\"message\":\" \"}").ok());
        // "Commit it": the message in the box.
        assertEquals("Fee Code: y", check("{\"hasChanges\":true,\"messageInBox\":\"Fee Code: y\"}", "{\"type\":\"commit\"}").proposal.path("message").asText());
        assertTrue(check("{\"applications\":[\"emea-street\"]}", "{\"type\":\"publish\",\"application\":\"EMEA-street\"}").ok());
        assertFalse(check("{\"applications\":[\"emea-street\"]}", "{\"type\":\"publish\",\"application\":\"amer-street\"}").ok());
    }

    @Test
    @DisplayName("a button or a place only as the screen lists it")
    void pressAndGo() throws Exception {
        ProposalCheck.Outcome press = check(DEPLOY, "{\"type\":\"press\",\"button\":\"deploy a tag\"}");
        assertEquals("Deploy a tag", press.proposal.path("button").asText());
        assertFalse(check(DEPLOY, "{\"type\":\"press\",\"button\":\"Delete everything\"}").ok());
        assertTrue(check(DEPLOY, "{\"type\":\"go\",\"place\":\"releases\"}").ok());
        assertFalse(check(DEPLOY, "{\"type\":\"go\",\"place\":\"Settings\"}").ok());
    }

    @Test
    @DisplayName("a plain command naming a place or button exactly is the app's to propose; anything else is not")
    void commands() throws Exception {
        Actions a = new Actions(JSON.readTree("{\"places\":[\"Releases\",\"fee\",\"step: Commit changes\"],\"buttons\":[\"Run the tests\"]}"), "alice");
        assertEquals("fee", a.command("Open fee").path("place").asText());
        assertEquals("Releases", a.command("take me to releases, please").path("place").asText());
        assertEquals("step: Commit changes", a.command("go to commit changes").path("place").asText());
        assertEquals("Run the tests", a.command("Run the tests!").path("button").asText());
        assertEquals("Run the tests", a.command("press run the tests").path("button").asText());
        assertEquals(null, a.command("how do I run the tests?"));
        assertEquals(null, a.command("open the pod bay doors"));
    }

    @Test
    @DisplayName("fields the model left out come from the question and the screen's suggestions")
    void completes() throws Exception {
        Actions releases = new Actions(JSON.readTree(RELEASES), "alice");
        JsonNode tag = releases.complete(JSON.readTree("{\"type\":\"tag\"}"), "tag the release branch");
        assertEquals("release/1.0", tag.path("branch").asText());
        assertEquals("v1.0.0", tag.path("version").asText());
        JsonNode branch = releases.complete(JSON.readTree("{\"type\":\"create_branch\"}"), "create release/1.2 from release/1.0");
        assertEquals("release/1.0", branch.path("from").asText());
        assertEquals("1.2", branch.path("number").asText());
        assertEquals("1.1", releases.complete(JSON.readTree("{\"type\":\"create_branch\"}"), "next release branch").path("number").asText());
        Actions deploy = new Actions(JSON.readTree(DEPLOY), "alice");
        assertEquals("v1.0.0", deploy.complete(JSON.readTree("{\"type\":\"deploy\"}"), "deploy the newest tag").path("tag").asText());
        // What the model did say is kept.
        assertEquals("v1.0.1", deploy.complete(JSON.readTree("{\"type\":\"deploy\",\"tag\":\"v1.0.1\"}"), "deploy").path("tag").asText());
    }

    @Test
    @DisplayName("one cell is checked by the engine and must change; one rule is removed by its number")
    void cellAndRemoval() throws Exception {
        ProposalCheck tables = new ProposalCheck(JSON.readTree(ProposalCheckTest.CONTEXT), ProposalCheckTest.XML);
        ProposalCheck.Outcome cell = tables.check(JSON.readTree("{\"type\":\"change_cell\",\"table\":\"Fee Code\",\"rule\":1,\"column\":\"Fee Code\",\"value\":\"\\\"US-HUGE\\\"\"}"));
        assertTrue(cell.ok(), cell.problems.toString());
        assertEquals("out", cell.proposal.path("side").asText());
        assertEquals("\"US-LRG\"", cell.proposal.path("before").asText());
        String bare = tables.check(JSON.readTree("{\"type\":\"change_cell\",\"table\":\"Fee Code\",\"rule\":1,\"column\":\"feeCode\",\"value\":\"US-HUGE\"}")).problems.toString();
        assertTrue(bare.contains("double quotes"), bare);
        assertFalse(tables.check(JSON.readTree("{\"type\":\"change_cell\",\"table\":\"Fee Code\",\"rule\":9,\"column\":\"feeCode\",\"value\":\"\\\"X\\\"\"}")).ok());
        assertFalse(tables.check(JSON.readTree("{\"type\":\"change_cell\",\"table\":\"Fee Code\",\"rule\":1,\"column\":\"feeCode\",\"value\":\"\\\"US-LRG\\\"\"}")).ok());

        ProposalCheck.Outcome removal = tables.check(JSON.readTree("{\"type\":\"remove_rule\",\"table\":\"Fee Code\",\"rule\":2}"));
        assertTrue(removal.ok(), removal.problems.toString());
        assertEquals("[\"\\\"XX-MIN\\\"\",\"\\\"BPS-MIN\\\"\"]", removal.proposal.path("outputs").toString());
    }

    @Test
    @DisplayName("a rule already in a table that an earlier one shadows is named, for the review")
    void reachOfARule() {
        // Rule 2 of the test table is the catch-all; a rule after it, for DE, would never apply.
        String xml = ProposalCheckTest.XML.replace("</decisionTable>",
                "<rule id=\"late\"><inputEntry id=\"x1\"><text>\"DE\"</text></inputEntry><inputEntry id=\"x2\"><text>-</text></inputEntry><inputEntry id=\"x3\"><text>-</text></inputEntry>"
                        + "<outputEntry id=\"y1\"><text>\"DE\"</text></outputEntry><outputEntry id=\"y2\"><text>\"X\"</text></outputEntry></rule></decisionTable>");
        String late = ProposalCheck.reach(xml, "FeeCode", "late");
        assertTrue(late != null && late.startsWith("rule 3 never applies: for country \"DE\", rule 2 matches first"), late);
        assertEquals(null, ProposalCheck.reach(xml, "FeeCode", "r1"));
    }
}
