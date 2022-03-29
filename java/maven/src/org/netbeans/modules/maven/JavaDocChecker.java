/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.netbeans.modules.maven;

import org.netbeans.modules.maven.api.execute.PrerequisitesChecker;
import org.netbeans.modules.maven.api.execute.RunConfig;
import org.netbeans.modules.maven.options.MavenSettings;
import org.netbeans.spi.project.ProjectServiceProvider;

/**
 *
 * @author mkleint
 */
@ProjectServiceProvider(service=PrerequisitesChecker.class, projectType="org-netbeans-modules-maven")
public class JavaDocChecker implements PrerequisitesChecker {

    /**
     * Skip java doc execution.
     */
    public static final String PROP_SKIP_JAVADOC = "maven.javadoc.skip"; // NOI18N

    @Override public boolean checkRunConfig(RunConfig config) {
        if (MavenSettings.getDefault().isSkipJavaDoc()) {
            if (!String.valueOf(config.getGoals()).contains("javadoc")) {
                if (config.getProperties().get(PROP_SKIP_JAVADOC) == null) {
                    config.setProperty(PROP_SKIP_JAVADOC, "true"); //NOI18N
                }
            }
        }
        
        return true;
    }

}
