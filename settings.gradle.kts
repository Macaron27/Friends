rootProject.name = "friends-plugin"
include("api", "common", "velocity", "bungee", "paper")
include("probe")
project(":probe").projectDir = file("tools/e2e/probe")
