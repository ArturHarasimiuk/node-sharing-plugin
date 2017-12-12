/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
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
package com.redhat.jenkins.nodesharing;

import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import com.redhat.jenkins.nodesharingfrontend.WorkloadReporter;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.queue.QueueTaskFuture;
import hudson.util.FormValidation;
import hudson.util.OneShotEvent;
import jenkins.model.Jenkins;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.TestBuilder;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ReservationTest {

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Rule
    public ConfigRepoRule configRepo = new ConfigRepoRule();

    @Test
    public void configRepoIsolation() throws Exception {
        GitClient cr = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        StringWriter capture = new StringWriter();
        cr.changelog().to(capture).execute();
        String output = capture.toString();
        assertThat(output, containsString("author Pool Maintainer <pool.maintainer@acme.com>"));
        assertThat(output, containsString("committer Pool Maintainer <pool.maintainer@acme.com>"));
    }

    @Test
    public void doTestConnection() throws Exception {
        GitClient cr = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));

        final Properties prop = new Properties();
        prop.load(this.getClass().getClassLoader().getResourceAsStream("nodesharingbackend.properties"));

        SharedNodeCloud.DescriptorImpl descriptor = (SharedNodeCloud.DescriptorImpl) j.jenkins.getDescriptorOrDie(SharedNodeCloud.class);
        FormValidation validation = descriptor.doTestConnection(cr.getWorkTree().getRemote());
        assertThat(validation.renderHtml(), containsString("Orchestrator version is " + prop.getProperty("version")));
    }

    @Test @Ignore // TODO Keep hacking until this passes
    public void runBuildSuccessfully() throws Exception {
        j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        j.addSharedNodeCloud(Pool.getInstance().getConfigEndpoint());

        // When I schedule a bunch of tasks on executor
        Label winLabel = Label.get("w2k12");
        FreeStyleProject winJob = j.createFreeStyleProject("win");
        winJob.setAssignedLabel(winLabel);
        BlockingBuilder winBuilder = new BlockingBuilder();
        winJob.getBuildersList().add(winBuilder);
        Label solarisLabel = Label.get("solaris11&&!(x86||x86_64)");
        FreeStyleProject solarisJob = j.createFreeStyleProject("sol");
        solarisJob.setAssignedLabel(solarisLabel);
        BlockingBuilder solarisBuilder = new BlockingBuilder();
        solarisJob.getBuildersList().add(solarisBuilder);
        Future<FreeStyleBuild> winStart = winJob.scheduleBuild2(0).getStartCondition();
        Future<FreeStyleBuild> solarisStart = solarisJob.scheduleBuild2(0).getStartCondition();
        Future<FreeStyleBuild> scheduledSolarisRun = solarisJob.scheduleBuild2(0).getStartCondition();

        // Trigger workload update now from executor
        WorkloadReporter.all().get(WorkloadReporter.class).doRun();

        // Then there should be reservation tasks on orchestrator
        List<ReservationTask> scheduledReservations = j.getScheduledReservations();
        assertThat(scheduledReservations, Matchers.<ReservationTask>iterableWithSize(2));

        winStart.get();
        solarisStart.get();
        assertFalse(scheduledSolarisRun.isDone());

        // They start occupying real computers or they stay in the queue
        assertEquals(solarisLabel, j.getComputer("solaris1.executor.com").getReservation().getParent().getAssignedLabel());
        assertEquals(winLabel, j.getComputer("win1.executor.com").getReservation().getParent().getAssignedLabel());
        assertNull(j.getComputer("win2.executor.com").getReservation());
        assertFalse(scheduledSolarisRun.isDone());

        winBuilder.end.signal();
        j.assertBuildStatusSuccess(winJob.getBuildByNumber(1));
        assertNull(j.getComputer("win1.executor.com").getReservation());
        assertNull(j.getComputer("win2.executor.com").getReservation());

        // When first solaris task completes
        solarisBuilder.end.signal();
        j.assertBuildStatusSuccess(solarisJob.getBuildByNumber(1));
        scheduledSolarisRun.get();
        assertTrue("Blocked task should resume", scheduledSolarisRun.isDone());
        assertNotNull(j.getComputer("solaris1.executor.com").getReservation());
    }

    private static final class BlockingBuilder extends TestBuilder {
        private OneShotEvent start = new OneShotEvent();
        private OneShotEvent end = new OneShotEvent();

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
            start.signal();
            end.block();
            return true;
        }
    }
}
