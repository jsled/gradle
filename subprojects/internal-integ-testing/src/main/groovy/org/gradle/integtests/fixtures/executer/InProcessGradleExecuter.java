/*
 * Copyright 2010 the original author or authors.
 *
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
 */

package org.gradle.integtests.fixtures.executer;

import junit.framework.AssertionFailedError;
import org.apache.commons.io.output.TeeOutputStream;
import org.gradle.BuildResult;
import org.gradle.StartParameter;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionGraph;
import org.gradle.api.execution.TaskExecutionGraphListener;
import org.gradle.api.execution.TaskExecutionListener;
import org.gradle.api.internal.StartParameterInternal;
import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.api.internal.file.TestFiles;
import org.gradle.api.logging.configuration.ConsoleOutput;
import org.gradle.api.tasks.TaskState;
import org.gradle.cli.CommandLineParser;
import org.gradle.configuration.GradleLauncherMetaData;
import org.gradle.execution.MultipleBuildFailures;
import org.gradle.initialization.BuildRequestContext;
import org.gradle.initialization.DefaultBuildCancellationToken;
import org.gradle.initialization.DefaultBuildRequestContext;
import org.gradle.initialization.DefaultBuildRequestMetaData;
import org.gradle.initialization.NoOpBuildEventConsumer;
import org.gradle.initialization.ReportedException;
import org.gradle.initialization.layout.BuildLayoutFactory;
import org.gradle.integtests.fixtures.logging.GroupedOutputFixture;
import org.gradle.internal.Factory;
import org.gradle.internal.SystemProperties;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.exceptions.LocationAwareException;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.jvm.Jvm;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.internal.logging.sink.ConsoleConfigureAction;
import org.gradle.internal.nativeintegration.ProcessEnvironment;
import org.gradle.launcher.Main;
import org.gradle.launcher.cli.Parameters;
import org.gradle.launcher.cli.ParametersConverter;
import org.gradle.launcher.cli.action.ExecuteBuildAction;
import org.gradle.launcher.exec.BuildActionExecuter;
import org.gradle.launcher.exec.BuildActionParameters;
import org.gradle.launcher.exec.DefaultBuildActionParameters;
import org.gradle.process.internal.JavaExecHandleBuilder;
import org.gradle.test.fixtures.file.TestDirectoryProvider;
import org.gradle.test.fixtures.file.TestFile;
import org.gradle.util.DeprecationLogger;
import org.gradle.util.GUtil;
import org.gradle.util.GradleVersion;
import org.gradle.util.SetSystemProperties;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;

import static org.gradle.integtests.fixtures.executer.OutputScrapingExecutionResult.flattenTaskPaths;
import static org.gradle.util.Matchers.hasMessage;
import static org.gradle.util.Matchers.isEmpty;
import static org.gradle.util.Matchers.normalizedLineSeparators;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

public class InProcessGradleExecuter extends AbstractGradleExecuter {
    private final ProcessEnvironment processEnvironment = GLOBAL_SERVICES.get(ProcessEnvironment.class);

    public static final TestFile COMMON_TMP = new TestFile(new File("build/tmp"));

    static {
        LoggingManagerInternal loggingManager = GLOBAL_SERVICES.getFactory(LoggingManagerInternal.class).create();
        loggingManager.start();
    }

    public InProcessGradleExecuter(GradleDistribution distribution, TestDirectoryProvider testDirectoryProvider) {
        super(distribution, testDirectoryProvider);
    }

    public InProcessGradleExecuter(GradleDistribution distribution, TestDirectoryProvider testDirectoryProvider, GradleVersion gradleVersion, IntegrationTestBuildContext buildContext) {
        super(distribution, testDirectoryProvider, gradleVersion, buildContext);
    }

    @Override
    public GradleExecuter reset() {
        DeprecationLogger.reset();
        return super.reset();
    }

    @Override
    protected ExecutionResult doRun() {
        if (isForkRequired()) {
            return createGradleHandle().waitForFinish();
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        BuildListenerImpl buildListener = new BuildListenerImpl();
        BuildResult result = doRun(outputStream, errorStream, buildListener);
        try {
            result.rethrowFailure();
        } catch (Exception e) {
            throw new UnexpectedBuildFailure(e);
        }

        return assertResult(new InProcessExecutionResult(buildListener.executedTasks, buildListener.skippedTasks,
            OutputScrapingExecutionResult.from(outputStream.toString(), errorStream.toString())));
    }

    @Override
    protected ExecutionFailure doRunWithFailure() {
        if (isForkRequired()) {
            return createGradleHandle().waitForFailure();
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        BuildListenerImpl buildListener = new BuildListenerImpl();
        try {
            doRun(outputStream, errorStream, buildListener).rethrowFailure();
            throw new AssertionError("expected build to fail but it did not.");
        } catch (GradleException e) {
            return assertResult(new InProcessExecutionFailure(buildListener.executedTasks, buildListener.skippedTasks,
                OutputScrapingExecutionFailure.from(outputStream.toString(), errorStream.toString()), e));
        }
    }

    private boolean isForkRequired() {
        if (isUseDaemon() || !getJavaHome().equals(Jvm.current().getJavaHome())) {
            return true;
        }
        File gradleProperties = new File(getWorkingDir(), "gradle.properties");
        if (gradleProperties.isFile()) {
            Properties properties = GUtil.loadProperties(gradleProperties);
            if (properties.getProperty("org.gradle.java.home") != null || properties.getProperty("org.gradle.jvmargs") != null) {
                return true;
            }
        }
        return false;

    }

    private <T extends ExecutionResult> T assertResult(T result) {
        getResultAssertion().execute(result);
        return result;
    }

    @Override
    protected GradleHandle createGradleHandle() {
        if (consoleAttachment == ConsoleAttachment.ATTACHED) {
            withCommandLineGradleOpts("-D" + ConsoleConfigureAction.TEST_CONSOLE_PROPERTY + "=" + ConsoleConfigureAction.CONSOLE_BOTH);
        } else if (consoleAttachment == ConsoleAttachment.ATTACHED_STDOUT_ONLY) {
            withCommandLineGradleOpts("-D" + ConsoleConfigureAction.TEST_CONSOLE_PROPERTY + "=" + ConsoleConfigureAction.CONSOLE_STDOUT_ONLY);
        }
        return new ForkingGradleHandle(getStdinPipe(), isUseDaemon(), getResultAssertion(), getDefaultCharacterEncoding(), getJavaExecBuilder(), getDurationMeasurement()).start();
    }

    private Factory<JavaExecHandleBuilder> getJavaExecBuilder() {
        return new Factory<JavaExecHandleBuilder>() {
            public JavaExecHandleBuilder create() {
                GradleInvocation invocation = buildInvocation();
                JavaExecHandleBuilder builder = TestFiles.execFactory().newJavaExec();
                builder.workingDir(getWorkingDir());
                builder.setExecutable(new File(getJavaHome(), "bin/java"));
                Collection<File> classpath = cleanup(GLOBAL_SERVICES.get(ModuleRegistry.class).getAdditionalClassPath().getAsFiles());
                builder.classpath(classpath);
                builder.jvmArgs(invocation.launcherJvmArgs);

                builder.setMain(Main.class.getName());
                builder.args(invocation.args);
                builder.setStandardInput(connectStdIn());

                return builder;
            }
        };
    }

    private Collection<File> cleanup(List<File> files) {
        List<File> result = new LinkedList<File>();
        String prefix = Jvm.current().getJavaHome().getPath() + File.separator;
        for (File file : files) {
            if (file.getPath().startsWith(prefix)) {
                // IDEA adds the JDK's bootstrap classpath to the classpath it uses to run test - remove this
                continue;
            }
            result.add(file);
        }
        return result;
    }

    private BuildResult doRun(OutputStream outputStream, OutputStream errorStream, BuildListenerImpl listener) {
        // Capture the current state of things that we will change during execution
        InputStream originalStdIn = System.in;
        Properties originalSysProperties = new Properties();
        originalSysProperties.putAll(System.getProperties());
        File originalUserDir = new File(originalSysProperties.getProperty("user.dir")).getAbsoluteFile();
        Map<String, String> originalEnv = new HashMap<String, String>(System.getenv());

        GradleInvocation invocation = buildInvocation();
        Set<String> changedEnvVars = new HashSet<String>(invocation.environmentVars.keySet());

        try {
            return executeBuild(invocation, outputStream, errorStream, listener);
        } finally {
            // Restore the environment
            System.setProperties(originalSysProperties);
            resetTempDirLocation();
            processEnvironment.maybeSetProcessDir(originalUserDir);
            for (String envVar : changedEnvVars) {
                String oldValue = originalEnv.get(envVar);
                if (oldValue != null) {
                    processEnvironment.maybeSetEnvironmentVariable(envVar, oldValue);
                } else {
                    processEnvironment.maybeRemoveEnvironmentVariable(envVar);
                }
            }
            System.setProperty("user.dir", originalSysProperties.getProperty("user.dir"));
            System.setIn(originalStdIn);
        }
    }

    private void resetTempDirLocation() {
        SetSystemProperties.resetTempDirLocation();
    }

    private LoggingManagerInternal createLoggingManager(StartParameter startParameter, OutputStream outputStream, OutputStream errorStream) {
        LoggingManagerInternal loggingManager = GLOBAL_SERVICES.getFactory(LoggingManagerInternal.class).create();
        loggingManager.captureSystemSources();

        ConsoleOutput consoleOutput = startParameter.getConsoleOutput();
        if (consoleOutput == ConsoleOutput.Auto) {
            // IDEA runs tests attached to a console, use plain so test can assume never attached to a console
            // Should really run all tests against a plain and a rich console to make these assumptions explicit
            consoleOutput = ConsoleOutput.Plain;
        }
        loggingManager.attachConsole(new TeeOutputStream(System.out, outputStream), new TeeOutputStream(System.err, errorStream), consoleOutput, consoleAttachment == ConsoleAttachment.ATTACHED);

        return loggingManager;
    }

    private BuildResult executeBuild(GradleInvocation invocation, OutputStream outputStream, OutputStream errorStream, BuildListenerImpl listener) {
        // Augment the environment for the execution
        System.setIn(connectStdIn());
        processEnvironment.maybeSetProcessDir(getWorkingDir());
        for (Map.Entry<String, String> entry : invocation.environmentVars.entrySet()) {
            processEnvironment.maybeSetEnvironmentVariable(entry.getKey(), entry.getValue());
        }
        Map<String, String> implicitJvmSystemProperties = getImplicitJvmSystemProperties();
        System.getProperties().putAll(implicitJvmSystemProperties);
        resetTempDirLocation();

        // TODO: Fix tests that rely on this being set before we process arguments like this...
        StartParameterInternal startParameter = new StartParameterInternal();
        startParameter.setCurrentDir(getWorkingDir());

        // TODO: Reuse more of CommandlineActionFactory
        CommandLineParser parser = new CommandLineParser();
        BuildLayoutFactory buildLayoutFactory = new BuildLayoutFactory();
        ParametersConverter parametersConverter = new ParametersConverter(buildLayoutFactory);
        parametersConverter.configure(parser);
        final Parameters parameters = new Parameters(startParameter);
        parametersConverter.convert(parser.parse(getAllArgs()), parameters);

        BuildActionExecuter<BuildActionParameters> actionExecuter = GLOBAL_SERVICES.get(BuildActionExecuter.class);

        ListenerManager listenerManager = GLOBAL_SERVICES.get(ListenerManager.class);
        listenerManager.addListener(listener);

        try {
            // TODO: Reuse more of BuildActionsFactory
            BuildAction action = new ExecuteBuildAction(startParameter);
            BuildActionParameters buildActionParameters = createBuildActionParameters(startParameter);
            BuildRequestContext buildRequestContext = createBuildRequestContext();

            LoggingManagerInternal loggingManager = createLoggingManager(startParameter, outputStream, errorStream);
            loggingManager.start();

            try {
                startMeasurement();
                try {
                    actionExecuter.execute(action, buildRequestContext, buildActionParameters, GLOBAL_SERVICES);
                } finally {
                    stopMeasurement();
                }
            } finally {
                loggingManager.stop();
            }
            return new BuildResult(null, null);
        } catch (ReportedException e) {
            return new BuildResult(null, e.getCause());
        } finally {
            listenerManager.removeListener(listener);
        }
    }

    private BuildActionParameters createBuildActionParameters(StartParameter startParameter) {
        return new DefaultBuildActionParameters(
            System.getProperties(),
            System.getenv(),
            SystemProperties.getInstance().getCurrentDir(),
            startParameter.getLogLevel(),
            false,
            startParameter.isContinuous(),
            interactive,
            ClassPath.EMPTY
        );
    }

    private BuildRequestContext createBuildRequestContext() {
        return new DefaultBuildRequestContext(
            new DefaultBuildRequestMetaData(new GradleLauncherMetaData()),
            new DefaultBuildCancellationToken(),
            new NoOpBuildEventConsumer());
    }

    public void assertCanExecute() {
        assertNull(getExecutable());
        String defaultEncoding = getImplicitJvmSystemProperties().get("file.encoding");
        if (defaultEncoding != null) {
            assertEquals(Charset.forName(defaultEncoding), Charset.defaultCharset());
        }
        Locale defaultLocale = getDefaultLocale();
        if (defaultLocale != null) {
            assertEquals(defaultLocale, Locale.getDefault());
        }
        assertFalse(isRequiresGradleDistribution());
    }

    @Override
    protected TestFile getDefaultTmpDir() {
        // File.createTempFile sets the location of the temp directory to a static variable on the first call.  This prevents future
        // changes to java.io.tmpdir from having any effect in the same process.  We set this to use a common tmp directory for all
        // tests running in the same process so that we don't have a situation where one process initializes with a tmp directory
        // that it then removes, causing an IOException for any future tests that run in the same process and call File.createTempFile.
        return COMMON_TMP;
    }

    @Override
    public GradleExecuter withTestConsoleAttached() {
        consoleAttachment = ConsoleAttachment.ATTACHED;
        return this;
    }

    @Override
    public GradleExecuter withTestConsoleAttachedToStdoutOnly() {
        consoleAttachment = ConsoleAttachment.ATTACHED_STDOUT_ONLY;
        return this;
    }

    private static class BuildListenerImpl implements TaskExecutionGraphListener {
        private final List<String> executedTasks = new CopyOnWriteArrayList<String>();
        private final Set<String> skippedTasks = new CopyOnWriteArraySet<String>();

        public void graphPopulated(TaskExecutionGraph graph) {
            List<Task> planned = new ArrayList<Task>(graph.getAllTasks());
            graph.addTaskExecutionListener(new TaskListenerImpl(planned, executedTasks, skippedTasks));
        }
    }

    private static class TaskListenerImpl implements TaskExecutionListener {
        private final List<Task> planned;
        private final List<String> executedTasks;
        private final Set<String> skippedTasks;

        public TaskListenerImpl(List<Task> planned, List<String> executedTasks, Set<String> skippedTasks) {
            this.planned = planned;
            this.executedTasks = executedTasks;
            this.skippedTasks = skippedTasks;
        }

        public void beforeExecute(Task task) {
            if (!planned.contains(task)) {
                System.out.println("Warning: " + task + " was executed even though it is not part of the task plan!");
            }

            String taskPath = path(task);
            if (taskPath.startsWith(":buildSrc:")) {
                return;
            }

            executedTasks.add(taskPath);
        }

        public void afterExecute(Task task, TaskState state) {
            String taskPath = path(task);
            if (taskPath.startsWith(":buildSrc:")) {
                return;
            }

            if (state.getSkipped()) {
                skippedTasks.add(taskPath);
            }
        }

        private String path(Task task) {
            return task.getProject().getGradle().getParent() == null ? task.getPath() : ":" + task.getProject().getRootProject().getName() + task.getPath();
        }
    }

    public static class InProcessExecutionResult implements ExecutionResult {
        private final List<String> plannedTasks;
        private final Set<String> skippedTasks;
        private final OutputScrapingExecutionResult outputResult;

        public InProcessExecutionResult(List<String> plannedTasks, Set<String> skippedTasks, OutputScrapingExecutionResult outputResult) {
            this.plannedTasks = plannedTasks;
            this.skippedTasks = skippedTasks;
            this.outputResult = outputResult;
        }

        public String getOutput() {
            return outputResult.getOutput();
        }

        @Override
        public String getNormalizedOutput() {
            return outputResult.getNormalizedOutput();
        }

        @Override
        public GroupedOutputFixture getGroupedOutput() {
            return outputResult.getGroupedOutput();
        }

        public ExecutionResult assertOutputEquals(String expectedOutput, boolean ignoreExtraLines, boolean ignoreLineOrder) {
            outputResult.assertOutputEquals(expectedOutput, ignoreExtraLines, ignoreLineOrder);
            return this;
        }

        @Override
        public ExecutionResult assertNotOutput(String expectedOutput) {
            outputResult.assertNotOutput(expectedOutput);
            return this;
        }

        @Override
        public ExecutionResult assertOutputContains(String expectedOutput) {
            outputResult.assertOutputContains(expectedOutput);
            return this;
        }

        @Override
        public ExecutionResult assertContentContains(String content, String expectedOutput, String label) {
            outputResult.assertContentContains(content, expectedOutput, label);
            return null;
        }

        @Override
        public ExecutionResult assertHasPostBuildOutput(String expectedOutput) {
            outputResult.assertHasPostBuildOutput(expectedOutput);
            return this;
        }

        @Override
        public boolean hasErrorOutput(String expectedOutput) {
            return outputResult.hasErrorOutput(expectedOutput);
        }

        @Override
        public ExecutionResult assertHasErrorOutput(String expectedOutput) {
            outputResult.assertHasErrorOutput(expectedOutput);
            return this;
        }

        public String getError() {
            return outputResult.getError();
        }

        public List<String> getExecutedTasks() {
            return new ArrayList<String>(plannedTasks);
        }

        public ExecutionResult assertTasksExecutedInOrder(Object... taskPaths) {
            Set<String> expected = TaskOrderSpecs.exact(taskPaths).getTasks();
            assertThat(plannedTasks, containsInAnyOrder(expected.toArray()));
            outputResult.assertTasksExecutedInOrder(taskPaths);
            return this;
        }

        public ExecutionResult assertTasksExecuted(Object... taskPaths) {
            Set<String> flattenedTasks = new TreeSet<String>(flattenTaskPaths(taskPaths));
            assertThat(plannedTasks, containsInAnyOrder(flattenedTasks.toArray()));
            outputResult.assertTasksExecuted(flattenedTasks);
            return this;
        }

        @Override
        public ExecutionResult assertTaskExecuted(String taskPath) {
            assertThat(plannedTasks, hasItem(taskPath));
            outputResult.assertTaskExecuted(taskPath);
            return this;
        }

        @Override
        public ExecutionResult assertTaskNotExecuted(String taskPath) {
            assertThat(plannedTasks, not(hasItem(taskPath)));
            outputResult.assertTaskNotExecuted(taskPath);
            return this;
        }

        @Override
        public ExecutionResult assertTaskOrder(Object... taskPaths) {
            Set<String> expected = TaskOrderSpecs.exact(taskPaths).getTasks();
            assertThat(plannedTasks, hasItems(expected.toArray(new String[]{})));
            outputResult.assertTaskOrder(taskPaths);
            return this;
        }

        public Set<String> getSkippedTasks() {
            return new TreeSet<String>(skippedTasks);
        }

        @Override
        public ExecutionResult assertTasksSkipped(Object... taskPaths) {
            Set<String> expected = new TreeSet<String>(flattenTaskPaths(taskPaths));
            assertThat(skippedTasks, equalTo(expected));
            outputResult.assertTasksSkipped(expected);
            return this;
        }

        public ExecutionResult assertTaskSkipped(String taskPath) {
            assertThat(skippedTasks, hasItem(taskPath));
            outputResult.assertTaskSkipped(taskPath);
            return this;
        }

        @Override
        public ExecutionResult assertTasksNotSkipped(Object... taskPaths) {
            Set<String> expected = new TreeSet<String>(flattenTaskPaths(taskPaths));
            Set<String> notSkipped = getNotSkippedTasks();
            assertThat(notSkipped, equalTo(expected));
            outputResult.assertTasksNotSkipped(expected);
            return this;
        }

        public ExecutionResult assertTaskNotSkipped(String taskPath) {
            assertThat(getNotSkippedTasks(), hasItem(taskPath));
            outputResult.assertTaskNotSkipped(taskPath);
            return this;
        }

        private Set<String> getNotSkippedTasks() {
            Set<String> notSkipped = new TreeSet<String>(plannedTasks);
            notSkipped.removeAll(skippedTasks);
            return notSkipped;
        }
    }

    private static class InProcessExecutionFailure extends InProcessExecutionResult implements ExecutionFailure {
        private static final Pattern LOCATION_PATTERN = Pattern.compile("(?m)^((\\w+ )+'.+') line: (\\d+)$");
        private final OutputScrapingExecutionFailure outputFailure;
        private final GradleException failure;
        private final String fileName;
        private final String lineNumber;
        private final List<String> descriptions = new ArrayList<String>();

        public InProcessExecutionFailure(List<String> tasks, Set<String> skippedTasks, OutputScrapingExecutionFailure outputFailure, GradleException failure) {
            super(tasks, skippedTasks, outputFailure);
            this.outputFailure = outputFailure;
            this.failure = failure;

            // Chop up the exception message into its expected parts
            java.util.regex.Matcher matcher = LOCATION_PATTERN.matcher(failure.getMessage());
            if (matcher.find()) {
                fileName = matcher.group(1);
                lineNumber = matcher.group(3);
                descriptions.add(failure.getMessage().substring(matcher.end()).trim());
            } else {
                fileName = "";
                lineNumber = "";
                descriptions.add(failure.getMessage().trim());
            }
            if (failure instanceof MultipleBuildFailures) {
                for (Throwable cause : ((MultipleBuildFailures) failure).getCauses()) {
                    matcher = LOCATION_PATTERN.matcher(cause.getMessage());
                    if (matcher.find()) {
                        descriptions.add(cause.getMessage().substring(matcher.end()).trim());
                    } else {
                        descriptions.add(cause.getMessage().trim());
                    }
                }
            }
        }

        public ExecutionFailure assertHasLineNumber(int lineNumber) {
            outputFailure.assertHasLineNumber(lineNumber);
            assertThat(this.lineNumber, equalTo(String.valueOf(lineNumber)));
            return this;
        }

        public ExecutionFailure assertHasFileName(String filename) {
            outputFailure.assertHasFileName(filename);
            assertThat(this.fileName, equalTo(filename));
            return this;
        }

        public ExecutionFailure assertHasResolution(String resolution) {
            outputFailure.assertHasResolution(resolution);
            return this;
        }

        @Override
        public ExecutionFailure assertHasFailures(int count) {
            outputFailure.assertHasFailures(count);
            if (count == 1) {
                assertFalse(failure instanceof MultipleBuildFailures);
            } else {
                assertEquals(((MultipleBuildFailures)failure).getCauses().size(), count);
            }
            return this;
        }

        public ExecutionFailure assertHasCause(String description) {
            assertThatCause(startsWith(description));
            return this;
        }

        public ExecutionFailure assertThatCause(Matcher<String> matcher) {
            outputFailure.assertThatCause(matcher);
            List<Throwable> causes = new ArrayList<Throwable>();
            extractCauses(failure, causes);
            assertThat(causes, Matchers.hasItem(hasMessage(normalizedLineSeparators(matcher))));
            return this;
        }

        private void extractCauses(Throwable failure, List<Throwable> causes) {
            if (failure instanceof MultipleBuildFailures) {
                MultipleBuildFailures exception = (MultipleBuildFailures) failure;
                for (Throwable componentFailure : exception.getCauses()) {
                    extractCauses(componentFailure, causes);
                }
            } else if (failure instanceof LocationAwareException) {
                causes.addAll(((LocationAwareException) failure).getReportableCauses());
            } else {
                causes.add(failure);
            }
        }

        @Override
        public ExecutionFailure assertHasNoCause(String description) {
            outputFailure.assertHasNoCause(description);
            Matcher<Throwable> matcher = hasMessage(containsString(description));
            List<Throwable> causes = new ArrayList<Throwable>();
            extractCauses(failure, causes);
            for (Throwable cause : causes) {
                if (matcher.matches(cause)) {
                    throw new AssertionFailedError(String.format("Expected no failure with description '%s', found: %s", description, cause));
                }
            }
            return this;
        }

        public ExecutionFailure assertHasNoCause() {
            outputFailure.assertHasNoCause();
            if (failure instanceof LocationAwareException) {
                LocationAwareException exception = (LocationAwareException) failure;
                assertThat(exception.getReportableCauses(), isEmpty());
            } else {
                assertThat(failure.getCause(), nullValue());
            }
            return this;
        }

        public ExecutionFailure assertHasDescription(String context) {
            assertThatDescription(startsWith(context));
            return this;
        }

        public ExecutionFailure assertThatDescription(Matcher<String> matcher) {
            outputFailure.assertThatDescription(matcher);
            assertThat(descriptions, hasItem(normalizedLineSeparators(matcher)));
            return this;
        }

        public ExecutionFailure assertTestsFailed() {
            new DetailedExecutionFailure(this).assertTestsFailed();
            return this;
        }

        public DependencyResolutionFailure assertResolutionFailure(String configurationPath) {
            return new DependencyResolutionFailure(this, configurationPath);
        }
    }
}
