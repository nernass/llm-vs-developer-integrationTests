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

/**
 * Integration tests for BitbucketSCMSource behavior during repository scanning failures.
 * Extends the original test coverage with additional failure scenarios for pull requests.
 */
@Issue("JENKINS-36029")
public class deepseek {

    @ClassRule
    public static JenkinsRule j = new JenkinsRule();
    private static final Random entropy = new Random();

    @Rule
    public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

    private String message;

    @Before
    public void resetEnvironment() throws Exception {
        for (TopLevelItem i : j.getInstance().getItems()) {
            i.delete();
        }
        BitbucketMockApiFactory.clear();
        message = "Test exception: " + Long.toHexString(entropy.nextLong());
    }

    // ======================== Branch failures ========================

    @Test
    public void getBranchesFailsWithIOException() throws Exception {
        getBranchesFails(() -> new IOException(message), Result.FAILURE);
    }

    @Test
    public void getBranchesFailsWithInterruptedException() throws Exception {
        getBranchesFails(() -> new InterruptedException(message), Result.ABORTED);
    }

    @Test
    public void getBranchesFailsWithRuntimeException() throws Exception {
        getBranchesFails(() -> new RuntimeException(message), Result.FAILURE);
    }

    @Test
    public void getBranchesFailsWithError() throws Exception {
        getBranchesFails(() -> new Error(message), Result.NOT_BUILT);
    }

    private void getBranchesFails(ThrowingSupplier<Throwable> exceptionSupplier, Result expectedResult) throws Exception {
        BitbucketApi api = setupMockApi();
        WorkflowMultiBranchProject mp = createMultiBranchProject(api);

        runInitialIndexing(mp);

        when(api.getBranches()).thenThrow(exceptionSupplier.get());

        if (Result.NOT_BUILT.equals(expectedResult) || Result.ABORTED.equals(expectedResult)) {
            mp.scheduleBuild2(0);
            j.waitUntilNoActivityUpTo(30_000);
            assertThat(mp.getIndexing().getResult(), is(expectedResult));
        } else {
            mp.scheduleBuild2(0).getFuture().get(30, TimeUnit.SECONDS);
            assertThat(mp.getIndexing().getResult(), is(expectedResult));
            assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));
        }

        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(master), not(instanceOf(Branch.Dead.class)));
    }

    @Test
    public void checkPathExistsFails() throws Exception {
        BitbucketApi api = setupMockApi();
        WorkflowMultiBranchProject mp = createMultiBranchProject(api);
        runInitialIndexing(mp);

        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenThrow(new IOException(message));

        mp.scheduleBuild2(0).getFuture().get(30, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));

        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(master), not(instanceOf(Branch.Dead.class)));
    }

    @Test
    public void resolveCommitFails() throws Exception {
        BitbucketApi api = setupMockApi();
        WorkflowMultiBranchProject mp = createMultiBranchProject(api);
        runInitialIndexing(mp);

        when(api.resolveCommit(sampleRepo.head())).thenThrow(new IOException(message));

        mp.scheduleBuild2(0).getFuture().get(30, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));

        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(master), not(instanceOf(Branch.Dead.class)));
    }

    @Test
    public void branchRemoved() throws Exception {
        BitbucketApi api = setupMockApi();
        WorkflowMultiBranchProject mp = createMultiBranchProject(api);
        runInitialIndexing(mp);

        Mockito.doReturn(Collections.emptyList()).when(api).getBranches();

        mp.scheduleBuild2(0).getFuture().get(30, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        WorkflowJob master = mp.getItem("main");
        assertThat(master, nullValue());
    }

    // ======================== Pull request failures ========================

    @Test
    public void getPullRequestsFailsWithIOException() throws Exception {
        BitbucketApi api = setupMockApi();
        WorkflowMultiBranchProject mp = createMultiBranchProject(api);
        runInitialIndexing(mp);

        when(api.getPullRequests()).thenThrow(new IOException(message));

        mp.scheduleBuild2(0).getFuture().get(30, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));

        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(master), not(instanceOf(Branch.Dead.class)));
    }

    @Test
    public void getPullRequestsFailsWithInterruptedException() throws Exception {
        BitbucketApi api = setupMockApi();
        WorkflowMultiBranchProject mp = createMultiBranchProject(api);
        runInitialIndexing(mp);

        when(api.getPullRequests()).thenThrow(new InterruptedException(message));

        mp.scheduleBuild2(0);
        j.waitUntilNoActivityUpTo(30_000);
        assertThat(mp.getIndexing().getResult(), is(Result.ABORTED));
    }

    @Test
    public void getPullRequestsFailsWithRuntimeException() throws Exception {
        BitbucketApi api = setupMockApi();
        WorkflowMultiBranchProject mp = createMultiBranchProject(api);
        runInitialIndexing(mp);

        when(api.getPullRequests()).thenThrow(new RuntimeException(message));

        mp.scheduleBuild2(0).getFuture().get(30, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));
    }

    // ======================== Tag-related stable checks ========================

    @Test
    public void tagLookupFailureDoesNotAffectScanningWhenTagsAreNotDiscovered() throws Exception {
        BitbucketApi api = setupMockApi();
        WorkflowMultiBranchProject mp = createMultiBranchProjectWithTags(api);
        runInitialIndexing(mp);

        when(api.getTags()).thenThrow(new IOException(message));

        mp.scheduleBuild2(0).getFuture().get(30, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
    }

    @Test
    public void tagLookupInterruptedExceptionIsIgnoredWhenTagsAreNotDiscovered() throws Exception {
        BitbucketApi api = setupMockApi();
        WorkflowMultiBranchProject mp = createMultiBranchProjectWithTags(api);
        runInitialIndexing(mp);

        when(api.getTags()).thenThrow(new InterruptedException(message));

        mp.scheduleBuild2(0).getFuture().get(30, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
    }

    // ======================== Repository metadata failures ========================

    @Test
    public void getRepositoryFailsDuringInitialization() throws Exception {
        BitbucketApi api = Mockito.mock(BitbucketApi.class);
        when(api.getRepository()).thenThrow(new IOException(message));
        when(api.getOwner()).thenReturn("bob");

        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);

        WorkflowMultiBranchProject mp = j.jenkins.createProject(WorkflowMultiBranchProject.class, "smokes");
        BitbucketSCMSource source = new BitbucketSCMSource("bob", "foo");
        source.setTraits(Collections.singletonList(new BranchDiscoveryTrait(true, true)));
        mp.getSourcesList().add(new BranchSource(source));

        mp.scheduleBuild2(0).getFuture().get(30, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));
    }

    @Test
    public void getRepositoryFailsDuringSubsequentScan() throws Exception {
        BitbucketApi api = setupMockApi();
        WorkflowMultiBranchProject mp = createMultiBranchProject(api);
        runInitialIndexing(mp);

        when(api.getRepository()).thenThrow(new IOException(message));

        mp.scheduleBuild2(0).getFuture().get(30, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));
    }

    // ======================== Helper methods ========================

    private BitbucketApi setupMockApi() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile", "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = Mockito.mock(BitbucketApi.class);

        BitbucketBranch branch = Mockito.mock(BitbucketBranch.class);
        List<BitbucketBranch> branchList = Collections.singletonList(branch);
        Mockito.doReturn(branchList).when(api).getBranches();

        when(branch.getName()).thenReturn("main");
        when(branch.getRawNode()).thenReturn(sampleRepo.head());

        BitbucketCommit commit = Mockito.mock(BitbucketCommit.class);
        when(api.resolveCommit(sampleRepo.head())).thenReturn(commit);
        when(commit.getDateMillis()).thenReturn(System.currentTimeMillis());

        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenReturn(true);

        when(api.getRepositoryUri(any(BitbucketRepositoryProtocol.class),
            anyString(), eq("bob"), eq("foo"))).thenReturn(sampleRepo.fileUrl());

        BitbucketRepository repository = Mockito.mock(BitbucketRepository.class);
        when(api.getRepository()).thenReturn(repository);
        when(repository.getOwnerName()).thenReturn("bob");
        when(repository.getRepositoryName()).thenReturn("foo");
        when(repository.getScm()).thenReturn("git");

        Mockito.doReturn(Collections.emptyList()).when(api).getPullRequests();
        Mockito.doReturn(Collections.emptyList()).when(api).getTags();

        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        return api;
    }

    private WorkflowMultiBranchProject createMultiBranchProject(BitbucketApi api) throws IOException {
        WorkflowMultiBranchProject mp = j.jenkins.createProject(WorkflowMultiBranchProject.class, "smokes");
        BitbucketSCMSource source = new BitbucketSCMSource("bob", "foo");
        source.setTraits(Arrays.asList(
            new BranchDiscoveryTrait(true, true),
            new OriginPullRequestDiscoveryTrait(EnumSet.of(ChangeRequestCheckoutStrategy.HEAD)),
            new ForkPullRequestDiscoveryTrait(
                EnumSet.of(ChangeRequestCheckoutStrategy.HEAD),
                new ForkPullRequestDiscoveryTrait.TrustTeamForks()
            )
        ));
        mp.getSourcesList().add(new BranchSource(source));
        return mp;
    }

    private WorkflowMultiBranchProject createMultiBranchProjectWithTags(BitbucketApi api) throws IOException {
        return createMultiBranchProject(api);
    }

    private void runInitialIndexing(WorkflowMultiBranchProject mp) throws Exception {
        mp.scheduleBuild2(0).getFuture().get(30, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));

        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
