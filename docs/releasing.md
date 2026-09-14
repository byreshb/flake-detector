# Releasing

Two channels exist: GitHub Releases, which is set up and used today, and Maven Central, which is
planned but **not set up yet**.

## GitHub Release (in use)

A release is a git tag of the form `vX.Y.Z`. Pushing the tag triggers
`.github/workflows/release.yml`, which builds the jars and publishes a GitHub Release with the
matching changelog section as its notes.

1. **Write the changelog.** In `CHANGELOG.md`, move the entries under `## [Unreleased]` into a
   new heading `## [X.Y.Z] - YYYY-MM-DD`, and add the comparison link at the bottom of the file.
2. **Set the version.** Run `mvn versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false`,
   which updates the parent `pom.xml` and every module pom in one go, and update the version in
   the README's dependency snippets.
3. **Commit and push** to `main`. Wait for the CI workflow to go green.
4. **Tag and push the tag:**

   ```bash
   git tag -a vX.Y.Z -m "Release X.Y.Z"
   git push origin vX.Y.Z
   ```

5. **Verify.** The Release workflow appears under Actions within a minute and finishes in about
   two. It fails deliberately if the tag does not match the pom version. When it succeeds the
   release is at `https://github.com/byreshb/flake-detector/releases/tag/vX.Y.Z` with the
   main, `-sources.jar` and `-javadoc.jar` of every module attached. The `flake-cli` main jar is
   the shaded, executable one.
6. **Start the next cycle.** Set the version back to `X.Y.(Z+1)-SNAPSHOT` (same `versions:set`
   command) and add a fresh
   `## [Unreleased]` heading to the changelog.

Consumers who do not use Maven can download the jars from the release page; the `flake-cli` jar
runs with `java -jar` and needs nothing else. Maven and Gradle users still need `mvn install` from
a checkout until the library is on Maven Central (see below).

### Fixing a bad release

Delete the release on GitHub, delete the tag (`git push origin :refs/tags/vX.Y.Z` and
`git tag -d vX.Y.Z`), fix the problem, and tag again. Never reuse a version number that anyone
may already have downloaded; prefer releasing a patch version instead.

## Maven Central (planned, not set up yet)

> **Status: not done.** Nothing below is configured in this repository. The steps are recorded so
> the work can be picked up later. Publishing to Maven Central is free for open-source projects.

Once done, users will add the dependency without cloning or `mvn install` (`flake-core` for the
library, `flake-junit` for the JUnit extension, `flake-github` for the GitHub integration):

```xml
<dependency>
  <groupId>io.github.byreshb</groupId>
  <artifactId>flake-core</artifactId>
  <version>1.0.0</version>
</dependency>
```

### One-time setup

1. **Central Portal account.** Sign in at https://central.sonatype.com with the GitHub account.
2. **Namespace verification.** Register the namespace `io.github.byreshb`. Because it is a
   `io.github.<user>` namespace, verification is automatic once GitHub ownership is proven,
   normally by creating a temporary public repository with the name the portal displays.
3. **GPG signing key.** Central requires every artifact to be signed.

   ```bash
   gpg --gen-key                                   # RSA, 4096 bits, your name and email
   gpg --list-keys --keyid-format LONG             # note the key id
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   ```

4. **Portal token.** In the portal, generate a user token (username plus password pair) and put
   it in `~/.m2/settings.xml`:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>TOKEN_USERNAME</username>
         <password>TOKEN_PASSWORD</password>
       </server>
     </servers>
   </settings>
   ```

5. **Pom additions.** Central validates that the pom has `name`, `description`, `url`,
   `licenses`, `developers` and `scm`, and that sources and Javadoc jars are attached (already
   the case). Add a `developers` and `scm` block to the parent pom (modules inherit them), then
   the signing and publishing plugins, ideally inside a `release` profile so ordinary builds do
   not need GPG:

   ```xml
   <developers>
     <developer>
       <id>byreshb</id>
       <name>Byresh B</name>
       <url>https://github.com/byreshb</url>
     </developer>
   </developers>

   <scm>
     <url>https://github.com/byreshb/flake-detector</url>
     <connection>scm:git:https://github.com/byreshb/flake-detector.git</connection>
     <developerConnection>scm:git:git@github.com:byreshb/flake-detector.git</developerConnection>
     <tag>HEAD</tag>
   </scm>

   <profiles>
     <profile>
       <id>release</id>
       <build>
         <plugins>
           <plugin>
             <groupId>org.apache.maven.plugins</groupId>
             <artifactId>maven-gpg-plugin</artifactId>
             <version>3.2.7</version>
             <executions>
               <execution>
                 <id>sign-artifacts</id>
                 <phase>verify</phase>
                 <goals><goal>sign</goal></goals>
               </execution>
             </executions>
           </plugin>
           <plugin>
             <groupId>org.sonatype.central</groupId>
             <artifactId>central-publishing-maven-plugin</artifactId>
             <version>0.7.0</version>
             <extensions>true</extensions>
             <configuration>
               <publishingServerId>central</publishingServerId>
               <autoPublish>true</autoPublish>
             </configuration>
           </plugin>
         </plugins>
       </build>
     </profile>
   </profiles>
   ```

   Check the plugin versions against Maven Central at the time; both move regularly.

### Per-release steps (once set up)

Follow the GitHub Release steps above, then from the tagged commit run:

```bash
mvn -Prelease clean deploy
```

The artifacts are validated, signed and published; they appear on
https://central.sonatype.com and in https://repo.maven.apache.org/maven2 within about an hour.
Add the `mvn -Prelease deploy` step to the release workflow afterwards, with the GPG key and
portal token stored as GitHub Actions secrets, so releases stay a single tag push.

### Where this stands

The GitHub Release channel is complete. Maven Central was deliberately deferred on
2026-09-13 and is tracked as a to-do to revisit around November 2026.
