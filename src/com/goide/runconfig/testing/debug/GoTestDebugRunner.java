/*
 * Copyright 2013-2018 Sergey Ignatov, Alexander Zolotov, Florin Patan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.goide.runconfig.testing.debug;

import com.goide.GoEnvironmentUtil;
import com.goide.dlv.DlvDebugProcess;
import com.goide.dlv.DlvRemoteVmConnection;
import com.goide.runconfig.testing.GoTestRunConfiguration;
import com.goide.util.GoHistoryProcessListener;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.runners.AsyncProgramRunner;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.RunContentBuilder;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.debugger.connection.RemoteVmConnection;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

public class GoTestDebugRunner extends AsyncProgramRunner {
  private static final String ID = "GoBuildingRunner";

  @NotNull
  @Override
  public String getRunnerId() {
    return ID;
  }

  @Override
  public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
    if (profile instanceof GoTestRunConfiguration) {
      return DefaultRunExecutor.EXECUTOR_ID.equals(executorId)
             || DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && !DlvDebugProcess.IS_DLV_DISABLED;
    }
    return false;
  }

  @NotNull
  @Override
  protected Promise<RunContentDescriptor> execute(@NotNull ExecutionEnvironment environment, @NotNull RunProfileState state)
    throws ExecutionException {
    File outputFile = getOutputFile(environment, (GoTestDebugRunningState)state);
    FileDocumentManager.getInstance().saveAllDocuments();

    AsyncPromise<RunContentDescriptor> buildingPromise = new AsyncPromise<>();
    GoHistoryProcessListener historyProcessListener = new GoHistoryProcessListener();
    boolean debug = ((GoTestDebugRunningState)state).isDebug();
    ((GoTestDebugRunningState)state).createCommonExecutor()
      .withParameters("test")
      .withParameterString(((GoTestDebugRunningState)state).getGoBuildParams())
      .withParameters("-o", outputFile.getAbsolutePath())
      .withParameters(debug ? new String[]{"-gcflags", "-N -l"} : ArrayUtil.EMPTY_STRING_ARRAY)
      .withParameters(((GoTestDebugRunningState)state).getTarget())
      .disablePty()
      .withPresentableName("go test")
      .withProcessListener(historyProcessListener)
      .withProcessListener(new ProcessAdapter() {
        @Override
        public void processTerminated(ProcessEvent event) {
          super.processTerminated(event);
          boolean compilationFailed = event.getExitCode() != 0;
          ((GoTestDebugRunningState)state).setHistoryProcessHandler(historyProcessListener);
          ((GoTestDebugRunningState)state).setOutputFilePath(outputFile.getAbsolutePath());
          ((GoTestDebugRunningState)state).setCompilationFailed(compilationFailed);
          try {
            GoTestDebugRunner.RunContentDescriptorSupplier
              runContentSupplier = new GoTestDebugRunner.RunContentDescriptorSupplier(environment, (GoTestDebugRunningState)state);
            if (runContentSupplier.executionResult != null) {
              ApplicationManager.getApplication().invokeLater(() -> {
                try {
                  buildingPromise.setResult(runContentSupplier.get());
                }
                catch (ExecutionException ex) {
                  buildingPromise.setError(ex);
                }
              });
            }
            else buildingPromise.setResult(null);
          } catch (Throwable ex) {
            buildingPromise.setError(ex);
          }
        }
      }).executeWithProgress(false);
    return buildingPromise;
  }

  @NotNull
  private static File getOutputFile(@NotNull ExecutionEnvironment environment, @NotNull GoTestDebugRunningState state)
    throws ExecutionException {
    File outputFile;
    String outputDirectoryPath = state.getConfiguration().getOutputFilePath();
    RunnerAndConfigurationSettings settings = environment.getRunnerAndConfigurationSettings();
    String configurationName = settings != null ? settings.getName() : "application";
    if (StringUtil.isEmpty(outputDirectoryPath)) {
      try {
        outputFile = FileUtil.createTempFile(configurationName, "", true);
      }
      catch (IOException e) {
        throw new ExecutionException("Cannot create temporary output file", e);
      }
    }
    else {
      File outputDirectory = new File(outputDirectoryPath);
      if (outputDirectory.isDirectory() || !outputDirectory.exists() && outputDirectory.mkdirs()) {
        outputFile = new File(outputDirectoryPath, GoEnvironmentUtil.getBinaryFileNameForPath(configurationName));
        try {
          if (!outputFile.exists() && !outputFile.createNewFile()) {
            throw new ExecutionException("Cannot create output file " + outputFile.getAbsolutePath());
          }
        }
        catch (IOException e) {
          throw new ExecutionException("Cannot create output file " + outputFile.getAbsolutePath());
        }
      }
      else {
        throw new ExecutionException("Cannot create output file in " + outputDirectory.getAbsolutePath());
      }
    }
    if (!prepareFile(outputFile)) {
      throw new ExecutionException("Cannot make temporary file executable " + outputFile.getAbsolutePath());
    }
    return outputFile;
  }

  private static boolean prepareFile(@NotNull File file) {
    try {
      FileUtil.writeToFile(file, new byte[]{0x7F, 'E', 'L', 'F'});
    }
    catch (IOException e) {
      return false;
    }
    return file.setExecutable(true);
  }

  private class RunContentDescriptorSupplier {
    private final boolean debug;
    private final int port;
    private final ExecutionEnvironment env;
    private ExecutionResult executionResult;

    private RunContentDescriptorSupplier(ExecutionEnvironment env, GoTestDebugRunningState state) throws ExecutionException {
      this.env = env;
      debug = state.isDebug();
      port = debug ? findFreePort() : -1;
      state.setDebugPort(port);
      executionResult = state.execute(env.getExecutor(), GoTestDebugRunner.this);
      if (debug && executionResult == null) throw new ExecutionException("Cannot run debugger");
    }

    public RunContentDescriptor get() throws ExecutionException {
      if (debug) {
        return XDebuggerManager.getInstance(env.getProject()).startSession(env, new XDebugProcessStarter() {
          @NotNull
          @Override
          public XDebugProcess start(@NotNull XDebugSession session) {
            RemoteVmConnection connection = new DlvRemoteVmConnection();
            DlvDebugProcess process = new DlvDebugProcess(session, connection, executionResult);
            connection.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return process;
          }
        }).getRunContentDescriptor();
      }
      return new RunContentBuilder(executionResult, env).showRunContent(env.getContentToReuse());
    }
  }

  private static int findFreePort() {
    try(ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
    catch (Exception ignore) {
    }
    throw new IllegalStateException("Could not find a free TCP/IP port to start dlv");
  }
}
