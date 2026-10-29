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
import java.util.Random;
import jenkins.branch.BranchSource;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mockito;
import org.mockito.internal.stubbing.answers.Returns;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;


public class GeminiIT {

    @ClassRule
    public static JenkinsRule j = new JenkinsRule();

    private static final Random entropy = new Random();

    @Rule
    public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

    private String errorMessage;
    private BitbucketApi mockApi;

    @Before
    public void setUp() throws Exception {
        for (TopLevelItem i : j.getInstance().getItems()) {
            i.delete();
        }
        BitbucketMockApiFactory.clear();
        errorMessage = "Test simulated failure - " + Long.toHexString(entropy.nextLong());
        mockApi = Mockito.mock(BitbucketApi.class);
    }

    @After
    public void tearDown() throws Exception {
        BitbucketMockApiFactory.clear();
        for (TopLevelItem i : j.getInstance().getItems()) {
            i.delete();
        }
    }

    @Test
    public void shouldReturnFailureWhenGetBranchesThrowsIoException() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile", "echo test");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");

        BitbucketBranch branch = Mockito.mock(BitbucketBranch.class);
        when(mockApi.getBranches()).thenAnswer(new Returns(Collections.singletonList(branch)));
        when(branch.getName()).thenReturn("main");
        when(branch.getRawNode()).thenReturn(sampleRepo.head());

        BitbucketCommit commit = Mockito.mock(BitbucketCommit.class);
        when(mockApi.resolveCommit(sampleRepo.head())).thenReturn(commit);
        when(commit.getDateMillis()).thenReturn(System.currentTimeMillis());

        when(mockApi.checkPathExists(anyString(), eq("Jenkinsfile"))).thenReturn(true);

        BitbucketRepository repository = Mockito.mock(BitbucketRepository.class);
        when(mockApi.getRepository()).thenReturn(repository);
        when(repository.getOwnerName()).thenReturn("testowner");
        when(repository.getRepositoryName()).thenReturn("testrepo");
        when(repository.getScm()).thenReturn("git");

        when(mockApi.getRepositoryUri(any(BitbucketRepositoryProtocol.class),
            anyString(), eq("testowner"), eq("testrepo")))
            .thenReturn(sampleRepo.fileUrl());

        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, mockApi);

        WorkflowMultiBranchProject mbp =
            j.jenkins.createProject(WorkflowMultiBranchProject.class, "test-mbp");

        BitbucketSCMSource source = new BitbucketSCMSource("testowner", "testrepo");
        source.setTraits(Arrays.asList(
            new BranchDiscoveryTrait(true, true),
            new OriginPullRequestDiscoveryTrait(EnumSet.of(ChangeRequestCheckoutStrategy.HEAD)),
            new ForkPullRequestDiscoveryTrait(
                EnumSet.of(ChangeRequestCheckoutStrategy.HEAD),
                new ForkPullRequestDiscoveryTrait.TrustTeamForks()
            )
        ));

        mbp.getSourcesList().add(new BranchSource(source));

        mbp.scheduleBuild2(0).getFuture().get();
        j.waitUntilNoActivity();

        when(mockApi.getBranches()).thenThrow(new IOException(errorMessage));

        mbp.scheduleBuild2(0).getFuture().get();

        assertThat(mbp.getIndexing().getResult(), is(Result.FAILURE));
    }
}
