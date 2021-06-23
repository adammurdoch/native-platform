package gradlebuild;

import com.google.common.collect.ImmutableList;
import com.gradle.enterprise.gradleplugin.testdistribution.TestDistributionExtension;
import com.gradle.enterprise.gradleplugin.testdistribution.internal.TestDistributionExtensionInternal;
import groovy.util.Node;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.DependencySubstitutions;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentSelector;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.logging.StandardOutputListener;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestListener;
import org.gradle.api.tasks.testing.TestResult;
import org.gradle.internal.jvm.Jvm;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.language.cpp.CppSourceSet;
import org.gradle.language.cpp.tasks.CppCompile;
import org.gradle.model.Each;
import org.gradle.model.ModelMap;
import org.gradle.model.Mutate;
import org.gradle.model.RuleSource;
import org.gradle.model.internal.registry.ModelRegistry;
import org.gradle.nativeplatform.NativeBinarySpec;
import org.gradle.nativeplatform.PreprocessingTool;
import org.gradle.nativeplatform.SharedLibraryBinarySpec;
import org.gradle.nativeplatform.Tool;
import org.gradle.nativeplatform.internal.NativeBinarySpecInternal;
import org.gradle.nativeplatform.platform.Architecture;
import org.gradle.nativeplatform.platform.NativePlatform;
import org.gradle.nativeplatform.platform.OperatingSystem;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.tasks.LinkSharedLibrary;
import org.gradle.nativeplatform.toolchain.Clang;
import org.gradle.nativeplatform.toolchain.Gcc;
import org.gradle.nativeplatform.toolchain.NativeToolChainRegistry;
import org.gradle.nativeplatform.toolchain.VisualCpp;
import org.gradle.platform.base.BinarySpec;
import org.gradle.platform.base.PlatformContainer;
import org.gradle.platform.base.SourceComponentSpec;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static gradlebuild.NativeRulesUtils.addPlatform;

@SuppressWarnings("UnstableApiUsage")
public abstract class JniPlugin implements Plugin<Project> {

    private static String binaryToVariantName(NativeBinarySpec binary) {
        return binary.getTargetPlatform().getName().replace('_', '-');
    }

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(NativePlatformComponentPlugin.class);
        VariantsExtension variants = project.getExtensions().getByType(VariantsExtension.class);
        project.getPluginManager().apply(JniRules.class);

        configureCppTasks(project);
        configureNativeVersionGeneration(project);
        configureJniTest(project);
        configureDistributedTest(project);

        configurePomOfMainJar(project, variants);

        // Enabling this property means that the tests will try to resolve an external dependency for native platform
        // and test that instead of building native platform for the current machine.
        // The external dependency can live in the file repository `incoming-repo`.
        boolean testVersionFromLocalRepository = project.getProviders().gradleProperty("testVersionFromLocalRepository").forUseAtConfigurationTime().isPresent();
        if (testVersionFromLocalRepository) {
            setupDependencySubstitutionForTestTask(project);
        }

        configureNativeJars(project, variants, testVersionFromLocalRepository);
    }

    private void configureJniTest(Project project) {
        TaskProvider<Test> testJni = project.getTasks().register("testJni", Test.class, task -> {
            // See https://docs.oracle.com/javase/8/docs/technotes/guides/troubleshoot/clopts002.html
            task.jvmArgs("-Xcheck:jni");

            // Only run tests that have the category
            task.useJUnit(jUnitOptions ->
                jUnitOptions.includeCategories("net.rubygrapefruit.platform.testfixture.JniChecksEnabled")
            );
            // Check standard output for JNI warnings and fail if we find anything
            DetectJniWarnings detectJniWarnings = new DetectJniWarnings();
            task.addTestListener(detectJniWarnings);
            task.getLogging().addStandardOutputListener(detectJniWarnings);
            task.doLast(new Action<Task>() {
                @Override
                public void execute(Task task) {
                    List<String> detectedWarnings = detectJniWarnings.getDetectedWarnings();
                    if (!detectedWarnings.isEmpty()) {
                        throw new RuntimeException(String.format(
                            "Detected JNI check warnings on standard output while executing tests:\n - %s",
                            String.join("\n - ", detectedWarnings)
                        ));
                    }
                }
            });
        });
        project.getTasks().named("check", check -> check.dependsOn(testJni));
    }

    private void configureDistributedTest(Project project) {
        project.getTasks().register("testDistributed", Test.class, task -> {
            TestDistributionExtension distribution = task.getExtensions().getByType(TestDistributionExtension.class);
            distribution.getEnabled().set(true);
            // Only run remotely
            distribution.getMaxLocalExecutors().set(0);
                // Dogfooding TD against ge-experiment until GE 2021.1 is available on e.grdev.net and ge.gradle.org (and the new TD Gradle plugin version 2.0 is accepted)
                ((TestDistributionExtensionInternal) distribution).getServer().set(project.uri("https://ge-experiment.grdev.net"));
                distribution.getRequirements().set(ImmutableList.of("os=linux", "gbt-dogfooding"));
        });
    }

    private void configureNativeVersionGeneration(Project project) {
        NativePlatformVersionExtension nativeVersion = project.getExtensions().create("nativeVersion", NativePlatformVersionExtension.class);

        File generatedFilesDir = new File(project.getBuildDir(), "generated");

        TaskProvider<WriteNativeVersionSources> writeNativeVersionSources = project.getTasks().register("writeNativeVersionSources", WriteNativeVersionSources.class, task -> {
            task.getGeneratedNativeHeaderDirectory().set(new File(generatedFilesDir, "version/header"));
            task.getGeneratedJavaSourcesDir().set(new File(generatedFilesDir, "version/java"));
            task.getVersionClassPackageName().set(nativeVersion.getVersionClassPackageName());
            task.getVersionClassName().set(nativeVersion.getVersionClassName());
        });


        project.getTasks().withType(CppCompile.class).configureEach(task ->
            task.includes(writeNativeVersionSources.flatMap(WriteNativeVersionSources::getGeneratedNativeHeaderDirectory)
        ));
        JavaPluginConvention javaPluginConvention = project.getConvention().getPlugin(JavaPluginConvention.class);
        javaPluginConvention.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME).java(javaSources ->
            javaSources.srcDir(writeNativeVersionSources.flatMap(WriteNativeVersionSources::getGeneratedJavaSourcesDir))
        );
    }

    @SuppressWarnings("unchecked")
    private void configureNativeJars(Project project, VariantsExtension variants, boolean testVersionFromLocalRepository) {
        TaskProvider<Jar> emptyZip = project.getTasks().register("emptyZip", Jar.class, jar -> jar.getArchiveClassifier().set("empty"));
        // We register the publications here, so they are available when the project is used as a composite build.
// When we don't use the software model plugins anymore, then this can move out of the afterEvaluate block.
        project.afterEvaluate(ignored -> {
            String artifactId = project.getTasks().named("jar", Jar.class).get().getArchiveBaseName().get();
            ModelRegistry modelRegistry = ((ProjectInternal) project).getModelRegistry();
            // Realize the software model components, so we can create the corresponding publications.
            modelRegistry.realize("components", ModelMap.class)
                .forEach(spec -> getBinaries(spec).withType(NativeBinarySpec.class)
                    .forEach(binary -> {
                        if (variants.getVariantNames().get().contains(binaryToVariantName(binary)) && binary.isBuildable()) {
                            String variantName = binaryToVariantName(binary);
                            String taskName = "jar-" + variantName;
                            Jar foundNativeJar = (Jar) project.getTasks().findByName(taskName);
                            Jar nativeJar = foundNativeJar == null
                                ? project.getTasks().create(taskName, Jar.class, jar -> jar.getArchiveBaseName().set(artifactId + "-" + variantName))
                                : foundNativeJar;
                            if (foundNativeJar == null) {
                                project.getArtifacts().add("runtimeElements", nativeJar);
                                project.getExtensions().configure(PublishingExtension.class, publishingExtension -> publishingExtension.publications(publications -> publications.create(variantName, MavenPublication.class, publication -> {
                                    publication.artifact(nativeJar);
                                    publication.artifact(emptyZip.get(), it -> it.setClassifier("sources"));
                                    publication.artifact(emptyZip.get(), it -> it.setClassifier("javadoc"));
                                    publication.setArtifactId(nativeJar.getArchiveBaseName().get());
                                })));
                            }
                            binary.getTasks().withType(LinkSharedLibrary.class, builderTask ->
                                nativeJar.into(String.join(
                                    "/",
                                    project.getGroup().toString().replace(".", "/"),
                                    "platform",
                                    variantName
                                ), it -> it.from(builderTask.getLinkedFile()))
                            );
                            if (!testVersionFromLocalRepository) {
                                project.getTasks().withType(Test.class).configureEach(it -> ((ConfigurableFileCollection) it.getClasspath()).from(nativeJar));
                            }
                        }
                    }));
        });
    }

    private void setupDependencySubstitutionForTestTask(Project project) {
        // We need to change the group here, since dependency substitution will not replace
        // a project artifact with an external artifact with the same GAV coordinates.
        project.setGroup("new-group-for-root-project");

        project.getConfigurations().all(configuration ->
        {
            DependencySubstitutions dependencySubstitution = configuration.getResolutionStrategy().getDependencySubstitution();
            dependencySubstitution.all(spec -> {
                ComponentSelector requested = spec.getRequested();
                if (requested instanceof ProjectComponentSelector) {
                    Project projectDependency = project.project(((ProjectComponentSelector) requested).getProjectPath());
                    // Exclude test fixtures by excluding requested dependencies with capabilities.
                    if (requested.getRequestedCapabilities().isEmpty()) {
                        spec.useTarget(dependencySubstitution.module(String.join(":", NativePlatformComponentPlugin.GROUP_ID, projectDependency.getName(), projectDependency.getVersion().toString())));
                    }
                }
            });
        });
        project.getRepositories().maven(maven -> {
            maven.setName("IncomingLocalRepository");
            maven.setUrl(project.getRootProject().file("incoming-repo"));
        });
    }

    private void configurePomOfMainJar(Project project, VariantsExtension variants) {
        project.getExtensions().configure(
            PublishingExtension.class,
            extension -> extension.getPublications().named("main", MavenPublication.class, main ->
                main.getPom().withXml(xmlProvider -> {
                    Node node = xmlProvider.asNode();
                    Node deps = node.appendNode("dependencies");
                    variants.getVariantNames().get().forEach(variantName -> {
                        Node dep = deps.appendNode("dependency");
                        dep.appendNode("groupId", project.getGroup());
                        dep.appendNode("artifactId", main.getArtifactId() + "-" + variantName);
                        dep.appendNode("version", project.getVersion());
                        dep.appendNode("scope", "runtime");
                    });
                })));
    }

    private void configureCppTasks(Project project) {
        TaskContainer tasks = project.getTasks();
        TaskProvider<JavaCompile> compileJavaProvider = tasks.named("compileJava", JavaCompile.class);
        tasks.withType(CppCompile.class)
            .configureEach(task -> task.includes(
                compileJavaProvider.flatMap(it -> it.getOptions().getHeaderOutputDirectory())
            ));
    }

    @SuppressWarnings("unchecked")
    private static ModelMap<BinarySpec> getBinaries(Object modelSpec) {
        try {
            return (ModelMap<BinarySpec>) modelSpec.getClass().getMethod("getBinaries").invoke(modelSpec);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public static class JniRules extends RuleSource {
        @Mutate
        void createPlatforms(PlatformContainer platformContainer) {
            addPlatform(platformContainer, "osx_amd64", "osx", "amd64");
            addPlatform(platformContainer, "osx_aarch64", "osx", "aarch64");
            addPlatform(platformContainer, "linux_amd64", "linux", "amd64");
            addPlatform(platformContainer, "linux_aarch64", "linux", "aarch64");
            addPlatform(platformContainer, "windows_i386", "windows", "i386");
            addPlatform(platformContainer, "windows_amd64", "windows", "amd64");
            addPlatform(platformContainer, "windows_i386_min", "windows", "i386");
            addPlatform(platformContainer, "windows_amd64_min", "windows", "amd64");
        }

        @Mutate void createToolChains(NativeToolChainRegistry toolChainRegistry) {
            toolChainRegistry.create("gcc", Gcc.class, toolChain -> {
                // The core Gradle toolchain for gcc only targets x86 and x86_64 out of the box.
                // https://github.com/gradle/gradle/blob/36614ee523e5906ddfa1fed9a5dc00a5addac1b0/subprojects/platform-native/src/main/java/org/gradle/nativeplatform/toolchain/internal/gcc/AbstractGccCompatibleToolChain.java
                toolChain.target("linux_aarch64");
            });
            toolChainRegistry.create("clang", Clang.class, toolChain -> {
                // The core Gradle toolchain for Clang only targets x86 and x86_64 out of the box.
                OperatingSystem os = new DefaultNativePlatform("current").getOperatingSystem();
                if (os.isMacOsX()) {
                    toolChain.target("osx_aarch64");
                }
            });
            toolChainRegistry.create("visualCpp", VisualCpp.class);
        }

        @Mutate
        void addComponentSourcesSetsToProjectSourceSet(ModelMap<Task> tasks, ModelMap<SourceComponentSpec> sourceContainer) {
            sourceContainer.forEach(sources -> sources.getSources().withType(CppSourceSet.class).forEach(sourceSet ->
                tasks.withType(WriteNativeVersionSources.class, task -> {
                    task.getNativeSources().from(sourceSet.getSource().getSourceDirectories());
                    task.getNativeSources().from(sourceSet.getExportedHeaders().getSourceDirectories());
                })));
        }

        @Mutate void configureBinaries(@Each NativeBinarySpecInternal binarySpec) {
            DefaultNativePlatform currentPlatform = new DefaultNativePlatform("current");
            Architecture currentArch = currentPlatform.getArchitecture();
            NativePlatform targetPlatform = binarySpec.getTargetPlatform();
            Architecture targetArch = targetPlatform.getArchitecture();
            OperatingSystem targetOs = targetPlatform.getOperatingSystem();
            if (ImmutableList.of("linux", "freebsd", "osx").contains(targetOs.getName()) && !targetArch.equals(currentArch)) {
                // Native plugins don't detect whether multilib support is available or not. Assume not for now
                binarySpec.setBuildable(false);
            }

            PreprocessingTool cppCompiler = binarySpec.getCppCompiler();
            Tool linker = binarySpec.getLinker();
            if (targetOs.isMacOsX()) {
                cppCompiler.getArgs().addAll(determineJniIncludes("darwin"));
                cppCompiler.args("-mmacosx-version-min=10.9");
                linker.args("-mmacosx-version-min=10.9");
                linker.args("-framework", "CoreServices");
            } else if (targetOs.isLinux()) {
                cppCompiler.getArgs().addAll(determineJniIncludes("linux"));
                cppCompiler.args("-D_FILE_OFFSET_BITS=64");
            } else if (targetOs.isWindows()) {
                if (binarySpec.getName().contains("_min")) {
                    cppCompiler.define("WINDOWS_MIN");
                }
                cppCompiler.getArgs().addAll(determineWindowsJniIncludes());
                linker.args("Shlwapi.lib", "Advapi32.lib");
            } else if (targetOs.isFreeBSD()) {
                cppCompiler.getArgs().addAll(determineJniIncludes("freebsd"));
            }
        }

        @Mutate void configureSharedLibraryBinaries(@Each SharedLibraryBinarySpec binarySpec, ExtensionContainer extensions, ServiceRegistry serviceRegistry) {
            // Only depend on variants which can be built on the current machine
            boolean onlyLocalVariants = serviceRegistry.get(ProviderFactory.class).gradleProperty("onlyLocalVariants").forUseAtConfigurationTime().isPresent();
            if (onlyLocalVariants && !binarySpec.isBuildable()) {
                return;
            }
            String variantName = binaryToVariantName(binarySpec);
            extensions.getByType(VariantsExtension.class).getVariantNames().add(variantName);
        }

        private List<String> determineJniIncludes(String osSpecificInclude) {
            Jvm currentJvm = Jvm.current();
            File jvmIncludePath = new File(currentJvm.getJavaHome(), "include");
            return ImmutableList.of(
                "-I", jvmIncludePath.getAbsolutePath(),
                "-I", new File(jvmIncludePath, osSpecificInclude).getAbsolutePath()
            );
        }

        private List<String> determineWindowsJniIncludes() {
            Jvm currentJvm = Jvm.current();
            File jvmIncludePath = new File(currentJvm.getJavaHome(), "include");
            return ImmutableList.of(
                "-I" + jvmIncludePath.getAbsolutePath(),
                "-I" + new File(jvmIncludePath, "win32").getAbsolutePath()
            );
        }
    }

    private static class DetectJniWarnings implements TestListener, StandardOutputListener {
        private String currentTest;
        private List<String> detectedWarnings = new ArrayList<>();

        @Override
        public void beforeSuite(TestDescriptor testDescriptor) {}

        @Override
        public void afterSuite(TestDescriptor testDescriptor, TestResult testResult) {}

        @Override
        public void beforeTest(TestDescriptor testDescriptor) {
            currentTest = testDescriptor.getClassName() + "." + testDescriptor.getDisplayName();
        }

        @Override
        public void afterTest(TestDescriptor testDescriptor, TestResult testResult) {
            currentTest = null;
        }

        @Override
        public void onOutput(CharSequence message) {
            if (currentTest != null && message.toString().startsWith("WARNING")) {
                detectedWarnings.add(String.format("%s (test: %s)", message, currentTest));
            }
        }

        public List<String> getDetectedWarnings() {
            return detectedWarnings;
        }
    }
}
