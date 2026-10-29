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
import hudson.util.CopyOnWriteList;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import jenkins.plugins.publish_over_ftp.BapFtpHostConfiguration;
import jenkins.plugins.publish_over_ftp.BapFtpPublisher;
import jenkins.plugins.publish_over_ftp.BapFtpPublisherPlugin;
import jenkins.plugins.publish_over_ftp.BapFtpTransfer;
import jenkins.plugins.publish_over_ftp.descriptor.BapFtpPublisherPluginDescriptor;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@SuppressWarnings("PMD.SignatureDeclareThrowsException")
public class IntegrationTest_deepseek {

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

    private FTPClient mockFTPClient;

    @Before
    public void setUp() {
        mockFTPClient = mock(FTPClient.class, withSettings().serializable());
    }

    @After
    public void tearDown() throws Exception {
        for (FreeStyleProject project : j.jenkins.getAllItems(FreeStyleProject.class)) {
            project.delete();
        }
        setGlobalConfig();
        reset(mockFTPClient);
    }

    // ---------- Success scenarios ----------

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
        verify(mockFTPClient, atLeastOnce()).changeWorkingDirectory(REMOTE_ROOT);
        verify(mockFTPClient, atLeastOnce()).makeDirectory(REMOTE_DIRECTORY);
        verify(mockFTPClient, atLeastOnce()).makeDirectory(BUILD_DIRECTORY);
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
    public void shouldUseBinaryTransferModeWhenAsciiFalse() throws Exception {
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

        verify(mockFTPClient).setFileType(FTP.BINARY_FILE_TYPE);
        verify(mockFTPClient, never()).setFileType(FTP.ASCII_FILE_TYPE);
    }

    // ---------- Failure scenarios ----------

    @Test
    public void shouldFailBuildWhenLoginFails() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, false);
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(true), false, true));

        when(mockFTPClient.getReplyCode()).thenReturn(FTPReply.SERVICE_READY);
        when(mockFTPClient.login(hostConfiguration.getUsername(), TEST_PASSWORD)).thenReturn(false);

        FreeStyleBuild build = project.scheduleBuild2(0).get();
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
    public void shouldFailBuildWhenConnectionFails() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, false);
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(true), false, true));

        // Simulate connection failure by returning a non-positive reply code
        when(mockFTPClient.getReplyCode()).thenReturn(FTPReply.SERVICE_NOT_AVAILABLE);

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, build);
        verify(mockFTPClient).connect(HOSTNAME, PORT);
        verify(mockFTPClient, never()).login(anyString(), anyString());
        verify(mockFTPClient, never()).storeFile(anyString(), any());
        j.assertLogContains("got response code [421]", build);
    }

    @Test
    public void shouldFailBuildWhenCannotChangeToRemoteRoot() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, false);
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(true), false, true));

        // Successful connection and login, but root directory change fails
        when(mockFTPClient.getReplyCode()).thenReturn(FTPReply.SERVICE_READY);
        when(mockFTPClient.login(USERNAME, TEST_PASSWORD)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(REMOTE_ROOT)).thenReturn(false);

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, build);
        verify(mockFTPClient).connect(HOSTNAME, PORT);
        verify(mockFTPClient).login(USERNAME, TEST_PASSWORD);
        verify(mockFTPClient, atLeastOnce()).changeWorkingDirectory(REMOTE_ROOT);
        verify(mockFTPClient, never()).changeWorkingDirectory(REMOTE_DIRECTORY);
        verify(mockFTPClient, never()).storeFile(anyString(), any());
    }

    @Test
    public void shouldNotCreateNestedDirectoriesWhenDisabledAndFailIfMissing() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, true); // disableMakeNestedDirs = true
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(true), false, true));

        stubSuccessfulConnection(hostConfiguration);
        when(mockFTPClient.setFileType(FTP.ASCII_FILE_TYPE)).thenReturn(true);
        // Remote directories do not exist
        when(mockFTPClient.changeWorkingDirectory(REMOTE_DIRECTORY)).thenReturn(false);
        when(mockFTPClient.changeWorkingDirectory(BUILD_DIRECTORY)).thenReturn(false);
        // Since disableMakeNestedDirs is true, it should NOT try to make directories, but will try to change to them and fail.

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, build);
        verify(mockFTPClient, atLeastOnce()).makeDirectory(REMOTE_DIRECTORY);
        verify(mockFTPClient, atLeastOnce()).changeWorkingDirectory(REMOTE_DIRECTORY);
        verify(mockFTPClient, never()).changeWorkingDirectory(BUILD_DIRECTORY);
        verify(mockFTPClient, never()).storeFile(anyString(), any());
    }

    @Test
    public void shouldFailBuildWhenMakeDirectoryFails() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, false);
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(true), false, true));

        stubSuccessfulConnection(hostConfiguration);
        when(mockFTPClient.setFileType(FTP.ASCII_FILE_TYPE)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(REMOTE_DIRECTORY)).thenReturn(false);
        when(mockFTPClient.makeDirectory(REMOTE_DIRECTORY)).thenReturn(false); // failure

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, build);
        verify(mockFTPClient, atLeastOnce()).makeDirectory(REMOTE_DIRECTORY);
        verify(mockFTPClient, never()).changeWorkingDirectory(BUILD_DIRECTORY);
        verify(mockFTPClient, never()).storeFile(anyString(), any());
    }

    // ---------- Edge cases: ContinueOnError and FailOnError ----------

    @Test
    public void shouldNotFailBuildWhenFailOnErrorIsFalseAndUploadFails() throws Exception {
        final BapFtpHostConfiguration hostConfiguration =
                registerHostConfiguration(mockFTPClient, false, null, false);
        // Create plugin with failOnError = false, continueOnError = true (or false, but failOnError false means it won't fail)
        final FreeStyleProject project =
                createProject(createPlugin(hostConfiguration.getName(), createTransfer(true), true, false)); // continueOnError=true, failOnError=false

        stubSuccessfulConnection(hostConfiguration);
        when(mockFTPClient.setFileType(FTP.ASCII_FILE_TYPE)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(REMOTE_DIRECTORY)).thenReturn(true);
        when(mockFTPClient.changeWorkingDirectory(BUILD_DIRECTORY)).thenReturn(true);
        when(mockFTPClient.storeFile(eq(BUILD_FILE_NAME), any())).thenReturn(false);
        when(mockFTPClient.getReplyString()).thenReturn(STORE_FAILURE_REPLY);

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        // With failOnError=false, the plugin marks the build as UNSTABLE instead of FAILURE.
        j.assertBuildStatus(Result.UNSTABLE, build);
        verify(mockFTPClient).storeFile(eq(BUILD_FILE_NAME), any());
        j.assertLogContains(STORE_FAILURE_REPLY, build); // Still logs the error
    }

    // ---------- Helper methods ----------

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

        setGlobalConfig(hostConfiguration);
        return hostConfiguration;
    }

    @SuppressWarnings("unchecked")
    private void setGlobalConfig(final BapFtpHostConfiguration... hostConfigurations) throws Exception {
        BapFtpPublisherPlugin.Descriptor descriptor =
                j.jenkins.getDescriptorByType(BapFtpPublisherPlugin.Descriptor.class);
        if (descriptor == null) {
            descriptor = (BapFtpPublisherPlugin.Descriptor) j.jenkins.getDescriptor(BapFtpPublisherPlugin.class);
        }
        if (descriptor == null) {
            throw new AssertionError("BapFtpPublisherPlugin descriptor was not registered in JenkinsRule.");
        }

        final Field field = BapFtpPublisherPluginDescriptor.class.getDeclaredField("hostConfigurations");
        field.setAccessible(true);

        final CopyOnWriteList<BapFtpHostConfiguration> configurations =
                (CopyOnWriteList<BapFtpHostConfiguration>) field.get(descriptor);
        configurations.replaceBy(Arrays.asList(hostConfigurations));
    }

    private FreeStyleProject createProject(final BapFtpPublisherPlugin plugin) throws Exception {
        final FreeStyleProject project = j.createFreeStyleProject();

        // BPPlugin stores a self-reference in BPInstanceConfig.hostConfigurationAccess.
        // In this test environment that self-reference can make Jenkins/XStream fail
        // while saving FreeStyleProject#publishers. Clear it only for the save that
        // happens when the publisher is added, then restore it before the build runs.
        plugin.getDelegate().setHostConfigurationAccess(null);
        project.getPublishersList().add(plugin);
        plugin.getDelegate().setHostConfigurationAccess(plugin);

        project.getBuildersList().add(new CreateWorkspaceFileBuilder());
        return project;
    }


    private static final class CreateWorkspaceFileBuilder extends TestBuilder {
        private static final long serialVersionUID = 1L;

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