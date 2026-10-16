package limitium.art.camunda.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production changes nothing: it reads, tests, tries, and deploys released versions. */
class DeployOnlyGuardTest {

    private boolean allows(String role, String method, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean ok = new DeployOnlyGuard(role).preHandle(new MockHttpServletRequest(method, path), response, null);
        if (!ok) {
            assertEquals(403, response.getStatus());
        }
        return ok;
    }

    @Test
    @DisplayName("a deploy-only instance looks, tests, tries and deploys, and refuses every change")
    void productionChangesNothing() throws Exception {
        String prod = "deployer";
        assertTrue(allows(prod, "GET", "/api/applications"));
        assertTrue(allows(prod, "GET", "/api/commit/abc"));
        assertTrue(allows(prod, "POST", "/api/applications/emea-street/deploy"));
        assertTrue(allows(prod, "POST", "/api/applications/emea-street/restore"));
        assertTrue(allows(prod, "POST", "/api/commit/tests"));
        assertTrue(allows(prod, "POST", "/api/runs"));
        assertTrue(allows(prod, "POST", "/api/evaluate"));
        assertTrue(allows(prod, "POST", "/api/sync"));

        assertFalse(allows(prod, "POST", "/api/applications/emea-street/draft"));
        assertFalse(allows(prod, "POST", "/api/workspaces"));
        assertFalse(allows(prod, "POST", "/api/workspaces/feature/alice/x/commit"));
        assertFalse(allows(prod, "DELETE", "/api/workspaces/feature/alice/x"));
        assertFalse(allows(prod, "PUT", "/api/dmn/pricing.dmn"));
        assertFalse(allows(prod, "POST", "/api/dmn"));
        assertFalse(allows(prod, "DELETE", "/api/dmn/pricing.dmn"));
        assertFalse(allows(prod, "PUT", "/api/yaml/pricing.yaml"));
        assertFalse(allows(prod, "POST", "/api/lifecycle/release"));
        assertFalse(allows(prod, "POST", "/api/lifecycle/lines/open"));
        assertFalse(allows(prod, "POST", "/api/lifecycle/carry"));
        assertFalse(allows(prod, "POST", "/api/cleanup/apply"));
    }

    @Test
    @DisplayName("an authoring instance is not restricted by it")
    void authoringIsUntouched() throws Exception {
        assertTrue(allows("author", "PUT", "/api/dmn/pricing.dmn"));
        assertTrue(allows("author", "POST", "/api/lifecycle/release"));
    }
}
