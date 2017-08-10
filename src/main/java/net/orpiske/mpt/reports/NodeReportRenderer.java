/*
 *  Copyright 2017 Otavio R. Piske <angusyoung@gmail.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.orpiske.mpt.reports;

import com.google.common.base.Charsets;
import com.hubspot.jinjava.Jinjava;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


public class NodeReportRenderer {
    private static final Logger logger = LoggerFactory.getLogger(NodeReportRenderer.class);

    private Map<String, Object> context = new HashMap<>();;

    public NodeReportRenderer(Map<String, Object> context) {
        this.context = context;
    }


    public String renderNodeInfo() throws Exception {
        Jinjava jinjava = new Jinjava();

        String text = null;
        try {
            File template = new File(this.getClass().getResource("index-node.html").toURI());

            text = FileUtils.readFileToString(template, Charsets.UTF_8.name());
        } catch (IOException e) {
            e.printStackTrace();
        }

        return jinjava.render(text, context);
    }
}