/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

package com.intellij.codeInsight.daemon.impl;

import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeInsight.folding.impl.FoldingUpdate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

class InjectedCodeFoldingPass extends TextEditorHighlightingPass implements DumbAware {
  private Runnable myRunnable;
  private final Editor myEditor;
  private final PsiFile myFile;

  InjectedCodeFoldingPass(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    super(project, editor.getDocument(), false);
    myEditor = editor;
    myFile = file;
  }

  public void doCollectInformation(ProgressIndicator progress) {
    Runnable runnable = FoldingUpdate.updateInjectedFoldRegions(myEditor, myFile);
    synchronized (this) {
      myRunnable = runnable;
    }
  }

  public void doApplyInformationToEditor() {
    Runnable runnable;
    synchronized (this) {
      runnable = myRunnable;
    }
    if (runnable != null){
      try {
        runnable.run();
      }
      catch (IndexNotReadyException e) {
      }
    }
  }
}