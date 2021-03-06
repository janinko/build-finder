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
package org.jboss.pnc.build.finder.pnc.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.commons.collections4.ListUtils;
import org.jboss.pnc.build.finder.core.BuildConfig;
import org.jboss.pnc.build.finder.pnc.client.model.Artifact;
import org.jboss.pnc.build.finder.pnc.client.model.BuildConfiguration;
import org.jboss.pnc.build.finder.pnc.client.model.BuildRecord;
import org.jboss.pnc.build.finder.pnc.client.model.BuildRecordPushResult;
import org.jboss.pnc.build.finder.pnc.client.model.PageParameterArtifact;
import org.jboss.pnc.build.finder.pnc.client.model.PageParameterBuildConfiguration;
import org.jboss.pnc.build.finder.pnc.client.model.PageParameterBuildRecord;
import org.jboss.pnc.build.finder.pnc.client.model.PageParameterBuildRecordPushResult;
import org.jboss.pnc.build.finder.pnc.client.model.PageParameterProductVersion;
import org.jboss.pnc.build.finder.pnc.client.model.ProductVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.ObjectMapper;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

/**
 * PncClient14 class for PNC 1.4.x APIs
 *
 */
public class PncClient14 {
    private static final Logger LOGGER = LoggerFactory.getLogger(PncClient14.class);

    private BuildConfig config;

    /**
     * Create a new PncClient14 object
     *
     * @param config the build configuration
     */
    public PncClient14(BuildConfig config) {
        this.config = config;
        unirestSetup();
        Unirest.setTimeouts(config.getPncConnectionTimeout(), config.getPncReadTimeout());
    }

    /**
     * Setup Unirest to automatically convert JSON into DTO
     */
    private static void unirestSetup() {
        Unirest.setObjectMapper(new ObjectMapper() {
            private com.fasterxml.jackson.databind.ObjectMapper jacksonObjectMapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new JavaTimeModule());

            public <T> T readValue(String value, Class<T> valueType) {
                try {
                    return jacksonObjectMapper.readValue(value, valueType);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            public String writeValue(Object value) {
                try {
                    return jacksonObjectMapper.writeValueAsString(value);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    /**
     * Get a list of artifacts with matching md5. Returns empty list if no matching artifacts
     *
     * @param value md5 value
     * @return list of artifacts
     *
     * @throws PncClientException in case something goes wrong
     */
    public List<Artifact> getArtifactsByMd5(String value) throws PncClientException {
        return getArtifacts("md5", value);
    }

    /**
     * Get a list of artifacts with matching sha1. Returns empty list if no matching artifacts
     *
     * @param value sha1 value
     * @return list of artifacts
     *
     * @throws PncClientException in case something goes wrong
     */
    public List<Artifact> getArtifactsBySha1(String value) throws PncClientException {
        return getArtifacts("sha1", value);
    }

    /**
     * Get a list of artifacts with matching sha256. Returns empty list if no matching artifacts
     *
     * @param value sha256 value
     * @return list of artifacts
     *
     * @throws PncClientException in case something goes wrong
     */
    public List<Artifact> getArtifactsBySha256(String value) throws PncClientException {
        return getArtifacts("sha256", value);
    }

    private String getArtifactsUrl(String key, String value) {
        return config.getPncURL() + "/pnc-rest/rest/artifacts" + "?" + key + "=" + value;
    }

    private List<Artifact> getArtifacts(String key, String value) throws PncClientException {
        try {
            String urlRequest = getArtifactsUrl(key, value);
            HttpResponse<PageParameterArtifact> artifacts = Unirest.get(urlRequest)
                    .asObject(PageParameterArtifact.class);
            PageParameterArtifact artifactData = artifacts.getBody();

            if (artifactData == null) {
                return Collections.emptyList();
            } else {
                return artifactData.getContent();
            }
        } catch (UnirestException e) {
            throw new PncClientException(e);
        }

    }

    private List<List<Artifact>> getArtifactsSplit(List<String> values, String key) throws PncClientException {
        int size = values.size();
        int partitionSize = config.getPncPartitionSize();
        List<List<Artifact>> result = new ArrayList<>(size);
        List<List<String>> splitValues = ListUtils.partition(values, partitionSize);

        for (List<String> splitValue : splitValues) {
            int keysSize = splitValue.size();
            List<String> keys = Collections.nCopies(keysSize, key);
            List<List<Artifact>> artifacts = getArtifacts(keys, splitValue);
            result.addAll(artifacts);
        }

        return result;
    }

    public List<List<Artifact>> getArtifactsByMd5(List<String> values) throws PncClientException {
        return getArtifactsSplit(values, "md5");
    }

    public List<List<Artifact>> getArtifactsBySha1(List<String> values) throws PncClientException {
        return getArtifactsSplit(values, "sha1");
    }

    public List<List<Artifact>> getArtifactsBySha256(List<String> values) throws PncClientException {
        return getArtifactsSplit(values, "sha256");
    }

    private List<List<Artifact>> getArtifacts(List<String> keys, List<String> values) throws PncClientException {
        if (keys.size() != values.size()) {
            throw new PncClientException("Mismatched keys and values sizes");
        }

        List<List<Artifact>> artifactsList = new ArrayList<>(values.size());
        List<Future<HttpResponse<PageParameterArtifact>>> futures = new ArrayList<>(values.size());
        int size = keys.size();

        for (int i = 0; i < size; i++) {
            String key = keys.get(i);
            String value = values.get(i);
            String urlRequest = getArtifactsUrl(key, value);
            LOGGER.debug("key={}, value={}, urlRequest={}", key, value, urlRequest);
            Future<HttpResponse<PageParameterArtifact>> artifacts = Unirest.get(urlRequest)
                    .asObjectAsync(PageParameterArtifact.class);
            futures.add(artifacts);
        }

        for (Future<HttpResponse<PageParameterArtifact>> artifact : futures) {
            try {
                HttpResponse<PageParameterArtifact> artifacts = artifact.get();
                PageParameterArtifact artifactData = artifacts.getBody();

                if (artifactData == null) {
                    artifactsList.add(Collections.emptyList());
                } else {
                    artifactsList.add(artifactData.getContent());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                throw new PncClientException(e);
            }
        }

        return artifactsList;
    }

    private String getBuiltArtifactsByIdUrl(int id) {
        return config.getPncURL() + "/pnc-rest/rest/build-records/" + id + "/built-artifacts";
    }

    /**
     * Get a list of artifacts with matching id. Returns empty list if no matching artifacts
     *
     * @param id buildrecord id
     * @return list of artifacts
     *
     * @throws PncClientException in case something goes wrong
     */
    public List<Artifact> getBuiltArtifactsById(int id) throws PncClientException {
        try {
            String urlRequest = getBuiltArtifactsByIdUrl(id);
            HttpResponse<PageParameterArtifact> artifacts = Unirest.get(urlRequest)
                    .asObject(PageParameterArtifact.class);
            PageParameterArtifact artifactData = artifacts.getBody();

            if (artifactData == null) {
                return Collections.emptyList();
            } else {
                return artifactData.getContent();
            }

        } catch (UnirestException e) {
            throw new PncClientException(e);
        }
    }

    public List<List<Artifact>> getBuiltArtifactsById(List<Integer> ids) throws PncClientException {
        List<List<Artifact>> artifactList = new ArrayList<>(ids.size());
        List<Future<HttpResponse<PageParameterArtifact>>> futures = new ArrayList<>(ids.size());

        for (Integer id : ids) {
            String urlRequest = getBuiltArtifactsByIdUrl(id);
            Future<HttpResponse<PageParameterArtifact>> artifactFuture = Unirest.get(urlRequest)
                    .asObjectAsync(PageParameterArtifact.class);
            futures.add(artifactFuture);
        }

        for (Future<HttpResponse<PageParameterArtifact>> artifact : futures) {
            try {
                HttpResponse<PageParameterArtifact> artifacts = artifact.get();
                PageParameterArtifact artifactData = artifacts.getBody();

                if (artifactData == null) {
                    artifactList.add(Collections.emptyList());
                } else {
                    artifactList.add(artifactData.getContent());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                throw new PncClientException(e);
            }
        }

        return artifactList;
    }

    private String getBuildRecordByIdUrl(int id) {
        return config.getPncURL() + "/pnc-rest/rest/build-records/" + id;
    }

    private String getBuildRecordPushResultByIdUrl(int id) {
        return config.getPncURL() + "/pnc-rest/rest/build-record-push/status/" + id;
    }

    private String getBuildConfigurationByIdUrl(int id) {
        return config.getPncURL() + "/pnc-rest/rest/build-configurations/" + id;
    }

    private String getProductVersionByIdUrl(int id) {
        return config.getPncURL() + "/pnc-rest/rest/product-versions/" + id;
    }

    /**
     * Get the BuildRecord object from the buildrecord id. Returns null if no buildrecord found
     *
     * @param id buildrecord id
     * @return buildrecord DTO
     *
     * @throws PncClientException if something goes wrong
     */
    public BuildRecord getBuildRecordById(int id) throws PncClientException {
        try {
            String urlRequest = getBuildRecordByIdUrl(id);
            HttpResponse<PageParameterBuildRecord> buildRecords = Unirest.get(urlRequest)
                    .asObject(PageParameterBuildRecord.class);
            PageParameterBuildRecord buildRecordData = buildRecords.getBody();

            if (buildRecordData == null) {
                return null;
            } else {
                return buildRecordData.getContent();
            }
        } catch (UnirestException e) {
            throw new PncClientException(e);
        }
    }

    public List<BuildRecord> getBuildRecordsById(List<Integer> ids) throws PncClientException {
        List<Future<HttpResponse<PageParameterBuildRecord>>> futures = new ArrayList<>(ids.size());

        for (Integer id : ids) {
            String urlRequest = getBuildRecordByIdUrl(id);
            Future<HttpResponse<PageParameterBuildRecord>> buildRecordFuture = Unirest.get(urlRequest)
                    .asObjectAsync(PageParameterBuildRecord.class);
            futures.add(buildRecordFuture);
        }

        List<BuildRecord> buildRecordList = new ArrayList<>(ids.size());

        for (Future<HttpResponse<PageParameterBuildRecord>> future : futures) {

            try {
                HttpResponse<PageParameterBuildRecord> buildRecord = future.get();
                PageParameterBuildRecord buildRecordData = buildRecord.getBody();

                if (buildRecordData == null) {
                    buildRecordList.add(null);
                } else {
                    buildRecordList.add(buildRecordData.getContent());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                throw new PncClientException(e);
            }
        }

        return buildRecordList;
    }

    /**
     * Get the BuildRecordPushResult object from the buildrecord id. Returns null if no buildrecord push result found
     *
     * @param id buildrecord id
     * @return buildrecord push result DTO
     *
     * @throws PncClientException if something goes wrong
     */
    public BuildRecordPushResult getBuildRecordPushResultById(int id) throws PncClientException {
        try {
            String urlRequest = getBuildRecordPushResultByIdUrl(id);
            HttpResponse<PageParameterBuildRecordPushResult> buildRecordPushResults = Unirest.get(urlRequest)
                    .asObject(PageParameterBuildRecordPushResult.class);
            PageParameterBuildRecordPushResult buildRecordPushResultData = buildRecordPushResults.getBody();

            if (buildRecordPushResultData == null) {
                return null;
            } else {
                return buildRecordPushResultData.getContent();
            }
        } catch (UnirestException e) {
            throw new PncClientException(e);
        }
    }

    public List<BuildRecordPushResult> getBuildRecordPushResultsById(List<Integer> ids) throws PncClientException {
        List<Future<HttpResponse<PageParameterBuildRecordPushResult>>> futures = new ArrayList<>(ids.size());

        for (Integer id : ids) {
            String urlRequest = getBuildRecordPushResultByIdUrl(id);
            Future<HttpResponse<PageParameterBuildRecordPushResult>> buildRecordFuture = Unirest.get(urlRequest)
                    .asObjectAsync(PageParameterBuildRecordPushResult.class);
            futures.add(buildRecordFuture);
        }

        List<BuildRecordPushResult> buildRecordPushResultList = new ArrayList<>(ids.size());

        for (Future<HttpResponse<PageParameterBuildRecordPushResult>> future : futures) {

            try {
                HttpResponse<PageParameterBuildRecordPushResult> buildRecordPushResultData = future.get();
                PageParameterBuildRecordPushResult buildRecordData = buildRecordPushResultData.getBody();

                if (buildRecordData == null) {
                    buildRecordPushResultList.add(null);
                } else {
                    buildRecordPushResultList.add(buildRecordData.getContent());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                throw new PncClientException(e);
            }
        }

        return buildRecordPushResultList;
    }

    /**
     * Get the BuildConfiguration object from the buildconfiguration id. Returns null if no buildconfiguration found
     *
     * @param id buildconfiguration id
     * @return buildconfiguration DTO
     *
     * @throws PncClientException if something goes wrong
     */
    public BuildConfiguration getBuildConfigurationById(int id) throws PncClientException {
        try {
            String urlRequest = getBuildConfigurationByIdUrl(id);
            HttpResponse<PageParameterBuildConfiguration> buildConfigurations = Unirest.get(urlRequest)
                    .asObject(PageParameterBuildConfiguration.class);
            PageParameterBuildConfiguration buildConfigurationData = buildConfigurations.getBody();

            if (buildConfigurationData == null) {
                return null;
            } else {
                return buildConfigurationData.getContent();
            }
        } catch (UnirestException e) {
            throw new PncClientException(e);
        }
    }

    public List<BuildConfiguration> getBuildConfigurationsById(List<Integer> ids) throws PncClientException {
        List<Future<HttpResponse<PageParameterBuildConfiguration>>> futures = new ArrayList<>(ids.size());

        for (Integer id : ids) {
            String urlRequest = getBuildConfigurationByIdUrl(id);
            Future<HttpResponse<PageParameterBuildConfiguration>> buildConfigurationFuture = Unirest.get(urlRequest)
                    .asObjectAsync(PageParameterBuildConfiguration.class);
            futures.add(buildConfigurationFuture);
        }

        List<BuildConfiguration> buildConfigurationList = new ArrayList<>(ids.size());

        for (Future<HttpResponse<PageParameterBuildConfiguration>> future : futures) {

            try {
                HttpResponse<PageParameterBuildConfiguration> buildConfiguration = future.get();
                PageParameterBuildConfiguration buildConfigurationData = buildConfiguration.getBody();

                if (buildConfigurationData == null) {
                    buildConfigurationList.add(null);
                } else {
                    buildConfigurationList.add(buildConfigurationData.getContent());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                throw new PncClientException(e);
            }
        }

        return buildConfigurationList;
    }

    /**
     * Get the ProductVersion object from the productversion id. Returns null if no productversion found
     *
     * @param id productversion id
     * @return productversion DTO
     *
     * @throws PncClientException if something goes wrong
     */
    public ProductVersion getProductVersionById(int id) throws PncClientException {
        try {
            String urlRequest = getProductVersionByIdUrl(id);
            HttpResponse<PageParameterProductVersion> buildConfigurations = Unirest.get(urlRequest)
                    .asObject(PageParameterProductVersion.class);
            PageParameterProductVersion buildConfigurationData = buildConfigurations.getBody();

            if (buildConfigurationData == null) {
                return null;
            } else {
                return buildConfigurationData.getContent();
            }
        } catch (UnirestException e) {
            throw new PncClientException(e);
        }
    }

    public List<ProductVersion> getProductVersionsById(List<Integer> ids) throws PncClientException {
        List<Future<HttpResponse<PageParameterProductVersion>>> futures = new ArrayList<>(ids.size());

        for (Integer id : ids) {
            String urlRequest = getProductVersionByIdUrl(id);
            Future<HttpResponse<PageParameterProductVersion>> buildConfigurationFuture = Unirest.get(urlRequest)
                    .asObjectAsync(PageParameterProductVersion.class);
            futures.add(buildConfigurationFuture);
        }

        List<ProductVersion> buildConfigurationList = new ArrayList<>(ids.size());

        for (Future<HttpResponse<PageParameterProductVersion>> future : futures) {

            try {
                HttpResponse<PageParameterProductVersion> buildConfiguration = future.get();
                PageParameterProductVersion buildConfigurationData = buildConfiguration.getBody();

                if (buildConfigurationData == null) {
                    buildConfigurationList.add(null);
                } else {
                    buildConfigurationList.add(buildConfigurationData.getContent());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                throw new PncClientException(e);
            }
        }

        return buildConfigurationList;
    }

    public BuildConfig getConfig() {
        return config;
    }
}
