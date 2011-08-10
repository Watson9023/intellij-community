/*
 * Copyright 2000-2007 JetBrains s.r.o.
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

package com.intellij.execution.configurations.coverage;

import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.coverage.CoverageRunner;
import com.intellij.coverage.JavaCoverageEngine;
import com.intellij.execution.CommonJavaRunConfigurationParameters;
import com.intellij.execution.ExecutionBundle;
import com.intellij.execution.configurations.ModuleBasedConfiguration;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.util.JreVersionDetector;
import com.intellij.ide.ui.ListCellRendererWrapper;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.PackageChooser;
import com.intellij.openapi.ui.VerticalFlowLayout;
import com.intellij.peer.PeerFactory;
import com.intellij.psi.PsiPackage;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.classFilter.ClassFilterEditor;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

/**
 * Base {@link com.intellij.openapi.options.Configurable} for configuring code coverage
 * To obtain a full configurable use
 * <code>
 * SettingsEditorGroup<YourConfiguration> group = new SettingsEditorGroup<YourConfiguration>();
 * group.addEditor(title, yourConfigurable);
 * group.addEditor(title, yourCoverageConfigurable);
 * </code>
 * @author ven
 */
public class CoverageConfigurable extends SettingsEditor<RunConfigurationBase> {
  private static final Logger LOG = Logger.getInstance("#" + CoverageConfigurable.class.getName());

  private final JreVersionDetector myVersionDetector = new JreVersionDetector();
  Project myProject;
  private MyClassFilterEditor myClassFilterEditor;
  private JCheckBox myCoverageEnabledCheckbox;
  private JLabel myCoverageNotSupportedLabel;
  private JComboBox myCoverageRunnerCb;
  private JPanel myRunnerPanel;
  private JCheckBox myTrackPerTestCoverageCb;
  private JCheckBox myTrackTestSourcesCb;

  private JRadioButton myTracingRb;
  private JRadioButton mySamplingRb;
  private final RunConfigurationBase myConfig;

  private static class MyClassFilterEditor extends ClassFilterEditor {
    public MyClassFilterEditor(Project project) {
      super(project);
    }

    protected void addPatternFilter() {
      PackageChooser chooser = PeerFactory.getInstance().getUIHelper().
        createPackageChooser(CodeInsightBundle.message("coverage.pattern.filter.editor.choose.package.title"), myProject);
      chooser.show();
      if (chooser.isOK()) {
        List<PsiPackage> packages = chooser.getSelectedPackages();
        if (!packages.isEmpty()) {
          for (final PsiPackage aPackage : packages) {
            final String fqName = aPackage.getQualifiedName();
            final String pattern = fqName.length() > 0 ? fqName + ".*" : "*";
            myTableModel.addRow(createFilter(pattern));
          }
          int row = myTableModel.getRowCount() - 1;
          myTable.getSelectionModel().setSelectionInterval(row, row);
          myTable.scrollRectToVisible(myTable.getCellRect(row, 0, true));
          myTable.requestFocus();
        }
      }
    }

    protected String getAddPatternButtonText() {
      return CodeInsightBundle.message("coverage.button.add.package");
    }
  }

  public CoverageConfigurable(RunConfigurationBase config) {
    myConfig = config;
    myProject = config.getProject();
  }

  protected void resetEditorFrom(final RunConfigurationBase runConfiguration) {
    final boolean isJre50;
    if (runConfiguration instanceof CommonJavaRunConfigurationParameters && myVersionDetector.isJre50Configured((CommonJavaRunConfigurationParameters)runConfiguration)) {
      isJre50 = true;
    } else if (runConfiguration instanceof ModuleBasedConfiguration){
      isJre50 = myVersionDetector.isModuleJre50Configured((ModuleBasedConfiguration)runConfiguration);
    } else {
      isJre50 = true;
    }

    myCoverageNotSupportedLabel.setVisible(!isJre50);

    myCoverageEnabledCheckbox.setEnabled(isJre50);

    final JavaCoverageEnabledConfiguration configuration = (JavaCoverageEnabledConfiguration)CoverageEnabledConfiguration.getOrCreate(runConfiguration);
    final CoverageRunner runner = configuration.getCoverageRunner();
    if (runner != null) {
      myCoverageRunnerCb.setSelectedItem(new CoverageRunnerItem(runner));
    } else {
      final String runnerId = configuration.getRunnerId();
      if (runnerId != null){
        final CoverageRunnerItem runnerItem = new CoverageRunnerItem(runnerId);
        final DefaultComboBoxModel model = (DefaultComboBoxModel)myCoverageRunnerCb.getModel();
        if (model.getIndexOf(runnerItem) == -1) {
          model.addElement(runnerItem);
        }
        myCoverageRunnerCb.setSelectedItem(runnerItem);
      } else {
        myCoverageRunnerCb.setSelectedIndex(0);
      }
    }
    UIUtil.setEnabled(myRunnerPanel, isJre50 && configuration.isCoverageEnabled(), true);


    myCoverageEnabledCheckbox.setSelected(isJre50 && configuration.isCoverageEnabled());
    myClassFilterEditor.setEnabled(myCoverageEnabledCheckbox.isSelected());

    myClassFilterEditor.setFilters(configuration.getCoveragePatterns());
    final boolean isCoverageByTestApplicable = runner != null && runner.isCoverageByTestApplicable();
    myTracingRb.setEnabled(myTracingRb.isEnabled() && isCoverageByTestApplicable);
    mySamplingRb.setSelected(configuration.isSampling() || !isCoverageByTestApplicable);
    myTracingRb.setSelected(!mySamplingRb.isSelected());

    myTrackPerTestCoverageCb.setSelected(configuration.isTrackPerTestCoverage());
    myTrackPerTestCoverageCb.setEnabled(myTracingRb.isEnabled() && myTracingRb.isSelected() && canHavePerTestCoverage());

    myTrackTestSourcesCb.setSelected(configuration.isTrackTestFolders());
  }
  
  protected boolean canHavePerTestCoverage() {
    return CoverageEnabledConfiguration.getOrCreate(myConfig).canHavePerTestCoverage();
  }

  protected void applyEditorTo(final RunConfigurationBase runConfiguration) throws ConfigurationException {
    final JavaCoverageEnabledConfiguration configuration = (JavaCoverageEnabledConfiguration)CoverageEnabledConfiguration.getOrCreate(runConfiguration);
    configuration.setCoverageEnabled(myCoverageEnabledCheckbox.isSelected());
    configuration.setCoveragePatterns(myClassFilterEditor.getFilters());
    configuration.setCoverageRunner(getSelectedRunner());
    configuration.setTrackPerTestCoverage(myTrackPerTestCoverageCb.isSelected());
    configuration.setSampling(mySamplingRb.isSelected());
    configuration.setTrackTestFolders(myTrackTestSourcesCb.isSelected());
  }

  @NotNull
  protected JComponent createEditor() {
    JPanel result = new JPanel(new VerticalFlowLayout());

    myCoverageEnabledCheckbox = new JCheckBox(ExecutionBundle.message("enable.coverage.with.emma"));
    result.add(myCoverageEnabledCheckbox);

    final DefaultComboBoxModel runnersModel = new DefaultComboBoxModel();
    myCoverageRunnerCb = new JComboBox(runnersModel);

    final JavaCoverageEnabledConfiguration javaCoverageEnabledConfiguration = JavaCoverageEnabledConfiguration.getFrom(myConfig);
    LOG.assertTrue(javaCoverageEnabledConfiguration != null);
    final JavaCoverageEngine provider = javaCoverageEnabledConfiguration.getCoverageProvider();
    for (CoverageRunner runner : Extensions.getExtensions(CoverageRunner.EP_NAME)) {
      if (runner.acceptsCoverageEngine(provider)) {
        runnersModel.addElement(new CoverageRunnerItem(runner));
      }
    }
    myCoverageRunnerCb.setRenderer(new ListCellRendererWrapper<CoverageRunnerItem>(myCoverageRunnerCb.getRenderer()) {
      @Override
      public void customize(JList list, CoverageRunnerItem value, int index, boolean selected, boolean hasFocus) {
        if (value != null) {
          setText(value.getPresentableName());
        }
      }
    });
    myCoverageRunnerCb.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        final CoverageRunner runner = getSelectedRunner();
        enableTracingPanel(runner != null && runner.isCoverageByTestApplicable());
        myTrackPerTestCoverageCb.setEnabled(myTracingRb.isSelected() && canHavePerTestCoverage() && runner != null && runner.isCoverageByTestApplicable());
      }
    });
    myRunnerPanel = new JPanel(new BorderLayout());
    myRunnerPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
    myRunnerPanel.add(new JLabel("Choose coverage runner:"), BorderLayout.NORTH);
    myRunnerPanel.add(myCoverageRunnerCb, BorderLayout.CENTER);
    final JPanel cPanel = new JPanel(new VerticalFlowLayout());

    mySamplingRb = new JRadioButton("Sampling");
    cPanel.add(mySamplingRb);
    myTracingRb = new JRadioButton("Tracing");
    cPanel.add(myTracingRb);

    final ButtonGroup group = new ButtonGroup();
    group.add(mySamplingRb);
    group.add(myTracingRb);

    ActionListener samplingListener = new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        final CoverageRunner runner = getSelectedRunner();
        myTrackPerTestCoverageCb.setEnabled(canHavePerTestCoverage() && myTracingRb.isSelected() && runner != null && runner.isCoverageByTestApplicable());
      }
    };

    mySamplingRb.addActionListener(samplingListener);
    myTracingRb.addActionListener(samplingListener);

    myTrackPerTestCoverageCb = new JCheckBox("Track per test coverage");
    final JPanel tracingPanel = new JPanel(new BorderLayout());
    tracingPanel.setBorder(BorderFactory.createEmptyBorder(0, 15, 0, 0));
    tracingPanel.add(myTrackPerTestCoverageCb, BorderLayout.CENTER);
    cPanel.add(tracingPanel);
    myRunnerPanel.add(cPanel, BorderLayout.SOUTH);

    result.add(myRunnerPanel);

    JPanel panel = new JPanel(new VerticalFlowLayout());
    panel.setBorder(IdeBorderFactory.createTitledBorder(ExecutionBundle.message("record.coverage.filters.title"), false, false));
    myClassFilterEditor = new MyClassFilterEditor(myProject);
    panel.add(myClassFilterEditor);
    myTrackTestSourcesCb = new JCheckBox("Enable coverage in test folders");
    panel.add(myTrackTestSourcesCb);
    result.add(panel);

    myCoverageEnabledCheckbox.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        final boolean isCoverageEnabled = myCoverageEnabledCheckbox.isSelected();
        myClassFilterEditor.setEnabled(isCoverageEnabled);
        UIUtil.setEnabled(myRunnerPanel, isCoverageEnabled, true);
        final CoverageRunner runner = getSelectedRunner();
        enableTracingPanel(isCoverageEnabled && runner != null && runner.isCoverageByTestApplicable());
        myTrackPerTestCoverageCb.setEnabled(myTracingRb.isSelected() && isCoverageEnabled && canHavePerTestCoverage() && runner != null && runner.isCoverageByTestApplicable());
      }
    });

    myCoverageNotSupportedLabel = new JLabel(CodeInsightBundle.message("code.coverage.is.not.supported"));
    myCoverageNotSupportedLabel.setIcon(UIUtil.getOptionPanelWarningIcon());
    result.add(myCoverageNotSupportedLabel);
    return result;
  }

  @Nullable
  private CoverageRunner getSelectedRunner() {
    final CoverageRunnerItem runnerItem = (CoverageRunnerItem)myCoverageRunnerCb.getSelectedItem();
    if (runnerItem == null) {
      LOG.debug("Available runners: " + myCoverageRunnerCb.getModel().getSize());
    }
    return runnerItem != null ? runnerItem.getRunner() : null;
  }

  private void enableTracingPanel(final boolean enabled) {
    myTracingRb.setEnabled(enabled);
  }

  protected void disposeEditor() {}

  private static class CoverageRunnerItem {
    private CoverageRunner myRunner;
    private @NotNull String myRunnerId;

    private CoverageRunnerItem(@NotNull CoverageRunner runner) {
      myRunner = runner;
      myRunnerId = runner.getId();
    }

    private CoverageRunnerItem(String runnerId) {
      myRunnerId = runnerId;
    }

    public CoverageRunner getRunner() {
      return myRunner;
    }

    public String getRunnerId() {
      return myRunnerId;
    }

    public String getPresentableName() {
      return myRunner != null ? myRunner.getPresentableName() : myRunnerId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      CoverageRunnerItem that = (CoverageRunnerItem)o;

      if (!myRunnerId.equals(that.myRunnerId)) return false;

      return true;
    }

    @Override
    public int hashCode() {
      return myRunnerId.hashCode();
    }
  }
}
