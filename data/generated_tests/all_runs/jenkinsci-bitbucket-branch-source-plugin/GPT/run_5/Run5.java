
package integration;

import com.cloudbees.jenkins.plugins.bitbucket.BitbucketMockApiFactory;
import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMSource;
import com.cloudbees.jenkins.plugins.bitbucket.BranchDiscoveryTrait;
import com.cloudbees.jenkins.plugins.bitbucket.ForkPullRequestDiscoveryTrait;
import com.cloudbees.jenkins.plugins.bitbucket.OriginPullRequestDiscoveryTrait;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketApi;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketBranch;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketCommit;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketRepository;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketRepositoryProtocol;
import com.cloudbees.jenkins.plugins.bitbucket.endpoints.BitbucketCloudEndpoint;
import hudson.model.Result;
import hudson.model.TopLevelItem;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import jenkins.branch.Branch;
import jenkins.branch.BranchSource;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mockito;
import org.mockito.internal.stubbing.answers.Returns;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Issue("JENKINS-36029")
public class BitbucketSCMSourceScanningIntegrationTest {

    private static final String OWNER = "bob";
    private static final String REPOSITORY = "foo";
    private static final String PROJECT_NAME = "smokes";
    private static final String BRANCH_NAME = "main";

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
        message = "We gonna Boom Boom Boom 'til the break of Boom. "
                + Long.toHexString(entropy.nextLong())
                + " Who's the Boom King? Who? I'm the Boom King!";
    }

    @Test
    public void shouldFailIndexingWhenGetBranchesThrowsIOException() throws Exception {
        getBranchesFails(() -> new IOException(message), Result.FAILURE);
    }

    @Test
    public void shouldAbortIndexingWhenGetBranchesThrowsInterruptedException() throws Exception {
        getBranchesFails(() -> new InterruptedException(message), Result.ABORTED);
    }

    @Test
    public void shouldFailIndexingWhenGetBranchesThrowsRuntimeException() throws Exception {
        getBranchesFails(() -> new RuntimeException(message), Result.FAILURE);
    }

    @Test
    public void shouldMarkIndexingNotBuiltWhenGetBranchesThrowsError() throws Exception {
        getBranchesFails(() -> new Error(message), Result.NOT_BUILT);
    }

    private void getBranchesFails(Callable<Throwable> exceptionFactory, Result expectedResult) throws Exception {
        ScanFixture fixture = createFixture();

        fixture.project.scheduleBuild2(0).getFuture().get();
        assertThat(fixture.project.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(fixture.project.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();

        WorkflowJob main = fixture.project.getItem(BRANCH_NAME);
        assertThat(main, notNullValue());

        when(fixture.api.getBranches()).thenThrow(exceptionFactory.call());

        if (Result.NOT_BUILT.equals(expectedResult) || Result.ABORTED.equals(expectedResult)) {
            fixture.project.scheduleBuild2(0);
            j.waitUntilNoActivity();
            assertThat(fixture.project.getIndexing().getResult(), is(expectedResult));
        } else {
            fixture.project.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);
            assertThat(fixture.project.getIndexing().getResult(), is(expectedResult));
            assertThat(FileUtils.readFileToString(fixture.project.getIndexing().getLogFile()), containsString(message));
        }

        main = fixture.project.getItem(BRANCH_NAME);
        assertThat(main, notNullValue());
        assertThat(fixture.project.getProjectFactory().getBranch(main), not(instanceOf(Branch.Dead.class)));
    }

    @Test
    public void shouldFailIndexingAndKeepBranchAliveWhenCheckPathExistsThrowsIOException() throws Exception {
        ScanFixture fixture = createFixture();

        fixture.project.scheduleBuild2(0).getFuture().get();
        assertThat(fixture.project.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(fixture.project.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();

        WorkflowJob main = fixture.project.getItem(BRANCH_NAME);
        assertThat(main, notNullValue());

        when(fixture.api.checkPathExists(Mockito.anyString(), eq("Jenkinsfile"))).thenThrow(new IOException(message));

        fixture.project.scheduleBuild2(0).getFuture().get();

        assertThat(fixture.project.getIndexing().getResult(), is(Result.FAILURE));
        assertThat(FileUtils.readFileToString(fixture.project.getIndexing().getLogFile()), containsString(message));

        main = fixture.project.getItem(BRANCH_NAME);
        assertThat(main, notNullValue());
        assertThat(fixture.project.getProjectFactory().getBranch(main), not(instanceOf(Branch.Dead.class)));
    }

    @Test
    public void shouldSucceedAndPreserveBuildStateWhenResolveCommitThrowsIOException() throws Exception {
        ScanFixture fixture = createFixture();

        fixture.project.scheduleBuild2(0).getFuture().get();
        assertThat(fixture.project.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(fixture.project.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();

        WorkflowJob main = fixture.project.getItem(BRANCH_NAME);
        assertThat(main, notNullValue());
        assertThat(main.getLastBuild(), notNullValue());
        assertThat(main.getNextBuildNumber(), is(2));

        when(fixture.api.resolveCommit(sampleRepo.head())).thenThrow(new IOException(message));

        fixture.project.scheduleBuild2(0).getFuture().get();

        assertThat(fixture.project.getIndexing().getResult(), is(Result.SUCCESS));

        main = fixture.project.getItem(BRANCH_NAME);
        assertThat(main, notNullValue());
        assertThat(fixture.project.getProjectFactory().getBranch(main), not(instanceOf(Branch.Dead.class)));
        assertThat(main.getLastBuild(), notNullValue());
        assertThat(main.getNextBuildNumber(), is(2));
    }

    @Test
    public void shouldRemoveBranchProjectWhenBranchIsRemovedFromRemote() throws Exception {
        ScanFixture fixture = createFixture();

        fixture.project.scheduleBuild2(0).getFuture().get();
        assertThat(fixture.project.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(fixture.project.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();

        WorkflowJob main = fixture.project.getItem(BRANCH_NAME);
        assertThat(main, notNullValue());
        assertThat(main.getLastBuild(), notNullValue());
        assertThat(main.getNextBuildNumber(), is(2));

        when(fixture.api.getBranches()).thenAnswer(new Returns(Collections.emptyList()));

        fixture.project.scheduleBuild2(0).getFuture().get();

        assertThat(fixture.project.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(fixture.project.getItem(BRANCH_NAME), nullValue());
    }

    private ScanFixture createFixture() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile", "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = Mockito.mock(BitbucketApi.class);

        BitbucketBranch branch = Mockito.mock(BitbucketBranch.class);
        List<? extends BitbucketBranch> branchList = Collections.singletonList(branch);
        when(api.getBranches()).thenAnswer(new Returns(branchList));
        when(branch.getName()).thenReturn(BRANCH_NAME);
        when(branch.getRawNode()).thenReturn(sampleRepo.head());

        BitbucketCommit commit = Mockito.mock(BitbucketCommit.class);
        when(api.resolveCommit(sampleRepo.head())).thenReturn(commit);
        when(commit.getDateMillis()).thenReturn(System.currentTimeMillis());

        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenReturn(true);

        when(api.getRepositoryUri(any(BitbucketRepositoryProtocol.class),
                anyString(), eq(OWNER), eq(REPOSITORY))).thenReturn(sampleRepo.fileUrl());

        BitbucketRepository repository = Mockito.mock(BitbucketRepository.class);
        when(api.getRepository()).thenReturn(repository);
        when(repository.getOwnerName()).thenReturn(OWNER);
        when(repository.getRepositoryName()).thenReturn(REPOSITORY);
        when(repository.getScm()).thenReturn("git");

        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);

        WorkflowMultiBranchProject project =
                j.jenkins.createProject(WorkflowMultiBranchProject.class, PROJECT_NAME);
        BitbucketSCMSource source = new BitbucketSCMSource(OWNER, REPOSITORY);
        source.setTraits(Arrays.asList(
                new BranchDiscoveryTrait(true, true),
                new OriginPullRequestDiscoveryTrait(EnumSet.of(ChangeRequestCheckoutStrategy.HEAD)),
                new ForkPullRequestDiscoveryTrait(
                        EnumSet.of(ChangeRequestCheckoutStrategy.HEAD),
                        new ForkPullRequestDiscoveryTrait.TrustTeamForks()
                )
        ));
        project.getSourcesList().add(new BranchSource(source));

        return new ScanFixture(api, project);
    }

    private static final class ScanFixture {
        private final BitbucketApi api;
        private final WorkflowMultiBranchProject project;

        private ScanFixture(BitbucketApi api, WorkflowMultiBranchProject project) {
            this.api = api;
            this.project = project;
        }
    }
}