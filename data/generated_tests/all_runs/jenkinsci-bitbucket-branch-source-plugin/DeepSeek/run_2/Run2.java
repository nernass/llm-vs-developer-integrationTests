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
public class BitbucketSCMSourceFailureHandlingTest {

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

    // ---------- Existing tests from reference (branches, checkPath, resolveCommit, branchRemoved) ----------

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
        // initial setup: successful scan
        BitbucketApi api = createMockApiWithBranch("main", sampleRepo.head());
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject(api);

        // first scan succeeds
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();
        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());

        // second scan fails in getBranches
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
        master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(master), not(instanceOf(Branch.Dead.class)));
    }

    @Test
    public void checkPathExistsFails() throws Exception {
        BitbucketApi api = createMockApiWithBranch("main", sampleRepo.head());
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject(api);

        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();
        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());

        // second scan fails in checkPathExists
        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenThrow(new IOException(message));

        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.FAILURE));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));
        master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(master), not(instanceOf(Branch.Dead.class)));
    }

    @Test
    public void resolveCommitFails() throws Exception {
        BitbucketApi api = createMockApiWithBranch("main", sampleRepo.head());
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject(api);

        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();
        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(master.getLastBuild(), notNullValue());
        assertThat(master.getNextBuildNumber(), is(2));

        // second scan fails in resolveCommit
        when(api.resolveCommit(sampleRepo.head())).thenThrow(new IOException(message));

        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(master), not(instanceOf(Branch.Dead.class)));
        assertThat(master.getLastBuild(), notNullValue());
        assertThat(master.getNextBuildNumber(), is(2));
    }

    @Test
    public void branchRemoved() throws Exception {
        BitbucketApi api = createMockApiWithBranch("main", sampleRepo.head());
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject(api);

        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();
        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(master.getLastBuild(), notNullValue());
        assertThat(master.getNextBuildNumber(), is(2));

        // second scan returns empty branches
        when(api.getBranches()).thenReturn(Collections.emptyList());

        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        master = mp.getItem("main");
        assertThat(master, nullValue());
    }

    // ---------- New tests for pull request failures ----------

    @Test
    public void getPullRequestsFailsWithIOException() throws Exception {
        getPullRequestsFails(() -> new IOException(message), Result.FAILURE);
    }

    @Test
    public void getPullRequestsFailsWithInterruptedException() throws Exception {
        getPullRequestsFails(() -> new InterruptedException(message), Result.ABORTED);
    }

    @Test
    public void getPullRequestsFailsWithRuntimeException() throws Exception {
        getPullRequestsFails(() -> new RuntimeException(message), Result.FAILURE);
    }

    @Test
    public void getPullRequestsFailsWithError() throws Exception {
        getPullRequestsFails(() -> new Error(message), Result.NOT_BUILT);
    }

    private void getPullRequestsFails(Callable<Throwable> exception, Result expectedResult) throws Exception {
        // Setup with a branch and a pull request
        BitbucketApi api = createMockApiWithBranchAndPR("main", "feature", sampleRepo.head());
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject(api);

        // first scan succeeds
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();
        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());
        // PR job should exist
        WorkflowJob prJob = mp.getItem("PR-1");
        assertThat(prJob, notNullValue());

        // second scan fails in getPullRequests
        when(api.getPullRequests()).thenThrow(exception.call());

        if (Result.NOT_BUILT.equals(expectedResult) || Result.ABORTED.equals(expectedResult)) {
            mp.scheduleBuild2(0);
            j.waitUntilNoActivity();
            assertThat(mp.getIndexing().getResult(), is(expectedResult));
        } else {
            mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);
            assertThat(mp.getIndexing().getResult(), is(expectedResult));
            assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));
        }
        // Branch should still exist and not be dead
        master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(master), not(instanceOf(Branch.Dead.class)));
        // PR job may still exist (if not removed) but might be dead? We just check it's not null, but if scan fails early, it might remain.
        // However, we don't assert on PR job because failure may prevent PR processing.
    }

    // ---------- New tests for tag failures ----------

    @Test
    public void getTagsFailsWithIOException() throws Exception {
        getTagsFails(() -> new IOException(message), Result.FAILURE);
    }

    @Test
    public void getTagsFailsWithInterruptedException() throws Exception {
        getTagsFails(() -> new InterruptedException(message), Result.ABORTED);
    }

    @Test
    public void getTagsFailsWithRuntimeException() throws Exception {
        getTagsFails(() -> new RuntimeException(message), Result.FAILURE);
    }

    @Test
    public void getTagsFailsWithError() throws Exception {
        getTagsFails(() -> new Error(message), Result.NOT_BUILT);
    }

    private void getTagsFails(Callable<Throwable> exception, Result expectedResult) throws Exception {
        // Setup with a branch and a tag
        BitbucketApi api = createMockApiWithBranchAndTag("main", "v1.0", sampleRepo.head());
        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);
        WorkflowMultiBranchProject mp = createMultiBranchProject(api);

        // first scan succeeds
        mp.scheduleBuild2(0).getFuture().get();
        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), not(containsString(message)));
        j.waitUntilNoActivity();
        WorkflowJob master = mp.getItem("main");
        assertThat(master, notNullValue());
        // Tag job may not be created by default unless we have a TagSCMHead category; for simplicity we don't assert tag job.

        // second scan fails in getTags
        when(api.getTags()).thenThrow(exception.call());

        if (Result.NOT_BUILT.equals(expectedResult) || Result.ABORTED.equals(expectedResult)) {
            mp.scheduleBuild2(0);
            j.waitUntilNoActivity();
            assertThat(mp.getIndexing().getResult(), is(expectedResult));
        } else {
            mp.scheduleBuild2(0).getFuture().get(10, TimeUnit.SECONDS);
            assertThat(mp.getIndexing().getResult(), is(expectedResult));
            assertThat(FileUtils.readFileToString(mp.getIndexing().getLogFile()), containsString(message));
        }
        // Branch should still exist and not be dead
        master = mp.getItem("main");
        assertThat(master, notNullValue());
        assertThat(mp.getProjectFactory().getBranch(master), not(instanceOf(Branch.Dead.class)));
    }

    // ---------- Helper methods ----------

    private WorkflowMultiBranchProject createMultiBranchProject(BitbucketApi api) throws Exception {
        // Ensure we have a local Git repo with a Jenkinsfile
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile", "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file')}");
        sampleRepo.write("file", "initial content");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        // Create the project
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

        // Also ensure the mock API returns the repository URI for Git operations
        when(api.getRepositoryUri(any(BitbucketRepositoryProtocol.class),
                anyString(), eq("bob"), eq("foo"))).thenReturn(sampleRepo.fileUrl());

        return mp;
    }

    /**
     * Creates a mock BitbucketApi with a single branch.
     */
    private BitbucketApi createMockApiWithBranch(String branchName, String commitHash) throws IOException, InterruptedException {
        BitbucketApi api = Mockito.mock(BitbucketApi.class);

        // Branch
        BitbucketBranch branch = Mockito.mock(BitbucketBranch.class);
        when(branch.getName()).thenReturn(branchName);
        when(branch.getRawNode()).thenReturn(commitHash);
        when(api.getBranches()).thenReturn(Collections.singletonList(branch));

        // Commit
        BitbucketCommit commit = Mockito.mock(BitbucketCommit.class);
        when(api.resolveCommit(commitHash)).thenReturn(commit);
        when(commit.getDateMillis()).thenReturn(System.currentTimeMillis());

        // Path check
        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenReturn(true);

        // Repository
        BitbucketRepository repository = Mockito.mock(BitbucketRepository.class);
        when(api.getRepository()).thenReturn(repository);
        when(repository.getOwnerName()).thenReturn("bob");
        when(repository.getRepositoryName()).thenReturn("foo");
        when(repository.getScm()).thenReturn("git");

        return api;
    }

    /**
     * Creates a mock BitbucketApi with a branch and a pull request.
     */
    private BitbucketApi createMockApiWithBranchAndPR(String branchName, String prSourceBranch, String commitHash) throws IOException, InterruptedException {
        BitbucketApi api = createMockApiWithBranch(branchName, commitHash);

        // Pull request
        BitbucketPullRequest pr = Mockito.mock(BitbucketPullRequest.class);
        when(pr.getId()).thenReturn("1");
        when(pr.getTitle()).thenReturn("Test PR");

        // Source branch of PR
        BitbucketBranch sourceBranch = Mockito.mock(BitbucketBranch.class);
        when(sourceBranch.getName()).thenReturn(prSourceBranch);
        when(sourceBranch.getRawNode()).thenReturn(commitHash);
        BitbucketPullRequest.Source source = Mockito.mock(BitbucketPullRequest.Source.class);
        when(source.getBranch()).thenReturn(sourceBranch);
        BitbucketRepository sourceRepo = Mockito.mock(BitbucketRepository.class);
        when(sourceRepo.getFullName()).thenReturn("bob/foo");
        when(sourceRepo.getOwnerName()).thenReturn("bob");
        when(sourceRepo.getRepositoryName()).thenReturn("foo");
        when(source.getRepository()).thenReturn(sourceRepo);
        when(pr.getSource()).thenReturn(source);

        // Destination branch of PR (target)
        BitbucketBranch destBranch = Mockito.mock(BitbucketBranch.class);
        when(destBranch.getName()).thenReturn(branchName);
        BitbucketPullRequest.Destination dest = Mockito.mock(BitbucketPullRequest.Destination.class);
        when(dest.getBranch()).thenReturn(destBranch);
        when(pr.getDestination()).thenReturn(dest);

        when(api.getPullRequests()).thenReturn(Collections.singletonList(pr));

        // Additional mock for PR retrieval if needed (but not called without event)
        when(api.getPullRequestById(1)).thenReturn(pr);

        return api;
    }

    /**
     * Creates a mock BitbucketApi with a branch and a tag.
     */
    private BitbucketApi createMockApiWithBranchAndTag(String branchName, String tagName, String commitHash) throws IOException, InterruptedException {
        BitbucketApi api = createMockApiWithBranch(branchName, commitHash);

        // Tag
        BitbucketBranch tag = Mockito.mock(BitbucketBranch.class);
        when(tag.getName()).thenReturn(tagName);
        when(tag.getRawNode()).thenReturn(commitHash);
        when(tag.getDateMillis()).thenReturn(System.currentTimeMillis());
        when(api.getTags()).thenReturn(Collections.singletonList(tag));

        return api;
    }
}