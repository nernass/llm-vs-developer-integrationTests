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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Random;
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
import org.mockito.internal.stubbing.answers.Returns;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;



public class claudeIT_1 {

    @ClassRule
    public static JenkinsRule j = new JenkinsRule();

    private static final Random entropy = new Random();

    @Rule
    public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

    private String message;
    private BitbucketApi api;
    private BitbucketRepository repository;

    @Before
    public void resetEnvironment() throws Exception {
        for (TopLevelItem i : j.getInstance().getItems()) {
            i.delete();
        }
        BitbucketMockApiFactory.clear();
        message = "TestError_" + Long.toHexString(entropy.nextLong());
    }

    private void initSampleRepo() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile", "echo test");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=InitialCommit");
    }

    private BitbucketApi createMockApi () throws Exception {
        api = Mockito.mock(BitbucketApi.class);
        repository = Mockito.mock(BitbucketRepository.class);

        when(api.getRepository()).thenReturn(repository);
        when(repository.getOwnerName()).thenReturn("bob");
        when(repository.getRepositoryName()).thenReturn("foo");
        when(repository.getScm()).thenReturn("git");

        when(api.getRepositoryUri(any(BitbucketRepositoryProtocol.class),
            anyString(), eq("bob"), eq("foo")))
            .thenReturn(sampleRepo.fileUrl());

        return api;
    }

    private BitbucketBranch createMockBranch(String name, String hash) {
        BitbucketBranch branch = Mockito.mock(BitbucketBranch.class);
        when(branch.getName()).thenReturn(name);
        when(branch.getRawNode()).thenReturn(hash);
        return branch;
    }

    private BitbucketSCMSource createSource() {
        BitbucketSCMSource source = new BitbucketSCMSource("bob", "foo");
        source.setTraits(Arrays.asList(
            new BranchDiscoveryTrait(true, true),
            new OriginPullRequestDiscoveryTrait(EnumSet.of(ChangeRequestCheckoutStrategy.HEAD)),
            new ForkPullRequestDiscoveryTrait(
                EnumSet.of(ChangeRequestCheckoutStrategy.HEAD),
                new ForkPullRequestDiscoveryTrait.TrustTeamForks()
            )
        ));
        return source;
    }

    @Test
    public void shouldDiscoverMultipleBranchesOnInitialScan() throws Exception {
        initSampleRepo();
        BitbucketApi api = createMockApi();

        String hash = sampleRepo.head();
        BitbucketBranch main = createMockBranch("main", hash);
        BitbucketBranch develop = createMockBranch("develop", hash);

        when(api.getBranches()).thenAnswer(new Returns(Arrays.asList(main, develop)));

        BitbucketCommit commit = Mockito.mock(BitbucketCommit.class);
        when(api.resolveCommit(hash)).thenReturn(commit);
        when(commit.getDateMillis()).thenReturn(System.currentTimeMillis());
        when(api.checkPathExists(anyString(), eq("Jenkinsfile"))).thenReturn(true);

        BitbucketMockApiFactory.add(BitbucketCloudEndpoint.SERVER_URL, api);

        WorkflowMultiBranchProject mp =
            j.jenkins.createProject(WorkflowMultiBranchProject.class, "multi-branch");

        mp.getSourcesList().add(new BranchSource(createSource()));

        mp.scheduleBuild2(0).getFuture().get();
        j.waitUntilNoActivity();

        assertThat(mp.getIndexing().getResult(), is(Result.SUCCESS));
        assertThat(mp.getItem("main"), notNullValue());
        assertThat(mp.getItem("develop"), notNullValue());
    }
}

