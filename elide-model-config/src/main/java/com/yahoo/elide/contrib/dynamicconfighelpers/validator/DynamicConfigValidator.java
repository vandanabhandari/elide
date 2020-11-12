/*
 * Copyright 2020, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.contrib.dynamicconfighelpers.validator;

import static com.yahoo.elide.contrib.dynamicconfighelpers.parser.handlebars.HandlebarsHelper.REFERENCE_PARENTHESES;
import static com.yahoo.elide.core.EntityDictionary.NO_VERSION;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.yahoo.elide.contrib.dynamicconfighelpers.Config;
import com.yahoo.elide.contrib.dynamicconfighelpers.DynamicConfigHelpers;
import com.yahoo.elide.contrib.dynamicconfighelpers.compile.ElideDynamicEntityCompiler;
import com.yahoo.elide.contrib.dynamicconfighelpers.model.DBConfig;
import com.yahoo.elide.contrib.dynamicconfighelpers.model.ElideDBConfig;
import com.yahoo.elide.contrib.dynamicconfighelpers.model.ElideSQLDBConfig;
import com.yahoo.elide.contrib.dynamicconfighelpers.model.ElideSecurityConfig;
import com.yahoo.elide.contrib.dynamicconfighelpers.model.ElideTableConfig;
import com.yahoo.elide.contrib.dynamicconfighelpers.model.Join;
import com.yahoo.elide.contrib.dynamicconfighelpers.model.Named;
import com.yahoo.elide.contrib.dynamicconfighelpers.model.Table;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.io.IOUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Data
/**
 * Util class to validate and parse the config files.
 */
public class DynamicConfigValidator {

    private static final Set<String> SQL_DISALLOWED_WORDS = new HashSet<>(
            Arrays.asList("DROP", "TRUNCATE", "DELETE", "INSERT", "UPDATE", "ALTER", "COMMENT", "CREATE", "DESCRIBE",
                    "SHOW", "USE", "GRANT", "REVOKE", "CONNECT", "LOCK", "EXPLAIN", "CALL", "MERGE", "RENAME"));
    private static final String[] ROLE_NAME_DISALLOWED_WORDS = new String[] { "," };
    private static final String SQL_SPLIT_REGEX = "\\s+";
    private static final String SEMI_COLON = ";";
    private static final Pattern HANDLEBAR_REGEX = Pattern.compile("<%(.*?)%>");
    private static final String RESOURCES = "resources";
    private static final int RESOURCES_LENGTH = 9; //"resources".length()
    private static final String CLASSPATH_PATTERN = "classpath*:";
    private static final String FILEPATH_PATTERN = "file:";
    private static final String HJSON_EXTN = "**/*.hjson";

    private final ElideTableConfig elideTableConfig = new ElideTableConfig();
    private ElideSecurityConfig elideSecurityConfig;
    private Map<String, Object> modelVariables;
    private Map<String, Object> dbVariables;
    private final ElideDBConfig elideSQLDBConfig = new ElideSQLDBConfig();
    private final String configDir;
    private final Map<String, Resource> resourceMap = new HashMap<>();
    private final PathMatchingResourcePatternResolver resolver;

    public DynamicConfigValidator(String configDir) {
        resolver = new PathMatchingResourcePatternResolver(this.getClass().getClassLoader());

        String pattern = CLASSPATH_PATTERN + DynamicConfigHelpers.formatFilePath(formatClassPath(configDir));

        boolean classPathExists = false;
        try {
            classPathExists = (resolver.getResources(pattern).length != 0);
        } catch (IOException e) {
            //NOOP
        }

        if (classPathExists) {
            this.configDir = pattern;
        } else {
            File config = new File(configDir);
            if (! config.exists()) {
                throw new IllegalStateException(configDir + " : config path does not exist");
            }
            this.configDir = FILEPATH_PATTERN + DynamicConfigHelpers.formatFilePath(config.getAbsolutePath());
        }
    }

    public static void main(String[] args) {
        Options options = prepareOptions();

        try {
            CommandLine cli = new DefaultParser().parse(options, args);

            if (cli.hasOption("help")) {
                printHelp(options);
                return;
            }
            if (!cli.hasOption("configDir")) {
                printHelp(options);
                System.err.println("Missing required option");
                System.exit(1);
            }
            String configDir = cli.getOptionValue("configDir");

            DynamicConfigValidator dynamicConfigValidator = new DynamicConfigValidator(configDir);
            dynamicConfigValidator.readAndValidateConfigs();
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(2);
        }

        System.out.println("Configs Validation Passed!");
    }

    /**
     * Read and validate config files under config directory.
     * @throws IOException IOException
     */
    public void readAndValidateConfigs() throws IOException {
        this.loadConfigMap();
        this.setModelVariables(readVariableConfig(Config.MODELVARIABLE));
        this.setElideSecurityConfig(readSecurityConfig());
        validateRoleInSecurityConfig(this.elideSecurityConfig);
        this.setDbVariables(readVariableConfig(Config.DBVARIABLE));
        this.elideSQLDBConfig.setDbconfigs(readDbConfig());
        this.elideTableConfig.setTables(readTableConfig());
        populateInheritanceHierarchy();
        validateRequiredConfigsProvided();
        validateNameUniqueness(this.elideSQLDBConfig.getDbconfigs());
        validateNameUniqueness(this.elideTableConfig.getTables());
        validateTableConfig(this.elideTableConfig);
        validateJoinedTablesDBConnectionName(this.elideTableConfig);
    }

    private void populateInheritanceHierarchy() {
        for (Table table : this.elideTableConfig.getTables()) {
            Set<Table> parentTables = getParents(table);
            if (parentTables.size() > 0) {
                flagMeasuresToOverride(table, parentTables);
                flagDimensionsToOverride(table, parentTables);
            }
        }
    }

    private void flagMeasuresToOverride(Table table, Set<Table> parentTables) {
        Set<String> parentClassMeasures = new HashSet<>();
        parentTables.forEach(obj -> {
            obj.getMeasures().forEach(measure -> {
                parentClassMeasures.add(measure.getName());
            });
        });

        table.getMeasures().forEach(measure -> {
            if (parentClassMeasures.contains(measure.getName())) {
                measure.setOverride(true);
            }
        });
    }

    private void flagDimensionsToOverride(Table table, Set<Table> parentTables) {
        Set<String> parentClassDimensions = new HashSet<>();
        parentTables.forEach(obj -> {
            obj.getDimensions().forEach(dimension -> {
                parentClassDimensions.add(dimension.getName());
            });
        });

        table.getDimensions().forEach(dimension -> {
            if (parentClassDimensions.contains(dimension.getName())) {
                dimension.setOverride(true);
            }
        });
    }

    private Set<Table> getParents(Table table) {
        Set<Table> parentTables = new HashSet<>();
        Table current = table;

        while (current != null && current.getExtend() != null && !current.getExtend().equals("")) {
            Table parent = getTableByName(current.getExtend().trim());
            parentTables.add(parent);
            current = parent;
        }
        return parentTables;
    }

    private Table getTableByName(String tableName) {
        Table tableByName = this.elideTableConfig.getTables().stream().filter(
                table -> table.getName().equals(tableName)).findFirst().orElse(null);

        if (tableByName != null) {
            return tableByName;
        }
        throw new IllegalStateException("Table " + tableName + " is not defined in Dynamic Config.");
    }

    /**
     * Add all Hjson resources under configDir in resourceMap.
     * @throws IOException
     */
    private void loadConfigMap() throws IOException {
        int configDirURILength = resolver.getResources(this.configDir)[0].getURI().toString().length();

        Resource[] hjsonResources = resolver.getResources(this.configDir + HJSON_EXTN);
        for (Resource resource : hjsonResources) {
            this.resourceMap.put(resource.getURI().toString().substring(configDirURILength), resource);
        }
    }

    /**
     * Read variable file config.
     * @param config Config Enum
     * @return Map<String, Object> A map containing all the variables if variable config exists else empty map
     */
    private Map<String, Object> readVariableConfig(Config config) {

        return this.resourceMap
                        .entrySet()
                        .stream()
                        .filter(entry -> entry.getKey().startsWith(config.getConfigPath()))
                        .map(entry -> {
                            try {
                                String content = IOUtils.toString(entry.getValue().getInputStream(), UTF_8);
                                return DynamicConfigHelpers.stringToVariablesPojo
                                                (entry.getValue().getFilename(), content);
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                        .findFirst()
                        .orElse(new HashMap<>());
    }

    /**
     * Read and validates security config file.
     */
    private ElideSecurityConfig readSecurityConfig() {

        return this.resourceMap
                        .entrySet()
                        .stream()
                        .filter(entry -> entry.getKey().startsWith(Config.SECURITY.getConfigPath()))
                        .map(entry -> {
                            try {
                                String content = IOUtils.toString(entry.getValue().getInputStream(), UTF_8);
                                validateConfigForMissingVariables(content, this.modelVariables);
                                return DynamicConfigHelpers.stringToElideSecurityPojo
                                                (entry.getValue().getFilename(), content, this.modelVariables);
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                        .findAny()
                        .orElse(new ElideSecurityConfig());
    }

    /**
     * Read and validates db config files.
     * @return Set<DBConfig> Set of SQL DB Configs
     */
    private Set<DBConfig> readDbConfig() {

        return this.resourceMap
                        .entrySet()
                        .stream()
                        .filter(entry -> entry.getKey().startsWith(Config.SQLDBConfig.getConfigPath()))
                        .map(entry -> {
                            try {
                                String content = IOUtils.toString(entry.getValue().getInputStream(), UTF_8);
                                validateConfigForMissingVariables(content, this.dbVariables);
                                return DynamicConfigHelpers.stringToElideDBConfigPojo
                                                (entry.getValue().getFilename(), content, this.dbVariables);
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                        .flatMap(dbconfig -> dbconfig.getDbconfigs().stream())
                        .collect(Collectors.toSet());
    }

    /**
     * Read and validates table config files.
     */
    private Set<Table> readTableConfig() {

        return this.resourceMap
                        .entrySet()
                        .stream()
                        .filter(entry -> entry.getKey().startsWith(Config.TABLE.getConfigPath()))
                        .map(entry -> {
                            try {
                                String content = IOUtils.toString(entry.getValue().getInputStream(), UTF_8);
                                validateConfigForMissingVariables(content, this.modelVariables);
                                return DynamicConfigHelpers.stringToElideTablePojo
                                                (entry.getValue().getFilename(), content, this.modelVariables);
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                        .flatMap(table -> table.getTables().stream())
                        .collect(Collectors.toSet());
    }

    /**
     * Checks if neither Table nor DB config files provided.
     */
    private void validateRequiredConfigsProvided() {
        if (this.elideTableConfig.getTables().isEmpty() && this.elideSQLDBConfig.getDbconfigs().isEmpty()) {
            throw new IllegalStateException("Neither Table nor DB configs found under: " + this.configDir);
        }
    }

    /**
     * Extracts any handlebar variables in config file and checks if they are
     * defined in variable config. Throw exception for undefined variables.
     * @param config config file
     * @param variables A map of defined variables
     */
    private static void validateConfigForMissingVariables(String config, Map<String, Object> variables) {
        Matcher regexMatcher = HANDLEBAR_REGEX.matcher(config);
        while (regexMatcher.find()) {
            String str = regexMatcher.group(1).trim();
            if (!variables.containsKey(str)) {
                throw new IllegalStateException(str + " is used as a variable in either table or security config files "
                        + "but is not defined in variables config file.");
            }
        }
    }

    /**
     * Validate table sql and column definition provided in table configs.
     * @param elideTableConfig ElideTableConfig
     * @return boolean true if all sql/definition passes validation
     */
    private static boolean validateTableConfig(ElideTableConfig elideTableConfig) {
        for (Table table : elideTableConfig.getTables()) {
            validateSql(table.getSql());
            table.getDimensions().forEach(dim -> validateSql(dim.getDefinition()));
            table.getMeasures().forEach(measure -> validateSql(measure.getDefinition()));
            table.getJoins().forEach(join -> validateJoin(join, elideTableConfig));
        }
        return true;
    }

    /**
     * Validates join clause does not refer to a Table which is not in the same DBConnection.
     * If joined table is not part of dynamic configuration, then ignore
     */
    private static void validateJoinedTablesDBConnectionName(ElideTableConfig elideTableConfig) {

        for (Table table : elideTableConfig.getTables()) {
            if (!table.getJoins().isEmpty()) {

                Set<String> joinedTables = table.getJoins()
                        .stream()
                        .map(join -> join.getTo())
                        .collect(Collectors.toSet());

                Set<String> connections = elideTableConfig.getTables()
                        .stream()
                        .filter(t -> joinedTables.contains(t.getName()))
                        .map(t -> t.getDbConnectionName())
                        .collect(Collectors.toSet());

                if (connections.size() > 1 || (connections.size() == 1
                                && !table.getDbConnectionName().equals(connections.iterator().next()))) {
                    throw new IllegalStateException("DBConnection name mismatch between table: " + table.getName()
                                    + " and tables in its Join Clause.");
                }
            }
        }
    }

    /**
     * Validates table (or db connection) name is unique across all the dynamic table (or db connection) configs.
     */
    private void validateNameUniqueness(Set<? extends Named> configs) {

        Set<String> names = new HashSet<>();

        configs.forEach(obj -> {
            if (!names.add(obj.getName().toLowerCase(Locale.ENGLISH))) {
                throw new IllegalStateException("Duplicate!! Either Table or DB configs found with the same name.");
            }
        });
    }

    /**
     * Check if input sql definition contains either semicolon or any of disallowed
     * keywords. Throw exception if check fails.
     */
    private static void validateSql(String sqlDefinition) {
        if (!DynamicConfigHelpers.isNullOrEmpty(sqlDefinition) && (sqlDefinition.contains(SEMI_COLON)
                || containsDisallowedWords(sqlDefinition, SQL_SPLIT_REGEX, SQL_DISALLOWED_WORDS))) {
            throw new IllegalStateException("sql/definition provided in table config contain either '" + SEMI_COLON
                    + "' or one of these words: " + Arrays.toString(SQL_DISALLOWED_WORDS.toArray()));
        }
    }

    /**
     * Check if input join definition is valid.
     */
    private static void validateJoin(Join join, ElideTableConfig elideTableConfig) {
        validateSql(join.getDefinition());

        String joinModelName = join.getTo();
        Set<String> dynamicModels = elideTableConfig.getTables()
                        .stream()
                        .map(t -> t.getName())
                        .collect(Collectors.toSet());

        if (!(dynamicModels.contains(joinModelName) || ElideDynamicEntityCompiler.getStaticModelClassName(joinModelName,
                        NO_VERSION, null) != null)) {
            throw new IllegalStateException(
                            "Model: " + joinModelName + " is neither included in dynamic models nor in static models");
        }

        Matcher matcher = REFERENCE_PARENTHESES.matcher(join.getDefinition());
        Set<String> references = new HashSet<>();
        while (matcher.find()) {
            references.add(matcher.group(1).trim());
        }

        if (references.size() < 2) {
            throw new IllegalStateException("Atleast 2 unique references are expected in join definition");
        }

        references.forEach(reference -> {
            if (reference.indexOf('.') != -1) {
                String joinField = reference.substring(0, reference.indexOf('.'));
                if (!joinField.equals(join.getName())) {
                    throw new IllegalStateException("Join name must be used before '.' in join definition. Found '"
                                    + joinField + "' instead of '" + join.getName() + "'");
                }
            }
        });
    }

    /**
     * Validate role name provided in security config.
     * @param elideSecurityConfig ElideSecurityConfig
     * @return boolean true if all role name passes validation else throw exception
     */
    private static boolean validateRoleInSecurityConfig(ElideSecurityConfig elideSecurityConfig) {
        for (String role : elideSecurityConfig.getRoles()) {
            if (containsDisallowedWords(role, ROLE_NAME_DISALLOWED_WORDS)) {
                throw new IllegalStateException("ROLE provided in security config contain one of these words: "
                        + Arrays.toString(ROLE_NAME_DISALLOWED_WORDS));
            }
        }
        return true;
    }

    /**
     * Checks if input string has any of the disallowed words.
     * @param String input string to validate
     * @param keywords Array of disallowed words
     * @return boolean true if input string does not contain any of the keywords
     *         else false
     */
    private static boolean containsDisallowedWords(String str, String[] keywords) {
        return Arrays.stream(keywords).anyMatch(str.toUpperCase(Locale.ENGLISH)::contains);
    }

    /**
     * Checks if any word in the input string matches any of the disallowed words.
     * @param String input string to validate
     * @param splitter regex for splitting input string
     * @param keywords Set of disallowed words
     * @return boolean true if any word in the input string matches any of the
     *         disallowed words else false
     */
    private static boolean containsDisallowedWords(String str, String splitter, Set<String> keywords) {
        return DynamicConfigHelpers.isNullOrEmpty(str) ? false
                : Arrays.stream(str.trim().toUpperCase(Locale.ENGLISH).split(splitter)).anyMatch(keywords::contains);
    }

    /**
     * Define Arguments.
     */
    private static final Options prepareOptions() {
        Options options = new Options();
        options.addOption(new Option("h", "help", false, "Print a help message and exit."));
        options.addOption(new Option("c", "configDir", true,
                "Path for Configs Directory.\n"
                        + "Expected Directory Structure under Configs Directory:\n"
                        + "./models/security.hjson(optional)\n"
                        + "./models/variables.hjson(optional)\n"
                        + "./models/tables/\n"
                        + "./models/tables/table1.hjson\n"
                        + "./models/tables/table2.hjson\n"
                        + "./models/tables/tableN.hjson\n"
                        + "./db/variables.hjson(optional)\n"
                        + "./db/sql/(optional)\n"
                        + "./db/sql/db1.hjson\n"
                        + "./db/sql/db2.hjson\n"
                        + "./db/sql/dbN.hjson\n"));

        return options;
    }

    /**
     * Print Help.
     */
    private static void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(
                "java -cp <Jar File> com.yahoo.elide.contrib.dynamicconfighelpers.validator.DynamicConfigValidator",
                options);
    }

    /**
     * Remove src/.../resources/ from filepath.
     * @param filePath
     * @return Path to model dir
     */
    public static String formatClassPath(String filePath) {
        if (filePath.indexOf(RESOURCES + File.separator) > -1) {
            return filePath.substring(filePath.indexOf(RESOURCES + File.separator) + RESOURCES_LENGTH + 1);
        } else if (filePath.indexOf(RESOURCES) > -1) {
            return filePath.substring(filePath.indexOf(RESOURCES) + RESOURCES_LENGTH);
        }
        return filePath;
    }
}