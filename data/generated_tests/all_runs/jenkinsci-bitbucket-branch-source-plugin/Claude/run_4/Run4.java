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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BranchScanningIntegrationTest {

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
        message = "Unique test sentinel: " + Long.toHexString(entropy.nextLong());
    }

    // -------------------------------------------------------------------------
    // getBranches() — four exception-type variants
    // -------------------------------------------------------------------------

    @Test
    public void shouldProduceFailureWhenGetBranchesThrowsIOException() throws Exception {
        runGetBranchesFailureScenario(() -> new IOException(message), Result.FAILURE, true);
    }

    @Test
    public void shouldProduceAbortedWhenGetBranchesThrowsInterruptedException() throws Exception {
        runGetBranchesFailureScenario(() -> new InterruptedException(message), Result.ABORTED, false);
    }

    @Test
    public void shouldProduceFailureWhenGetBranchesThrowsRuntimeException() throws Exception {
        runGetBranchesFailureScenario(() -> new RuntimeException(message), Result.FAILURE, true);
    }

    @Test
    public void shouldProduceNotBuiltWhenGetBranchesThrowsError() throws Exception {
        runGetBranchesFailureScenario(() -> new Error(message), Result.NOT_BUILT, false);
    }

    private void runGetBranchesFailureScenario(Callable<Throwable> exceptionFactory,
                                                Result expectedResult,
                                                boolean expectMessageInLog) throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = buildMockedApi(sampleRepo, "bob", "foo");
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject("smokes", "bob", "foo");

        // Baseline: first scan must succeed and create the branch project
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();
        WorkflowJob mainJob = mp.getItem("main");
        assertThat(mainJob, notNullValue());

        // Inject getBranches() failure
        when(api.getBranches()).thenThrow(exceptionFactory.call());

        if (Result.NOT_BUILT.equals(expectedResult) || Result.ABORTED.equals(expectedResult)) {
            // ABORTED / NOT_BUILT futures never complete normally
            mp.scheduleBuild2(0);
            j.waitUntilNoActivity();
        } else {
            mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);
        }

        assertThat(mp.getIndexing().getResult(), is(expectedResult));
        if (expectMessageInLog) {
            assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));
        }

        // Branch project must survive and must NOT be marked Dead
        mainJob = mp.getItem("main");
        assertThat(mainJob, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(mainJob), not(instanceOf(Branch.Dead.class)));
    }

    // -------------------------------------------------------------------------
    // checkPathExists() throws IOException
    // -------------------------------------------------------------------------

    @Test
    public void shouldProduceFailureAndKeepBranchAliveWhenCheckPathExistsThrowsIOException() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = buildMockedApi(sampleRepo, "bob", "foo");
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject("smokes", "bob", "foo");

        // Baseline scan
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();
        assertThat(mp.getItem("main"), notNullValue());

        // Inject checkPathExists() failure
        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenThrow(new IOException(message));

        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);

        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));

        WorkflowJob mainJob = mp.getItem("main");
        assertThat(mainJob, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(mainJob), not(instanceOf(Branch.Dead.class)));
    }

    // -------------------------------------------------------------------------
    // checkPathExists() throws InterruptedException — probe wraps it as IOException
    // -------------------------------------------------------------------------

    @Test
    public void shouldProduceFailureAndKeepBranchAliveWhenCheckPathExistsThrowsInterruptedException()
            throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = buildMockedApi(sampleRepo, "bob", "foo");
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject("smokes", "bob", "foo");

        // Baseline scan
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        j.waitUntilNoActivity();
        assertThat(mp.getItem("main"), notNullValue());

        // InterruptedException is wrapped to IOException by the probe layer
        when(api.checkPathExists(anyString(), eq("Jenkinsfile")))
                .thenThrow(new InterruptedException(message));

        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);

        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));

        WorkflowJob mainJob = mp.getItem("main");
        assertThat(mainJob, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(mainJob), not(instanceOf(Branch.Dead.class)));
    }

    // -------------------------------------------------------------------------
    // resolveCommit() throws IOException — graceful degradation
    // -------------------------------------------------------------------------

    @Test
    public void shouldSucceedGracefullyAndNotTriggerNewBuildWhenResolveCommitThrowsIOException()
            throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = buildMockedApi(sampleRepo, "bob", "foo");
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject("smokes", "bob", "foo");

        // Baseline scan
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();

        WorkflowJob mainJob = mp.getItem("main");
        assertThat(mainJob, notNullValue());
        assertThat(mainJob.getLastBuild(), notNullValue());
        assertThat(mainJob.getNextBuildNumber(), is(2));

        // Inject resolveCommit() failure
        when(api.resolveCommit(sampleRepo.head())).thenThrow(new IOException(message));

        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);

        // Graceful degradation — scan must still report SUCCESS
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));

        mainJob = mp.getItem("main");
        assertThat(mainJob, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(mainJob), not(instanceOf(Branch.Dead.class)));
        // No new build must have been triggered
        assertThat(mainJob.getLastBuild(), notNullValue());
        assertThat(mainJob.getNextBuildNumber(), is(2));
    }

    // -------------------------------------------------------------------------
    // Branch legitimately removed from remote
    // -------------------------------------------------------------------------

    @Test
    public void shouldDeleteBranchProjectWhenBranchIsRemovedFromRemote() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = buildMockedApi(sampleRepo, "bob", "foo");
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject("smokes", "bob", "foo");

        // Baseline scan — branch project created and build triggered
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();

        WorkflowJob mainJob = mp.getItem("main");
        assertThat(mainJob, notNullValue());
        assertThat(mainJob.getLastBuild(), notNullValue());
        assertThat(mainJob.getNextBuildNumber(), is(2));

        // Remote drops the branch
        when(api.getBranches()).thenAnswer(new Returns(Collections.emptyList()));

        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);

        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(mp.getItem("main"), nullValue());
    }

    // -------------------------------------------------------------------------
    // Multiple branches — only the removed one is deleted
    // -------------------------------------------------------------------------

    @Test
    public void shouldDeleteOnlyTheRemovedBranchProjectWhenOneOfMultipleBranchesIsDropped()
            throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        // Create a second local branch so the git repo has two distinct heads
        sampleRepo.git("checkout", "-b", "feature-x");
        sampleRepo.write("file", "feature content");
        sampleRepo.git("commit", "--all", "--message=FeatureCommit");
        String featureHead = sampleRepo.head();
        sampleRepo.git("checkout", "master");
        String mainHead = sampleRepo.head();

        BitbucketApi api = Mockito.mock(BitbucketApi.class);

        BitbucketBranch mainBranch = Mockito.mock(BitbucketBranch.class);
        when(mainBranch.getName()).thenReturn("main");
        when(mainBranch.getRawNode()).thenReturn(mainHead);

        BitbucketBranch featureBranch = Mockito.mock(BitbucketBranch.class);
        when(featureBranch.getName()).thenReturn("feature-x");
        when(featureBranch.getRawNode()).thenReturn(featureHead);

        when(api.getBranches()).thenAnswer(new Returns(Arrays.asList(mainBranch, featureBranch)));

        BitbucketCommit mainCommit = Mockito.mock(BitbucketCommit.class);
        when(mainCommit.getDateMillis()).thenReturn(System.currentTimeMillis());
        BitbucketCommit featureCommit = Mockito.mock(BitbucketCommit.class);
        when(featureCommit.getDateMillis()).thenReturn(System.currentTimeMillis());
        when(api.resolveCommit(mainHead)).thenReturn(mainCommit);
        when(api.resolveCommit(featureHead)).thenReturn(featureCommit);

        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenReturn(true);
        when(api.getRepositoryUri(any(BitbucketRepositoryProtocol.class),
                anyString(), eq("bob"), eq("multi"))).thenReturn(sampleRepo.fileUrl());

        BitbucketRepository repository = Mockito.mock(BitbucketRepository.class);
        when(api.getRepository()).thenReturn(repository);
        when(repository.getOwnerName()).thenReturn("bob");
        when(repository.getRepositoryName()).thenReturn("multi");
        when(repository.getScm()).thenReturn("git");

        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject("smokes", "bob", "multi");

        // Baseline: both branch projects created
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        j.waitUntilNoActivity();
        assertThat(mp.getItem("main"), notNullValue());
        assertThat(mp.getItem("feature-x"), notNullValue());

        // feature-x disappears from the remote
        when(api.getBranches()).thenAnswer(new Returns(Collections.singletonList(mainBranch)));

        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);

        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(mp.getItem("main"), notNullValue());
        assertThat(mp.getItem("feature-x"), nullValue());
    }

    // -------------------------------------------------------------------------
    // Recovery: scan succeeds again after a prior getBranches() IOException
    // -------------------------------------------------------------------------

    @Test
    public void shouldRecoverToSuccessOnThirdScanAfterGetBranchesIOExceptionOnSecondScan()
            throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = buildMockedApi(sampleRepo, "bob", "foo");
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject("smokes", "bob", "foo");

        // Scan 1 — success
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        j.waitUntilNoActivity();
        assertThat(mp.getItem("main"), notNullValue());

        // Scan 2 — failure
        when(api.getBranches()).thenThrow(new IOException(message));
        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));

        // Restore healthy state
        BitbucketBranch branch = Mockito.mock(BitbucketBranch.class);
        when(branch.getName()).thenReturn("main");
        when(branch.getRawNode()).thenReturn(sampleRepo.head());
        when(api.getBranches()).thenAnswer(new Returns(Collections.singletonList(branch)));

        // Scan 3 — must recover to SUCCESS
        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));

        WorkflowJob mainJob = mp.getItem("main");
        assertThat(mainJob, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(mainJob), not(instanceOf(Branch.Dead.class)));
    }

    // -------------------------------------------------------------------------
    // No Jenkinsfile — branch discovered but no project created
    // -------------------------------------------------------------------------

    @Test
    public void shouldNotCreateBranchProjectWhenJenkinsfileIsAbsentOnBranch() throws Exception {
        sampleRepo.init();
        sampleRepo.write("file", "no Jenkinsfile in this repo");
        sampleRepo.git("add", "file");
        sampleRepo.git("commit", "--all", "--message=NoJenkinsfile");

        BitbucketApi api = Mockito.mock(BitbucketApi.class);

        BitbucketBranch branch = Mockito.mock(BitbucketBranch.class);
        when(branch.getName()).thenReturn("main");
        when(branch.getRawNode()).thenReturn(sampleRepo.head());
        when(api.getBranches()).thenAnswer(new Returns(Collections.singletonList(branch)));

        BitbucketCommit commit = Mockito.mock(BitbucketCommit.class);
        when(commit.getDateMillis()).thenReturn(System.currentTimeMillis());
        when(api.resolveCommit(sampleRepo.head())).thenReturn(commit);

        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenReturn(false);
        when(api.getRepositoryUri(any(BitbucketRepositoryProtocol.class),
                anyString(), eq("bob"), eq("nojf"))).thenReturn(sampleRepo.fileUrl());

        BitbucketRepository repository = Mockito.mock(BitbucketRepository.class);
        when(api.getRepository()).thenReturn(repository);
        when(repository.getOwnerName()).thenReturn("bob");
        when(repository.getRepositoryName()).thenReturn("nojf");
        when(repository.getScm()).thenReturn("git");

        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject("smokes", "bob", "nojf");

        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);

        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(mp.getItem("main"), nullValue());
    }

    // -------------------------------------------------------------------------
    // getRepository() throws on initial scan
    // -------------------------------------------------------------------------

    @Test
    public void shouldProduceFailureAndLogMessageWhenGetRepositoryThrowsIOExceptionOnInitialScan()
            throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = Mockito.mock(BitbucketApi.class);
        when(api.getRepository()).thenThrow(new IOException(message));

        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject("smokes", "bob", "badrepo");

        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);

        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));
    }

    // -------------------------------------------------------------------------
    // Interaction verification
    // -------------------------------------------------------------------------

    @Test
    public void shouldInvokeGetBranchesAndCheckPathExistsOnApiDuringSuccessfulScan() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = buildMockedApi(sampleRepo, "bob", "foo");
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject("smokes", "bob", "foo");

        mp.scheduleBuild2(0).getFuture().get();
        j.waitUntilNoActivity();

        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(mp.getItem("main"), notNullValue());

        verify(api, atLeastOnce()).getBranches();
        verify(api, atLeastOnce()).checkPathExists(anyString(), eq("Jenkinsfile"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a fully wired Mockito mock of {@link BitbucketApi} backed by the given local
     * git sample repository. All interactions required for a standard single-branch discovery
     * scan are pre-stubbed with working defaults.
     */
    private BitbucketApi buildMockedApi(GitSampleRepoRule repo, String owner, String repoName)
            throws Exception {
        BitbucketApi api = Mockito.mock(BitbucketApi.class);

        BitbucketBranch branch = Mockito.mock(BitbucketBranch.class);
        when(branch.getName()).thenReturn("main");
        when(branch.getRawNode()).thenReturn(repo.head());
        when(api.getBranches()).thenAnswer(new Returns(Collections.singletonList(branch)));

        BitbucketCommit commit = Mockito.mock(BitbucketCommit.class);
        when(commit.getDateMillis()).thenReturn(System.currentTimeMillis());
        when(api.resolveCommit(repo.head())).thenReturn(commit);

        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenReturn(true);
        when(api.getRepositoryUri(
                any(BitbucketRepositoryProtocol.class),
                anyString(),
                eq(owner),
                eq(repoName))).thenReturn(repo.fileUrl());

        BitbucketRepository repository = Mockito.mock(BitbucketRepository.class);
        when(api.getRepository()).thenReturn(repository);
        when(repository.getOwnerName()).thenReturn(owner);
        when(repository.getRepositoryName()).thenReturn(repoName);
        when(repository.getScm()).thenReturn("git");

        return api;
    }

    /**
     * Creates a {@link WorkflowMultiBranchProject} with a {@link BitbucketSCMSource} configured
     * with the standard set of branch and pull-request discovery traits.
     */
    private WorkflowMultiBranchProject createMultiBranchProject(String jobName,
                                                                  String owner,
                                                                  String repoName)
            throws IOException {
        WorkflowMultiBranchProject mp =
                j.jenkins.createProject(WorkflowMultiBranchProject.class, jobName);
        BitbucketSCMSource source = new BitbucketSCMSource(owner, repoName);
        source.setTraits(Arrays.asList(
                new BranchDiscoveryTrait(true, true),
                new OriginPullRequestDiscoveryTrait(EnumSet.of(ChangeRequestCheckoutStrategy.HEAD)),
                new ForkPullRequestDiscoveryTrait(
                        EnumSet.of(ChangeRequestCheckoutStrategy.HEAD),
                        new ForkPullRequestDiscoveryTrait.TrustTeamForks())));
        mp.getSourcesList().add(new BranchSource(source));
        return mp;
    }
}