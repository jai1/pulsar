/**
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
package org.apache.pulsar.proxy.server;

import static org.mockito.Mockito.spy;

import java.util.HashSet;
import java.util.Set;

import org.apache.bookkeeper.test.PortManager;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerConfiguration;
import org.apache.pulsar.client.api.ProducerConsumerBase;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.PropertyAdmin;
import org.apache.pulsar.proxy.server.ProxyRolesEnforcementTest.BasicAuthentication;
import org.apache.pulsar.proxy.server.ProxyRolesEnforcementTest.BasicAuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class ProxyForwardAuthDataTest extends ProducerConsumerBase {
    private static final Logger log = LoggerFactory.getLogger(ProxyForwardAuthDataTest.class);
    private int webServicePort;
    private int servicePort;
    
    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        webServicePort = PortManager.nextFreePort();
        servicePort = PortManager.nextFreePort();
        conf.setAuthenticationEnabled(true);
        conf.setAuthorizationEnabled(true);
        conf.setTlsEnabled(false);
        conf.setBrokerClientAuthenticationPlugin(BasicAuthentication.class.getName());
        conf.setBrokerClientAuthenticationParameters("authParam:broker");
        conf.setAuthenticateOriginalAuthData(true);
        
        Set<String> superUserRoles = new HashSet<String>();
        superUserRoles.add("admin");
        conf.setSuperUserRoles(superUserRoles);
        
        Set<String> providers = new HashSet<String>();
        providers.add(BasicAuthenticationProvider.class.getName());
        conf.setAuthenticationProviders(providers);

        conf.setClusterName("use");
        Set<String> proxyRoles = new HashSet<String>();
        proxyRoles.add("proxy");
        conf.setProxyRoles(proxyRoles);

        super.init();
    }

    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();       
    }
    
    @Test
    void testForwardAuthData() throws Exception {
        log.info("-- Starting {} test --", methodName);

        // Step 1: Create Admin Client
        createAdminClient();
        final String proxyServiceUrl = "pulsar://localhost:" + servicePort;
        // create a client which connects to proxy and pass authData
        String namespaceName = "my-property/use/my-ns";
        String topicName = "persistent://my-property/use/my-ns/my-topic1";
        String subscriptionName = "my-subscriber-name";
        String clientAuthParams = "authParam:client";
        String proxyAuthParams = "authParam:proxy";
        admin.clusters().createCluster("use", new ClusterData(brokerUrl.toString(), brokerUrlTls.toString(),
                "pulsar://localhost:" + BROKER_PORT, "pulsar+ssl://localhost:" + BROKER_PORT_TLS));
        admin.properties().createProperty("my-property",
                new PropertyAdmin(Lists.newArrayList("appid1", "appid2"), Sets.newHashSet("use")));
        admin.namespaces().createNamespace(namespaceName);
        
        admin.namespaces().grantPermissionOnNamespace(namespaceName, "proxy", Sets.newHashSet(AuthAction.consume, AuthAction.produce));
        admin.namespaces().grantPermissionOnNamespace(namespaceName, "client", Sets.newHashSet(AuthAction.consume, AuthAction.produce));

        
        // Step 2: Run Pulsar Proxy without forwarding authData - expect Exception
        ProxyConfiguration proxyConfig = new ProxyConfiguration();
        proxyConfig.setAuthenticationEnabled(true);

        proxyConfig.setServicePort(servicePort);
        proxyConfig.setWebServicePort(webServicePort);
        proxyConfig.setBrokerServiceURL("pulsar://localhost:" + BROKER_PORT);
        
        proxyConfig.setBrokerClientAuthenticationPlugin(BasicAuthentication.class.getName());
        proxyConfig.setBrokerClientAuthenticationParameters(proxyAuthParams);

        Set<String> providers = new HashSet<>();
        providers.add(BasicAuthenticationProvider.class.getName());
        proxyConfig.setAuthenticationProviders(providers);
        ProxyService proxyService = new ProxyService(proxyConfig);

        proxyService.start();
        PulsarClient proxyClient = createPulsarClient(proxyServiceUrl, clientAuthParams);
        Consumer consumer;
        boolean exceptionOccured = false;
        try {
            consumer = proxyClient.subscribe(topicName, subscriptionName);
        } catch(Exception ex) {
            exceptionOccured  = true;
        }         
        Assert.assertTrue(exceptionOccured);
        proxyService.close();
        
        // Step 3: Create proxy with forwardAuthData enabled
        proxyConfig.setForwardAuthorizationCredentials(true);
        proxyService = new ProxyService(proxyConfig);

        proxyService.start();
        Thread.sleep(1000);
        consumer = proxyClient.subscribe(topicName, subscriptionName);   
        Assert.assertTrue(exceptionOccured);
        proxyService.close();
    }

    private void createAdminClient() throws PulsarClientException {
        String adminAuthParams = "authParam:admin";
        org.apache.pulsar.client.api.ClientConfiguration clientConf = new org.apache.pulsar.client.api.ClientConfiguration();
        clientConf.setAuthentication(BasicAuthentication.class.getName(), adminAuthParams);

        admin = spy(new PulsarAdmin(brokerUrl, clientConf));        
    }
    
    private PulsarClient createPulsarClient(String proxyServiceUrl, String authParams) throws PulsarClientException {
        org.apache.pulsar.client.api.ClientConfiguration clientConf = new org.apache.pulsar.client.api.ClientConfiguration();
        clientConf.setAuthentication(BasicAuthentication.class.getName(), authParams);
        return PulsarClient.create(proxyServiceUrl, clientConf);
    }
}
