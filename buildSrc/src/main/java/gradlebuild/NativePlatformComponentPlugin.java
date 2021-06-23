package gradlebuild;

import com.google.common.collect.ImmutableMap;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetOutput;
import org.gradle.api.tasks.compile.GroovyCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testretry.TestRetryPlugin;
import org.gradle.testretry.TestRetryTaskExtension;

public abstract class NativePlatformComponentPlugin implements Plugin<Project> {
    public static final String GROUP_ID = "net.rubygrapefruit";

    @Override
    public void apply(Project project) {
        project.getRootProject().getPlugins().apply(BasePlugin.class);
        project.setGroup(GROUP_ID);

        project.getPlugins().apply(JavaPlugin.class);
        project.getRepositories().jcenter();

        project.getPlugins().apply(ReleasePlugin.class);

        configureTestTasks(project);
        configureJavaCompatibility(project);

        project.getTasks().withType(GroovyCompile.class).configureEach(task -> task.getOptions().setIncremental(true));
    }

    private void configureJavaCompatibility(Project project) {
        // Java 9 and later don't support targetting Java 5
        JavaVersion compatibility = JavaVersion.VERSION_1_8;

        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.setSourceCompatibility(compatibility);
        java.setTargetCompatibility(compatibility);
    }

    private void configureTestTasks(Project project) {
        project.getPluginManager().apply(TestRetryPlugin.class);
        boolean isCiServer = project.getProviders().environmentVariable("CI").forUseAtConfigurationTime().isPresent();
        JavaPluginConvention javaPluginConvention = project.getConvention().getPlugin(JavaPluginConvention.class);
        project.getTasks().withType(Test.class).configureEach(test -> {
            test.systemProperty("test.directory", project.getLayout().getBuildDirectory().dir("test files").map(dir -> dir.getAsFile().getAbsoluteFile()).get());
            TestRetryTaskExtension retry = test.getExtensions().getByType(TestRetryTaskExtension.class);
            retry.getMaxRetries().set(isCiServer ? 1 : 0);
            retry.getMaxFailures().set(10);
            retry.getFailOnPassedAfterRetry().set(true);

            // Reconfigure the classpath for the test task here, so we can use dependency substitution on `testRuntimeClasspath`
            // to test an external dependency.
            // We omit `sourceSets.main.output` here and replace it with a test dependency on `project(':')` further down.
            Configuration testRuntimeClasspath = project.getConfigurations().getByName("testRuntimeClasspath");
            SourceSetOutput testOutput = javaPluginConvention.getSourceSets().getByName(SourceSet.TEST_SOURCE_SET_NAME).getOutput();
            test.setClasspath(project.files(testRuntimeClasspath, testOutput));
            test.useJUnitPlatform();
        });
        // We need to add the root project to testImplementation manually, since we changed the wiring
        // for the test task to not use sourceSets.main.output.
        // This allows using dependency substitution for the root project.
        DependencyHandler dependencies = project.getDependencies();
        project.getDependencies().add("testImplementation", project.getDependencies().project(ImmutableMap.of("path", project.getPath())));
        dependencies.add("testImplementation", "org.spockframework:spock-core:2.0-groovy-3.0");
        dependencies.add("testImplementation", "org.spockframework:spock-junit4:2.0-groovy-3.0");
        dependencies.add("testImplementation", project.getDependencies().platform("org.codehaus.groovy:groovy-bom:3.0.8"));
    }
}
