// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.cpp.CcCommon.CoptsFilter;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Variables;
import com.google.devtools.build.lib.rules.cpp.CppCompileAction.DotdFile;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Builder class to construct C++ compile actions.
 */
public class CppCompileActionBuilder {
  public static final UUID GUID = UUID.fromString("97493805-894f-493a-be66-9a698f45c31d");

  private final ActionOwner owner;
  private final BuildConfiguration configuration;
  private CcToolchainFeatures.FeatureConfiguration featureConfiguration;
  private CcToolchainFeatures.Variables variables = Variables.EMPTY;
  private Artifact sourceFile;
  private final NestedSetBuilder<Artifact> mandatoryInputsBuilder;
  private Artifact optionalSourceFile;
  private Artifact outputFile;
  private Artifact dwoFile;
  private Artifact ltoIndexingFile;
  private PathFragment tempOutputFile;
  private DotdFile dotdFile;
  private Artifact gcnoFile;
  private CcCompilationInfo ccCompilationInfo = CcCompilationInfo.EMPTY;
  private final List<String> pluginOpts = new ArrayList<>();
  private CoptsFilter coptsFilter = CoptsFilter.alwaysPasses();
  private ImmutableList<PathFragment> extraSystemIncludePrefixes = ImmutableList.of();
  private boolean usePic;
  private boolean allowUsingHeaderModules;
  private UUID actionClassId = GUID;
  private CppConfiguration cppConfiguration;
  private ImmutableMap<Artifact, IncludeScannable> lipoScannableMap;
  private final ImmutableList.Builder<Artifact> additionalIncludeScanningRoots;
  private Boolean shouldScanIncludes;
  private Map<String, String> executionInfo = new LinkedHashMap<>();
  private Map<String, String> environment = new LinkedHashMap<>();
  private CppSemantics cppSemantics;
  private CcToolchainProvider ccToolchain;
  private final ImmutableMap<String, String> localShellEnvironment;
  private final boolean codeCoverageEnabled;
  @Nullable private String actionName;
  private ImmutableList<Artifact> builtinIncludeFiles;
  // New fields need to be added to the copy constructor.

  /**
   * Creates a builder from a rule. This also uses the configuration and artifact factory from the
   * rule.
   */
  public CppCompileActionBuilder(RuleContext ruleContext, CcToolchainProvider ccToolchain) {
    this(
        ruleContext.getActionOwner(),
        ruleContext.getConfiguration(),
        getLipoScannableMap(ruleContext, ccToolchain),
        ccToolchain);
  }

  /** Creates a builder from a rule and configuration. */
  public CppCompileActionBuilder(
      RuleContext ruleContext,
      CcToolchainProvider ccToolchain,
      BuildConfiguration configuration) {
    this(
        ruleContext.getActionOwner(),
        configuration,
        getLipoScannableMap(ruleContext, ccToolchain),
        ccToolchain);
  }

  /** Creates a builder from a rule and configuration. */
  private CppCompileActionBuilder(
      ActionOwner actionOwner,
      BuildConfiguration configuration,
      Map<Artifact, IncludeScannable> lipoScannableMap,
      CcToolchainProvider ccToolchain) {
    this.owner = actionOwner;
    this.configuration = configuration;
    this.cppConfiguration = configuration.getFragment(CppConfiguration.class);
    this.lipoScannableMap = ImmutableMap.copyOf(lipoScannableMap);
    this.mandatoryInputsBuilder = NestedSetBuilder.stableOrder();
    this.additionalIncludeScanningRoots = new ImmutableList.Builder<>();
    this.allowUsingHeaderModules = true;
    this.localShellEnvironment = configuration.getLocalShellEnvironment();
    this.codeCoverageEnabled = configuration.isCodeCoverageEnabled();
    this.ccToolchain = ccToolchain;
  }

  /**
   * Creates a builder that is a copy of another builder.
   */
  public CppCompileActionBuilder(CppCompileActionBuilder other) {
    this.owner = other.owner;
    this.featureConfiguration = other.featureConfiguration;
    this.sourceFile = other.sourceFile;
    this.mandatoryInputsBuilder = NestedSetBuilder.<Artifact>stableOrder()
        .addTransitive(other.mandatoryInputsBuilder.build());
    this.additionalIncludeScanningRoots =
        new ImmutableList.Builder<Artifact>().addAll(other.additionalIncludeScanningRoots.build());
    this.optionalSourceFile = other.optionalSourceFile;
    this.outputFile = other.outputFile;
    this.dwoFile = other.dwoFile;
    this.ltoIndexingFile = other.ltoIndexingFile;
    this.tempOutputFile = other.tempOutputFile;
    this.dotdFile = other.dotdFile;
    this.gcnoFile = other.gcnoFile;
    this.ccCompilationInfo = other.ccCompilationInfo;
    this.pluginOpts.addAll(other.pluginOpts);
    this.coptsFilter = other.coptsFilter;
    this.extraSystemIncludePrefixes = ImmutableList.copyOf(other.extraSystemIncludePrefixes);
    this.actionClassId = other.actionClassId;
    this.cppConfiguration = other.cppConfiguration;
    this.configuration = other.configuration;
    this.usePic = other.usePic;
    this.allowUsingHeaderModules = other.allowUsingHeaderModules;
    this.lipoScannableMap = other.lipoScannableMap;
    this.shouldScanIncludes = other.shouldScanIncludes;
    this.executionInfo = new LinkedHashMap<>(other.executionInfo);
    this.environment = new LinkedHashMap<>(other.environment);
    this.localShellEnvironment = other.localShellEnvironment;
    this.codeCoverageEnabled = other.codeCoverageEnabled;
    this.cppSemantics = other.cppSemantics;
    this.ccToolchain = other.ccToolchain;
    this.actionName = other.actionName;
  }

  private static ImmutableMap<Artifact, IncludeScannable> getLipoScannableMap(
      RuleContext ruleContext, CcToolchainProvider toolchain) {
    if (!CppHelper.isLipoOptimization(ruleContext.getFragment(CppConfiguration.class), toolchain)
        // Rules that do not contain sources that are compiled into object files, but may
        // contain headers, will still create CppCompileActions without providing a
        // lipo_context_collector.
        || ruleContext
                .attributes()
                .getAttributeDefinition(TransitiveLipoInfoProvider.LIPO_CONTEXT_COLLECTOR)
            == null) {
      return ImmutableMap.<Artifact, IncludeScannable>of();
    }
    LipoContextProvider provider =
        ruleContext.getPrerequisite(
            TransitiveLipoInfoProvider.LIPO_CONTEXT_COLLECTOR,
            Mode.DONT_CHECK,
            LipoContextProvider.class);
    return provider.getIncludeScannables();
  }

  public PathFragment getTempOutputFile() {
    return tempOutputFile;
  }

  public CppCompileActionBuilder setSourceFile(Artifact sourceFile) {
    this.sourceFile = sourceFile;
    return this;
  }

  public Artifact getSourceFile() {
    return sourceFile;
  }

  public CcCompilationInfo getCcCompilationInfo() {
    return ccCompilationInfo;
  }

  public NestedSet<Artifact> getMandatoryInputs() {
    return mandatoryInputsBuilder.build();
  }

  private Iterable<IncludeScannable> getLipoScannables(NestedSet<Artifact> realMandatoryInputs) {
    boolean fake = tempOutputFile != null;

    return lipoScannableMap.isEmpty() || fake
        ? ImmutableList.<IncludeScannable>of()
        : Iterables.filter(
            Iterables.transform(
                Iterables.filter(
                    FileType.filter(
                        realMandatoryInputs,
                        CppFileTypes.C_SOURCE,
                        CppFileTypes.CPP_SOURCE,
                        CppFileTypes.ASSEMBLER_WITH_C_PREPROCESSOR),
                    Predicates.not(Predicates.equalTo(getSourceFile()))),
                Functions.forMap(lipoScannableMap, null)),
            Predicates.notNull());
  }

  private String getActionName() {
    if (actionName != null) {
      return actionName;
    }
    PathFragment sourcePath = sourceFile.getExecPath();
    if (CppFileTypes.CPP_MODULE_MAP.matches(sourcePath)) {
      return CppCompileAction.CPP_MODULE_COMPILE;
    } else if (CppFileTypes.CPP_HEADER.matches(sourcePath)) {
      // TODO(bazel-team): Handle C headers that probably don't work in C++ mode.
      if (!cppConfiguration.getParseHeadersVerifiesModules()
          && featureConfiguration.isEnabled(CppRuleClasses.PARSE_HEADERS)) {
        return CppCompileAction.CPP_HEADER_PARSING;
      } else if (!cppConfiguration.getParseHeadersVerifiesModules()
          && featureConfiguration.isEnabled(CppRuleClasses.PREPROCESS_HEADERS)) {
        return CppCompileAction.CPP_HEADER_PREPROCESSING;
      } else {
        // CcCommon.collectCAndCppSources() ensures we do not add headers to
        // the compilation artifacts unless either 'parse_headers' or
        // 'preprocess_headers' is set.
        throw new IllegalStateException();
      }
    } else if (CppFileTypes.C_SOURCE.matches(sourcePath)) {
      return CppCompileAction.C_COMPILE;
    } else if (CppFileTypes.CPP_SOURCE.matches(sourcePath)) {
      return CppCompileAction.CPP_COMPILE;
    } else if (CppFileTypes.OBJC_SOURCE.matches(sourcePath)) {
      return CppCompileAction.OBJC_COMPILE;
    } else if (CppFileTypes.OBJCPP_SOURCE.matches(sourcePath)) {
      return CppCompileAction.OBJCPP_COMPILE;
    } else if (CppFileTypes.ASSEMBLER.matches(sourcePath)) {
      return CppCompileAction.ASSEMBLE;
    } else if (CppFileTypes.ASSEMBLER_WITH_C_PREPROCESSOR.matches(sourcePath)) {
      return CppCompileAction.PREPROCESS_ASSEMBLE;
    } else if (CppFileTypes.CLIF_INPUT_PROTO.matches(sourcePath)) {
      return CppCompileAction.CLIF_MATCH;
    } else if (CppFileTypes.CPP_MODULE.matches(sourcePath)) {
      return CppCompileAction.CPP_MODULE_CODEGEN;
    }
    // CcCompilationHelper ensures CppCompileAction only gets instantiated for supported file types.
    throw new IllegalStateException();
  }

  /**
   * Builds the Action as configured and performs some validations on the action. Uses {@link
   * RuleContext#throwWithRuleError(String)} to report errors. Prefer this method over {@link
   * CppCompileActionBuilder#buildOrThrowIllegalStateException()} whenever possible (meaning
   * whenever you have access to {@link RuleContext}).
   *
   * <p>This method may be called multiple times to create multiple compile actions (usually after
   * calling some setters to modify the generated action).
   */
  public CppCompileAction buildOrThrowRuleError(RuleContext ruleContext) throws RuleErrorException {
    List<String> errorMessages = new ArrayList<>();
    CppCompileAction result =
        buildAndVerify((String errorMessage) -> errorMessages.add(errorMessage));

    if (!errorMessages.isEmpty()) {
      for (String errorMessage : errorMessages) {
        ruleContext.ruleError(errorMessage);
      }

      throw new RuleErrorException();
    }

    return result;
  }

  /**
   * Builds the Action as configured and performs some validations on the action. Throws {@link
   * IllegalStateException} to report errors. Prefer {@link
   * CppCompileActionBuilder#buildOrThrowRuleError(RuleContext)} over this method whenever possible
   * (meaning whenever you have access to {@link RuleContext}).
   *
   * <p>This method may be called multiple times to create multiple compile actions (usually after
   * calling some setters to modify the generated action).
   */
  public CppCompileAction buildOrThrowIllegalStateException() {
    return buildAndVerify(
        (String errorMessage) -> {
          throw new IllegalStateException(errorMessage);
        });
  }

  /**
   * Builds the Action as configured and performs some validations on the action. Uses given {@link
   * Consumer} to collect validation errors.
   */
  public CppCompileAction buildAndVerify(Consumer<String> errorCollector) {
    // This must be set either to false or true by CppSemantics, otherwise someone forgot to call
    // finalizeCompileActionBuilder on this builder.
    Preconditions.checkNotNull(shouldScanIncludes);
    Preconditions.checkNotNull(featureConfiguration);
    boolean useHeaderModules =
        allowUsingHeaderModules
            && featureConfiguration.isEnabled(CppRuleClasses.USE_HEADER_MODULES);

    if (featureConfiguration.actionIsConfigured(getActionName())) {
      for (String executionRequirement :
          featureConfiguration.getToolForAction(getActionName()).getExecutionRequirements()) {
        executionInfo.put(executionRequirement, "");
      }
    } else {
      errorCollector.accept(
          String.format("Expected action_config for '%s' to be configured", getActionName()));
    }

    NestedSet<Artifact> realMandatoryInputs = buildMandatoryInputs();
    NestedSet<Artifact> allInputs = buildAllInputs(realMandatoryInputs);

    NestedSetBuilder<Artifact> prunableInputBuilder = NestedSetBuilder.stableOrder();
    prunableInputBuilder.addTransitive(ccCompilationInfo.getDeclaredIncludeSrcs());
    prunableInputBuilder.addTransitive(cppSemantics.getAdditionalPrunableIncludes());

    Iterable<IncludeScannable> lipoScannables = getLipoScannables(realMandatoryInputs);
    // We need to add "legal generated scanner files" coming through LIPO scannables here. These
    // usually contain pre-grepped source files, i.e. files just containing the #include lines
    // extracted from generated files. With LIPO, some of these files can be accessed, even though
    // there is no direct dependency on them. Adding the artifacts as inputs to this compile
    // action ensures that the action generating them is actually executed.
    for (IncludeScannable lipoScannable : lipoScannables) {
      for (Artifact value : lipoScannable.getLegalGeneratedScannerFileMap().values()) {
        if (value != null) {
          prunableInputBuilder.add(value);
        }
      }
    }

    NestedSet<Artifact> prunableInputs = prunableInputBuilder.build();

    // Copying the collections is needed to make the builder reusable.
    CppCompileAction action;
    boolean fake = tempOutputFile != null;
    if (fake) {
      action =
          new FakeCppCompileAction(
              owner,
              allInputs,
              featureConfiguration,
              cppConfiguration.getCrosstoolTopPathFragment(),
              variables,
              sourceFile,
              shouldScanIncludes,
              shouldPruneModules(),
              usePic,
              useHeaderModules,
              cppConfiguration.isStrictSystemIncludes(),
              realMandatoryInputs,
              getBuiltinIncludeFiles(),
              prunableInputs,
              outputFile,
              tempOutputFile,
              dotdFile,
              localShellEnvironment,
              ccCompilationInfo,
              coptsFilter,
              getLipoScannables(realMandatoryInputs),
              cppSemantics,
              ccToolchain,
              ImmutableMap.copyOf(executionInfo));
    } else {
      action =
          new CppCompileAction(
              owner,
              allInputs,
              featureConfiguration,
              cppConfiguration.getCrosstoolTopPathFragment(),
              variables,
              sourceFile,
              shouldScanIncludes,
              shouldPruneModules(),
              usePic,
              useHeaderModules,
              cppConfiguration.isStrictSystemIncludes(),
              realMandatoryInputs,
              getBuiltinIncludeFiles(),
              prunableInputs,
              outputFile,
              dotdFile,
              gcnoFile,
              dwoFile,
              ltoIndexingFile,
              optionalSourceFile,
              localShellEnvironment,
              ccCompilationInfo,
              coptsFilter,
              getLipoScannables(realMandatoryInputs),
              additionalIncludeScanningRoots.build(),
              actionClassId,
              ImmutableMap.copyOf(executionInfo),
              ImmutableMap.copyOf(environment),
              getActionName(),
              cppSemantics,
              ccToolchain);
    }

    if (cppSemantics.needsIncludeValidation()) {
      verifyActionIncludePaths(action, errorCollector);
    }
    return action;
  }

  private ImmutableList<Artifact> getBuiltinIncludeFiles() {
    ImmutableList.Builder<Artifact> result = ImmutableList.builder();
    result.addAll(ccToolchain.getBuiltinIncludeFiles());
    if (builtinIncludeFiles != null) {
      result.addAll(builtinIncludeFiles);
    }
    return result.build();
  }

  /**
   * Returns the list of mandatory inputs for the {@link CppCompileAction} as configured.
   */
  NestedSet<Artifact> buildMandatoryInputs() {
    NestedSetBuilder<Artifact> realMandatoryInputsBuilder = NestedSetBuilder.compileOrder();
    realMandatoryInputsBuilder.addTransitive(mandatoryInputsBuilder.build());
    realMandatoryInputsBuilder.addAll(getBuiltinIncludeFiles());
    realMandatoryInputsBuilder.addAll(ccCompilationInfo.getTransitiveCompilationPrerequisites());
    if (useHeaderModules() && !shouldPruneModules()) {
      realMandatoryInputsBuilder.addTransitive(ccCompilationInfo.getTransitiveModules(usePic));
    }
    realMandatoryInputsBuilder.addTransitive(ccCompilationInfo.getAdditionalInputs());
    realMandatoryInputsBuilder.add(Preconditions.checkNotNull(sourceFile));
    return realMandatoryInputsBuilder.build();
  }

  /**
   * Returns the list of all inputs for the {@link CppCompileAction} as configured.
   */
  NestedSet<Artifact> buildAllInputs(NestedSet<Artifact> mandatoryInputs) {
    NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
    if (optionalSourceFile != null) {
      builder.add(optionalSourceFile);
    }
    builder.addTransitive(mandatoryInputs);
    return builder.build();
  }

  private boolean useHeaderModules() {
    return allowUsingHeaderModules
        && featureConfiguration.isEnabled(CppRuleClasses.USE_HEADER_MODULES);
  }

  private boolean shouldPruneModules() {
    return cppConfiguration.getPruneCppModules() && shouldScanIncludes && useHeaderModules();
  }

  private void verifyActionIncludePaths(CppCompileAction action, Consumer<String> errorReporter) {
    Iterable<PathFragment> ignoredDirs = action.getValidationIgnoredDirs();
    // We currently do not check the output of:
    // - getQuoteIncludeDirs(): those only come from includes attributes, and are checked in
    //   CcCommon.getIncludeDirsFromIncludesAttribute().
    // - getBuiltinIncludeDirs(): while in practice this doesn't happen, bazel can be configured
    //   to use an absolute system root, in which case the builtin include dirs might be absolute.

    Iterable<PathFragment> includePathsToVerify =
        Iterables.concat(action.getIncludeDirs(), action.getSystemIncludeDirs());
    for (PathFragment includePath : includePathsToVerify) {
      if (FileSystemUtils.startsWithAny(includePath, ignoredDirs)) {
        continue;
      }
      // One starting ../ is okay for getting to a sibling repository.
      if (includePath.startsWith(Label.EXTERNAL_PATH_PREFIX)) {
        includePath = includePath.relativeTo(Label.EXTERNAL_PATH_PREFIX);
      }
      if (includePath.isAbsolute() || includePath.containsUplevelReferences()) {
        errorReporter.accept(
            String.format(
                "The include path '%s' references a path outside of the execution root.",
                includePath));
      }
    }
  }

  /**
   * Set action name that is used to pick the right action_config and features from {@link
   * FeatureConfiguration}. By default the action name is decided from the source filetype.
   */
  public CppCompileActionBuilder setActionName(String actionName) {
    this.actionName = actionName;
    return this;
  }

  /**
   * Sets the feature configuration to be used for the action.
   */
  public CppCompileActionBuilder setFeatureConfiguration(
      FeatureConfiguration featureConfiguration) {
    Preconditions.checkNotNull(featureConfiguration);
    this.featureConfiguration = featureConfiguration;
    return this;
  }

  /**
   * Sets the feature build variables to be used for the action.
   */
  public CppCompileActionBuilder setVariables(CcToolchainFeatures.Variables variables) {
    this.variables = variables;
    return this;
  }

  /** Returns the build variables to be used for the action. */
  public CcToolchainFeatures.Variables getVariables() {
    return variables;
  }

  public CppCompileActionBuilder addEnvironment(Map<String, String> environment) {
    this.environment.putAll(environment);
    return this;
  }

  public CppCompileActionBuilder addExecutionInfo(Map<String, String> executionInfo) {
    this.executionInfo.putAll(executionInfo);
    return this;
  }

  public CppCompileActionBuilder setCppConfiguration(CppConfiguration cppConfiguration) {
    this.cppConfiguration = cppConfiguration;
    return this;
  }

  public CppCompileActionBuilder setActionClassId(UUID uuid) {
    this.actionClassId = uuid;
    return this;
  }

  /**
   * Set an optional source file (usually with metadata of the main source file). The optional
   * source file can only be set once, whether via this method or through the constructor
   * {@link #CppCompileActionBuilder(CppCompileActionBuilder)}.
   */
  public CppCompileActionBuilder addOptionalSourceFile(Artifact artifact) {
    Preconditions.checkState(optionalSourceFile == null, "%s %s", optionalSourceFile, artifact);
    optionalSourceFile = artifact;
    return this;
  }

  public CppCompileActionBuilder addMandatoryInputs(Iterable<Artifact> artifacts) {
    mandatoryInputsBuilder.addAll(artifacts);
    return this;
  }

  public CppCompileActionBuilder addTransitiveMandatoryInputs(NestedSet<Artifact> artifacts) {
    mandatoryInputsBuilder.addTransitive(artifacts);
    return this;
  }

  public CppCompileActionBuilder addAdditionalIncludeScanningRoots(
      Iterable<Artifact> additionalIncludeScanningRoots) {
    this.additionalIncludeScanningRoots.addAll(additionalIncludeScanningRoots);
    return this;
  }

  public CppCompileActionBuilder setOutputs(Artifact outputFile, Artifact dotdFile) {
    this.outputFile = outputFile;
    this.dotdFile = dotdFile == null ? null : new DotdFile(dotdFile);
    return this;
  }

  public CppCompileActionBuilder setOutputs(
      RuleContext ruleContext,
      ArtifactCategory outputCategory,
      String outputName,
      boolean generateDotd)
      throws RuleErrorException {
    this.outputFile = CppHelper.getCompileOutputArtifact(
        ruleContext,
        CppHelper.getArtifactNameForCategory(ruleContext, ccToolchain, outputCategory, outputName),
        configuration);
    if (generateDotd) {
      String dotdFileName =
          CppHelper.getDotdFileName(ruleContext, ccToolchain, outputCategory, outputName);
      if (cppConfiguration.getInmemoryDotdFiles()) {
        // Just set the path, no artifact is constructed
        BuildConfiguration configuration = ruleContext.getConfiguration();
        dotdFile = new DotdFile(
            configuration.getBinDirectory(ruleContext.getRule().getRepository()).getExecPath()
                .getRelative(CppHelper.getObjDirectory(ruleContext.getLabel()))
                .getRelative(dotdFileName));
      } else {
        dotdFile = new DotdFile(CppHelper.getCompileOutputArtifact(ruleContext, dotdFileName,
            configuration));
      }
    } else {
      dotdFile = null;
    }
    return this;
  }

  public CppCompileActionBuilder setDwoFile(Artifact dwoFile) {
    this.dwoFile = dwoFile;
    return this;
  }

  /**
   * Set the minimized bitcode file emitted by this (ThinLTO) compilation that can be used in place
   * of the full bitcode outputFile in the LTO indexing step.
   */
  public CppCompileActionBuilder setLtoIndexingFile(Artifact ltoIndexingFile) {
    this.ltoIndexingFile = ltoIndexingFile;
    return this;
  }

  Artifact getOutputFile() {
    return outputFile;
  }

  /**
   * The temp output file is not an artifact, since it does not appear in the outputs of the
   * action.
   *
   * <p>This is theoretically a problem if that file already existed before, since then Blaze
   * does not delete it before executing the rule, but 1. that only applies for local
   * execution which does not happen very often and 2. it is only a problem if the compiler is
   * affected by the presence of this file, which it should not be.
   */
  public CppCompileActionBuilder setTempOutputFile(PathFragment tempOutputFile) {
    this.tempOutputFile = tempOutputFile;
    return this;
  }

  public DotdFile getDotdFile() {
    return this.dotdFile;
  }

  public CppCompileActionBuilder setGcnoFile(Artifact gcnoFile) {
    this.gcnoFile = gcnoFile;
    return this;
  }

  public CppCompileActionBuilder setCcCompilationInfo(CcCompilationInfo ccCompilationInfo) {
    this.ccCompilationInfo = ccCompilationInfo;
    return this;
  }

  /** Sets whether the CompileAction should use pic mode. */
  public CppCompileActionBuilder setPicMode(boolean usePic) {
    this.usePic = usePic;
    return this;
  }

  /** Sets whether the CompileAction should use header modules. */
  public CppCompileActionBuilder setAllowUsingHeaderModules(boolean allowUsingHeaderModules) {
    this.allowUsingHeaderModules = allowUsingHeaderModules;
    return this;
  }

  /** Sets the CppSemantics for this compile. */
  public CppCompileActionBuilder setSemantics(CppSemantics semantics) {
    this.cppSemantics = semantics;
    return this;
  }

  public CppCompileActionBuilder setShouldScanIncludes(boolean shouldScanIncludes) {
    this.shouldScanIncludes = shouldScanIncludes;
    return this;
  }

  public boolean getShouldScanIncludes() {
    return shouldScanIncludes;
  }

  public CcToolchainProvider getToolchain() {
    return ccToolchain;
  }

  public CppCompileActionBuilder setCoptsFilter(CoptsFilter coptsFilter) {
    this.coptsFilter = Preconditions.checkNotNull(coptsFilter);
    return this;
  }

  public CppCompileActionBuilder setBuiltinIncludeFiles(
      ImmutableList<Artifact> builtinIncludeFiles) {
    this.builtinIncludeFiles = builtinIncludeFiles;
    return this;
  }
}
