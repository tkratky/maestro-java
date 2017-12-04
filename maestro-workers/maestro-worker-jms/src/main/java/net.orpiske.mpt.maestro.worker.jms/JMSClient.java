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

/**
 * NOTE: this a fork of Justin Ross' Quiver at:
 * https://raw.githubusercontent.com/ssorj/quiver/master/java/quiver-jms-driver/src/main/java/net/ssorj/quiver/QuiverArrowJms.java
 * <p>
 * The code was modified to integrate more tightly with maestro.
 */

package net.orpiske.mpt.maestro.worker.jms;

import net.orpiske.mpt.common.URLQuery;
import net.orpiske.mpt.common.jms.Client;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import java.net.URI;

class JMSClient implements Client {

    protected String url = null;
    protected Destination destination = null;
    protected Connection connection = null;

    // JMS urls cannot have the query part
    private String filterURL() {
        String filteredUrl;

        int queryStartIndex = url.indexOf('?');
        if (queryStartIndex != -1) {
            filteredUrl = url.substring(0, queryStartIndex);
        } else {
            filteredUrl = url;
        }

        return filteredUrl;
    }

    @Override
    public void start() throws Exception {
        Destination destination;
        Connection connection = null;
        try {
            final URI uri = new URI(url);
            final String connectionUrl = filterURL();
            final URLQuery urlQuery = new URLQuery(uri);
            final JMSProtocol protocol = JMSProtocol.valueOf(urlQuery.getString("protocol", JMSProtocol.AMQP.name()));
            final ConnectionFactory factory = protocol.createConnectionFactory(connectionUrl);
            //doesn't need to use any enum yet
            final String type = urlQuery.getString("type", "queue");
            final String destinationName = uri.getPath().substring(1);
            switch (type) {
                case "queue":
                    destination = protocol.createQueue(destinationName);
                    break;
                case "topic":
                    destination = protocol.createTopic(destinationName);
                    break;
                default:
                    throw new UnsupportedOperationException("not supported destination type: " + type);
            }
            connection = factory.createConnection();
        } catch (Throwable t) {
            JMSResourceUtil.capturingClose(connection);
            throw t;
        }
        this.destination = destination;
        this.connection = connection;
        this.connection.start();
    }

    @Override
    public void stop() {
        final Throwable t = JMSResourceUtil.capturingClose(connection);
        this.connection = null;
        if (t != null) {
            t.printStackTrace();
        }
    }

    @Override
    public void setUrl(String url) {
        this.url = url;
    }
}
