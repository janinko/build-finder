/*
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.red.build.finder;

import static com.redhat.red.build.finder.AnsiUtils.boldYellow;
import static com.redhat.red.build.finder.AnsiUtils.cyan;
import static com.redhat.red.build.finder.AnsiUtils.green;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.fusesource.jansi.AnsiConsole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.red.build.finder.report.BuildStatisticsReport;
import com.redhat.red.build.finder.report.GAVReport;
import com.redhat.red.build.finder.report.HTMLReport;
import com.redhat.red.build.finder.report.NVRReport;
import com.redhat.red.build.finder.report.ProductReport;
import com.redhat.red.build.finder.report.Report;
import com.redhat.red.build.koji.KojiClientException;
import com.redhat.red.build.koji.model.xmlrpc.KojiChecksumType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;

public final class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static final int TERM_WIDTH = 80;

    private Main() {
        throw new AssertionError();
    }

    private static void usage(Options options) {
        HelpFormatter formatter = new HelpFormatter();

        formatter.setSyntaxPrefix("Usage: ");
        formatter.setWidth(TERM_WIDTH);
        formatter.printHelp(BuildFinder.getName() + " <files>", options);

        System.exit(1);
    }

    private static void verifyURL(String key, String value, CommandLine line, File configFile) throws ParseException {
        String location = null;

        if (line.hasOption(key)) {
            location = "on the command line";
        } else {
            location = "in the configuration file";

            if (configFile != null) {
                location += " (" + configFile.getAbsolutePath() + ")";
            }
        }

        if (value == null || value.isEmpty()) {
            throw new ParseException("You must specify a non-empty value for " + key + " " + location + ".");
        }

        try {
            new URL(value);
        } catch (MalformedURLException e) {
            throw new ParseException("The value specified for " + key + " (" + value + ") " + location + " is malformed.");
        }
    }

    public static void main(String[] args) {
        Options options = new Options();

        options.addOption(Option.builder("h").longOpt("help").desc("Show this help message.").build());
        options.addOption(Option.builder("c").longOpt("config").numberOfArgs(1).argName("file").required(false).desc("Specify configuration file to use. Default: " + ConfigDefaults.CONFIG + ".").build());
        options.addOption(Option.builder("d").longOpt("debug").desc("Enable debug logging.").build());
        options.addOption(Option.builder("k").longOpt("checksum-only").numberOfArgs(0).required(false).desc("Only checksum files and do not find sources. Default: " + ConfigDefaults.CHECKSUM_ONLY + ".").build());
        options.addOption(Option.builder("t").longOpt("checksum-type").argName("type").numberOfArgs(1).required(false).type(String.class).desc("Checksum types (" + Arrays.stream(KojiChecksumType.values()).map(KojiChecksumType::getAlgorithm).collect(Collectors.joining(",")) + "). Default: " + ConfigDefaults.CHECKSUM_TYPE + ".").build());
        options.addOption(Option.builder("a").longOpt("archive-type").argName("type").numberOfArgs(1).required(false).desc("Add a koji archive type to check. Default: [" + ConfigDefaults.ARCHIVE_TYPES.stream().collect(Collectors.joining(",")) + "].").type(List.class).build());
        options.addOption(Option.builder("x").longOpt("exclude").numberOfArgs(1).argName("pattern").required(false).desc("Add a pattern to exclude files from source check. Default: [" + ConfigDefaults.EXCLUDES.stream().collect(Collectors.joining(",")) + "].").build());
        options.addOption(Option.builder().longOpt("koji-hub-url").numberOfArgs(1).argName("url").required(false).desc("Set the Koji hub URL.").build());
        options.addOption(Option.builder().longOpt("koji-web-url").numberOfArgs(1).argName("url").required(false).desc("Set the Koji web URL.").build());
        options.addOption(Option.builder().longOpt("krb-ccache").numberOfArgs(1).argName("ccache").required(false).desc("Set the location of Kerberos credential cache.").build());
        options.addOption(Option.builder().longOpt("krb-keytab").numberOfArgs(1).argName("keytab").required(false).desc("Set the location of Kerberos keytab.").build());
        options.addOption(Option.builder().longOpt("krb-service").numberOfArgs(1).argName("service").required(false).desc("Set Kerberos client service.").build());
        options.addOption(Option.builder().longOpt("krb-principal").numberOfArgs(1).argName("principal").required(false).desc("Set Kerberos client principal.").build());
        options.addOption(Option.builder().longOpt("krb-password").numberOfArgs(1).argName("password").required(false).desc("Set Kerberos password.").build());
        options.addOption(Option.builder("o").longOpt("output-directory").numberOfArgs(1).argName("directory").required(false).desc("Set output directory.").build());

        String krbCCache = null;
        String krbKeytab = null;
        String krbService = null;
        String krbPrincipal = null;
        String krbPassword = null;
        File outputDirectory = null;

        try {
            AnsiConsole.systemInstall();

            List<File> files = new ArrayList<>();

            String[] unparsedArgs;

            CommandLineParser parser = new DefaultParser();

            CommandLine line = parser.parse(options, args);

            unparsedArgs = line.getArgs();

            if (line.hasOption("help")) {
                usage(options);
            } else if (unparsedArgs.length == 0) {
                throw new ParseException("Must specify at least one file");
            }

            if (line.hasOption("debug")) {
                ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
                rootLogger.setLevel(Level.DEBUG);

                LoggerContext loggerContext = rootLogger.getLoggerContext();

                PatternLayoutEncoder encoder = new PatternLayoutEncoder();
                encoder.setContext(loggerContext);
                encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
                encoder.start();

                ConsoleAppender<ILoggingEvent> appender = (ConsoleAppender<ILoggingEvent>) rootLogger.getAppender("STDOUT");

                if (appender != null) {
                    appender.setContext(loggerContext);
                    appender.setEncoder(encoder);
                    appender.start();
                }
            }

            LOGGER.info("{} {} (SHA: {})", boldYellow(BuildFinder.getName()), boldYellow(BuildFinder.getVersion()), cyan(BuildFinder.getScmRevision()));

            // Initial value taken from configuration value and then allow command line to override.
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

            Path configPath = null;

            if (line.hasOption("config")) {
                configPath = Paths.get(line.getOptionValue("config"));
            } else {
                configPath = Paths.get(ConfigDefaults.CONFIG);
            }

            File configFile = configPath.toFile();
            BuildConfig config;

            if (configFile.exists()) {
                config = mapper.readValue(configPath.toFile(), BuildConfig.class);
            } else {
                LOGGER.debug("Configuration does not exist. Implicitly creating with defaults.");
                config = new BuildConfig();
            }

            if (line.hasOption("checksum-only")) {
                config.setChecksumOnly(Boolean.TRUE);
            }

            if (line.hasOption("checksum-type")) {
                config.setChecksumType(KojiChecksumType.valueOf(line.getOptionValue("checksum-type")));
            }

            if (line.hasOption("archive-type")) {
                @SuppressWarnings("unchecked")
                List<String> a = (List<String>) line.getParsedOptionValue("archive-types");
                config.setArchiveTypes(a);
            }

            if (line.hasOption("exclude")) {
                @SuppressWarnings("unchecked")
                List<String> e = (List<String>) line.getParsedOptionValue("exclude");
                config.setExcludes(e);
            }

            if (line.hasOption("koji-hub-url")) {
                config.setKojiHubURL(line.getOptionValue("koji-hub-url"));
            }

            if (!config.getChecksumOnly()) {
                verifyURL("koji-hub-url", config.getKojiHubURL(), line, configFile);
            }

            if (line.hasOption("koji-web-url")) {
                config.setKojiWebURL(line.getOptionValue("koji-web-url"));
            }

            if (!config.getChecksumOnly()) {
                verifyURL("koji-web-url", config.getKojiWebURL(), line, configFile);
            }

            if (line.hasOption("krb-ccache")) {
                krbCCache = line.getOptionValue("krb-ccache");
                LOGGER.debug("Kerberos ccache: {}", krbCCache);
            }

            if (line.hasOption("krb-keytab")) {
                krbKeytab = line.getOptionValue("krb-keytab");
                LOGGER.debug("Kerberos keytab {}", krbKeytab);
            }

            if (line.hasOption("krb-service")) {
                krbService = line.getOptionValue("krb-service");
                LOGGER.debug("Kerberos service: {}", krbService);
            }

            if (line.hasOption("krb-principal")) {
                krbPrincipal = line.getOptionValue("krb-principal");
                LOGGER.debug("Kerberos principal: {}", krbPrincipal);
            }

            if (line.hasOption("krb-password")) {
                krbPassword = line.getOptionValue("krb-password");
                LOGGER.debug("Read Kerberos password");
            }

            if (line.hasOption("output-directory")) {
                outputDirectory = new File(line.getOptionValue("output-directory"));
                LOGGER.info("Output will be stored in directory: {}", green(outputDirectory));
            }

            LOGGER.debug("Configuration {} ", config);

            if (!configFile.exists()) {
                File configDir = configPath.toFile().getParentFile();

                if (configDir != null && !configDir.exists()) {
                    boolean created = configDir.mkdirs();

                    if (!created) {
                        LOGGER.warn("Failed to create directory: {}", configDir);
                    }
                }

                JSONUtils.dumpObjectToFile(config, configPath.toFile());
            }

            for (String unparsedArg : unparsedArgs) {
                File file = new File(unparsedArg);

                if (!file.canRead()) {
                    LOGGER.warn("Could not read file: {}", file.getPath());
                    continue;
                }

                if (file.isDirectory()) {
                    LOGGER.debug("Adding all files in directory: {}", file.getPath());
                    files.addAll(FileUtils.listFiles(file, null, true));
                } else {
                    LOGGER.debug("Adding file: {}", file.getPath());
                    files.add(new File(unparsedArg));
                }
            }

            File checksumFile = new File(outputDirectory, BuildFinder.getChecksumFilename(config.getChecksumType()));
            Map<String, Collection<String>> checksums = null;

            LOGGER.info("Checksum type: {}", green(config.getChecksumType()));

            if (!checksumFile.exists()) {
                LOGGER.info("Calculating checksums for files: {}", green(files));
                DistributionAnalyzer pda = new DistributionAnalyzer(files, config.getChecksumType().getAlgorithm());
                pda.checksumFiles();
                checksums = pda.getMap().asMap();
                pda.outputToFile(checksumFile);
            } else {
                LOGGER.info("Loading checksums from file: {}", green(checksumFile));
                checksums = JSONUtils.loadChecksumsFile(checksumFile);
            }

            if (checksums.isEmpty()) {
                LOGGER.warn("The list of checksums is empty. If this is unexpected, try removing the checksum cache ({}) and try again.", checksumFile.getAbsolutePath());
            }

            if (config.getChecksumOnly()) {
                return;
            }

            File buildsFile = new File(outputDirectory, BuildFinder.getBuildsFilename());
            Map<Integer, KojiBuild> builds = null;
            KojiClientSession session = null;

            try {
                session = new KojiClientSession(config.getKojiHubURL(), krbService, krbPrincipal, krbPassword, krbCCache, krbKeytab);
            } catch (KojiClientException e) {
                e.printStackTrace();
            }

            if (session == null) {
                LOGGER.warn("Creating session failed");
                return;
            }

            if (buildsFile.exists()) {
                LOGGER.info("Loading builds from file: {}", green(buildsFile.getPath()));
                builds = JSONUtils.loadBuildsFile(buildsFile);
            } else {
                BuildFinder bf = new BuildFinder(session, config);
                builds = bf.findBuilds(checksums);
                JSONUtils.dumpObjectToFile(builds, buildsFile);
            }

            if (!builds.isEmpty()) {
                LOGGER.info("Generating reports");
                List<KojiBuild> buildList = new ArrayList<>(builds.values());

                Collections.sort(buildList, (b1, b2) -> Integer.compare(b1.getBuildInfo().getId(), b2.getBuildInfo().getId()));
                buildList = Collections.unmodifiableList(buildList);

                List<Report> reports = new ArrayList<>();
                reports.add(new BuildStatisticsReport(outputDirectory, buildList));
                reports.add(new ProductReport(outputDirectory, buildList));
                reports.add(new NVRReport(outputDirectory, buildList));
                reports.add(new GAVReport(outputDirectory, buildList));
                reports.forEach(Report::outputText);

                new HTMLReport(outputDirectory, files, buildList, config.getKojiWebURL(), Collections.unmodifiableList(reports)).outputHTML();

                LOGGER.info("{}", boldYellow("DONE"));
            } else {
                LOGGER.warn("Could not generate any reports since list of builds is empty. If this is unexpected, try removing the builds cache ({}) and try again.", buildsFile.getAbsolutePath());
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ParseException e) {
            e.printStackTrace();
            usage(options);
        } finally {
            AnsiConsole.systemUninstall();
        }
    }
}
