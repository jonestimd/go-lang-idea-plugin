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

import com.goide.GoConstants;
import com.goide.psi.GoFile;
import com.goide.runconfig.testing.GoTestFinder;
import com.goide.runconfig.testing.GoTestRunConfiguration;
import com.goide.runconfig.testing.GoTestRunningState;
import com.goide.util.GoExecutor;
import com.goide.util.GoHistoryProcessListener;
import com.goide.util.GoUtil;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.ObjectUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;


public class GoTestDebugRunningState extends GoTestRunningState {
    private String myOutputFilePath;
    @Nullable private GoHistoryProcessListener myHistoryProcessHandler;
    private int myDebugPort = 59090;
    private boolean myCompilationFailed;

    public GoTestDebugRunningState(@NotNull ExecutionEnvironment env, @NotNull Module module,
                                     @NotNull GoTestRunConfiguration configuration) {
      super(env, module, configuration);
    }

    @NotNull
    public String getTarget() {
      return myConfiguration.getKind() == GoTestRunConfiguration.Kind.PACKAGE
             ? myConfiguration.getPackage()
             : myConfiguration.getFilePath();
    }

    @NotNull
    public String getGoBuildParams() {
      return myConfiguration.getGoToolParams();
    }

    public boolean isDebug() {
      return DefaultDebugExecutor.EXECUTOR_ID.equals(getEnvironment().getExecutor().getId());
    }

    @NotNull
    @Override
    protected ProcessHandler startProcess() throws ExecutionException {
      GoExecutor executor = patchExecutor(createCommonExecutor()).withParameterString(myConfiguration.getParams());
      GeneralCommandLine commandLine = executor.createCommandLine();
      KillableColoredProcessHandler handler = new KillableColoredProcessHandler(commandLine, true);
      ProcessTerminatedListener.attach(handler);

      handler.addProcessListener(new ProcessAdapter() {
        @Override
        public void startNotified(ProcessEvent event) {
          if (myHistoryProcessHandler != null) {
            myHistoryProcessHandler.apply(handler);
          }
        }

        @Override
        public void processTerminated(ProcessEvent event) {
          super.processTerminated(event);
          if (StringUtil.isEmpty(myConfiguration.getOutputFilePath())) {
            File file = new File(myOutputFilePath);
            if (file.exists()) {
              //noinspection ResultOfMethodCallIgnored
              file.delete();
            }
          }
        }
      });

      return handler;
    }

    @NotNull
    public GoExecutor createCommonExecutor() {
      return GoExecutor.in(myModule).withWorkDirectory(myConfiguration.getWorkingDirectory())
        .withExtraEnvironment(myConfiguration.getCustomEnvironment())
        .withPassParentEnvironment(myConfiguration.isPassParentEnvironment());
    }

    @Override
    protected void addFilterParameter(@NotNull GoExecutor executor, String pattern) {
      if (StringUtil.isNotEmpty(pattern)) {
        executor.withParameters("-test.run", pattern);
      }
    }

    private GoExecutor addConfig(@NotNull GoExecutor executor) throws ExecutionException {
      // same as in GoTesRunningState:
      executor.withParameterString(myConfiguration.getGoToolParams());
      switch (myConfiguration.getKind()) {
        case DIRECTORY:
          String relativePath = FileUtil.getRelativePath(myConfiguration.getWorkingDirectory(),
                                                         myConfiguration.getDirectoryPath(),
                                                         File.separatorChar);
          // TODO Once Go gets support for covering multiple packages the ternary condition should be reverted
          // See https://golang.org/issues/6909
          String pathSuffix = myCoverageFilePath == null ? "..." : ".";
          if (relativePath != null && !".".equals(relativePath)) {
            executor.withParameters("./" + relativePath + "/" + pathSuffix);
          }
          else {
            executor.withParameters("./" + pathSuffix);
            executor.withWorkDirectory(myConfiguration.getDirectoryPath());
          }
          addFilterParameter(executor, ObjectUtils.notNull(myFailedTestsPattern, myConfiguration.getPattern()));
          break;
        case PACKAGE:
          executor = executor.withParameters("test", myConfiguration.getPackage());
          executor = executor.withParameters("--listen=localhost:" + myDebugPort, "--headless=true", "--");
          executor = executor.withParameters("-test.v");
          addFilterParameter(executor, ObjectUtils.notNull(myFailedTestsPattern, myConfiguration.getPattern()));
          break;
        case FILE:
          String filePath = myConfiguration.getFilePath();
          VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath);
          if (virtualFile == null) {
            throw new ExecutionException("Test file doesn't exist");
          }
          PsiFile file = PsiManager.getInstance(myConfiguration.getProject()).findFile(virtualFile);
          if (file == null || !GoTestFinder.isTestFile(file)) {
            throw new ExecutionException("File '" + filePath + "' is not test file");
          }

          String importPath = ((GoFile)file).getImportPath(false);
          if (StringUtil.isEmpty(importPath)) {
            throw new ExecutionException("Cannot find import path for " + filePath);
          }

          //executor.withParameters(importPath);
          executor = executor.withParameters("test", importPath);
          executor = executor.withParameters("--listen=localhost:" + myDebugPort, "--headless=true", "--");
          executor = executor.withParameters("-test.v");
          addFilterParameter(executor, myFailedTestsPattern != null ? myFailedTestsPattern : buildFilterPatternForFile((GoFile)file));
          break;
      }

      if (myCoverageFilePath != null) {
        executor.withParameters("-coverprofile=" + myCoverageFilePath, "-covermode=atomic");
      }
      return executor;
    }

    @Override
    protected GoExecutor patchExecutor(@NotNull GoExecutor executor) throws ExecutionException {
      if (isDebug()) {
        File dlv = dlv();
        if (dlv.exists() && !dlv.canExecute()) {
          //noinspection ResultOfMethodCallIgnored
          dlv.setExecutable(true, false);
        }
        executor = executor.withExePath(dlv.getAbsolutePath());
        return addConfig(executor);
          //.withParameters("--listen=localhost:" + myDebugPort, "--headless=true", "exec", myOutputFilePath, "--");
      }
      return executor.showGoEnvVariables(false).withExePath(myOutputFilePath);
    }

    @NotNull
    private static File dlv() {
      String dlvPath = System.getProperty("dlv.path");
      if (StringUtil.isNotEmpty(dlvPath)) return new File(dlvPath);
      return new File(GoUtil.getPlugin().getPath(),
                      "lib/dlv/" + (SystemInfo.isMac ? "mac" : SystemInfo.isWindows ? "windows" : "linux") + "/"
                      + GoConstants.DELVE_EXECUTABLE_NAME + (SystemInfo.isWindows ? ".exe" : ""));
    }

    public void setOutputFilePath(@NotNull String outputFilePath) {
      myOutputFilePath = outputFilePath;
    }

    public void setHistoryProcessHandler(@Nullable GoHistoryProcessListener historyProcessHandler) {
      myHistoryProcessHandler = historyProcessHandler;
    }

    public void setDebugPort(int debugPort) {
      myDebugPort = debugPort;
    }

    public void setCompilationFailed(boolean compilationFailed) {
      myCompilationFailed = compilationFailed;
    }
  }
