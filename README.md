# Dash DAPI Client for JVM

# Building
- `git clone https://github.com/github/dashevo/dapi-client-android.git`
- `cd dapi-client-android`
- `./gradlew assemble`
- After building it will be available on the local Maven repository.
- To use it with gradle, add `mavenLocal()` to the `repositories` list in your `build.gradle` file and add `org.dashevo:dapi-client:0.11-SNAPSHOT` as dependency. 

# Tests
Run tests with `gradle build test`

# TODO
- Publish to jcenter/maven central
