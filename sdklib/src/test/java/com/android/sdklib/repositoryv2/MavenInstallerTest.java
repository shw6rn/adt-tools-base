/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.sdklib.repositoryv2;

import com.android.repository.testframework.FakeDownloader;
import com.android.repository.testframework.FakeProgressIndicator;
import com.android.repository.testframework.FakeProgressRunner;
import com.android.repository.testframework.FakeSettingsController;
import com.android.repository.Revision;
import com.android.repository.api.ConstantSourceProvider;
import com.android.repository.api.LocalPackage;
import com.android.repository.api.RepoManager.RepoLoadedCallback;
import com.android.repository.api.RepoManager;
import com.android.repository.impl.manager.RepoManagerImpl;
import com.android.repository.impl.meta.RepositoryPackages;
import com.android.repository.testframework.MockFileOp;
import com.google.common.collect.ImmutableList;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Tests for {@link MavenInstaller}
 */
public class MavenInstallerTest extends TestCase {

    public void testInstallFirst() throws Exception {
        File root = new File("/repo");
        MockFileOp fop = new MockFileOp();
        AndroidSdkHandler androidSdkHandler = new AndroidSdkHandler(root, fop);
        RepoManager mgr = new RepoManagerImpl(fop);
        FakeProgressIndicator progress = new FakeProgressIndicator();
        mgr.registerSchemaModule(androidSdkHandler.getCommonModule(progress));
        mgr.registerSchemaModule(androidSdkHandler.getAddonModule(progress));
        progress.assertNoErrorsOrWarnings();
        mgr.setLocalPath(root);
        FakeDownloader downloader = new FakeDownloader(fop);
        URL repoUrl = new URL("http://example.com/dummy.xml");

        // The repo we're going to download
        downloader.registerUrl(repoUrl,
                getClass().getResourceAsStream("testdata/remote_maven_repo.xml"));

        // Create the archive and register the URL
        URL archiveUrl = new URL("http://example.com/2/arch1");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1000);
        ZipOutputStream zos = new ZipOutputStream(baos);
        zos.putNextEntry(new ZipEntry("top-level/a"));
        zos.write("contents1".getBytes());
        zos.closeEntry();
        zos.close();
        ByteArrayInputStream is = new ByteArrayInputStream(baos.toByteArray());
        downloader.registerUrl(archiveUrl, is);

        // Register a source provider to get the repo
        mgr.registerSourceProvider(new ConstantSourceProvider(repoUrl.toString(), "dummy",
                ImmutableList.of(androidSdkHandler.getAddonModule(progress))));
        progress.assertNoErrorsOrWarnings();
        FakeProgressRunner runner = new FakeProgressRunner();

        // Load
        mgr.load(RepoManager.DEFAULT_EXPIRATION_PERIOD_MS, ImmutableList.<RepoLoadedCallback>of(),
                ImmutableList.<RepoLoadedCallback>of(), ImmutableList.<Runnable>of(), runner,
                downloader, new FakeSettingsController(false), true);

        runner.getProgressIndicator().assertNoErrorsOrWarnings();

        RepositoryPackages pkgs = mgr.getPackages();

        // Install
        new MavenInstaller().install(
                pkgs.getRemotePackages().get("com;android;group1;artifact1;1.2.3").iterator()
                        .next(),
                downloader, new FakeSettingsController(false), runner.getProgressIndicator(), mgr,
                fop);
        runner.getProgressIndicator().assertNoErrorsOrWarnings();

        File artifactRoot = new File(root, "m2repository/com/android/group1/artifact1");
        File mavenMetadata = new File(artifactRoot, "maven-metadata.xml");
        MavenInstaller.MavenMetadata metadata = MavenInstaller
                .unmarshalMetadata(mavenMetadata, runner.getProgressIndicator(), fop);

        assertEquals("artifact1", metadata.artifactId);
        assertEquals("com.android.group1", metadata.groupId);
        assertEquals("1.2.3", metadata.versioning.release);
        assertEquals(ImmutableList.of("1.2.3"), metadata.versioning.versions.version);

        File[] contents = fop
                .listFiles(new File(root, "m2repository/com/android/group1/artifact1/1.2.3"));

        // Ensure it was installed on the filesystem
        assertEquals(2, contents.length);
        assertEquals(new File(root, "m2repository/com/android/group1/artifact1/1.2.3/a"),
                contents[0]);
        assertEquals(new File(root, "m2repository/com/android/group1/artifact1/1.2.3/package.xml"),
                contents[1]);

        // Reload
        mgr.load(0, ImmutableList.<RepoLoadedCallback>of(), ImmutableList.<RepoLoadedCallback>of(),
                ImmutableList.<Runnable>of(), runner, downloader, new FakeSettingsController(false),
                true);

        // Ensure it was recognized as a package.
        Map<String, ? extends LocalPackage> locals = mgr.getPackages().getLocalPackages();
        assertEquals(1, locals.size());
        assertTrue(locals.containsKey("com;android;group1;artifact1;1.2.3"));
        LocalPackage newPkg = locals.get("com;android;group1;artifact1;1.2.3");
        assertEquals("maven package", newPkg.getDisplayName());
        assertEquals(new Revision(3), newPkg.getVersion());

    }


    public void testInstallAdditional() throws Exception {
        MockFileOp fop = new MockFileOp();
        fop.recordExistingFile("/repo/m2repository/com/android/group1/artifact1/maven-metadata.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<metadata>\n"
                        + "  <groupId>com.android.group1</groupId>\n"
                        + "  <artifactId>artifact1</artifactId>\n"
                        + "  <release>1.0.0</release>\n"
                        + "  <versioning>\n"
                        + "    <versions>\n"
                        + "      <version>1.0.0</version>\n"
                        + "    </versions>\n"
                        + "    <lastUpdated>20151006162600</lastUpdated>\n"
                        + "  </versioning>\n"
                        + "</metadata>\n");
        File root = new File("/repo");
        AndroidSdkHandler androidSdkHandler = new AndroidSdkHandler(root, fop);
        RepoManager mgr = new RepoManagerImpl(fop);
        FakeProgressIndicator progress = new FakeProgressIndicator();
        mgr.registerSchemaModule(androidSdkHandler.getCommonModule(progress));
        mgr.registerSchemaModule(androidSdkHandler.getAddonModule(progress));
        progress.assertNoErrorsOrWarnings();
        mgr.setLocalPath(root);
        FakeDownloader downloader = new FakeDownloader(fop);
        URL repoUrl = new URL("http://example.com/dummy.xml");

        // The repo we're going to download
        downloader.registerUrl(repoUrl,
                getClass().getResourceAsStream("testdata/remote_maven_repo.xml"));

        // Create the archive and register the URL
        URL archiveUrl = new URL("http://example.com/2/arch1");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1000);
        ZipOutputStream zos = new ZipOutputStream(baos);
        zos.putNextEntry(new ZipEntry("top-level/a"));
        zos.write("contents1".getBytes());
        zos.closeEntry();
        zos.close();
        ByteArrayInputStream is = new ByteArrayInputStream(baos.toByteArray());
        downloader.registerUrl(archiveUrl, is);

        // Register a source provider to get the repo
        mgr.registerSourceProvider(new ConstantSourceProvider(repoUrl.toString(), "dummy",
                ImmutableList.of(androidSdkHandler.getAddonModule(progress))));
        progress.assertNoErrorsOrWarnings();
        FakeProgressRunner runner = new FakeProgressRunner();

        // Load
        mgr.load(RepoManager.DEFAULT_EXPIRATION_PERIOD_MS,
                ImmutableList.<RepoLoadedCallback>of(), ImmutableList.<RepoLoadedCallback>of(),
                ImmutableList.<Runnable>of(), runner,
                downloader, new FakeSettingsController(false), true);

        runner.getProgressIndicator().assertNoErrorsOrWarnings();

        RepositoryPackages pkgs = mgr.getPackages();

        // Install
        new MavenInstaller().install(
                pkgs.getRemotePackages().get("com;android;group1;artifact1;1.2.3").iterator()
                        .next(),
                downloader, new FakeSettingsController(false), runner.getProgressIndicator(), mgr,
                fop);
        runner.getProgressIndicator().assertNoErrorsOrWarnings();

        File artifactRoot = new File(root, "m2repository/com/android/group1/artifact1");
        File mavenMetadata = new File(artifactRoot, "maven-metadata.xml");
        MavenInstaller.MavenMetadata metadata = MavenInstaller
                .unmarshalMetadata(mavenMetadata, runner.getProgressIndicator(), fop);

        assertEquals("artifact1", metadata.artifactId);
        assertEquals("com.android.group1", metadata.groupId);
        assertEquals("1.2.3", metadata.versioning.release);
        assertEquals(ImmutableList.of("1.0.0", "1.2.3"), metadata.versioning.versions.version);

        File[] contents = fop
                .listFiles(new File(root, "m2repository/com/android/group1/artifact1/1.2.3"));

        // Ensure it was installed on the filesystem
        assertEquals(2, contents.length);
        assertEquals(new File(root, "m2repository/com/android/group1/artifact1/1.2.3/a"),
                contents[0]);
        assertEquals(new File(root, "m2repository/com/android/group1/artifact1/1.2.3/package.xml"),
                contents[1]);

        // Reload
        mgr.load(0, ImmutableList.<RepoLoadedCallback>of(), ImmutableList.<RepoLoadedCallback>of(),
                ImmutableList.<Runnable>of(), runner, downloader, new FakeSettingsController(false),
                true);

        // Ensure it was recognized as a package.
        Map<String, ? extends LocalPackage> locals = mgr.getPackages().getLocalPackages();
        assertEquals(1, locals.size());
        assertTrue(locals.containsKey("com;android;group1;artifact1;1.2.3"));
        LocalPackage newPkg = locals.get("com;android;group1;artifact1;1.2.3");
        assertEquals("maven package", newPkg.getDisplayName());
        assertEquals(new Revision(3), newPkg.getVersion());

    }

    public void testRemove() throws Exception {
        MockFileOp fop = new MockFileOp();
        fop.recordExistingFile(
                "/repo/m2repository/com/example/groupId/artifactId/1.2.3/package.xml",
                "<repo:sdk-addon\n"
                        + "        xmlns:repo=\"http://schemas.android.com/sdk/android/repo/addon2/01\"\n"
                        + "        xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"
                        + "\n"
                        + "    <localPackage path=\"com;example;groupId;artifactId;1.2.3\">\n"
                        + "        <revision>\n"
                        + "            <major>3</major>\n"
                        + "        </revision>\n"
                        + "        <display-name>A Maven artifact</display-name>\n"
                        + "    </localPackage>\n"
                        + "</repo:sdk-addon>"
        );
        fop.recordExistingFile(
                "/repo/m2repository/com/example/groupId/artifactId/1.2.4/package.xml",
                "<repo:sdk-addon\n"
                        + "        xmlns:repo=\"http://schemas.android.com/sdk/android/repo/addon2/01\"\n"
                        + "        xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"
                        + "\n"
                        + "    <localPackage path=\"com;example;groupId;artifactId;1.2.4\">\n"
                        + "        <revision>\n"
                        + "            <major>3</major>\n"
                        + "        </revision>\n"
                        + "        <display-name>Another Maven artifact</display-name>\n"
                        + "    </localPackage>\n"
                        + "</repo:sdk-addon>"
        );

        String metadataPath
                = "/repo/m2repository/com/example/groupId/artifactId/maven-metadata.xml";
        fop.recordExistingFile(
                metadataPath,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<metadata>\n"
                        + "  <groupId>com.example.groupId</groupId>\n"
                        + "  <artifactId>artifactId</artifactId>\n"
                        + "  <release>1.2.4</release>\n"
                        + "  <versioning>\n"
                        + "    <versions>\n"
                        + "      <version>1.2.3</version>\n"
                        + "      <version>1.2.4</version>\n"
                        + "    </versions>\n"
                        + "    <lastUpdated>20151006162600</lastUpdated>\n"
                        + "  </versioning>\n"
                        + "</metadata>\n");

        File root = new File("/repo");
        AndroidSdkHandler androidSdkHandler = new AndroidSdkHandler(root, fop);
        RepoManager mgr = new RepoManagerImpl(fop);
        mgr.setLocalPath(root);
        FakeProgressIndicator progress = new FakeProgressIndicator();
        mgr.registerSchemaModule(androidSdkHandler.getCommonModule(progress));
        mgr.registerSchemaModule(androidSdkHandler.getAddonModule(progress));

        FakeProgressRunner runner = new FakeProgressRunner();
        FakeDownloader downloader = new FakeDownloader(fop);
        // Reload
        mgr.load(0, ImmutableList.<RepoLoadedCallback>of(), ImmutableList.<RepoLoadedCallback>of(),
                ImmutableList.<Runnable>of(), runner, downloader, new FakeSettingsController(false),
                true);
        runner.getProgressIndicator().assertNoErrorsOrWarnings();

        Map<String, ? extends LocalPackage> locals = mgr.getPackages().getLocalPackages();
        assertEquals(2, locals.size());
        assertTrue(locals.containsKey("com;example;groupId;artifactId;1.2.4"));

        MavenInstaller installer = new MavenInstaller();
        installer.uninstall(locals.get("com;example;groupId;artifactId;1.2.4"), progress, mgr, fop);
        progress.assertNoErrorsOrWarnings();
        MavenInstaller.MavenMetadata metadata = MavenInstaller
                .unmarshalMetadata(new File(metadataPath), progress, fop);
        progress.assertNoErrorsOrWarnings();
        assertNotNull(metadata);
        assertEquals(ImmutableList.of("1.2.3"), metadata.versioning.versions.version);
        assertEquals("1.2.3", metadata.versioning.release);
    }
}
