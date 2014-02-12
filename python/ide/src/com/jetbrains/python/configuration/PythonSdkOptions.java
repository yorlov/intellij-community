/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.jetbrains.python.configuration;

import com.google.common.collect.Sets;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkModel;
import com.intellij.openapi.projectRoots.SdkModificator;
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Comparing;
import com.intellij.remotesdk.RemoteCredentials;
import com.intellij.ui.*;
import com.intellij.ui.components.JBList;
import com.intellij.util.NullableConsumer;
import com.intellij.util.NullableFunction;
import com.intellij.util.containers.FactoryMap;
import com.jetbrains.python.remote.PythonRemoteInterpreterManager;
import com.jetbrains.python.sdk.*;
import com.jetbrains.python.sdk.flavors.PythonSdkFlavor;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.util.*;
import java.util.List;

public class PythonSdkOptions extends DialogWrapper {
  private JPanel myPanel;
  private JList mySdkList;
  private boolean mySdkListChanged = false;
  private Sdk myAddedSdk;
  private final PyConfigurableInterpreterList myInterpreterList;
  private final ProjectSdksModel myProjectSdksModel;

  private Map<Sdk, SdkModificator> myModificators = new FactoryMap<Sdk, SdkModificator>() {
    @Override
    protected SdkModificator create(Sdk sdk) {
      return sdk.getSdkModificator();
    }
  };
  private Set<SdkModificator> myModifiedModificators = new HashSet<SdkModificator>();
  private boolean myFirstReset;
  private final Project myProject;

  private boolean myNewProject = false;
  private boolean myShowOtherProjectVirtualenvs = true;

  public void setNewProject(final boolean newProject) {
    myNewProject = newProject;
  }

  public PythonSdkOptions(Project project) {
    super(project);

    setTitle("Project Interpreters");
    myProject = project;
    myInterpreterList = PyConfigurableInterpreterList.getInstance(myProject);
    myProjectSdksModel = myInterpreterList.getModel();
    myFirstReset = true;
    init();
    updateOkButton();
  }

  @Nullable
  @Override
  protected JComponent createCenterPanel() {
    mySdkList = new JBList();
    mySdkList.setCellRenderer(new PySdkListCellRenderer("", myModificators));
    mySdkList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

    ToolbarDecorator decorator = ToolbarDecorator.createDecorator(mySdkList).disableUpDownActions()
      .setAddAction(new AnActionButtonRunnable() {
        @Override
        public void run(AnActionButton button) {
          addSdk(button);
          updateOkButton();
        }
      })
      .setEditAction(new AnActionButtonRunnable() {
        @Override
        public void run(AnActionButton button) {
          editSdk();
          updateOkButton();
        }
      })
      .setRemoveAction(new AnActionButtonRunnable() {
        @Override
        public void run(AnActionButton button) {
          removeSdk();
          updateOkButton();
        }
      })
      .addExtraAction(new ToggleVirtualEnvFilterButton())
      .addExtraAction(new ShowPathButton());

    decorator.setPreferredSize(new Dimension(600, 500));
    myPanel = decorator.createPanel();
    refreshSdkList();
    addListeners();
    return myPanel;
  }

  private void addListeners() {
    myProjectSdksModel.addListener(new SdkModel.Listener() {
      @Override
      public void sdkAdded(Sdk sdk) {
      }

      @Override
      public void beforeSdkRemove(Sdk sdk) {
      }

      @Override
      public void sdkChanged(Sdk sdk, String previousName) {
        refreshSdkList();
      }

      @Override
      public void sdkHomeSelected(Sdk sdk, String newSdkHome) {
      }
    });
    mySdkList.addListSelectionListener(new ListSelectionListener() {
      public void valueChanged(ListSelectionEvent event) {
        updateUI(getSelectedSdk());
      }
    });
  }

  private void updateUI(final Sdk selectedSdk) {
    myProjectSdksModel.setProjectSdk(selectedSdk);
  }

  public boolean isModified() {
    return mySdkListChanged ||
           myProjectSdksModel.isModified() ||
           !myModifiedModificators.isEmpty();
  }

  @Nullable
  private String getSelectedSdkName() {
    final Sdk selectedSdk = (Sdk)mySdkList.getSelectedValue();
    return selectedSdk == null ? null : selectedSdk.getName();
  }

  protected void updateOkButton() {
    super.setOKActionEnabled(isModified());
  }

  @Override
  protected void doOKAction() {
    try {
      apply();
    }
    catch (ConfigurationException ignored) {
    }
    super.doOKAction();
  }

  public void apply() throws ConfigurationException {
    for (SdkModificator modificator : myModifiedModificators) {
      modificator.commitChanges();
    }
    myModificators.clear();
    myModifiedModificators.clear();
    myProjectSdksModel.apply();
    mySdkListChanged = false;
    SdkConfigurationUtil.setDirectoryProjectSdk(myProject, myAddedSdk);
    myProjectSdksModel.setProjectSdk(myAddedSdk);
    myInterpreterList.setSelectedSdk(myAddedSdk);
  }

  /**
   * Returns the stable copy of the SDK currently selected in the SDK table.
   *
   * @return the selected SDK, or null if there's no selection
   */
  @Nullable
  public Sdk getRealSelectedSdk() {
    return ProjectJdkTable.getInstance().findJdk(getSelectedSdkName());
  }

  @Nullable
  public Sdk getSelectedSdk() {
    return (Sdk)mySdkList.getSelectedValue();
  }

  public void reset() {
    clearModificators();
    if (myFirstReset) {
      myFirstReset = false;
    }
    else {
      myProjectSdksModel.reset(null);
    }
    refreshSdkList();
  }

  private void clearModificators() {
    myModificators.clear();
    myModifiedModificators.clear();
  }

  private void refreshSdkList() {
    final List<Sdk> pythonSdks = myInterpreterList.getAllPythonSdks(myProject);
    Sdk projectSdk = myProjectSdksModel.getProjectSdk();
    if (!myShowOtherProjectVirtualenvs) {
      VirtualEnvProjectFilter.removeNotMatching(myProject, pythonSdks);
    }
    Collections.sort(pythonSdks, new PreferredSdkComparator());
    mySdkList.setModel(new CollectionListModel<Sdk>(pythonSdks));

    mySdkListChanged = false;
    if (projectSdk == null) projectSdk = getSdk();
    if (projectSdk != null) {
      projectSdk = myProjectSdksModel.findSdk(projectSdk.getName());
      mySdkList.clearSelection();
      mySdkList.setSelectedValue(projectSdk, true);
      mySdkList.updateUI();
    }
  }

  @Nullable
  private Sdk getSdk() {
    final Module[] modules = ModuleManager.getInstance(myProject).getModules();
    if (modules.length > 0) {
      final Module module = modules[0];
      final ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
      return rootManager.getSdk();
    }
    return ProjectRootManager.getInstance(myProject).getProjectSdk();
  }

  private void addSdk(AnActionButton button) {
    DetailsChooser
      .show(myProject, myProjectSdksModel.getSdks(), this, button.getPreferredPopupPoint(), false, new NullableConsumer<Sdk>() {
        @Override
        public void consume(Sdk sdk) {
          addCreatedSdk(sdk, false);
        }
      });
  }

  private void addCreatedSdk(@Nullable final Sdk sdk, boolean newVirtualEnv) {
    if (sdk != null) {
      myAddedSdk = sdk;
      boolean isVirtualEnv = PythonSdkType.isVirtualEnv(sdk);
      if (isVirtualEnv && !newVirtualEnv) {
        AddVEnvOptionsDialog dialog = new AddVEnvOptionsDialog(myPanel);
        dialog.show();
        if (dialog.getExitCode() != DialogWrapper.OK_EXIT_CODE) {
          return;
        }
        SdkModificator modificator = myModificators.get(sdk);
        setSdkAssociated(modificator, !dialog.makeAvailableToAll());
        myModifiedModificators.add(modificator);
      }
      final Sdk oldSdk = myProjectSdksModel.findSdk(sdk);
      if (oldSdk == null)
        myProjectSdksModel.addSdk(sdk);
      refreshSdkList();
      mySdkList.setSelectedValue(sdk, true);
      mySdkListChanged = true;
    }
  }

  private void editSdk() {
    final Sdk currentSdk = getSelectedSdk();
    if (currentSdk != null) {
      if (currentSdk.getSdkAdditionalData() instanceof RemoteCredentials) {
        editRemoteSdk(currentSdk);
      }
      else {
        editSdk(currentSdk);
      }
      updateUI(currentSdk);
    }
  }

  private void editRemoteSdk(Sdk currentSdk) {
    PythonRemoteInterpreterManager remoteInterpreterManager = PythonRemoteInterpreterManager.getInstance();
    if (remoteInterpreterManager != null) {
      final SdkModificator modificator = myModificators.get(currentSdk);
      Set<Sdk> existingSdks = Sets.newHashSet(myProjectSdksModel.getSdks());
      existingSdks.remove(currentSdk);
      if (remoteInterpreterManager.editSdk(myProject, modificator, existingSdks)) {
        myModifiedModificators.add(modificator);
      }
    }
  }

  private void editSdk(final Sdk currentSdk) {
    final SdkModificator modificator = myModificators.get(currentSdk);
    final EditSdkDialog dialog = new EditSdkDialog(myProject, modificator, new NullableFunction<String, String>() {
      @Override
      public String fun(String s) {
        if (isDuplicateSdkName(s, currentSdk)) {
          return "Please specify a unique name for the interpreter";
        }
        return null;
      }
    });
    dialog.show();
    if (dialog.isOK()) {
      final boolean pathChanged = !Comparing.equal(currentSdk.getHomePath(), dialog.getHomePath());
      if (!currentSdk.getName().equals(dialog.getName()) || pathChanged || dialog.isAssociateChanged()) {
        myModifiedModificators.add(modificator);
        modificator.setName(dialog.getName());
        modificator.setHomePath(dialog.getHomePath());

        if (dialog.isAssociateChanged()) {
          setSdkAssociated(modificator, dialog.associateWithProject());
        }
        if (pathChanged) {
          reloadSdk(currentSdk);
        }
      }
    }
  }

  private void setSdkAssociated(SdkModificator modificator, boolean isAssociated) {
    PythonSdkAdditionalData additionalData = (PythonSdkAdditionalData)modificator.getSdkAdditionalData();
    if (additionalData == null) {
      additionalData = new PythonSdkAdditionalData(PythonSdkFlavor.getFlavor(modificator.getHomePath()));
      modificator.setSdkAdditionalData(additionalData);
    }
    if (isAssociated) {
      additionalData.associateWithProject(myProject);
    }
    else {
      additionalData.setAssociatedProjectPath(null);
    }
  }

  private boolean isDuplicateSdkName(String s, Sdk sdk) {
    for (Sdk existingSdk : myProjectSdksModel.getSdks()) {
      if (existingSdk == sdk) {
        continue;
      }
      String existingName;
      if (myModificators.containsKey(existingSdk)) {
        existingName = myModificators.get(existingSdk).getName();
      }
      else {
        existingName = existingSdk.getName();
      }
      if (existingName.equals(s)) {
        return true;
      }
    }
    return false;
  }

  private void removeSdk() {
    final Sdk current_sdk = getSelectedSdk();
    if (current_sdk != null) {
      myProjectSdksModel.removeSdk(current_sdk);
      if (myModificators.containsKey(current_sdk)) {
        SdkModificator modificator = myModificators.get(current_sdk);
        myModifiedModificators.remove(modificator);
        myModificators.remove(current_sdk);
      }
      refreshSdkList();
      mySdkListChanged = true;
      // TODO select initially selected SDK
      if (mySdkList.getSelectedIndex() < 0) {
        mySdkList.setSelectedIndex(0);
      }
    }
  }

  private void reloadSdk() {
    final Sdk currentSdk = getSelectedSdk();
    if (currentSdk != null) {
      myModifiedModificators.add(myModificators.get(currentSdk));
      reloadSdk(currentSdk);
    }
  }

  private void reloadSdk(Sdk currentSdk) {
    PythonSdkType.setupSdkPaths(myProject, null, currentSdk, myModificators.get(currentSdk)); // or must it be a RunWriteAction?
  }

  private class ToggleVirtualEnvFilterButton extends ToggleActionButton implements DumbAware {
    public ToggleVirtualEnvFilterButton() {
      super("Show virtual environments associated with other projects", AllIcons.General.Filter);
    }

    @Override
    public boolean isSelected(AnActionEvent e) {
      return myShowOtherProjectVirtualenvs;
    }

    @Override
    public void setSelected(AnActionEvent e, boolean state) {
      myShowOtherProjectVirtualenvs = state;
      refreshSdkList();
      updateOkButton();
    }
  }
  private class ShowPathButton extends AnActionButton implements DumbAware {
    public ShowPathButton() {
      super("Show path for the selected interpreter", AllIcons.Actions.ShowAsTree);
    }

    @Override
    public boolean isEnabled() {
      return !(getSelectedSdk() instanceof PyDetectedSdk);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      DialogBuilder dialog = new DialogBuilder(myProject);

      final PythonPathEditor editor =
        new PythonPathEditor("Classes", OrderRootType.CLASSES, FileChooserDescriptorFactory.createAllButJarContentsDescriptor()) {
          @Override
          protected void onReloadButtonClicked() {
            reloadSdk();
          }
        };
      final JComponent component = editor.createComponent();
      component.setPreferredSize(new Dimension(600, 400));
      component.setBorder(IdeBorderFactory.createBorder(SideBorder.ALL));
      dialog.setCenterPanel(component);
      editor.reload(getSelectedSdk().getSdkModificator());

      dialog.setTitle("Interpreter Paths");
      dialog.show();
      updateOkButton();
    }
  }
}
