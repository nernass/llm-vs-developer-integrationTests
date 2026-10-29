/*
 * The MIT License
 *
 * Copyright (C) 2010-2011 by Anthony Robinson
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package jenkins.plugins.publish_over_ftp.jenkins;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import jenkins.plugins.publish_over_ftp.BapFtpHostConfiguration;
import jenkins.plugins.publish_over_ftp.BapFtpPublisher;
import jenkins.plugins.publish_over_ftp.BapFtpPublisherPlugin;
import jenkins.plugins.publish_over_ftp.BapFtpTransfer;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.SignatureDeclareThrowsException")
public class IntegrationTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private static final String TEST_PASSWORD = "testPassword";
    private static final String CONFIG_NAME = "testConfig";
    private static final String HOSTNAME = "testHostname";
    private static final String USERNAME = "testUsername";
    private static final int PORT = 21;
    private static final int TIMEOUT = 3000;

    private static final String CONTROL_ENCODING = "UTF-8";
    private static final String REMOVE_PREFIX = "target";
    private static final String REMOTE_ROOT = "/testRemoteRoot";
    private static final String REMOTE_DIRECTORY = "sub-home";
    private static final String BUILD_DIRECTORY = "build-dir";
    private static final String BUILD_FILE_NAME = "file.txt";
    private static final String BUILD_FILE_CONTENT = "Helloooooo";
    private static final String STORE_FAILURE_REPLY = "permission denied";

    private JenkinsTestHelper jenkinsTestHelper;
    private FTPClient mockFTPClient;

    @Before
    public void setUp() {
        jenkinsTestHelper = new JenkinsTestHelper();
        mockFTPClient = mock(FTPClient.class);
    }

    @After
    public void tearDown() throws Exception {
        for (FreeStyleProject project : j.jenkins.getAllItems(FreeStyleProject.class)) {
            project.delete();
        }
        jenkinsTestHelper.setGlobalConfig();
        reset(mockFTPClient);
    }

    @Test
    public void testIntegration() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, false);
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(true), false, false));

        stubSuccessfulConnection(hostConfiguration);
        when(mockFTPClient.setFileType(FTP.ASCII_FILE_TYPE)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(REMOTE_DIRECTORY)).thenReturn(false).thenReturn(true);
        when(mockFTPClient.makeDirectory(REMOTE_DIRECTORY)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(BUILD_DIRECTORY)).thenReturn(false).thenReturn(true);
        when(mockFTPClient.makeDirectory(BUILD_DIRECTORY)).thenReturn(true);
        when(mockFTPClient.storeFile(eq(BUILD_FILE_NAME), any())).thenReturn(true);

        j.assertBuildStatusSuccess(project.scheduleBuild2(0).get());

        verify(mockFTPClient).connect(HOSTNAME, PORT);
        verify(mockFTPClient).storeFile(eq(BUILD_FILE_NAME), any());
        verify(mockFTPClient).setDefaultTimeout(TIMEOUT);
        verify(mockFTPClient).setDataTimeout(TIMEOUT);
    }

    @Test
    public void shouldUploadWorkspaceArtifactWhenRemoteDirectoriesNeedCreating() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, false);
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(true), false, true));

        stubSuccessfulConnection(hostConfiguration);
        when(mockFTPClient.setFileType(FTP.ASCII_FILE_TYPE)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(REMOTE_DIRECTORY)).thenReturn(false).thenReturn(true);
        when(mockFTPClient.makeDirectory(REMOTE_DIRECTORY)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(BUILD_DIRECTORY)).thenReturn(false).thenReturn(true);
        when(mockFTPClient.makeDirectory(BUILD_DIRECTORY)).thenReturn(true);
        when(mockFTPClient.storeFile(eq(BUILD_FILE_NAME), any())).thenReturn(true);

        j.assertBuildStatusSuccess(project.scheduleBuild2(0).get());

        verify(mockFTPClient).connect(HOSTNAME, PORT);
        verify(mockFTPClient).changeWorkingDirectory(REMOTE_ROOT);
        verify(mockFTPClient).makeDirectory(REMOTE_DIRECTORY);
        verify(mockFTPClient).makeDirectory(BUILD_DIRECTORY);
        verify(mockFTPClient).storeFile(eq(BUILD_FILE_NAME), any());
        verify(mockFTPClient).setDefaultTimeout(TIMEOUT);
        verify(mockFTPClient).setDataTimeout(TIMEOUT);
        verify(mockFTPClient).enterLocalPassiveMode();
    }

    @Test
    public void shouldSkipDirectoryCreationWhenRemoteDirectoriesAlreadyExist() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, false);
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(false), false, true));

        stubSuccessfulConnection(hostConfiguration);
        when(mockFTPClient.setFileType(FTP.BINARY_FILE_TYPE)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(REMOTE_DIRECTORY)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(BUILD_DIRECTORY)).thenReturn(true);
        when(mockFTPClient.storeFile(eq(BUILD_FILE_NAME), any())).thenReturn(true);

        j.assertBuildStatusSuccess(project.scheduleBuild2(0).get());

        verify(mockFTPClient, never()).makeDirectory(anyString());
        verify(mockFTPClient).setFileType(FTP.BINARY_FILE_TYPE);
        verify(mockFTPClient).storeFile(eq(BUILD_FILE_NAME), any());
    }

    @Test
    public void shouldUseActiveModeAndConfiguredControlEncoding() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, true, CONTROL_ENCODING, false);
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(false), false, true));

        stubSuccessfulConnection(hostConfiguration);
        when(mockFTPClient.setFileType(FTP.BINARY_FILE_TYPE)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(REMOTE_DIRECTORY)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(BUILD_DIRECTORY)).thenReturn(true);
        when(mockFTPClient.storeFile(eq(BUILD_FILE_NAME), any())).thenReturn(true);

        j.assertBuildStatusSuccess(project.scheduleBuild2(0).get());

        verify(mockFTPClient).setControlEncoding(CONTROL_ENCODING);
        verify(mockFTPClient).enterLocalActiveMode();
        verify(mockFTPClient, never()).enterLocalPassiveMode();
        verify(mockFTPClient).setFileType(FTP.BINARY_FILE_TYPE);
    }

    @Test
    public void shouldFailBuildWhenLoginFails() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, false);
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(true), false, true));

        when(mockFTPClient.getReplyCode()).thenReturn(FTPReply.SERVICE_READY);
        when(mockFTPClient.login(hostConfiguration.getUsername(), TEST_PASSWORD)).thenReturn(false);

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, build);

        verify(mockFTPClient).connect(HOSTNAME, PORT);
        verify(mockFTPClient).login(USERNAME, TEST_PASSWORD);
        verify(mockFTPClient, never()).storeFile(anyString(), any());
        verify(mockFTPClient, never()).changeWorkingDirectory(REMOTE_DIRECTORY);
    }

    @Test
    public void shouldFailBuildWhenArtifactUploadFails() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, false);
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(true), false, true));

        stubSuccessfulConnection(hostConfiguration);
        when(mockFTPClient.setFileType(FTP.ASCII_FILE_TYPE)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(REMOTE_DIRECTORY)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(BUILD_DIRECTORY)).thenReturn(true);
        when(mockFTPClient.storeFile(eq(BUILD_FILE_NAME), any())).thenReturn(false);
        when(mockFTPClient.getReplyString()).thenReturn(STORE_FAILURE_REPLY);

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, build);
        verify(mockFTPClient).storeFile(eq(BUILD_FILE_NAME), any());
        verify(mockFTPClient).getReplyString();
        j.assertLogContains(STORE_FAILURE_REPLY, build);
    }

    @Test
    public void shouldFailBuildWhenConnectionReplyCodeIsNotPositive() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, false);
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(true), false, true));

        when(mockFTPClient.getReplyCode()).thenReturn(FTPReply.SERVICE_NOT_AVAILABLE);

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, build);

        verify(mockFTPClient).connect(HOSTNAME, PORT);
        verify(mockFTPClient, never()).login(anyString(), anyString());
        verify(mockFTPClient, never()).storeFile(anyString(), any());
    }

    @Test
    public void shouldFailBuildWhenRemoteRootDirectoryNavigationFails() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, false);
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(true), false, true));

        when(mockFTPClient.getReplyCode()).thenReturn(FTPReply.SERVICE_READY);
        when(mockFTPClient.login(USERNAME, TEST_PASSWORD)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(REMOTE_ROOT)).thenReturn(false);

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, build);

        verify(mockFTPClient).connect(HOSTNAME, PORT);
        verify(mockFTPClient).login(USERNAME, TEST_PASSWORD);
        verify(mockFTPClient, never()).storeFile(anyString(), any());
    }

    @Test
    public void shouldSetTimeoutsBeforeEstablishingConnection() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, false);
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(false), false, true));

        stubSuccessfulConnection(hostConfiguration);
        when(mockFTPClient.setFileType(FTP.BINARY_FILE_TYPE)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(REMOTE_DIRECTORY)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(BUILD_DIRECTORY)).thenReturn(true);
        when(mockFTPClient.storeFile(eq(BUILD_FILE_NAME), any())).thenReturn(true);

        j.assertBuildStatusSuccess(project.scheduleBuild2(0).get());

        final InOrder orderedVerifier = inOrder(mockFTPClient);
        orderedVerifier.verify(mockFTPClient).setDefaultTimeout(TIMEOUT);
        orderedVerifier.verify(mockFTPClient).setDataTimeout(TIMEOUT);
        orderedVerifier.verify(mockFTPClient).connect(HOSTNAME, PORT);
    }

    @Test
    public void shouldFailBuildWhenDisableMakeNestedDirsAndDirectoryDoesNotExist() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, true);
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(true), false, true));

        stubSuccessfulConnection(hostConfiguration);
        when(mockFTPClient.setFileType(FTP.ASCII_FILE_TYPE)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(REMOTE_DIRECTORY)).thenReturn(false);

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, build);

        verify(mockFTPClient, never()).makeDirectory(anyString());
        verify(mockFTPClient, never()).storeFile(anyString(), any());
    }

    private BapFtpHostConfiguration registerHostConfiguration(
            final FTPClient ftpClient,
            final boolean useActiveData,
            final String controlEncoding,
            final boolean disableMakeNestedDirs) throws Exception {

        final BapFtpHostConfiguration hostConfiguration = new TestBapFtpHostConfiguration(
                CONFIG_NAME,
                HOSTNAME,
                USERNAME,
                TEST_PASSWORD,
                REMOTE_ROOT,
                PORT,
                TIMEOUT,
                useActiveData,
                controlEncoding,
                disableMakeNestedDirs,
                false,
                ftpClient);

        jenkinsTestHelper.setGlobalConfig(hostConfiguration);
        return hostConfiguration;
    }

    private FreeStyleProject createProject(final BapFtpPublisherPlugin plugin) throws Exception {
        final FreeStyleProject project = j.createFreeStyleProject();
        project.getPublishersList().add(plugin);
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(
                    final AbstractBuild<?, ?> build,
                    final Launcher launcher,
                    final BuildListener listener) throws InterruptedException, IOException {

                final FilePath dir = build.getWorkspace().child(REMOVE_PREFIX).child(BUILD_DIRECTORY);
                dir.mkdirs();
                dir.child(BUILD_FILE_NAME).write(BUILD_FILE_CONTENT, "UTF-8");
                build.setResult(Result.SUCCESS);
                return true;
            }
        });
        return project;
    }

    private BapFtpPublisherPlugin createPlugin(
            final String configurationName,
            final BapFtpTransfer transfer,
            final boolean continueOnError,
            final boolean failOnError) {

        final ArrayList<BapFtpTransfer> transfers =
                new ArrayList<>(Collections.singletonList(transfer));
        final BapFtpPublisher publisher =
                new BapFtpPublisher(configurationName, false, transfers, false, false, null, null, null);
        final ArrayList<BapFtpPublisher> publishers =
                new ArrayList<>(Collections.singletonList(publisher));
        return new BapFtpPublisherPlugin(publishers, continueOnError, failOnError, false, "master", null);
    }

    private BapFtpTransfer createTransfer(final boolean asciiMode) {
        return new BapFtpTransfer(
                "**/*",
                null,
                REMOTE_DIRECTORY,
                REMOVE_PREFIX,
                asciiMode,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    private void stubSuccessfulConnection(final BapFtpHostConfiguration hostConfiguration) throws Exception {
        when(mockFTPClient.getReplyCode()).thenReturn(FTPReply.SERVICE_READY);
        when(mockFTPClient.login(hostConfiguration.getUsername(), TEST_PASSWORD)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(hostConfiguration.getRemoteRootDir())).thenReturn(true);
    }

    private static final class TestBapFtpHostConfiguration extends BapFtpHostConfiguration {

        private final FTPClient ftpClient;

        private TestBapFtpHostConfiguration(
                final String name,
                final String hostname,
                final String username,
                final String encryptedPassword,
                final String remoteRootDir,
                final int port,
                final int timeout,
                final boolean useActiveData,
                final String controlEncoding,
                final boolean disableMakeNestedDirs,
                final boolean disableRemoteVerification,
                final FTPClient ftpClient) {

            super(
                    name,
                    hostname,
                    username,
                    encryptedPassword,
                    remoteRootDir,
                    port,
                    timeout,
                    useActiveData,
                    controlEncoding,
                    disableMakeNestedDirs,
                    disableRemoteVerification);
            this.ftpClient = ftpClient;
        }

        @Override
        public FTPClient createFTPClient() {
            return ftpClient;
        }
    }
}