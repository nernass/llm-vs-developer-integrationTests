package integration;

import com.cloudbees.jenkins.plugins.bitbucket.BitbucketMockApiFactory;
import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMSource;
import com.cloudbees.jenkins.plugins.bitbucket.BranchDiscoveryTrait;
import com.cloudbees.jenkins.plugins.bitbucket.ForkPullRequestDiscoveryTrait;
import com.cloudbees.jenkins.plugins.bitbucket.OriginPullRequestDiscoveryTrait;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketApi;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketBranch;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketCommit;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketPullRequest;
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
public class BitbucketSCMSourceScanningFailuresTest {

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
        message = "We gonna Boom Boom Boom 'til the break of Boom. " + Long.toHexString(entropy.nextLong()) +
                " Who's the Boom King? Who? I'm the Boom King!";
    }

    // ------------------------------------------------------------------------
    // getBranches() failures
    // ------------------------------------------------------------------------

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

    private void getBranchesFails(Callable<Throwable> exception, Result expectedResult) throws Exception {
        // Setup initial successful scan
        BitbucketApi api = setupMockApiWithBranch();

        WorkflowMultiBranchProject mp = createMultiBranchProject(api);

        // First scan succeeds
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();
        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());

        // Now cause getBranches to fail
        when(api.getBranches()).thenThrow(exception.call());

        if (Result.NOT_BUILT.equals(expectedResult) || Result.ABORTED.equals(expectedResult)) {
            mp.scheduleBuild2(0);
            j.waitUntilNoActivity();
            assertThat(mp.getIndexing().getResult(), is(expectedResult));
        } else {
            mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);
            assertThat(mp.getIndexing().getResult(), is(expectedResult));
            assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));
        }

        // Branch should survive (not be marked dead) even though scan failed
        master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(master), not(instanceOf(Branch.Dead.class)));
    }

    // ------------------------------------------------------------------------
    // checkPathExists() failure
    // ------------------------------------------------------------------------

    @Test
    public void checkPathExistsFails() throws Exception {
        BitbucketApi api = setupMockApiWithBranch();

        WorkflowMultiBranchProject mp = createMultiBranchProject(api);

        // First scan succeeds
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();
        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());

        // Cause checkPathExists to fail
        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenThrow(new IOException(message));

        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));

        master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(master), not(instanceOf(Branch.Dead.class)));
    }

    // ------------------------------------------------------------------------
    // resolveCommit() failure
    // ------------------------------------------------------------------------

    @Test
    public void resolveCommitFails() throws Exception {
        BitbucketApi api = setupMockApiWithBranch();

        WorkflowMultiBranchProject mp = createMultiBranchProject(api);

        // First scan succeeds
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();
        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(master.getLastBuild(), notNullValue());
        assertThat(master.getNextBuildNumber(), is(2));

        // Cause resolveCommit to fail (should be gracefully handled)
        when(api.resolveCommit(sampleRepo.head())).thenThrow(new IOException(message));

        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));

        master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(master), not(instanceOf(Branch.Dead.class)));
        assertThat(master.getLastBuild(), notNullValue());
        assertThat(master.getNextBuildNumber(), is(2));
    }

    // ------------------------------------------------------------------------
    // Branch removal (getBranches returns empty)
    // ------------------------------------------------------------------------

    @Test
    public void branchRemoved() throws Exception {
        BitbucketApi api = setupMockApiWithBranch();

        WorkflowMultiBranchProject mp = createMultiBranchProject(api);

        // First scan succeeds
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();
        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(master.getLastBuild(), notNullValue());
        assertThat(master.getNextBuildNumber(), is(2));

        // Branch removed from remote
        when(api.getBranches()).thenAnswer(new Returns(Collections.emptyList()));

        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        master = mp.getItem("main");
        assertThat(master, nullValue());
    }

    // ------------------------------------------------------------------------
    // Pull request discovery failures
    // ------------------------------------------------------------------------

    @Test
    public void getPullRequestsFailsWithIOException() throws Exception {
        // Setup a mock that includes a pull request
        BitbucketApi api = setupMockApiWithBranchAndPullRequest();

        WorkflowMultiBranchProject mp = createMultiBranchProject(api);

        // First scan succeeds
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();
        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());
        // There should be a PR job (the PR discovery is enabled)
        WorkflowJob prJob = mp.getItem("PR-1");
        assertThat(prJob, notNullValue());

        // Now cause getPullRequests to fail
        when(api.getPullRequests()).thenThrow(new IOException(message));

        mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);
        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));

        // Existing branch and PR jobs should remain (not dead)
        master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(master), not(instanceOf(Branch.Dead.class)));
        prJob = mp.getItem("PR-1");
        assertThat(prJob, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(prJob), not(instanceOf(Branch.Dead.class)));
    }

    @Test
    public void getPullRequestsFailsWithInterruptedException() throws Exception {
        BitbucketApi api = setupMockApiWithBranchAndPullRequest();

        WorkflowMultiBranchProject mp = createMultiBranchProject(api);

        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        j.waitUntilNoActivity();

        when(api.getPullRequests()).thenThrow(new InterruptedException(message));

        mp.scheduleBuild2(0);
        j.waitUntilNoActivity();
        assertThat(mp.getIndexing().getResult(), is(Result.ABORTED));
    }

    // ------------------------------------------------------------------------
    // Helper methods
    // ------------------------------------------------------------------------

    private BitbucketApi setupMockApiWithBranch() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile",
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketApi api = Mockito.mock(BitbucketApi.class);

        BitbucketBranch branch = Mockito.mock(BitbucketBranch.class);
        List<? extends BitbucketBranch> branchList = Collections.singletonList(branch);
        when(api.getBranches()).thenAnswer(new Returns(branchList));
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

        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);

        return api;
    }

    private BitbucketApi setupMockApiWithBranchAndPullRequest() throws Exception {
        BitbucketApi api = setupMockApiWithBranch();

        // Add a pull request
        BitbucketPullRequest pr = Mockito.mock(BitbucketPullRequest.class);
        when(pr.getId()).thenReturn("1");
        when(pr.getTitle()).thenReturn("Test PR");

        // Need to mock source and destination repository/branch
        BitbucketPullRequest.Source source = Mockito.mock(BitbucketPullRequest.Source.class);
        BitbucketPullRequest.Destination destination = Mockito.mock(BitbucketPullRequest.Destination.class);
        BitbucketBranch sourceBranch = Mockito.mock(BitbucketBranch.class);
        BitbucketBranch destBranch = Mockito.mock(BitbucketBranch.class);
        BitbucketRepository sourceRepo = Mockito.mock(BitbucketRepository.class);
        BitbucketRepository destRepo = Mockito.mock(BitbucketRepository.class);

        when(pr.getSource()).thenReturn(source);
        when(pr.getDestination()).thenReturn(destination);
        when(source.getBranch()).thenReturn(sourceBranch);
        when(destination.getBranch()).thenReturn(destBranch);
        when(source.getRepository()).thenReturn(sourceRepo);
        when(destination.getRepository()).thenReturn(destRepo);
        when(sourceBranch.getName()).thenReturn("feature");
        when(destBranch.getName()).thenReturn("main");
        when(sourceBranch.getRawNode()).thenReturn(sampleRepo.head());
        when(destBranch.getRawNode()).thenReturn(sampleRepo.head());
        when(sourceRepo.getFullName()).thenReturn("bob/foo");
        when(sourceRepo.getOwnerName()).thenReturn("bob");
        when(sourceRepo.getRepositoryName()).thenReturn("foo");
        when(destRepo.getFullName()).thenReturn("bob/foo");
        when(destRepo.getOwnerName()).thenReturn("bob");
        when(destRepo.getRepositoryName()).thenReturn("foo");

        when(api.getPullRequests()).thenReturn(Collections.singletonList(pr));
        // For PR retrieval, getPullRequestById is called during initialisation
        when(api.getPullRequestById(1)).thenReturn(pr);

        return api;
    }

    private WorkflowMultiBranchProject createMultiBranchProject(BitbucketApi api) throws Exception {
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);

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
}