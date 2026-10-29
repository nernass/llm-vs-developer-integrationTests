package integration;

import com.cloudbees.jenkins.plugins.bitbucket.BitbucketMockApiFactory;
import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMSource;
import com.cloudbees.jenkins.plugins.bitbucket.BranchDiscoveryTrait;
import com.cloudbees.jenkins.plugins.bitbucket.ForkPullRequestDiscoveryTrait;
import com.cloudbees.jenkins.plugins.bitbucket.OriginPullRequestDiscoveryTrait;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketApi;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketRepository;
import com.cloudbees.jenkins.plugins.bitbucket.endpoints.BitbucketCloudEndpoint;
import hudson.model.Result;
import hudson.model.TopLevelItem;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import jenkins.branch.BranchSource;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mockito;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

public class gptIT_1 {

    @ClassRule
    public static JenkinsRule j = new JenkinsRule();

    private static final Random entropy = new Random();

    @Rule
    public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

    private String message;

    @Before
    public void resetEnvironment() throws Exception {

        for (TopLevelItem item : j.getInstance().getItems()) {
            item.delete();
        }

        BitbucketMockApiFactory.clear();

        message = "gptIT-" + Long.toHexString(entropy.nextLong());
    }

    @Test
    public void shouldFailIndexingWhenGetBranchesThrowsIOException()
        throws Exception {

        Fixture fixture = createFixture("branches-io");

        when(fixture.api.getBranches())
            .thenThrow(new IOException(message));

        fixture.project
            .scheduleBuild2(0)
            .getFuture()
            .get(10, TimeUnit.SECONDS);

        assertThat(
            fixture.project.getIndexing().getResult(),
            is(Result.FAILURE)
        );
    }

    @Test
    public void shouldAbortIndexingWhenGetBranchesThrowsInterruptedException()
        throws Exception {

        Fixture fixture = createFixture("branches-interrupt");

        when(fixture.api.getBranches())
            .thenThrow(new InterruptedException(message));

        fixture.project.scheduleBuild2(0);

        j.waitUntilNoActivity();

        assertThat(
            fixture.project.getIndexing().getResult(),
            is(Result.ABORTED)
        );
    }

    @Test
    public void shouldMarkIndexingNotBuiltWhenGetBranchesThrowsError()
        throws Exception {

        Fixture fixture = createFixture("branches-error");

        when(fixture.api.getBranches())
            .thenThrow(new Error(message));

        fixture.project.scheduleBuild2(0);

        j.waitUntilNoActivity();

        assertThat(
            fixture.project.getIndexing().getResult(),
            is(Result.NOT_BUILT)
        );
    }

    private Fixture createFixture(String projectName)
        throws Exception {

        sampleRepo.init();

        sampleRepo.write(
            "Jenkinsfile",
            "echo test"
        );

        sampleRepo.git("add", "Jenkinsfile");

        sampleRepo.git(
            "commit",
            "--all",
            "--message=InitialCommit"
        );

        BitbucketApi api =
            Mockito.mock(BitbucketApi.class);

        BitbucketRepository repository =
            Mockito.mock(BitbucketRepository.class);

        when(api.getRepository())
            .thenReturn(repository);

        when(repository.getOwnerName())
            .thenReturn("bob");

        when(repository.getRepositoryName())
            .thenReturn("foo");

        when(repository.getScm())
            .thenReturn("git");

        BitbucketMockApiFactory.add(
            BitbucketCloudEndpoint.SERVER_URL,
            api
        );

        WorkflowMultiBranchProject project =
            j.jenkins.createProject(
                WorkflowMultiBranchProject.class,
                projectName
            );

        BitbucketSCMSource source =
            new BitbucketSCMSource(
                "bob",
                "foo"
            );

        source.setTraits(
            Arrays.asList(
                new BranchDiscoveryTrait(true, true),

                new OriginPullRequestDiscoveryTrait(
                    EnumSet.of(
                        ChangeRequestCheckoutStrategy.HEAD
                    )
                ),

                new ForkPullRequestDiscoveryTrait(
                    EnumSet.of(
                        ChangeRequestCheckoutStrategy.HEAD
                    ),
                    new ForkPullRequestDiscoveryTrait.TrustTeamForks()
                )
            )
        );

        project.getSourcesList()
            .add(new BranchSource(source));

        project.scheduleBuild2(0)
            .getFuture()
            .get();

        j.waitUntilNoActivity();

        return new Fixture(
            project,
            api
        );
    }

    private static class Fixture {

        private final WorkflowMultiBranchProject project;
        private final BitbucketApi api;

        private Fixture(
            WorkflowMultiBranchProject project,
            BitbucketApi api) {

            this.project = project;
            this.api = api;
        }
    }
}

