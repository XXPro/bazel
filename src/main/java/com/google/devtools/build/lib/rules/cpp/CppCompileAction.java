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

import static java.util.Collections.unmodifiableSet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionLookupValue.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactResolver;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionInfoSpecifier;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.extra.CppCompileInfo;
import com.google.devtools.build.lib.actions.extra.EnvironmentVariable;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.CollectionUtils;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.rules.cpp.CcCommon.CoptsFilter;
import com.google.devtools.build.lib.rules.cpp.CcCompilationContext.HeaderInfo;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppCompileActionContext.Reply;
import com.google.devtools.build.lib.skyframe.ActionExecutionValue;
import com.google.devtools.build.lib.util.DependencySet;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.ShellEscaper;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/** Action that represents some kind of C++ compilation step. */
@ThreadCompatible
public class CppCompileAction extends AbstractAction
    implements IncludeScannable, ExecutionInfoSpecifier, CommandAction {

  private static final PathFragment BUILD_PATH_FRAGMENT = PathFragment.create("BUILD");

  private static final int VALIDATION_DEBUG = 0;  // 0==none, 1==warns/errors, 2==all
  private static final boolean VALIDATION_DEBUG_WARN = VALIDATION_DEBUG >= 1;

  protected final Artifact outputFile;
  private final Artifact sourceFile;
  private final CppConfiguration cppConfiguration;
  private final NestedSet<Artifact> mandatoryInputs;
  private final Iterable<Artifact> inputsForInvalidation;

  /**
   * The set of input files that in addition to {@link CcCompilationContext#declaredIncludeSrcs}
   * need to be added to the set of input artifacts of the action if we don't use input discovery.
   * They may be pruned after execution. See {@link #findUsedHeaders} for more details.
   */
  private final NestedSet<Artifact> additionalPrunableHeaders;

  @Nullable private final Artifact grepIncludes;
  private final boolean shouldScanIncludes;
  private final boolean shouldPruneModules;
  private final boolean usePic;
  private final boolean useHeaderModules;
  private final boolean needsDotdInputPruning;
  protected final boolean needsIncludeValidation;
  private final IncludeProcessing includeProcessing;

  private final CcCompilationContext ccCompilationContext;
  private final ImmutableList<Artifact> builtinIncludeFiles;
  // A list of files to include scan that are not source files, pcm files, or
  // included via a command-line "-include file.h". Actions that use non C++ files as source
  // files--such as Clif--may use this mechanism.
  private final ImmutableList<Artifact> additionalIncludeScanningRoots;
  @VisibleForTesting public final CompileCommandLine compileCommandLine;
  private final ImmutableMap<String, String> executionInfo;
  private final String actionName;

  private final FeatureConfiguration featureConfiguration;

  /**
   * Identifier for the actual execution time behavior of the action.
   *
   * <p>Required because the behavior of this class can be modified by injecting code in the
   * constructor or by inheritance, and we want to have different cache keys for those.
   */
  private final UUID actionClassId;

  /** Whether this action needs to discover inputs. */
  private final boolean discoversInputs;

  private final ImmutableList<PathFragment> builtInIncludeDirectories;

  /**
   * Set when the action prepares for execution. Used to preserve state between preparation and
   * execution.
   */
  private Iterable<Artifact> additionalInputs = null;

  /**
   * Set when a two-stage input discovery is used.
   *
   * <p>Used only during action execution.
   */
  private Set<Artifact> usedModules = null;

  /** Used modules that are not transitively used through other topLevelModules. */
  private Iterable<Artifact> topLevelModules = null;

  private CcToolchainVariables overwrittenVariables = null;

  /**
   * Set when a two-stage input discovery is used.
   *
   * <p>This field is used in the following scenarios.
   *
   * <ul>
   *   <li><i>Action caching.</i> It is set when restoring from the action cache. It is queried
   *       immediately after restoration to populate the {@link
   *       com.google.devtools.build.lib.skyframe.ActionExecutionValue}.
   *   <li><i>Input discovery</i>It is set by {@link discoverInputsStage2}. It is queried to
   *       populate the {@link com.google.devtools.build.lib.skyframe.ActionExecutionValue}.
   *   <li><i>Compilation</i>Compilation reads this field to know what needs to be staged.
   * </ul>
   */
  // TODO(djasper): investigate releasing memory used by this field as early as possible, for
  // example, by including these values in additionalInputs.
  private ImmutableSet<Artifact> discoveredModules = null;

  /**
   * Creates a new action to compile C/C++ source files.
   *
   * @param owner the owner of the action, usually the configured target that emitted it
   * @param allInputs the list of all action inputs.
   * @param featureConfiguration TODO(bazel-team): Add parameter description.
   * @param variables TODO(bazel-team): Add parameter description.
   * @param sourceFile the source file that should be compiled. {@code mandatoryInputs} must contain
   *     this file
   * @param shouldScanIncludes a boolean indicating whether scanning of {@code sourceFile} is to be
   *     performed looking for inclusions.
   * @param usePic TODO(bazel-team): Add parameter description.
   * @param mandatoryInputs any additional files that need to be present for the compilation to
   *     succeed, can be empty but not null, for example, extra sources for FDO.
   * @param inputsForInvalidation are there only to invalidate this action when they change, but are
   *     not needed during actual execution.
   * @param outputFile the object file that is written as result of the compilation, or the fake
   *     object for {@link FakeCppCompileAction}s
   * @param dotdFile the .d file that is generated as a side-effect of compilation
   * @param gcnoFile the coverage notes that are written in coverage mode, can be null
   * @param dwoFile the .dwo output file where debug information is stored for Fission builds (null
   *     if Fission mode is disabled)
   * @param ccCompilationContext the {@code CcCompilationContext}
   * @param coptsFilter regular expression to remove options from {@code copts}
   * @param additionalIncludeScanningRoots list of additional artifacts to include-scan
   * @param actionClassId TODO(bazel-team): Add parameter description
   * @param actionName a string giving the name of this action for the purpose of toolchain
   *     evaluation
   * @param cppSemantics C++ compilation semantics
   * @param cppProvider - CcToolchainProvider with configuration-dependent information.
   */
  CppCompileAction(
      ActionOwner owner,
      NestedSet<Artifact> allInputs,
      FeatureConfiguration featureConfiguration,
      CcToolchainVariables variables,
      Artifact sourceFile,
      CppConfiguration cppConfiguration,
      boolean shouldScanIncludes,
      boolean shouldPruneModules,
      boolean usePic,
      boolean useHeaderModules,
      NestedSet<Artifact> mandatoryInputs,
      Iterable<Artifact> inputsForInvalidation,
      ImmutableList<Artifact> builtinIncludeFiles,
      NestedSet<Artifact> additionalPrunableHeaders,
      Artifact outputFile,
      DotdFile dotdFile,
      @Nullable Artifact gcnoFile,
      @Nullable Artifact dwoFile,
      @Nullable Artifact ltoIndexingFile,
      ActionEnvironment env,
      CcCompilationContext ccCompilationContext,
      CoptsFilter coptsFilter,
      ImmutableList<Artifact> additionalIncludeScanningRoots,
      UUID actionClassId,
      ImmutableMap<String, String> executionInfo,
      String actionName,
      CppSemantics cppSemantics,
      CcToolchainProvider cppProvider,
      @Nullable Artifact grepIncludes) {
    super(
        owner,
        allInputs,
        CollectionUtils.asSetWithoutNulls(
            outputFile,
            dotdFile == null ? null : dotdFile.artifact(),
            gcnoFile,
            dwoFile,
            ltoIndexingFile),
        env);
    Preconditions.checkArgument(!shouldPruneModules || shouldScanIncludes);
    this.outputFile = Preconditions.checkNotNull(outputFile);
    this.sourceFile = sourceFile;
    this.cppConfiguration = cppConfiguration;
    // We do not need to include the middleman artifact since it is a generated artifact and will
    // definitely exist prior to this action execution.
    this.mandatoryInputs = mandatoryInputs;
    this.inputsForInvalidation = inputsForInvalidation;
    this.additionalPrunableHeaders = additionalPrunableHeaders;
    // inputsKnown begins as the logical negation of shouldScanIncludes.
    // When scanning includes, the inputs begin as not known, and become
    // known after inclusion scanning. When *not* scanning includes,
    // the inputs are as declared, hence known, and remain so.
    this.shouldScanIncludes = shouldScanIncludes;
    this.shouldPruneModules = shouldPruneModules;
    this.usePic = usePic;
    this.useHeaderModules = useHeaderModules;
    this.ccCompilationContext = ccCompilationContext;
    this.builtinIncludeFiles = builtinIncludeFiles;
    this.additionalIncludeScanningRoots = ImmutableList.copyOf(additionalIncludeScanningRoots);
    this.compileCommandLine =
        CompileCommandLine.builder(sourceFile, coptsFilter, actionName, dotdFile)
            .setFeatureConfiguration(featureConfiguration)
            .setVariables(variables)
            .build();
    this.executionInfo = executionInfo;
    this.actionName = actionName;
    this.featureConfiguration = featureConfiguration;
    this.needsDotdInputPruning = cppSemantics.needsDotdInputPruning();
    this.needsIncludeValidation = cppSemantics.needsIncludeValidation();
    this.includeProcessing = cppSemantics.getIncludeProcessing();
    this.actionClassId = actionClassId;
    this.discoversInputs = shouldScanIncludes || cppSemantics.needsDotdInputPruning();
    this.builtInIncludeDirectories =
        ImmutableList.copyOf(cppProvider.getBuiltInIncludeDirectories());
    this.additionalInputs = null;
    this.usedModules = null;
    this.topLevelModules = null;
    this.overwrittenVariables = null;
    this.grepIncludes = grepIncludes;
  }

  /**
   * Whether we should do "include scanning". Note that this does *not* mean whether we should parse
   * the .d files to determine which include files were used during compilation. Instead, this means
   * whether we should a) run the pre-execution include scanner (see {@code IncludeScanningContext})
   * if one exists and b) whether the action inputs should be modified to match the results of that
   * pre-execution scanning and (if enabled) again after execution to match the results of the .d
   * file parsing.
   *
   * <p>This does *not* have anything to do with "hdrs_check".
   */
  public boolean shouldScanIncludes() {
    return shouldScanIncludes;
  }

  private boolean shouldScanDotdFiles() {
    return !cppConfiguration.getNoDotdScanningWithModules()
        || !useHeaderModules
        || !shouldPruneModules;
  }

  @Override
  public List<PathFragment> getBuiltInIncludeDirectories() {
    return builtInIncludeDirectories;
  }

  @Nullable
  @Override
  public List<Artifact> getBuiltInIncludeFiles() {
    return builtinIncludeFiles;
  }

  @Override
  public NestedSet<Artifact> getMandatoryInputs() {
    return mandatoryInputs;
  }

  @Override
  public ImmutableSet<Artifact> getMandatoryOutputs() {
    // Never prune orphaned modules files. To cut down critical paths, CppCompileActions do not
    // add modules files as inputs. Instead they rely on input discovery to recognize the needed
    // ones. However, orphan detection runs before input discovery and thus module files would be
    // discarded as orphans.
    // This is strictly better than marking all transitive modules as inputs, which would also
    // effectively disable orphan detection for .pcm files.
    if (outputFile.isFileType(CppFileTypes.CPP_MODULE)) {
      return ImmutableSet.of(outputFile);
    }
    return super.getMandatoryOutputs();
  }

  /**
   * Returns the list of additional inputs found by dependency discovery, during action preparation.
   * {@link #discoverInputs(ActionExecutionContext)} must be called before this method is called on
   * each action execution.
   */
  public Iterable<Artifact> getAdditionalInputs() {
    return Preconditions.checkNotNull(additionalInputs);
  }

  @Override
  public boolean discoversInputs() {
    return discoversInputs;
  }

  @Override
  @VisibleForTesting // productionVisibility = Visibility.PRIVATE
  public Iterable<Artifact> getPossibleInputsForTesting() {
    return Iterables.concat(
        getInputs(), ccCompilationContext.getDeclaredIncludeSrcs(), additionalPrunableHeaders);
  }

  /**
   * Returns the results of include scanning or, when that is null, all prunable headers.
   *
   * <p>This is necessary because the inputs that can be pruned by .d file parsing must be returned
   * from {@link #discoverInputs(ActionExecutionContext)} and they cannot be in {@link
   * #mandatoryInputs}. Thus, even with include scanning turned off, we pretend that we "discover"
   * these headers.
   */
  private Iterable<Artifact> findUsedHeaders(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    Iterable<Artifact> includeScanningResult;
    try {
      includeScanningResult =
          actionExecutionContext
              .getContext(CppIncludeScanningContext.class)
              .findAdditionalInputs(this, actionExecutionContext, includeProcessing);
    } catch (ExecException e) {
      throw e.toActionExecutionException(
          "Include scanning of rule '" + getOwner().getLabel() + "'",
          actionExecutionContext.getVerboseFailures(),
          this);
    }
    if (includeScanningResult != null) {
      return includeScanningResult;
    }
    return NestedSetBuilder.fromNestedSet(ccCompilationContext.getDeclaredIncludeSrcs())
        .addTransitive(additionalPrunableHeaders)
        .build();
  }

  @Nullable
  @Override
  public Iterable<Artifact> discoverInputs(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    Iterable<Artifact> foundHeaders = findUsedHeaders(actionExecutionContext);
    if (!shouldPruneModules) {
      additionalInputs = foundHeaders;
      return additionalInputs;
    }

    Set<Artifact> usedHeadersAndModules = Sets.newLinkedHashSet(foundHeaders);
    if (sourceFile.isFileType(CppFileTypes.CPP_MODULE)) {
      // If we are generating code from a module, the module is all we need.
      // TODO(djasper): Do we really need the source files?
      usedModules = ImmutableSet.of(sourceFile);
      usedHeadersAndModules.add(sourceFile);
    } else {
      usedModules = Sets.newLinkedHashSet();
      // usedHeadersAndModules only contains headers now, so we can pass it to getUsedModules()
      // (and even if it contained other things, it's only used to check for the presence of headers
      // so it would not matter)
      for (HeaderInfo usedModule :
          ccCompilationContext.getUsedModules(usePic, usedHeadersAndModules)) {
        usedModules.add(usedModule.getModule(usePic));
      }
      usedHeadersAndModules.addAll(usedModules);
    }
    additionalInputs = usedHeadersAndModules;
    return additionalInputs;
  }

  /** @return null when either {@link usedModules} was null or on Skyframe lookup failure */
  @Nullable
  @Override
  public Iterable<Artifact> discoverInputsStage2(SkyFunction.Environment env)
      throws InterruptedException {
    if (this.usedModules == null) {
      return null;
    }

    Set<Artifact> additionalModules = computeTransitivelyUsedModules(env, usedModules);
    if (additionalModules == null) {
      return null;
    }

    this.discoveredModules =
        new ImmutableSet.Builder<Artifact>().addAll(usedModules).addAll(additionalModules).build();

    ImmutableSet.Builder<Artifact> topLevelModules = ImmutableSet.builder();
    for (Artifact artifact : this.usedModules) {
      if (!additionalModules.contains(artifact)) {
        topLevelModules.add(artifact);
      }
    }
    this.usedModules = null;
    this.topLevelModules = topLevelModules.build();
    return additionalModules;
  }

  @Override
  public Artifact getPrimaryInput() {
    return getSourceFile();
  }

  @Override
  public Artifact getPrimaryOutput() {
    return getOutputFile();
  }

  /**
   * Returns the path of the c/cc source for gcc.
   */
  public final Artifact getSourceFile() {
    return compileCommandLine.getSourceFile();
  }

  /**
   * Returns the path where gcc should put its result.
   */
  public Artifact getOutputFile() {
    return outputFile;
  }

  @Override
  public Map<Artifact, Artifact> getLegalGeneratedScannerFileMap() {
    return ccCompilationContext.createLegalGeneratedScannerFileMap();
  }

  @Override
  @Nullable
  public Set<Artifact> getModularHeaders() {
    return useHeaderModules && cppConfiguration.getPruneCppInputDiscovery()
        ? ccCompilationContext.getModularHeaders(usePic)
        : null;
  }

  @Override
  @Nullable
  public Artifact getGrepIncludes() {
    return grepIncludes;
  }

  /** Set by {@link discoverInputsStage2} */
  @Override
  @Nullable
  public ImmutableSet<Artifact> getDiscoveredModules() {
    return discoveredModules;
  }

  /**
   * Returns the path where gcc should put the discovered dependency
   * information.
   */
  public DotdFile getDotdFile() {
    return compileCommandLine.getDotdFile();
  }

  @VisibleForTesting
  public CcCompilationContext getCcCompilationContext() {
    return ccCompilationContext;
  }

  @Override
  public List<PathFragment> getQuoteIncludeDirs() {
    return ccCompilationContext.getQuoteIncludeDirs();
  }

  @Override
  public List<PathFragment> getIncludeDirs() {
    ImmutableList.Builder<PathFragment> result = ImmutableList.builder();
    result.addAll(ccCompilationContext.getIncludeDirs());
    for (String opt : compileCommandLine.getCopts()) {
      if (opt.startsWith("-I") && opt.length() > 2) {
        // We insist on the combined form "-Idir".
        result.add(PathFragment.create(opt.substring(2)));
      }
    }
    return result.build();
  }

  @Override
  public List<PathFragment> getSystemIncludeDirs() {
    // TODO(bazel-team): parsing the command line flags here couples us to gcc-style compiler
    // command lines; use a different way to specify system includes (for example through a
    // system_includes attribute in cc_toolchain); note that that would disallow users from
    // specifying system include paths via the copts attribute.
    // Currently, this works together with the include_paths features because getCommandLine() will
    // get the system include paths from the {@code CcCompilationContext} instead.
    ImmutableList.Builder<PathFragment> result = ImmutableList.builder();
    List<String> compilerOptions = getCompilerOptions();
    for (int i = 0; i < compilerOptions.size(); i++) {
      String opt = compilerOptions.get(i);
      if (opt.startsWith("-isystem")) {
        if (opt.length() > 8) {
          result.add(PathFragment.create(opt.substring(8).trim()));
        } else if (i + 1 < compilerOptions.size()) {
          i++;
          result.add(PathFragment.create(compilerOptions.get(i)));
        } else {
          System.err.println("WARNING: dangling -isystem flag in options for " + prettyPrint());
        }
      }
    }
    return result.build();
  }

  @Override
  public List<String> getCmdlineIncludes() {
    ImmutableList.Builder<String> cmdlineIncludes = ImmutableList.builder();
    List<String> args = getArguments();
    for (Iterator<String> argi = args.iterator(); argi.hasNext();) {
      String arg = argi.next();
      if (arg.equals("-include") && argi.hasNext()) {
        cmdlineIncludes.add(argi.next());
      }
    }
    return cmdlineIncludes.build();
  }

  @Override
  public Artifact getMainIncludeScannerSource() {
    return getSourceFile().isFileType(CppFileTypes.CPP_MODULE_MAP)
        ? Iterables.getFirst(ccCompilationContext.getHeaderModuleSrcs(), null)
        : getSourceFile();
  }

  @Override
  public Collection<Artifact> getIncludeScannerSources() {
    if (getSourceFile().isFileType(CppFileTypes.CPP_MODULE_MAP)) {
      // If this is an action that compiles the header module itself, the source we build is the
      // module map, and we need to include-scan all headers that are referenced in the module map.
      return ccCompilationContext.getHeaderModuleSrcs();
    }
    ImmutableList.Builder<Artifact> builder = ImmutableList.builder();
    builder.add(getSourceFile());
    builder.addAll(additionalIncludeScanningRoots);
    return builder.build();
  }

  /**
   * Returns the list of "-D" arguments that should be used by this gcc
   * invocation. Only used for testing.
   */
  @VisibleForTesting
  public ImmutableCollection<String> getDefines() {
    return ccCompilationContext.getDefines();
  }

  @Override
  @VisibleForTesting
  public ImmutableMap<String, String> getIncompleteEnvironmentForTesting() {
    return getEnvironment(ImmutableMap.of());
  }

  public ImmutableMap<String, String> getEnvironment(Map<String, String> clientEnv) {
    Map<String, String> environment = new LinkedHashMap<>(env.size());
    env.resolve(environment, clientEnv);

    if (!getExecutionInfo().containsKey(ExecutionRequirements.REQUIRES_DARWIN)) {
      // Linux: this prevents gcc/clang from writing the unpredictable (and often irrelevant) value
      // of getcwd() into the debug info. Not applicable to Darwin or Windows, which have no /proc.
      environment.put("PWD", "/proc/self/cwd");
    }

    environment.putAll(compileCommandLine.getEnvironment());
    return ImmutableMap.copyOf(environment);
  }

  @Override
  public List<String> getArguments() {
    return compileCommandLine.getArguments(overwrittenVariables);
  }

  @Override
  public ExtraActionInfo.Builder getExtraActionInfo(ActionKeyContext actionKeyContext) {
    CppCompileInfo.Builder info = CppCompileInfo.newBuilder();
    info.setTool(compileCommandLine.getToolPath());
    // TODO(djasper): We are getting discovered or transitive modules through the action's inputs
    // here. For shorter command lines, we'd prefer to use topLevelModules here, but they are not
    // computed in the codepaths leading here.
    for (String option :
        compileCommandLine.getCompilerOptions(getOverwrittenVariables(getInputs()))) {
      info.addCompilerOption(option);
    }
    info.setOutputFile(outputFile.getExecPathString());
    info.setSourceFile(getSourceFile().getExecPathString());
    if (inputsDiscovered()) {
      info.addAllSourcesAndHeaders(Artifact.toExecPaths(getInputs()));
    } else {
      info.addSourcesAndHeaders(getSourceFile().getExecPathString());
      info.addAllSourcesAndHeaders(
          Artifact.toExecPaths(ccCompilationContext.getDeclaredIncludeSrcs()));
    }
    // TODO(ulfjack): Extra actions currently ignore the client environment.
    for (Map.Entry<String, String> envVariable : getIncompleteEnvironmentForTesting().entrySet()) {
      info.addVariable(
          EnvironmentVariable.newBuilder()
              .setName(envVariable.getKey())
              .setValue(envVariable.getValue())
              .build());
    }

    try {
      return super.getExtraActionInfo(actionKeyContext)
          .setExtension(CppCompileInfo.cppCompileInfo, info.build());
    } catch (CommandLineExpansionException e) {
      throw new AssertionError("CppCompileAction command line expansion cannot fail.");
    }
  }

  /**
   * Returns the compiler options.
   */
  @VisibleForTesting
  public List<String> getCompilerOptions() {
    return compileCommandLine.getCompilerOptions(/* overwrittenVariables= */ null);
  }

  @Override
  public ImmutableMap<String, String> getExecutionInfo() {
    return executionInfo;
  }

  /**
   * Enforce that the includes actually visited during the compile were properly declared in the
   * rules.
   *
   * <p>The technique is to walk through all of the reported includes that gcc emits into the .d
   * file, and verify that they came from acceptable relative include directories. This is done in
   * two steps:
   *
   * <p>First, each included file is stripped of any include path prefix from {@code
   * quoteIncludeDirs} to produce an effective relative include dir+name.
   *
   * <p>Second, the remaining directory is looked up in {@code declaredIncludeDirs}, a list of
   * acceptable dirs. This list contains a set of dir fragments that have been calculated by the
   * configured target to be allowable for inclusion by this source. If no match is found, an error
   * is reported and an exception is thrown.
   *
   * @throws ActionExecutionException iff there was an undeclared dependency
   */
  @VisibleForTesting
  public void validateInclusions(
      ActionExecutionContext actionExecutionContext, Iterable<Artifact> inputsForValidation)
      throws ActionExecutionException {
    IncludeProblems errors = new IncludeProblems();
    IncludeProblems warnings = new IncludeProblems();
    Set<Artifact> allowedIncludes = new HashSet<>();
    for (Artifact input :
        Iterables.concat(
            mandatoryInputs,
            ccCompilationContext.getDeclaredIncludeSrcs(),
            additionalPrunableHeaders)) {
      if (input.isMiddlemanArtifact() || input.isTreeArtifact()) {
        actionExecutionContext.getArtifactExpander().expand(input, allowedIncludes);
      }
      allowedIncludes.add(input);
    }

    Iterable<PathFragment> ignoreDirs =
    cppConfiguration.isStrictSystemIncludes()
            ? getBuiltInIncludeDirectories()
            : getValidationIgnoredDirs();

    // Copy the nested sets to hash sets for fast contains checking, but do so lazily.
    // Avoid immutable sets here to limit memory churn.
    Set<PathFragment> declaredIncludeDirs = null;
    Set<PathFragment> warnIncludeDirs = null;
    for (Artifact input : inputsForValidation) {
      // Module input validation is already done by the dotd-file discovery.
      if (input.isFileType(CppFileTypes.CPP_MODULE)) {
        continue;
      }
      // The transitive compilation prerequisites contain all declaredIncludeSrcs() and thus we
      // don't need to check those separately.
      if (ccCompilationContext.getTransitiveCompilationPrerequisites().contains(input)
          || allowedIncludes.contains(input)) {
        continue; // ignore our fixed source in mandatoryInput: we just want includes
      }
      // Ignore headers from built-in include directories.
      if (FileSystemUtils.startsWithAny(input.getExecPath(), ignoreDirs)) {
        continue;
      }
      if (declaredIncludeDirs == null) {
        declaredIncludeDirs = Sets.newHashSet(ccCompilationContext.getDeclaredIncludeDirs());
      }
      if (!isDeclaredIn(actionExecutionContext, input, declaredIncludeDirs)) {
        if (warnIncludeDirs == null) {
          warnIncludeDirs = Sets.newHashSet(ccCompilationContext.getDeclaredIncludeWarnDirs());
        }
        // This call can never match the declared include sources (they would be matched above).
        if (isDeclaredIn(actionExecutionContext, input, warnIncludeDirs)) {
          warnings.add(input.getExecPath().toString());
        } else {
          errors.add(input.getExecPath().toString());
        }
      }
    }
    if (VALIDATION_DEBUG_WARN) {
      synchronized (System.err) {
        if (VALIDATION_DEBUG >= 2 || errors.hasProblems() || warnings.hasProblems()) {
          if (errors.hasProblems()) {
            System.err.println("ERROR: Include(s) were not in declared srcs:");
          } else if (warnings.hasProblems()) {
            System.err.println("WARN: Include(s) were not in declared srcs:");
          } else {
            System.err.println("INFO: Include(s) were OK for '" + getSourceFile()
                + "', declared srcs:");
          }
          for (Artifact a : ccCompilationContext.getDeclaredIncludeSrcs()) {
            System.err.println("  '" + a.toDetailString() + "'");
          }
          System.err.println(" or under declared dirs:");
          for (PathFragment f : Sets.newTreeSet(ccCompilationContext.getDeclaredIncludeDirs())) {
            System.err.println("  '" + f + "'");
          }
          System.err.println(" or under declared warn dirs:");
          for (PathFragment f :
              Sets.newTreeSet(ccCompilationContext.getDeclaredIncludeWarnDirs())) {
            System.err.println("  '" + f + "'");
          }
          System.err.println(" with prefixes:");
          for (PathFragment dirpath : ccCompilationContext.getQuoteIncludeDirs()) {
            System.err.println("  '" + dirpath + "'");
          }
        }
      }
    }

    if (warnings.hasProblems()) {
      actionExecutionContext
          .getEventHandler()
          .handle(
              Event.warn(getOwner().getLocation(), warnings.getMessage(this, getSourceFile()))
                  .withTag(Label.print(getOwner().getLabel())));
    }
    errors.assertProblemFree(this, getSourceFile());
  }

  Iterable<PathFragment> getValidationIgnoredDirs() {
    List<PathFragment> cxxSystemIncludeDirs = getBuiltInIncludeDirectories();
    return Iterables.concat(cxxSystemIncludeDirs, ccCompilationContext.getSystemIncludeDirs());
  }

  /**
   * Returns true if an included artifact is declared in a set of allowed include directories. The
   * simple case is that the artifact's parent directory is contained in the set, or is empty.
   *
   * <p>This check also supports a wildcard suffix of '**' for the cases where the calculations are
   * inexact.
   *
   * <p>It also handles unseen non-nested-package subdirs by walking up the path looking for
   * matches.
   */
  private static boolean isDeclaredIn(
      ActionExecutionContext actionExecutionContext,
      Artifact input,
      Set<PathFragment> declaredIncludeDirs) {
    // If it's a derived artifact, then it MUST be listed in "srcs" as checked above.
    // We define derived here as being not source and not under the include link tree.
    if (!input.isSourceArtifact()
        && !input.getRoot().getExecPath().getBaseName().equals("include")) {
      return false;
    }
    // Need to do dir/package matching: first try a quick exact lookup.
    PathFragment includeDir = input.getRootRelativePath().getParentDirectory();
    if (includeDir.isEmpty() || declaredIncludeDirs.contains(includeDir)) {
      return true;  // OK: quick exact match.
    }
    // Not found in the quick lookup: try the wildcards.
    for (PathFragment declared : declaredIncludeDirs) {
      if (declared.getBaseName().equals("**")) {
        if (includeDir.startsWith(declared.getParentDirectory())) {
          return true;  // OK: under a wildcard dir.
        }
      }
    }
    // Still not found: see if it is in a subdir of a declared package.
    Root root = actionExecutionContext.getRoot(input);
    for (Path dir = actionExecutionContext.getInputPath(input).getParentDirectory(); ; ) {
      if (dir.getRelative(BUILD_PATH_FRAGMENT).exists()) {
        return false;  // Bad: this is a sub-package, not a subdir of a declared package.
      }
      dir = dir.getParentDirectory();
      if (dir.equals(root.asPath())) {
        return false;  // Bad: at the top, give up.
      }
      if (declaredIncludeDirs.contains(root.relativize(dir))) {
        return true;  // OK: found under a declared dir.
      }
    }
  }

  /** Recalculates this action's live input collection, including sources, middlemen. */
  @VisibleForTesting // productionVisibility = Visibility.PRIVATE
  @ThreadCompatible
  public final void updateActionInputs(NestedSet<Artifact> discoveredInputs) {
    NestedSetBuilder<Artifact> inputs = NestedSetBuilder.stableOrder();
    try (SilentCloseable c = Profiler.instance().profile(ProfilerTask.ACTION_UPDATE, describe())) {
      inputs.addTransitive(mandatoryInputs);
      inputs.addAll(inputsForInvalidation);
      inputs.addTransitive(discoveredInputs);
      super.updateInputs(inputs.build());
    }
  }

  /** Sets module file flags based on the action's inputs. */
  protected void setModuleFileFlags() {
    if (useHeaderModules) {
      // If modules pruning is used, modules will be supplied via topLevelModules, otherwise they
      // are regular inputs.
      if (shouldPruneModules) {
        Preconditions.checkNotNull(this.topLevelModules);
        overwrittenVariables = getOverwrittenVariables(topLevelModules);
      } else {
        overwrittenVariables = getOverwrittenVariables(getInputs());
      }
    }
  }

  /**
   * Extracts all module (.pcm) files from potentialModules and returns a Variables object where
   * their exec paths are added to the value "module_files".
   */
  private static CcToolchainVariables getOverwrittenVariables(Iterable<Artifact> potentialModules) {
    ImmutableList.Builder<String> usedModulePaths = ImmutableList.builder();
    for (Artifact input : potentialModules) {
      if (input.isFileType(CppFileTypes.CPP_MODULE)) {
        usedModulePaths.add(input.getExecPathString());
      }
    }
    CcToolchainVariables.Builder variableBuilder = new CcToolchainVariables.Builder();
    variableBuilder.addStringSequenceVariable("module_files", usedModulePaths.build());
    return variableBuilder.build();
  }

  @Override
  public Iterable<Artifact> getAllowedDerivedInputs() {
    HashSet<Artifact> result = new HashSet<>();
    addNonSources(result, mandatoryInputs);
    addNonSources(result, additionalPrunableHeaders);
    addNonSources(result, getDeclaredIncludeSrcs());
    addNonSources(result, ccCompilationContext.getTransitiveModules(usePic));
    Artifact artifact = getSourceFile();
    if (!artifact.isSourceArtifact()) {
      result.add(artifact);
    }
    return unmodifiableSet(result);
  }

  /**
   * Called by {@link com.google.devtools.build.lib.actions.ActionCacheChecker}
   *
   * <p>Restores the value of {@link discoveredModules}, which is used to create the {@link
   * com.google.devtools.build.lib.skyframe.ActionExecutionValue} after an action cache hit.
   */
  @Override
  public synchronized void updateInputs(Iterable<Artifact> inputs) {
    super.updateInputs(inputs);
    ImmutableSet.Builder<Artifact> discoveredModules = ImmutableSet.builder();
    for (Artifact input : inputs) {
      if (input.isFileType(CppFileTypes.CPP_MODULE)) {
        discoveredModules.add(input);
      }
    }
    this.discoveredModules = discoveredModules.build();
  }

  private static void addNonSources(HashSet<Artifact> result, Iterable<Artifact> artifacts) {
    for (Artifact a : artifacts) {
      if (!a.isSourceArtifact()) {
        result.add(a);
      }
    }
  }

  @Override
  protected String getRawProgressMessage() {
    return "Compiling " + getSourceFile().prettyPrint();
  }

  /**
   * Return the directories in which to look for headers (pertains to headers not specifically
   * listed in {@code declaredIncludeSrcs}).
   */
  public NestedSet<PathFragment> getDeclaredIncludeDirs() {
    return ccCompilationContext.getDeclaredIncludeDirs();
  }

  /**
   * Return the directories in which to look for headers and issue a warning. (pertains to headers
   * not specifically listed in {@code declaredIncludeSrcs}).
   */
  public NestedSet<PathFragment> getDeclaredIncludeWarnDirs() {
    return ccCompilationContext.getDeclaredIncludeWarnDirs();
  }

  /** Return explicitly listed header files. */
  @Override
  public NestedSet<Artifact> getDeclaredIncludeSrcs() {
    return ccCompilationContext.getDeclaredIncludeSrcs();
  }

  /**
   * Estimate resource consumption when this action is executed locally.
   */
  public ResourceSet estimateResourceConsumptionLocal() {
    // We use a local compile, so much of the time is spent waiting for IO,
    // but there is still significant CPU; hence we estimate 50% cpu usage.
    return ResourceSet.createWithRamCpuIo(
        /* memoryMb= */ 200, /* cpuUsage= */ 0.5, /* ioUsage= */ 0.0);
  }

  @Override
  public void computeKey(ActionKeyContext actionKeyContext, Fingerprint fp) {
    fp.addUUID(actionClassId);
    fp.addStringMap(env.getFixedEnv());
    fp.addStrings(env.getInheritedEnv());
    fp.addStringMap(compileCommandLine.getEnvironment());
    fp.addStringMap(executionInfo);

    // For the argv part of the cache key, ignore all compiler flags that explicitly denote module
    // file (.pcm) inputs. Depending on input discovery, some of the unused ones are removed from
    // the command line. However, these actually don't have an influence on the compile itself and
    // so ignoring them for the cache key calculation does not affect correctness. The compile
    // itself is fully determined by the input source files and module maps.
    // A better long-term solution would be to make the compiler to find them automatically and
    // never hand in the .pcm files explicitly on the command line in the first place.
    fp.addStrings(compileCommandLine.getArguments(/* overwrittenVariables= */ null));
    actionKeyContext.addNestedSetToFingerprint(fp, ccCompilationContext.getDeclaredIncludeSrcs());
    fp.addInt(0); // mark the boundary between input types
    actionKeyContext.addNestedSetToFingerprint(fp, getMandatoryInputs());
    fp.addInt(0);
    actionKeyContext.addNestedSetToFingerprint(fp, additionalPrunableHeaders);

    fp.addBoolean(shouldScanDotdFiles());
    if (shouldScanDotdFiles()) {
      /**
       * getArguments() above captures all changes which affect the compilation command and hence
       * the contents of the object file. But we need to also make sure that we re-execute the
       * action if any of the fields that affect whether {@link #validateInclusions} will report an
       * error or warning have changed, otherwise we might miss some errors.
       */
      fp.addPaths(ccCompilationContext.getDeclaredIncludeDirs());
      fp.addPaths(ccCompilationContext.getDeclaredIncludeWarnDirs());
    }
  }

  @Override
  @ThreadCompatible
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    setModuleFileFlags();
    CppCompileActionContext.Reply reply;
    ShowIncludesFilter showIncludesFilterForStdout = null;
    ShowIncludesFilter showIncludesFilterForStderr = null;
    // If parse_showincludes feature is enabled, instead of parsing dotD file we parse the output of
    // cl.exe caused by /showIncludes option.
    if (featureConfiguration.isEnabled(CppRuleClasses.PARSE_SHOWINCLUDES)) {
      showIncludesFilterForStdout = new ShowIncludesFilter(getSourceFile().getFilename());
      showIncludesFilterForStderr = new ShowIncludesFilter(getSourceFile().getFilename());
      actionExecutionContext.getFileOutErr().setOutputFilter(showIncludesFilterForStdout);
      actionExecutionContext.getFileOutErr().setErrorFilter(showIncludesFilterForStderr);
    }

    if (!shouldScanDotdFiles()) {
      updateActionInputs(
          NestedSetBuilder.wrap(
              Order.STABLE_ORDER, Iterables.concat(discoveredModules, additionalInputs)));
    }

    List<SpawnResult> spawnResults;
    try {
      CppCompileActionResult cppCompileActionResult =
          actionExecutionContext
              .getContext(CppCompileActionContext.class)
              .execWithReply(this, actionExecutionContext);
      reply = cppCompileActionResult.contextReply();
      spawnResults = cppCompileActionResult.spawnResults();
    } catch (ExecException e) {
      throw e.toActionExecutionException(
          "C++ compilation of rule '" + getOwner().getLabel() + "'",
          actionExecutionContext.getVerboseFailures(),
          this);
    } finally {
      additionalInputs = null;
    }
    ensureCoverageNotesFilesExist(actionExecutionContext);

    if (!shouldScanDotdFiles()) {
      return ActionResult.create(spawnResults);
    }

    // This is the .d file scanning part.
    CppIncludeExtractionContext scanningContext =
        actionExecutionContext.getContext(CppIncludeExtractionContext.class);
    Path execRoot = actionExecutionContext.getExecRoot();

    NestedSet<Artifact> discoveredInputs;
    if (featureConfiguration.isEnabled(CppRuleClasses.PARSE_SHOWINCLUDES)) {
      discoveredInputs =
          discoverInputsFromShowIncludesFilters(
              execRoot,
              scanningContext.getArtifactResolver(),
              showIncludesFilterForStdout,
              showIncludesFilterForStderr);
    } else {
      discoveredInputs =
          discoverInputsFromDotdFiles(
              actionExecutionContext, execRoot, scanningContext.getArtifactResolver(), reply);
    }
    reply = null; // Clear in-memory .d files early.

    // Post-execute "include scanning", which modifies the action inputs to match what the compile
    // action actually used by incorporating the results of .d file parsing.
    updateActionInputs(discoveredInputs);

    // hdrs_check: This cannot be switched off for C++ build actions,
    // because doing so would allow for incorrect builds.
    // HeadersCheckingMode.NONE should only be used for ObjC build actions.
    if (needsIncludeValidation) {
      validateInclusions(actionExecutionContext, discoveredInputs);
    }
    return ActionResult.create(spawnResults);
  }

  @VisibleForTesting
  public NestedSet<Artifact> discoverInputsFromShowIncludesFilters(
      Path execRoot,
      ArtifactResolver artifactResolver,
      ShowIncludesFilter showIncludesFilterForStdout,
      ShowIncludesFilter showIncludesFilterForStderr)
      throws ActionExecutionException {
    if (!needsDotdInputPruning) {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }
    ImmutableList.Builder<Path> dependencies = new ImmutableList.Builder<>();
    dependencies.addAll(showIncludesFilterForStdout.getDependencies(execRoot));
    dependencies.addAll(showIncludesFilterForStderr.getDependencies(execRoot));
    HeaderDiscovery.Builder discoveryBuilder =
        new HeaderDiscovery.Builder()
            .setAction(this)
            .setSourceFile(getSourceFile())
            .setDependencies(dependencies.build())
            .setPermittedSystemIncludePrefixes(getPermittedSystemIncludePrefixes(execRoot))
            .setAllowedDerivedinputs(getAllowedDerivedInputs());

    if (needsIncludeValidation) {
      discoveryBuilder.shouldValidateInclusions();
    }

    return discoveryBuilder.build().discoverInputsFromDependencies(execRoot, artifactResolver);
  }

  @VisibleForTesting
  public NestedSet<Artifact> discoverInputsFromDotdFiles(
      ActionExecutionContext actionExecutionContext,
      Path execRoot,
      ArtifactResolver artifactResolver,
      Reply reply)
      throws ActionExecutionException {
    if (!needsDotdInputPruning || getDotdFile() == null) {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }
    HeaderDiscovery.Builder discoveryBuilder =
        new HeaderDiscovery.Builder()
            .setAction(this)
            .setSourceFile(getSourceFile())
            .setDependencies(
                processDepset(actionExecutionContext, execRoot, reply).getDependencies())
            .setPermittedSystemIncludePrefixes(getPermittedSystemIncludePrefixes(execRoot))
            .setAllowedDerivedinputs(getAllowedDerivedInputs());

    if (needsIncludeValidation) {
      discoveryBuilder.shouldValidateInclusions();
    }

    return discoveryBuilder.build().discoverInputsFromDependencies(execRoot, artifactResolver);
  }

  public DependencySet processDepset(
      ActionExecutionContext actionExecutionContext, Path execRoot, Reply reply)
      throws ActionExecutionException {
    try {
      DotdFile dotdFile = getDotdFile();
      Preconditions.checkNotNull(dotdFile);
      DependencySet depSet = new DependencySet(execRoot);
      // artifact() is null if we are using in-memory .d files. We also want to prepare for the
      // case where we expected an in-memory .d file, but we did not get an appropriate response.
      // Perhaps we produced the file locally.
      if (dotdFile.artifact() != null || reply == null) {
        Path dotdPath;
        if (dotdFile.artifact() != null) {
          dotdPath = dotdFile.getPath(actionExecutionContext);
        } else {
          dotdPath = execRoot.getRelative(dotdFile.getSafeExecPath());
        }
        return depSet.read(dotdPath);
      } else {
        // This is an in-memory .d file.
        return depSet.process(reply.getContents());
      }
    } catch (IOException e) {
      // Some kind of IO or parse exception--wrap & rethrow it to stop the build.
      throw new ActionExecutionException("error while parsing .d file", e, this, false);
    }
  }

  public List<Path> getPermittedSystemIncludePrefixes(Path execRoot) {
    List<Path> systemIncludePrefixes = new ArrayList<>();
    for (PathFragment includePath : getBuiltInIncludeDirectories()) {
      if (includePath.isAbsolute()) {
        systemIncludePrefixes.add(execRoot.getFileSystem().getPath(includePath));
      }
    }
    return systemIncludePrefixes;
  }

  /**
   * Gcc only creates ".gcno" files if the compilation unit is non-empty. To ensure that the set of
   * outputs for a CppCompileAction remains consistent and doesn't vary dynamically depending on the
   * _contents_ of the input files, we create empty ".gcno" files if gcc didn't create them.
   */
  private void ensureCoverageNotesFilesExist(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException {
    for (Artifact output : getOutputs()) {
      if (output.isFileType(CppFileTypes.COVERAGE_NOTES)) { // ".gcno"
        Path outputPath = actionExecutionContext.getInputPath(output);
        if (outputPath.exists()) {
          continue;
        }
        try {
          FileSystemUtils.createEmptyFile(outputPath);
        } catch (IOException e) {
          throw new ActionExecutionException(
              "Error creating file '" + outputPath + "': " + e.getMessage(), e, this, false);
        }
      }
    }
  }

  /**
   * When compiling with modules, the C++ compile action only has the {@code .pcm} files on its
   * inputs, which is not enough for extra actions that parse header files. Thus, re-run include
   * scanning and add headers to the inputs of the extra action, too.
   */
  @Override
  public Iterable<Artifact> getInputFilesForExtraAction(
      ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    Iterable<Artifact> discoveredInputs = findUsedHeaders(actionExecutionContext);
    return Sets.<Artifact>difference(
        ImmutableSet.<Artifact>copyOf(discoveredInputs),
        ImmutableSet.<Artifact>copyOf(getInputs()));
  }

  @Override
  public String getMnemonic() {
    switch (actionName) {
      case CppActionNames.OBJC_COMPILE:
      case CppActionNames.OBJCPP_COMPILE:
        return "ObjcCompile";

      case CppActionNames.LINKSTAMP_COMPILE:
        // When compiling shared native deps, e.g. when two java_binary rules have the same set of
        // native dependencies, the CppCompileAction for link stamp data is shared also. This means
        // that out of two CppCompileAction instances, only one is actually executed, which means
        // that if extra actions are attached to both, one of the extra actions will find a
        // CppCompileAction for which discoverInputs() hasn't been called and thus trigger an
        // assertion. As a band-aid, change the mnemonic of said actions so that one can attach
        // extra actions to regular CppCompileActions without tickling this bug.
        return "CppLinkstampCompile";

      default:
        return "CppCompile";
    }
  }

  @Override
  public String describeKey() {
    StringBuilder message = new StringBuilder();
    message.append(getProgressMessage());
    message.append('\n');
    // Outputting one argument per line makes it easier to diff the results.
    // The first element in getArguments() is actually the command to execute.
    String legend = "  Command: ";
    for (String argument : ShellEscaper.escapeAll(getArguments())) {
      message.append(legend);
      message.append(argument);
      message.append('\n');
      legend = "  Argument: ";
    }

    for (PathFragment path : ccCompilationContext.getDeclaredIncludeDirs()) {
      message.append("  Declared include directory: ");
      message.append(ShellEscaper.escapeString(path.getPathString()));
      message.append('\n');
    }

    for (Artifact src : getDeclaredIncludeSrcs()) {
      message.append("  Declared include source: ");
      message.append(ShellEscaper.escapeString(src.getExecPathString()));
      message.append('\n');
    }

    return message.toString();
  }

  public CompileCommandLine getCompileCommandLine() {
    return compileCommandLine;
  }

  /**
   * For the given {@code usedModules}, looks up modules discovered by their generating actions.
   *
   * <p>The returned value contains elements of {@code usedModules}. It can be null when skyframe
   * lookups return null.
   */
  @Nullable
  private static Set<Artifact> computeTransitivelyUsedModules(
      SkyFunction.Environment env, Set<Artifact> usedModules) throws InterruptedException {
    // ActionLookupKey → ActionLookupValue
    Map<SkyKey, SkyValue> actionLookupValues =
        env.getValues(
            Iterables.transform(
                usedModules, module -> (ActionLookupKey) module.getArtifactOwner()));
    ArrayList<ActionLookupData> executionValueLookups = new ArrayList<>(usedModules.size());
    for (Artifact module : usedModules) {
      ActionLookupData lookupData = lookupDataFromModule(actionLookupValues, module);
      if (lookupData == null) {
        return null;
      }
      executionValueLookups.add(lookupData);
    }

    Set<Artifact> additionalModules = Sets.newLinkedHashSet();
    // ActionLookupData → ActionExecutionValue
    Map<SkyKey, SkyValue> actionExecutionValues = env.getValues(executionValueLookups);
    for (ActionLookupData lookup : executionValueLookups) {
      ActionExecutionValue value = (ActionExecutionValue) actionExecutionValues.get(lookup);
      if (value == null) {
        return null;
      }
      additionalModules.addAll(value.getDiscoveredModules());
    }
    return additionalModules;
  }

  @Nullable
  private static ActionLookupData lookupDataFromModule(
      Map<SkyKey, SkyValue> actionLookupValues, Artifact module) {
    ActionLookupKey lookupKey = (ActionLookupKey) module.getArtifactOwner();
    ActionLookupValue lookupValue = (ActionLookupValue) actionLookupValues.get(lookupKey);
    if (lookupValue == null) {
      return null;
    }
    Preconditions.checkState(
        module.isFileType(CppFileTypes.CPP_MODULE), "Non-module? %s (%s)", module, lookupValue);
    return ActionLookupData.create(
        lookupKey,
        Preconditions.checkNotNull(
            lookupValue.getGeneratingActionIndex(module),
            "%s missing action index for module %s",
            lookupValue,
            module));
  }

  /**
   * A reference to a .d file. There are two modes:
   *
   * <ol>
   *   <li>an Artifact that represents a real on-disk file
   *   <li>just an execPath that refers to a virtual .d file that is not written to disk
   * </ol>
   */
  public static class DotdFile {
    private final Artifact artifact;
    private final PathFragment execPath;

    public DotdFile(Artifact artifact) {
      this.artifact = artifact;
      this.execPath = null;
    }

    public DotdFile(PathFragment execPath) {
      this.artifact = null;
      this.execPath = execPath;
    }

    /**
     * @return the Artifact or null
     */
    public Artifact artifact() {
      return artifact;
    }

    /**
     * @return Gets the execPath regardless of whether this is a real Artifact
     */
    public PathFragment getSafeExecPath() {
      return execPath == null ? artifact.getExecPath() : execPath;
    }

    /** @return the on-disk location of the .d file or null */
    public Path getPath(ActionExecutionContext actionExecutionContext) {
      return actionExecutionContext.getInputPath(artifact);
    }
  }
}
