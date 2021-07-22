package gradlebuild;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.Optional;

public class VersionDetails {

    public enum ReleaseRepository {
        GradleRepoSnapshots("https://repo.grdev.net/artifactory/ext-snapshots-local"),
        GradleRepoReleases("https://repo.grdev.net/artifactory/ext-releases-local");

        private final String url;

        ReleaseRepository(String url) {
            this.url = url;
        }

        public String getUrl() {
            return url;
        }
    }

    public enum BuildType {
        Dev(null),
        Snapshot(ReleaseRepository.GradleRepoSnapshots),
        Alpha(ReleaseRepository.GradleRepoReleases),
        Milestone(ReleaseRepository.GradleRepoReleases),
        Release(ReleaseRepository.GradleRepoReleases);

        private final ReleaseRepository releaseRepository;

        BuildType(@Nullable ReleaseRepository releaseRepository) {
            this.releaseRepository = releaseRepository;
        }

        Optional<ReleaseRepository> getReleaseRepository() {
            return Optional.ofNullable(releaseRepository);
        }
    }

    private final BuildType buildType;
    private final String version;

    @Inject
    public VersionDetails(BuildType buildType, String version) {
        this.buildType = buildType;
        this.version = version;
    }

    public boolean isUseRepo() {
        return buildType.getReleaseRepository().isPresent();
    }

    public Optional<ReleaseRepository> getReleaseRepository() {
        return buildType.getReleaseRepository();
    }

    public String getVersion() {
        return version;
    }
}
