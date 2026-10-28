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

    @Rule
    public GitSampleRepoRule secondSampleRepo = new GitSampleRepoRule();

    private String message;

    @Before
    public void resetEnvironment() throws Exception {
        for (TopLevelItem item : j.getInstance().getItems()) {
            item.delete();
        }
        BitbucketMockApiFactory.clear();
        message = "Unique error sentinel value: " + Long.toHexString(entropy.nextLong());
    }

    // -------------------------------------------------------------------------
    // getBranches() failure scenarios
    // -------------------------------------------------------------------------

    @Test
    public void getBranchesFailsWithIOException_shouldProduceFailureResultWithMessageInLog() throws Exception {
        getBranchesFails(() -> new IOException(message), Result.FAILURE, true);
    }

    @Test
    public void getBranchesFailsWithInterruptedException_shouldProduceAbortedResult() throws Exception {
        getBranchesFails(() -> new InterruptedException(message), Result.ABORTED, false);
    }

    @Test
    public void getBranchesFailsWithRuntimeException_shouldProduceFailureResultWithMessageInLog() throws Exception {
        getBranchesFails(() -> new RuntimeException(message), Result.FAILURE, true);
    }

    @Test
    public void getBranchesFailsWithError_shouldProduceNotBuiltResult() throws Exception {
        getBranchesFails(() -> new Error(message), Result.NOT_BUILT, false);
    }

    private void getBranchesFails(Callable<Throwable> exceptionFactory, Result expectedResult,
            boolean expectMessageInLog) throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = buildMockedApi(sampleRepo, "alice", "repo1");
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);

        WorkflowMultiBranchProject mp = createMultiBranchProject("scan-failure-test", "alice", "repo1");

        // Initial successful scan
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();
        assertThat(mp.getItem("main"), notNullValue());

        // Inject failure
        when(api.getBranches()).thenThrow(exceptionFactory.call());

        if (Result.NOT_BUILT.equals(expectedResult) || Result.ABORTED.equals(expectedResult)) {
            mp.scheduleBuild2(0);
            j.waitUntilNoActivity();
        } else {
            mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);
        }

        assertThat(mp.getIndexing().getResult(), is(expectedResult));
        if (expectMessageInLog) {
            assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));
        }

        // Branch project must still exist and must not be dead
        WorkflowJob mainJob = mp.getItem("main");
        assertThat(mainJob, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(mainJob), not(instanceOf(Branch.Dead.class)));
    }

    // -------------------------------------------------------------------------
    // checkPathExists() failure scenario
    // -------------------------------------------------------------------------

    @Test
    public void checkPathExistsFails_shouldProduceFailureWithMessageAndBranchNotDead() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = buildMockedApi(sampleRepo, "carol", "myrepo");
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);

        WorkflowMultiBranchProject mp = createMultiBranchProject("path-check-fail-test", "carol", "myrepo");

        // Initial successful scan
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        j.waitUntilNoActivity();
        assertThat(mp.getItem("main"), notNullValue());

        // Inject checkPathExists failure
        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenThrow(new IOException(message));

        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);

        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));

        WorkflowJob mainJob = mp.getItem("main");
        assertThat(mainJob, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(mainJob), not(instanceOf(Branch.Dead.class)));
    }

    // -------------------------------------------------------------------------
    // resolveCommit() failure scenario
    // -------------------------------------------------------------------------

    @Test
    public void resolveCommitFails_shouldSucceedGracefullyWithNoDuplicateBuild() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = buildMockedApi(sampleRepo, "dave", "proj");
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);

        WorkflowMultiBranchProject mp = createMultiBranchProject("resolve-commit-fail-test", "dave", "proj");

        // Initial successful scan
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        j.waitUntilNoActivity();

        WorkflowJob mainJob = mp.getItem("main");
        assertThat(mainJob, notNullValue());
        assertThat(mainJob.getLastBuild(), notNullValue());
        assertThat(mainJob.getNextBuildNumber(), is(2));

        // Inject resolveCommit failure
        when(api.resolveCommit(sampleRepo.head())).thenThrow(new IOException(message));

        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);

        // Scan must still succeed (graceful degradation)
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));

        mainJob = mp.getItem("main");
        assertThat(mainJob, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(mainJob), not(instanceOf(Branch.Dead.class)));
        // No new build must have been triggered
        assertThat(mainJob.getLastBuild(), notNullValue());
        assertThat(mainJob.getNextBuildNumber(), is(2));
    }

    // -------------------------------------------------------------------------
    // Branch removal scenario
    // -------------------------------------------------------------------------

    @Test
    public void branchRemovedFromRemote_shouldRemoveBranchProjectOnRescan() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = buildMockedApi(sampleRepo, "eve", "gone");
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);

        WorkflowMultiBranchProject mp = createMultiBranchProject("branch-removed-test", "eve", "gone");

        // Initial scan – branch project is created
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        j.waitUntilNoActivity();
        assertThat(mp.getItem("main"), notNullValue());
        assertThat(mp.getItem("main").getLastBuild(), notNullValue());
        assertThat(mp.getItem("main").getNextBuildNumber(), is(2));

        // Remote branch disappears
        when(api.getBranches()).thenAnswer(new Returns(Collections.emptyList()));

        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);

        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(mp.getItem("main"), nullValue());
    }

    // -------------------------------------------------------------------------
    // Multiple branches: one removed, one retained
    // -------------------------------------------------------------------------

    @Test
    public void oneBranchRemovedOneRetained_shouldOnlyDeleteRemovedBranchProject() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        // Create a second branch in the local git repo
        sampleRepo.git("checkout", "-b", "feature-branch");
        sampleRepo.write("file", "feature content");
        sampleRepo.git("commit", "--all", "--message=FeatureCommit");
        String featureHead = sampleRepo.head();
        sampleRepo.git("checkout", "master");
        String mainHead = sampleRepo.head();

        BitbucketApi api = Mockito.mock(BitbucketApi.class);

        BitbucketBranch mainBranch = Mockito.mock(BitbucketBranch.class);
        BitbucketBranch featureBranch = Mockito.mock(BitbucketBranch.class);

        when(mainBranch.getName()).thenReturn("main");
        when(mainBranch.getRawNode()).thenReturn(mainHead);
        when(featureBranch.getName()).thenReturn("feature-branch");
        when(featureBranch.getRawNode()).thenReturn(featureHead);

        List<BitbucketBranch> twoBranches = Arrays.asList(mainBranch, featureBranch);
        when(api.getBranches()).thenAnswer(new Returns(twoBranches));

        BitbucketCommit mainCommit = Mockito.mock(BitbucketCommit.class);
        BitbucketCommit featureCommit = Mockito.mock(BitbucketCommit.class);
        when(api.resolveCommit(mainHead)).thenReturn(mainCommit);
        when(api.resolveCommit(featureHead)).thenReturn(featureCommit);
        when(mainCommit.getDateMillis()).thenReturn(System.currentTimeMillis());
        when(featureCommit.getDateMillis()).thenReturn(System.currentTimeMillis());

        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenReturn(true);
        when(api.getRepositoryUri(any(BitbucketRepositoryProtocol.class),
                anyString(), eq("frank"), eq("multi"))).thenReturn(sampleRepo.fileUrl());

        BitbucketRepository repository = Mockito.mock(BitbucketRepository.class);
        when(api.getRepository()).thenReturn(repository);
        when(repository.getOwnerName()).thenReturn("frank");
        when(repository.getRepositoryName()).thenReturn("multi");
        when(repository.getScm()).thenReturn("git");

        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject("multi-branch-test", "frank", "multi");

        // Initial scan: both branches discovered
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        j.waitUntilNoActivity();
        assertThat(mp.getItem("main"), notNullValue());
        assertThat(mp.getItem("feature-branch"), notNullValue());

        // feature-branch is deleted on remote
        when(api.getBranches()).thenAnswer(new Returns(Collections.singletonList(mainBranch)));

        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);

        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(mp.getItem("main"), notNullValue());
        assertThat(mp.getItem("feature-branch"), nullValue());
    }

    // -------------------------------------------------------------------------
    // getBranches() recovers after prior failure
    // -------------------------------------------------------------------------

    @Test
    public void getBranchesRecovery_shouldSucceedAfterPreviousIOExceptionFailure() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = buildMockedApi(sampleRepo, "henry", "recover");
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);

        WorkflowMultiBranchProject mp = createMultiBranchProject("recovery-test", "henry", "recover");

        // First scan: success
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        j.waitUntilNoActivity();
        assertThat(mp.getItem("main"), notNullValue());

        // Second scan: failure
        when(api.getBranches()).thenThrow(new IOException(message));
        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));

        // Restore normal behaviour
        BitbucketBranch branch = Mockito.mock(BitbucketBranch.class);
        when(branch.getName()).thenReturn("main");
        when(branch.getRawNode()).thenReturn(sampleRepo.head());
        when(api.getBranches()).thenAnswer(new Returns(Collections.singletonList(branch)));

        // Third scan: should succeed again
        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));

        WorkflowJob mainJob = mp.getItem("main");
        assertThat(mainJob, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(mainJob), not(instanceOf(Branch.Dead.class)));
    }

    // -------------------------------------------------------------------------
    // checkPathExists() returns false: branch should not be built
    // -------------------------------------------------------------------------

    @Test
    public void checkPathExistsReturnsFalse_shouldNotCreateBranchProject() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = Mockito.mock(BitbucketApi.class);

        BitbucketBranch branch = Mockito.mock(BitbucketBranch.class);
        when(branch.getName()).thenReturn("main");
        when(branch.getRawNode()).thenReturn(sampleRepo.head());
        when(api.getBranches()).thenAnswer(new Returns(Collections.singletonList(branch)));

        BitbucketCommit commit = Mockito.mock(BitbucketCommit.class);
        when(api.resolveCommit(sampleRepo.head())).thenReturn(commit);
        when(commit.getDateMillis()).thenReturn(System.currentTimeMillis());

        // Jenkinsfile does NOT exist on this branch
        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenReturn(false);

        when(api.getRepositoryUri(any(BitbucketRepositoryProtocol.class),
                anyString(), eq("ivan"), eq("nojenkinsfile"))).thenReturn(sampleRepo.fileUrl());

        BitbucketRepository repository = Mockito.mock(BitbucketRepository.class);
        when(api.getRepository()).thenReturn(repository);
        when(repository.getOwnerName()).thenReturn("ivan");
        when(repository.getRepositoryName()).thenReturn("nojenkinsfile");
        when(repository.getScm()).thenReturn("git");

        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);

        WorkflowMultiBranchProject mp = createMultiBranchProject("no-jenkinsfile-test", "ivan", "nojenkinsfile");

        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);

        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        // No branch project because Jenkinsfile is absent
        assertThat(mp.getItem("main"), nullValue());
    }

    // -------------------------------------------------------------------------
    // getBranches() succeeds but getRepository() fails
    // -------------------------------------------------------------------------

    @Test
    public void getRepositoryFails_shouldProduceFailureResult() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = Mockito.mock(BitbucketApi.class);

        // getRepository() throws on first call used during retrieve()
        when(api.getRepository()).thenThrow(new IOException(message));

        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);

        WorkflowMultiBranchProject mp = j.jenkins.createProject(WorkflowMultiBranchProject.class,
                "get-repo-fail-test");
        BitbucketSCMSource source = new BitbucketSCMSource("jack", "badrepo");
        source.setTraits(Arrays.asList(
                new BranchDiscoveryTrait(true, true),
                new OriginPullRequestDiscoveryTrait(EnumSet.of(ChangeRequestCheckoutStrategy.HEAD)),
                new ForkPullRequestDiscoveryTrait(
                        EnumSet.of(ChangeRequestCheckoutStrategy.HEAD),
                        new ForkPullRequestDiscoveryTrait.TrustTeamForks())));
        mp.getSourcesList().add(new BranchSource(source));

        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);

        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));
    }

    // -------------------------------------------------------------------------
    // getBranches() is actually called during scan (interaction verification)
    // -------------------------------------------------------------------------

    @Test
    public void successfulScan_shouldCallGetBranchesOnApi() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = buildMockedApi(sampleRepo, "karen", "verified");
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);

        WorkflowMultiBranchProject mp = createMultiBranchProject("interaction-test", "karen", "verified");

        mp.scheduleBuild2(0).getFuture().get();
        j.waitUntilNoActivity();

        verify(api, atLeastOnce()).getBranches();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(mp.getItem("main"), notNullValue());
    }

    // -------------------------------------------------------------------------
    // resolveCommit() called during scan (interaction verification)
    // -------------------------------------------------------------------------

    @Test
    public void successfulScan_shouldCallResolveCommitOnApi() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = buildMockedApi(sampleRepo, "lena", "commitcheck");
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);

        WorkflowMultiBranchProject mp = createMultiBranchProject("commit-interaction-test", "lena", "commitcheck");

        mp.scheduleBuild2(0).getFuture().get();
        j.waitUntilNoActivity();

        verify(api, atLeastOnce()).checkPathExists(anyString(), eq("Jenkinsfile"));
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a fully configured mock {@link BitbucketApi} backed by the given local git sample repository.
     */
    private BitbucketApi buildMockedApi(GitSampleRepoRule repo, String owner, String repoName) throws Exception {
        BitbucketApi api = Mockito.mock(BitbucketApi.class);

        BitbucketBranch branch = Mockito.mock(BitbucketBranch.class);
        when(branch.getName()).thenReturn("main");
        when(branch.getRawNode()).thenReturn(repo.head());
        when(api.getBranches()).thenAnswer(new Returns(Collections.singletonList(branch)));

        BitbucketCommit commit = Mockito.mock(BitbucketCommit.class);
        when(api.resolveCommit(repo.head())).thenReturn(commit);
        when(commit.getDateMillis()).thenReturn(System.currentTimeMillis());

        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenReturn(true);
        when(api.getRepositoryUri(any(BitbucketRepositoryProtocol.class),
                anyString(), eq(owner), eq(repoName))).thenReturn(repo.fileUrl());

        BitbucketRepository repository = Mockito.mock(BitbucketRepository.class);
        when(api.getRepository()).thenReturn(repository);
        when(repository.getOwnerName()).thenReturn(owner);
        when(repository.getRepositoryName()).thenReturn(repoName);
        when(repository.getScm()).thenReturn("git");

        return api;
    }

    /**
     * Creates a {@link WorkflowMultiBranchProject} with a {@link BitbucketSCMSource} configured with the
     * standard set of discovery traits.
     */
    private WorkflowMultiBranchProject createMultiBranchProject(String jobName, String owner, String repoName)
            throws IOException {
        WorkflowMultiBranchProject mp = j.jenkins.createProject(WorkflowMultiBranchProject.class, jobName);
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