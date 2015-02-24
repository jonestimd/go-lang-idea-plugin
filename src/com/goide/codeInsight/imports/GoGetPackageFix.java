/*
 * Copyright 2013-2014 Sergey Ignatov, Alexander Zolotov
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

package com.goide.codeInsight.imports;

import com.goide.GoConstants;
import com.goide.GoEnvironmentUtil;
import com.goide.sdk.GoSdkService;
import com.goide.sdk.GoSdkUtil;
import com.intellij.codeInsight.intention.HighPriorityAction;
import com.intellij.codeInspection.LocalQuickFixBase;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Platform;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.*;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.PerformInBackgroundOption;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.PsiElement;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class GoGetPackageFix extends LocalQuickFixBase implements HighPriorityAction {
  private static final String TITLE = "Something went wrong with `go get`";
  @NotNull private final String myPackage;

  public GoGetPackageFix(@NotNull String packageName) {
    super("Go get '" + packageName + "'");
    myPackage = packageName;
  }

  @Override
  public void applyFix(@NotNull final Project project, @NotNull ProblemDescriptor descriptor) {
    PsiElement element = descriptor.getPsiElement();
    final Module module = ModuleUtilCore.findModuleForPsiElement(element);
    if (module == null) {
      return;
    }

    final String sdkPath = GoSdkService.getInstance().getSdkHomePath(module);
    if (StringUtil.isEmpty(sdkPath)) {
      return;
    }

    final Task task = new Task.Backgroundable(project, "Go get '" + myPackage + "'", true, PerformInBackgroundOption.ALWAYS_BACKGROUND) {
      private OSProcessHandler myHandler;

      @Override
      public void onCancel() {
        if (myHandler != null) myHandler.destroyProcess();
      }

      @Override
      public void onSuccess() {
        LocalFileSystem.getInstance().refresh(false);
      }

      public void run(@NotNull final ProgressIndicator indicator) {
        indicator.setIndeterminate(true);
        String executable = GoEnvironmentUtil.getExecutableForSdk(sdkPath).getAbsolutePath();

        final GeneralCommandLine install = new GeneralCommandLine();
        ContainerUtil.putIfNotNull(GoConstants.GO_PATH, GoSdkUtil.retrieveGoPath(module), install.getEnvironment());
        install.setExePath(executable);
        install.addParameter("get");
        install.addParameter(myPackage);
        try {
          myHandler = new KillableColoredProcessHandler(install.createProcess(), install.getPreparedCommandLine(Platform.current()));
          final List<String> out = ContainerUtil.newArrayList();
          myHandler.addProcessListener(new ProcessAdapter() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, Key outputType) {
              String text = event.getText();
              out.add(text);
              //indicator.setText2(text); // todo: look ugly
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
              int code = event.getExitCode();
              if (code == 0) return;
              String message = StringUtil.join(out.size() > 1 ? ContainerUtil.subList(out, 1) : out, "\n");
              Notifications.Bus.notify(new Notification(GoConstants.GO_NOTIFICATION_GROUP, TITLE, message, NotificationType.WARNING), 
                                       project);
            }
          });
          ProcessTerminatedListener.attach(myHandler);
          myHandler.startNotify();
          myHandler.waitFor();
          indicator.setText2("Refreshing");
        }
        catch (ExecutionException e) {
          Notifications.Bus.notify(new Notification(GoConstants.GO_NOTIFICATION_GROUP, TITLE, StringUtil.notNullize(e.getMessage()), 
                                                    NotificationType.WARNING), project);
        }
      }
    };
    CommandProcessor.getInstance().runUndoTransparentAction(new Runnable() {
      @Override
      public void run() {
        ProgressManager.getInstance().run(task);
      }
    });
  }
}
