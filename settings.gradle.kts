rootProject.name = "friends-plugin"
include("api", "core", "velocity", "bungee", "paper")
// The end-to-end tools stay local (not in the repository): build their test plugin only where they are.
if (file("tools/e2e/probe").isDirectory) {
    include("probe")
    project(":probe").projectDir = file("tools/e2e/probe")
}
