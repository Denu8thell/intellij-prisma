package org.intellij.activity;

import com.google.gson.Gson;
import com.intellij.openapi.application.PluginPathManager;
import com.intellij.openapi.application.PreloadingActivity;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import org.intellij.activity.json.NPMDependencies;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.IntellijLanguageClient;
import org.wso2.lsp4intellij.client.languageserver.serverdefinition.RawCommandServerDefinition;
import org.wso2.lsp4intellij.requests.Timeout;
import org.wso2.lsp4intellij.requests.Timeouts;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

public class PrismaPreloadingActivity extends PreloadingActivity {
    private static final String PRISMA_LANGUAGE_SERVER_PACKAGE = "@prisma/language-server";
    private static final String PRISMA_LANGUAGE_SERVER_VERSION = "3.0.28";
    private static final String PRISMA_PLUGIN_NAME = "Prisma";
    private static final String installSubDir = "language_server";
    private static final Logger LOG = Logger.getInstance(PrismaPreloadingActivity.class);

    @Override
    public void preload(@NotNull ProgressIndicator indicator) {
        indicator.setIndeterminate(true);
        indicator.setText("Setting up language server...");
        String installDir = PluginPathManager.getPluginHomePath(PRISMA_PLUGIN_NAME) + "/" + installSubDir;
        LOG.debug("Installing in directory: " + installDir);
        try {
            if (!isLanguageServerInstalled(installDir, PRISMA_LANGUAGE_SERVER_VERSION)) {

                LOG.debug("Prisma language server not installed/up to date, installing...");

                installLanguageServer(installDir, PRISMA_LANGUAGE_SERVER_VERSION);
            }
        } catch (IOException | InterruptedException e) {
            LOG.error(e);
        }

        //Setup language server with command
        String[] command;
        String entryPoint = Paths.get(installDir, "node_modules", "@prisma", "language-server", "dist", "src", "cli.js").toString();
        command = new String[]{"node", entryPoint, "--stdio"};

        LOG.info(entryPoint);

        Timeout.getTimeouts().put(Timeouts.INIT, 60 * 2 * 1000); // 2 minute timeout, just for testing
        IntellijLanguageClient.addServerDefinition(new RawCommandServerDefinition("prisma", command));
    }

    //Check if the specified version of the language server is installed in the specified directory.
    // If directory is null, a global install is considered instead.
    protected boolean isLanguageServerInstalled(@Nullable String directory, @NotNull String version) throws IOException, InterruptedException {
        ProcessBuilder npmListProcessBuilder;
        if (directory == null) {
            if (isWindows()) {
                npmListProcessBuilder = new ProcessBuilder("cmd", "/C", "npm -g list --depth=0 -json");
            } else {
                npmListProcessBuilder = new ProcessBuilder("npm", "-g", "list", "--depth=0", "-json");
            }
        } else {
            File dirFile = new File(directory);

            if (!Files.exists(dirFile.toPath())) {
                return false;
            }

            if (isWindows()) {
                npmListProcessBuilder = new ProcessBuilder("cmd", "/C", "npm list --depth=0 -json");
            } else {
                npmListProcessBuilder = new ProcessBuilder("npm", "list", "--depth=0", "-json");
            }

            npmListProcessBuilder.directory(dirFile);
        }

        npmListProcessBuilder.redirectErrorStream(false);
        Process npmList = npmListProcessBuilder.start();
        NPMDependencies deps;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(npmList.getInputStream()))) {
            npmList.waitFor();

            if (npmList.exitValue() != 0) {
                LOG.error("NPM list failed!");
                return false;
            }
            Gson gson = new Gson();
            deps = gson.fromJson(reader, NPMDependencies.class);
        }

        if (deps.dependencies.containsKey(PRISMA_LANGUAGE_SERVER_PACKAGE)) {
            LOG.debug("Found installation of the Prisma language server! Version: " + deps.dependencies.get(PRISMA_LANGUAGE_SERVER_PACKAGE).version);
            return deps.dependencies.get(PRISMA_LANGUAGE_SERVER_PACKAGE).version.equals(version);
        }

        return false;
    }

    protected void installLanguageServer(@Nullable String directory, @NotNull String version) throws IOException, InterruptedException {
        ProcessBuilder npmInstallProcessBuilder;
        if (directory == null) {
            if (isWindows()) {
                npmInstallProcessBuilder = new ProcessBuilder("cmd", "/c", "npm -g install " + PRISMA_LANGUAGE_SERVER_PACKAGE + "@" + version);
            } else {
                npmInstallProcessBuilder = new ProcessBuilder("npm", "-g", "install", PRISMA_LANGUAGE_SERVER_PACKAGE + "@" + version);
            }
        } else {
            File dirFile = new File(directory);

            if (!Files.exists(dirFile.toPath())) {
                Files.createDirectories(dirFile.toPath());
            }

            if (isWindows()) {
                npmInstallProcessBuilder = new ProcessBuilder("cmd", "/c", "npm install " + PRISMA_LANGUAGE_SERVER_PACKAGE + "@" + version);
            } else {
                npmInstallProcessBuilder = new ProcessBuilder("npm", "install", PRISMA_LANGUAGE_SERVER_PACKAGE + "@" + version);
            }

            npmInstallProcessBuilder.directory(dirFile);
        }

        Process npmInstall = npmInstallProcessBuilder.start();
        npmInstall.waitFor();

        if (npmInstall.exitValue() == 1) {
            LOG.error("NPM install failed!");
            return;
        }

        LOG.debug("Installed language server!");
    }

    protected boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }
}
