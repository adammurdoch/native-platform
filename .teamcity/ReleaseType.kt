enum class ReleaseType(val gradleProperty: String, val username: String, val password: String, val userProvidedVersion: Boolean = false) {
    Snapshot("snapshot", "bot-build-tool", "credentialsJSON:d94612fb-3291-41f5-b043-e2b3994aeeb4"),
    Alpha("alpha", "bot-build-tool", "credentialsJSON:d94612fb-3291-41f5-b043-e2b3994aeeb4", true),
    Milestone("milestone", "bot-build-tool", "credentialsJSON:d94612fb-3291-41f5-b043-e2b3994aeeb4"),
    Release("release", "bot-build-tool", "credentialsJSON:d94612fb-3291-41f5-b043-e2b3994aeeb4")
}
