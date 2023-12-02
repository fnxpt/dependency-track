/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) Steve Springett. All Rights Reserved.
 */
package org.dependencytrack.parser.trivy;

import alpine.common.logging.Logger;
import org.dependencytrack.model.Cwe;
import org.dependencytrack.model.Severity;
import org.dependencytrack.model.Vulnerability;
import org.dependencytrack.model.VulnerableSoftware;
import org.dependencytrack.parser.common.resolver.CweResolver;
import org.dependencytrack.parser.trivy.model.Bitnami;
import org.dependencytrack.persistence.QueryManager;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TrivyParser {

    private static final Logger LOGGER = Logger.getLogger(TrivyParser.class);

    public Vulnerability parse(org.dependencytrack.parser.trivy.model.Vulnerability data, QueryManager qm) {
        Vulnerability synchronizedVulnerability = new Vulnerability();
        Vulnerability vulnerability = new Vulnerability();
        List<VulnerableSoftware> vsList = new ArrayList<>();

        Vulnerability.Source source = Vulnerability.Source.NVD;

        if (data.getVulnerabilityID().startsWith("GHSA-")) {
            source = Vulnerability.Source.GITHUB;
        }

        vulnerability.setSource(source);

        vulnerability.setPatchedVersions(data.getFixedVersion());

        // get the id of the data record (vulnerability)
        vulnerability.setVulnId(data.getVulnerabilityID());
        vulnerability.setTitle(data.getTitle());
        vulnerability.setDescription(data.getDescription());
        vulnerability.setSeverity(parseSeverity(data.getSeverity()));

        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH);

        if (data.getPublishedDate() != null) {
            try {
                vulnerability.setPublished(formatter.parse(data.getPublishedDate()));
            } catch (ParseException ex) {
                LOGGER.error("Unable to parse published date %s".formatted(data.getPublishedDate()));
            }
            vulnerability.setCreated(vulnerability.getPublished());
        }

        if (data.getLastModifiedDate() != null) {
            try {
                vulnerability.setUpdated(formatter.parse(data.getLastModifiedDate()));
            } catch (ParseException ex) {
                LOGGER.error("Unable to parse last modified date %s".formatted(data.getLastModifiedDate()));
            }
        }

        vulnerability.setReferences(addReferences(data.getReferences()));

        // CWE
        for (String id : data.getCweIDS()) {
            final Cwe cwe = CweResolver.getInstance().resolve(qm, id);
            if (cwe != null) {
                vulnerability.addCwe(cwe);
            }
        }

        vulnerability = setCvssScore(data.getCvss().get(data.getSeveritySource()), vulnerability);

        final List<VulnerableSoftware> vsListOld = qm.detach(qm.getVulnerableSoftwareByVulnId(vulnerability.getSource(), vulnerability.getVulnId()));
        synchronizedVulnerability = qm.synchronizeVulnerability(vulnerability, false);
        qm.persist(vsList);
        qm.updateAffectedVersionAttributions(synchronizedVulnerability, vsList, source);
        vsList = qm.reconcileVulnerableSoftware(synchronizedVulnerability, vsListOld, vsList, source);
        synchronizedVulnerability.setVulnerableSoftware(vsList);
        qm.persist(synchronizedVulnerability);

        return synchronizedVulnerability;
    }

    public Severity parseSeverity(String severity) {

        if (severity != null) {
            if (severity.equalsIgnoreCase("CRITICAL")) {
                return Severity.CRITICAL;
            } else if (severity.equalsIgnoreCase("HIGH")) {
                return Severity.HIGH;
            } else if (severity.equalsIgnoreCase("MEDIUM")) {
                return Severity.MEDIUM;
            } else if (severity.equalsIgnoreCase("LOW")) {
                return Severity.LOW;
            } else {
                return Severity.UNASSIGNED;
            }
        }
        return Severity.UNASSIGNED;
    }

    public Vulnerability setCvssScore(Bitnami cvss, Vulnerability vulnerability) {
        if (cvss != null) {
            vulnerability.setCvssV2Vector(cvss.getV2Vector());
            vulnerability.setCvssV3Vector(cvss.getV3Vector());
            vulnerability.setCvssV2BaseScore(BigDecimal.valueOf(cvss.getV2Score()));
            vulnerability.setCvssV3BaseScore(BigDecimal.valueOf(cvss.getV3Score()));
        }

        return vulnerability;
    }

    public String addReferences(String[] references) {
        final StringBuilder sb = new StringBuilder();
        for (String reference : references) {
            if (reference != null) {
                sb.append("* [").append(reference).append("](").append(reference).append(")\n");
            }
        }
        return sb.toString();
    }
}