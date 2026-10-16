package limitium.art.camunda.backend.config;

import limitium.art.camunda.backend.deploy.DeployProperties;
import limitium.art.camunda.backend.deploy.DeployService;
import limitium.art.camunda.backend.deploy.FileTarget;
import limitium.art.camunda.backend.deploy.KafkaTarget;
import limitium.art.camunda.backend.deploy.RuleTarget;
import limitium.art.camunda.backend.service.TestExecutionService;
import limitium.art.camunda.backend.git.BranchCleanup;
import limitium.art.camunda.backend.git.GitRuleStore;
import limitium.art.camunda.backend.git.Instances;
import limitium.art.camunda.backend.git.Landing;
import limitium.art.camunda.backend.git.Releases;
import limitium.art.camunda.backend.git.RemoteSync;
import limitium.art.camunda.backend.lifecycle.TestGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration
public class GitConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GitConfiguration.class);

    @Bean
    public GitRuleStore gitRuleStore(GitProperties properties, DeployProperties deploy,
                                     @Qualifier("dataRootPath") Path dataRootPath) {
        GitRuleStore store = new GitRuleStore(properties, properties.workRootPath(dataRootPath));
        store.setInstance(deploy.getEnv()); // a space belongs to the instance that made it
        return store;
    }

    @Bean
    public DeployService deployService(GitRuleStore store,
                                       @Qualifier("dataRootPath") Path dataRootPath,
                                       DeployProperties deploy,
                                       TestGate gate) {
        return new DeployService(store, applications(deploy, dataRootPath), gate, deploy);
    }

    /** The applications this instance controls, as configured; none means nothing to deploy to. */
    static List<DeployService.Application> applications(DeployProperties deploy, Path dataRootPath) {
        List<DeployService.Application> applications = new ArrayList<>();
        for (Map.Entry<String, DeployProperties.Application> e : deploy.getApplications().entrySet()) {
            String name = e.getKey();
            DeployProperties.Application d = e.getValue();
            String topic = d.getTopic() == null || d.getTopic().isBlank() ? "dmn-" + name + "-" + deploy.getEnv() : d.getTopic();
            RuleTarget target;
            if ("file".equalsIgnoreCase(d.getType())) {
                Path dir = d.getDir() == null || d.getDir().isBlank() ? dataRootPath.resolve("topics").resolve(topic) : Path.of(d.getDir());
                target = new FileTarget(name, dir);
            } else if ("kafka".equalsIgnoreCase(d.getType())) {
                if (d.getBootstrapServers() == null || d.getBootstrapServers().isBlank()) {
                    throw new IllegalStateException("dmn.deploy.applications." + name + ".bootstrap-servers is not set");
                }
                target = new KafkaTarget(name, d.getBootstrapServers(), topic, d.getProperties(), d.getTimeoutSeconds());
            } else {
                throw new IllegalStateException("dmn.deploy.applications." + name + ".type must be kafka or file, not " + d.getType());
            }
            DeployService.Application domain = new DeployService.Application(name, d.getFiles(), target);
            applications.add(domain);
            log.info("application {} reads {} from {} ({}, topic {})", name, domain.files, d.getType(), target.describe().get("where"), topic);
        }
        if (applications.isEmpty()) {
            log.warn("no applications configured (dmn.deploy.applications.*): nothing is deployed from this instance");
        }
        return applications;
    }

    /** Every domain's tests at a commit: the one gate behind landing, releasing and deploying. */
    @Bean
    public TestGate testGate(GitRuleStore store, TestExecutionService tests) {
        return new TestGate(store, tests);
    }

    @Bean
    public Releases releases(GitRuleStore store, RemoteSync sync, TestGate gate) {
        return new Releases(store, sync, gate);
    }

    @Bean
    public Landing landing(GitRuleStore store, RemoteSync sync, TestGate gate) {
        return new Landing(store, sync, gate);
    }


    @Bean
    public RemoteSync remoteSync(GitRuleStore store, @org.springframework.beans.factory.annotation.Value("${dmn.role:author}") String role) {
        RemoteSync sync = new RemoteSync(store);
        sync.readOnly("deployer".equals(role));
        return sync;
    }

    @Bean
    public Instances instances(GitRuleStore store, RemoteSync sync, DeployService deploys, DeployProperties deploy) {
        Instances instances = new Instances(store);
        deploys.instances(instances);
        // Re-declared after every fetch, so the remote always holds this instance's current servers.
        sync.afterFetch(() -> {
            try {
                instances.declare(deploy.getEnv(), deploys.serversDeclared());
            } catch (Exception e) {
                log.warn("could not declare this instance's servers: {}", e.getMessage());
            }
        });
        return instances;
    }

    @Bean
    public BranchCleanup branchCleanup(GitRuleStore store, GitProperties properties) {
        return new BranchCleanup(store, properties);
    }

    /**
     * Runs after the data directories exist so the legacy import has something to look at.
     * Never fails startup: an unreachable repository is a degraded editor, not a dead one.
     */
    @Bean
    @Order(100)
    public CommandLineRunner gitInitialiser(GitProperties properties, GitRuleStore store, RemoteSync sync,
                                            Instances instances, DeployService deploys, DeployProperties deploy,
                                            @Qualifier("dmnDir") Path dmnDir,
                                            @Qualifier("yamlDir") Path yamlDir) {
        return args -> {
            if (!properties.isEnabled()) {
                log.info("git layer disabled; rules are read from the flat data root");
                return;
            }
            try {
                store.initialise();
                store.importLegacy(dmnDir, yamlDir);
                log.info("rule repository ready at {}", store.workRoot());
                instances.declare(deploy.getEnv(), deploys.serversDeclared());
                startSync(properties, sync);
            } catch (Exception e) {
                log.error("could not prepare the rule repository at {}: {}",
                        store.workRoot(), e.getMessage(), e);
            }
        };
    }

    /** Instances meet only in the remote: one round now, then on a timer. A daemon, so it never holds shutdown. */
    private static void startSync(GitProperties properties, RemoteSync sync) {
        if (!sync.enabled()) {
            log.info("no remote repository configured: this instance works alone");
            return;
        }
        RemoteSync.Report first = sync.run();
        log.info("first sync with {}: {}", properties.getRemoteUrl(), first.ok ? "ok" : first.error);
        if (properties.getSyncSeconds() <= 0) {
            return;
        }
        java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "git-sync");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(sync::run, properties.getSyncSeconds(), properties.getSyncSeconds(),
                java.util.concurrent.TimeUnit.SECONDS);
    }
}
