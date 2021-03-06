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
package org.jboss.pnc.build.finder.koji;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.infinispan.commons.marshall.AdvancedExternalizer;
import org.infinispan.commons.marshall.SerializeWith;
import org.infinispan.commons.util.Util;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.redhat.red.build.koji.model.json.KojiJsonConstants;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildRequest;
import com.redhat.red.build.koji.model.xmlrpc.KojiRpmInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiTagInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiTaskInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiTaskRequest;

@SerializeWith(KojiBuild.KojiBuildExternalizer.class)
public class KojiBuild {
    public static final String KEY_VERSION = "version";

    public static final String KEY_MAVEN = "maven";

    private KojiBuildInfo buildInfo;

    private KojiTaskInfo taskInfo;

    private transient KojiTaskRequest taskRequest;

    private transient List<KojiLocalArchive> archives;

    private List<KojiArchiveInfo> remoteArchives;

    private List<KojiTagInfo> tags;

    private transient List<String> types;

    private transient List<KojiRpmInfo> rpms;

    private List<KojiRpmInfo> remoteRpms;

    private transient List<KojiArchiveInfo> duplicateArchives;

    private transient boolean pnc;

    public KojiBuild() {
        this.archives = new ArrayList<>();
        this.rpms = new ArrayList<>();
        this.duplicateArchives = new ArrayList<>();
    }

    public KojiBuild(KojiBuildInfo buildInfo) {
        this.buildInfo = buildInfo;
        this.archives = new ArrayList<>();
        this.rpms = new ArrayList<>();
        this.duplicateArchives = new ArrayList<>();
    }

    public KojiBuild(
            KojiBuildInfo buildInfo,
            KojiTaskInfo taskInfo,
            KojiTaskRequest taskRequest,
            List<KojiLocalArchive> archives,
            List<KojiArchiveInfo> remoteArchives,
            List<KojiTagInfo> tags,
            List<String> types,
            List<KojiRpmInfo> rpms,
            List<KojiRpmInfo> remoteRpms) {
        this.buildInfo = buildInfo;
        this.taskInfo = taskInfo;
        this.taskRequest = taskRequest;
        this.archives = archives;
        this.remoteArchives = remoteArchives;
        this.tags = tags;
        this.types = types;
        this.rpms = rpms;
        this.remoteRpms = remoteRpms;
    }

    public KojiBuildInfo getBuildInfo() {
        return buildInfo;
    }

    public void setBuildInfo(KojiBuildInfo buildInfo) {
        this.buildInfo = buildInfo;
    }

    public KojiTaskInfo getTaskInfo() {
        return taskInfo;
    }

    public void setTaskInfo(KojiTaskInfo taskInfo) {
        this.taskInfo = taskInfo;
    }

    public KojiTaskRequest getTaskRequest() {
        if (taskRequest == null && taskInfo != null && taskInfo.getRequest() != null) {
            taskRequest = new KojiTaskRequest(taskInfo.getRequest());
        }

        return taskRequest;
    }

    public void setTaskRequest(KojiTaskRequest taskRequest) {
        this.taskRequest = taskRequest;
    }

    public List<KojiLocalArchive> getArchives() {
        return archives;
    }

    public void setArchives(List<KojiLocalArchive> archives) {
        this.archives = archives;
    }

    public List<KojiArchiveInfo> getRemoteArchives() {
        return remoteArchives;
    }

    public void setRemoteArchives(List<KojiArchiveInfo> remoteArchives) {
        this.remoteArchives = remoteArchives;
    }

    public List<KojiTagInfo> getTags() {
        return tags;
    }

    public void setTags(List<KojiTagInfo> tags) {
        this.tags = tags;
    }

    public List<String> getTypes() {
        if (types == null && buildInfo != null && buildInfo.getTypeNames() != null) {
            types = buildInfo.getTypeNames();
        }

        return types;
    }

    public void setTypes(List<String> types) {
        this.types = types;
    }

    public List<KojiRpmInfo> getRpms() {
        return rpms;
    }

    public void setRpms(List<KojiRpmInfo> rpms) {
        this.rpms = rpms;
    }

    public List<KojiRpmInfo> getRemoteRpms() {
        return remoteRpms;
    }

    public void setRemoteRpms(List<KojiRpmInfo> remoteRpms) {
        this.remoteRpms = remoteRpms;
    }

    public List<KojiArchiveInfo> getDuplicateArchives() {
        return duplicateArchives;
    }

    public void setDuplicateArchives(List<KojiArchiveInfo> duplicateArchives) {
        this.duplicateArchives = duplicateArchives;
    }

    @JsonIgnore
    public boolean isPnc() {
        return pnc;
    }

    @JsonIgnore
    public void setPnc(boolean pnc) {
        this.pnc = pnc;
    }

    @JsonIgnore
    public KojiArchiveInfo getProjectSourcesTgz() {
        String mavenArtifactId = buildInfo.getMavenArtifactId();
        String mavenVersion = buildInfo.getMavenVersion();
        KojiArchiveInfo sourcesZip;

        if (remoteArchives != null && mavenArtifactId != null && mavenVersion != null) {
            String sourcesZipFilename = mavenArtifactId + "-" + mavenVersion + "-project-sources.tar.gz";
            sourcesZip = remoteArchives.stream()
                    .filter(sArchive -> sArchive.getFilename().equals(sourcesZipFilename))
                    .findFirst()
                    .orElse(null);
            return sourcesZip;
        }

        return null;
    }

    @JsonIgnore
    public KojiArchiveInfo getScmSourcesZip() {
        String mavenArtifactId = buildInfo.getMavenArtifactId();
        String mavenVersion = buildInfo.getMavenVersion();
        KojiArchiveInfo sourcesZip;

        if (remoteArchives != null && mavenArtifactId != null && mavenVersion != null) {
            String sourcesZipFilename = mavenArtifactId + "-" + mavenVersion + "-scm-sources.zip";
            sourcesZip = remoteArchives.stream()
                    .filter(sArchive -> sArchive.getFilename().equals(sourcesZipFilename))
                    .findFirst()
                    .orElse(null);
            return sourcesZip;
        }

        return null;
    }

    @JsonIgnore
    public KojiArchiveInfo getPatchesZip() {
        String mavenArtifactId = buildInfo.getMavenArtifactId();
        String mavenVersion = buildInfo.getMavenVersion();
        KojiArchiveInfo patchesZip;

        if (remoteArchives != null && mavenArtifactId != null && mavenVersion != null) {
            String patchesZipFilename = mavenArtifactId + "-" + mavenVersion + "-patches.zip";
            patchesZip = remoteArchives.stream()
                    .filter(pArchive -> pArchive.getFilename().equals(patchesZipFilename))
                    .findFirst()
                    .orElse(null);
            return patchesZip;
        }

        return null;
    }

    @JsonIgnore
    public boolean isImport() {
        return !(buildInfo != null && buildInfo.getExtra() != null
                && buildInfo.getExtra().containsKey(KojiJsonConstants.BUILD_SYSTEM) || taskInfo != null);
    }

    @JsonIgnore
    public boolean isMaven() {
        return buildInfo != null && buildInfo.getExtra() != null
                && (buildInfo.getExtra().containsKey(KEY_MAVEN)
                        || buildInfo.getExtra().get(KojiJsonConstants.BUILD_SYSTEM) != null
                                && buildInfo.getExtra().get(KojiJsonConstants.BUILD_SYSTEM).equals("PNC"))
                || taskInfo != null && taskInfo.getMethod() != null && taskInfo.getMethod().equals(KEY_MAVEN);
    }

    @JsonIgnore
    public String getSource() {
        if (buildInfo != null) {
            String source = buildInfo.getSource();

            if (source != null) {
                return source;
            }
        }

        if (getTaskRequest() != null) {
            KojiBuildRequest buildRequest = taskRequest.asBuildRequest();

            if (buildRequest != null) {
                return buildRequest.getSource();
            }
        }

        return null;
    }

    @JsonIgnore
    public String getMethod() {
        if (taskInfo != null) {
            return taskInfo.getMethod();
        }

        if (buildInfo == null) {
            return null;
        }

        Map<String, Object> extra = buildInfo.getExtra();

        if (extra == null) {
            return null;
        }

        String buildSystem = (String) extra.get(KojiJsonConstants.BUILD_SYSTEM);
        String version = (String) extra.get(KEY_VERSION);

        if (buildSystem != null && version != null) {
            buildSystem += " " + version;
        }

        return buildSystem;
    }

    @Override
    public String toString() {
        return "KojiBuild [buildInfo=" + buildInfo + ", taskInfo=" + taskInfo + ", taskRequest=" + taskRequest
                + ", archives=" + archives + ", remoteArchives=" + remoteArchives + ", tags=" + tags + ", rpms=" + rpms
                + ", remoteRpms=" + remoteRpms + ", duplicateArchives=" + duplicateArchives + "]";
    }

    public static class KojiBuildExternalizer implements AdvancedExternalizer<KojiBuild> {
        private static final long serialVersionUID = 8698588352614405297L;

        private static final int VERSION = 2;

        private static final Integer ID = (Character.getNumericValue('K') << 16) | (Character.getNumericValue('B') << 8)
                | Character.getNumericValue('F');

        @Override
        public void writeObject(ObjectOutput output, KojiBuild build) throws IOException {
            output.writeInt(VERSION);
            output.writeObject(build.buildInfo);
            output.writeObject(build.taskInfo);
            output.writeObject(build.remoteArchives);
            output.writeObject(build.tags);
            output.writeObject(build.types);
            output.writeObject(build.remoteRpms);
        }

        @SuppressWarnings("unchecked")
        @Override
        public KojiBuild readObject(ObjectInput input) throws IOException, ClassNotFoundException {
            int version = input.readInt();

            if (version != 1 && version != 2) {
                throw new IOException("Invalid version: " + version);
            }

            KojiBuild build = new KojiBuild();

            build.setBuildInfo((KojiBuildInfo) input.readObject());
            build.setTaskInfo((KojiTaskInfo) input.readObject());
            build.setRemoteArchives((List<KojiArchiveInfo>) input.readObject());
            build.setTags((List<KojiTagInfo>) input.readObject());
            build.setTypes((List<String>) input.readObject());

            if (version > 1) {
                build.setRemoteRpms((List<KojiRpmInfo>) input.readObject());
            }

            return build;
        }

        @Override
        public Set<Class<? extends KojiBuild>> getTypeClasses() {
            return Util.asSet(KojiBuild.class);
        }

        @Override
        public Integer getId() {
            return ID;
        }
    }
}
