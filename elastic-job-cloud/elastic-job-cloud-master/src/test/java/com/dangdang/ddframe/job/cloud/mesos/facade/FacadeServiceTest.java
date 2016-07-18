/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.job.cloud.mesos.facade;

import com.dangdang.ddframe.job.cloud.config.CloudJobConfiguration;
import com.dangdang.ddframe.job.cloud.config.ConfigurationService;
import com.dangdang.ddframe.job.cloud.context.ExecutionType;
import com.dangdang.ddframe.job.cloud.context.JobContext;
import com.dangdang.ddframe.job.cloud.context.TaskContext;
import com.dangdang.ddframe.job.cloud.producer.TaskProducerSchedulerRegistry;
import com.dangdang.ddframe.job.cloud.state.failover.FailoverService;
import com.dangdang.ddframe.job.cloud.state.fixture.CloudJobConfigurationBuilder;
import com.dangdang.ddframe.job.cloud.state.misfired.MisfiredService;
import com.dangdang.ddframe.job.cloud.state.ready.ReadyService;
import com.dangdang.ddframe.job.cloud.state.running.RunningService;
import com.dangdang.ddframe.reg.base.CoordinatorRegistryCenter;
import com.google.common.base.Optional;
import org.apache.mesos.Protos;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.unitils.util.ReflectionUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public final class FacadeServiceTest {
    
    @Mock
    private CoordinatorRegistryCenter regCenter;
    
    @Mock
    private ConfigurationService configService;
    
    @Mock
    private ReadyService readyService;
    
    @Mock
    private RunningService runningService;
    
    @Mock
    private FailoverService failoverService;
    
    @Mock
    private MisfiredService misfiredService;
    
    @Mock
    private TaskProducerSchedulerRegistry taskProducerSchedulerRegistry;
    
    private FacadeService facadeService;
    
    @Before
    public void setUp() throws NoSuchFieldException {
        facadeService = new FacadeService(regCenter);
        ReflectionUtils.setFieldValue(facadeService, "configService", configService);
        ReflectionUtils.setFieldValue(facadeService, "readyService", readyService);
        ReflectionUtils.setFieldValue(facadeService, "runningService", runningService);
        ReflectionUtils.setFieldValue(facadeService, "failoverService", failoverService);
        ReflectionUtils.setFieldValue(facadeService, "misfiredService", misfiredService);
        ReflectionUtils.setFieldValue(facadeService, "taskProducerSchedulerRegistry", taskProducerSchedulerRegistry);
    }
    
    @Test
    public void assertStart() {
        facadeService.start();
        verify(runningService).clear();
        verify(taskProducerSchedulerRegistry).registerFromRegistryCenter();
    }
    
    @Test
    public void assertGetEligibleJobContext() {
        Collection<JobContext> failoverJobContexts = Collections.singletonList(JobContext.from(CloudJobConfigurationBuilder.createCloudJobConfiguration("failover_job"), ExecutionType.FAILOVER));
        Collection<JobContext> misfiredJobContexts = Collections.singletonList(JobContext.from(CloudJobConfigurationBuilder.createCloudJobConfiguration("misfire_job"), ExecutionType.MISFIRED));
        Collection<JobContext> readyJobContexts = Collections.singletonList(JobContext.from(CloudJobConfigurationBuilder.createCloudJobConfiguration("ready_job"), ExecutionType.READY));
        when(failoverService.getAllEligibleJobContexts()).thenReturn(failoverJobContexts);
        when(misfiredService.getAllEligibleJobContexts(failoverJobContexts)).thenReturn(misfiredJobContexts);
        when(readyService.getAllEligibleJobContexts(Arrays.asList(failoverJobContexts.iterator().next(), misfiredJobContexts.iterator().next()))).thenReturn(readyJobContexts);
        EligibleJobContext actual = facadeService.getEligibleJobContext();
        Collection<JobContext> actualFailoverJobContexts = ReflectionUtils.getFieldValue(actual, ReflectionUtils.getFieldWithName(EligibleJobContext.class, "failoverJobContexts", false));
        assertThat(actualFailoverJobContexts.size(), is(1));
        assertThat(actualFailoverJobContexts.iterator().next().getJobConfig().getJobName(), is("failover_job"));
        Collection<JobContext> actualMisfiredJobContexts = ReflectionUtils.getFieldValue(actual, ReflectionUtils.getFieldWithName(EligibleJobContext.class, "misfiredJobContexts", false));
        assertThat(actualMisfiredJobContexts.size(), is(1));
        assertThat(actualMisfiredJobContexts.iterator().next().getJobConfig().getJobName(), is("misfire_job"));
        Collection<JobContext> actualReadyJobContexts = ReflectionUtils.getFieldValue(actual, ReflectionUtils.getFieldWithName(EligibleJobContext.class, "readyJobContexts", false));
        assertThat(actualReadyJobContexts.size(), is(1));
        assertThat(actualReadyJobContexts.iterator().next().getJobConfig().getJobName(), is("ready_job"));
    }
    
    @Test
    public void assertRemoveLaunchTasksFromQueue() {
        facadeService.removeLaunchTasksFromQueue(
                new AssignedTaskContext(Collections.<Protos.TaskInfo>emptyList(), Collections.<TaskContext>emptyList(), Collections.<String>emptyList(), Collections.<String>emptyList()));
        verify(failoverService).remove(Collections.<TaskContext>emptyList());
        verify(misfiredService).remove(Collections.<String>emptyList());
        verify(readyService).remove(Collections.<String>emptyList());
    }
    
    @Test
    public void assertAddRunning() {
        facadeService.addRunning(TaskContext.from("test_job@-@0@-@READY@-@00"));
        verify(runningService).add(TaskContext.from("test_job@-@0@-@READY@-@00"));
    }
    
    @Test
    public void assertRemoveRunning() {
        facadeService.removeRunning(TaskContext.from("test_job@-@0@-@READY@-@00"));
        verify(runningService).remove(TaskContext.from("test_job@-@0@-@READY@-@00"));
    }
    
    @Test
    public void assertRecordFailoverTaskWhenJobConfigNotExisted() {
        when(configService.load("test_job")).thenReturn(Optional.<CloudJobConfiguration>absent());
        facadeService.recordFailoverTask(TaskContext.from("test_job@-@0@-@FAILOVER@-@00"));
        verify(failoverService, times(0)).add(TaskContext.from("test_job@-@0@-@FAILOVER@-@00"));
        verify(runningService).remove(TaskContext.from("test_job@-@0@-@FAILOVER@-@00"));
    }
    
    @Test
    public void assertRecordFailoverTaskWhenIsFailoverDisabled() {
        when(configService.load("test_job")).thenReturn(Optional.of(CloudJobConfigurationBuilder.createOtherCloudJobConfiguration("test_job")));
        facadeService.recordFailoverTask(TaskContext.from("test_job@-@0@-@FAILOVER@-@00"));
        verify(failoverService, times(0)).add(TaskContext.from("test_job@-@0@-@FAILOVER@-@00"));
        verify(runningService).remove(TaskContext.from("test_job@-@0@-@FAILOVER@-@00"));
    }
    
    @Test
    public void assertRecordFailoverTaskWhenIsFailoverEnabled() {
        when(configService.load("test_job")).thenReturn(Optional.of(CloudJobConfigurationBuilder.createCloudJobConfiguration("test_job")));
        facadeService.recordFailoverTask(TaskContext.from("test_job@-@0@-@FAILOVER@-@00"));
        verify(failoverService).add(TaskContext.from("test_job@-@0@-@FAILOVER@-@00"));
        verify(runningService).remove(TaskContext.from("test_job@-@0@-@FAILOVER@-@00"));
    }
    
    @Test
    public void assertStop() {
        facadeService.stop();
        verify(runningService).clear();
    }
}