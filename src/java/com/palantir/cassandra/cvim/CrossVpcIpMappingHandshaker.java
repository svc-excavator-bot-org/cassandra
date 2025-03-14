/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.cassandra.cvim;

import java.net.InetAddress;
import java.time.Duration;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.DebuggableScheduledThreadPoolExecutor;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.net.MessageOut;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.FBUtilities;

/**
 * Similar to {@link org.apache.cassandra.gms.Gossiper}, this class is responsible for sending
 * {@link CrossVpcIpMappingSyn} messages to seed nodes and managing the internal/external node IP
 * mapping for cross-vpc connections. If {@link DatabaseDescriptor#isCrossVpcInternodeCommunicationEnabled()} is false,
 * we will not trigger any handshakes or update this node's mappings in response to other CrossVpcIpMapping requests.
 * However, this node's CrossVpc verb handlers will still respond to requests with appropriate information.
 */
public class CrossVpcIpMappingHandshaker
{
    private static final Logger logger = LoggerFactory.getLogger(CrossVpcIpMappingHandshaker.class);
    public static final CrossVpcIpMappingHandshaker instance = new CrossVpcIpMappingHandshaker();
    private static final DebuggableScheduledThreadPoolExecutor executor = new DebuggableScheduledThreadPoolExecutor(
    "CrossVpcIpMappingTasks");
    private volatile ScheduledFuture<?> scheduledCVIMTask;
    public final static Duration scheduledInterval = Duration.ofSeconds(1);
    public final static Duration minHandshakeInterval = Duration.ofMillis(500);
    private static volatile long lastTriggeredHandshakeMillis = 0;
    private final ConcurrentHashMap<InetAddressIp, InetAddressIp> privatePublicIpMappings;
    private final ConcurrentHashMap<InetAddressIp, InetAddressHostname> ipHostnameMappings;

    private CrossVpcIpMappingHandshaker()
    {
        this.privatePublicIpMappings = new ConcurrentHashMap<>();
        this.ipHostnameMappings = new ConcurrentHashMap<>();
    }

    public void updateCrossVpcMappings(InetAddressHostname host, InetAddressIp internalIp, InetAddressIp externalIp)
    {
        if (!DatabaseDescriptor.isCrossVpcInternodeCommunicationEnabled()) {
            return;
        }
        InetAddressIp oldExternalIp = this.privatePublicIpMappings.get(internalIp);
        if (!externalIp.equals(oldExternalIp))
        {
            this.privatePublicIpMappings.put(internalIp, externalIp);
            logger.trace("Updated private/public IP mapping for {} from {}->{} to {}",
                         host, internalIp, oldExternalIp, externalIp);
        }

        InetAddressHostname old = this.ipHostnameMappings.get(internalIp);
        if (!host.equals(old))
        {
            this.ipHostnameMappings.put(internalIp, host);
            logger.trace("Updated private IP/hostname mapping from {}->{} to {}", internalIp, old, host);
        }
        old = this.ipHostnameMappings.get(externalIp);
        if (!host.equals(old))
        {
            this.ipHostnameMappings.put(externalIp, host);
            logger.trace("Updated public IP/hostname mapping from {}->{} to {}", externalIp, old, host);
        }
    }

    /**
     * Depending on which cross-vpc flags are enabled/disabled, will check the cross-vpc mappings and either swap the
     * given endpoint with an endpoint derived from a public IP, or DNS using a hostname. If no mappings are found will
     * return the original endpoint.
     */
    public InetAddress maybeSwapAddress(InetAddress endpoint)
    {
        if (!DatabaseDescriptor.isCrossVpcInternodeCommunicationEnabled()) {
            return endpoint;
        }
        InetAddressIp proposedAddress = new InetAddressIp(endpoint.getHostAddress());
        if (DatabaseDescriptor.isCrossVpcHostnameSwappingEnabled() && ipHostnameMappings.containsKey(proposedAddress)) {
            return maybeSwapHostname(endpoint);
        }
        if (DatabaseDescriptor.isCrossVpcIpSwappingEnabled() && privatePublicIpMappings.containsKey(proposedAddress)) {
            return maybeSwapIp(endpoint);
        }
        return endpoint;
    }

    private InetAddress maybeSwapHostname(InetAddress endpoint)
    {
        InetAddressHostname hostname = ipHostnameMappings.get(new InetAddressIp(endpoint.getHostAddress()));
        logger.trace("Performing DNS lookup for host {}", hostname);
        InetAddress resolved;
        try
        {
            resolved = InetAddress.getByName(hostname.toString());
        }
        catch (UnknownHostException e)
        {
            logger.error("Cross VPC mapping contains unresolvable hostname for endpoint {} (unresolved: {})",
                         endpoint, hostname);
            return endpoint;
        }
        if (!resolved.equals(endpoint))
        {
            logger.trace("DNS-resolved address different than provided endpoint. Swapping. provided: {} resolved: {}",
                         endpoint, resolved);
            return resolved;
        }
        return endpoint;
    }

    private InetAddress maybeSwapIp(InetAddress endpoint)
    {
        InetAddressIp proposedIp = new InetAddressIp(endpoint.getHostAddress());
        InetAddressIp mappedIp = privatePublicIpMappings.get(proposedIp);
        if (!mappedIp.equals(proposedIp))
        {
            try
            {
                InetAddress result = InetAddress.getByName(mappedIp.toString());
                logger.trace("Swapped address {} for {} via private-public IP mapping", endpoint, result);
                return result;
            }
            catch (UnknownHostException e)
            {
                logger.error("Failed to resolve host for externally-mapped IP {}->{}. " +
                             "Ensure the address mapping does not contain hostnames", endpoint, mappedIp);
            }
        }
        return endpoint;
    }

    /**
     * Checks cross-vpc mapping to return an associated hostname with the given endpoint if present. Use this method
     * if you don't want to invoke reverse-DNS like {@link #maybeSwapHostname(InetAddress)} within
     * {@link #maybeSwapAddress(InetAddress)} does. Additionally note that this method does not _swap_ hostnames, only
     * provides the hostname associated with a given endpoint if it is present in the cross-vpc mapping.
     */
    public Optional<InetAddressHostname> getAssociatedHostname(InetAddress endpoint)
    {
        if (!DatabaseDescriptor.isCrossVpcInternodeCommunicationEnabled()) {
            return Optional.empty();
        }
        InetAddressIp ip = new InetAddressIp(endpoint.getHostAddress());
        return Optional.ofNullable(ipHostnameMappings.get(ip));
    }

    public void triggerHandshakeWithAllPeers()
    {
        if (!DatabaseDescriptor.isCrossVpcInternodeCommunicationEnabled()) {
            return;
        }
        try {
            if (System.currentTimeMillis() - lastTriggeredHandshakeMillis < minHandshakeInterval.toMillis())
            {
                logger.trace("Ignoring handshake request as last handshake is too recent");
                return;
            }
            // peers should be provided via config by hostname with our setup
            Set<InetAddress> allPeers = DatabaseDescriptor.getAllHosts()
                                                       .stream()
                                                       .filter(host -> !host.equals(FBUtilities.getBroadcastAddress()))
                                                       .collect(Collectors.toSet());

            triggerHandshakeFromSelf(allPeers);
        }
        catch (Exception e)
        {
            logger.error("Caught exception trying to trigger CrossVpcIpMapping handshake with seeds", e);
        }
    }

    @VisibleForTesting
    synchronized void triggerHandshakeFromSelf(Set<InetAddress> targets)
    {
        lastTriggeredHandshakeMillis = System.currentTimeMillis();
        InetAddressHostname selfName = new InetAddressHostname(FBUtilities.getLocalAddress().getHostName());
        InetAddressIp selfIp = new InetAddressIp(FBUtilities.getBroadcastAddress().getHostAddress());
        targets.forEach(target -> {
            try
            {
                triggerHandshake(selfName, selfIp, target);
            }
            catch (Exception e)
            {
                logger.error("Caught exception trying to trigger handshake from {}/{} to {}", selfName, selfIp, target);
            }
        });
    }

    @VisibleForTesting
    void triggerHandshake(InetAddressHostname sourceName, InetAddressIp sourceIp, InetAddress target)
    {
        if (!DatabaseDescriptor.isCrossVpcInternodeCommunicationEnabled())
        {
            return;
        }
        logger.trace("Triggering cross VPC IP swapping handshake from {}/{} to {}", sourceName, sourceIp, target);
        CrossVpcIpMappingSyn syn = new CrossVpcIpMappingSyn(sourceName,
                                                            sourceIp,
                                                            new InetAddressHostname(target.getHostName()),
                                                            new InetAddressIp(target.getHostAddress()));

        MessageOut<CrossVpcIpMappingSyn> synMessage = new MessageOut<>(MessagingService.Verb.CROSS_VPC_IP_MAPPING_SYN,
                                                                       syn,
                                                                       CrossVpcIpMappingSyn.serializer);
        MessagingService.instance().sendOneWay(synMessage, target);
    }

    public void start()
    {
        if (!DatabaseDescriptor.isCrossVpcInternodeCommunicationEnabled())
        {
            logger.warn("Cross VPC internode communication is disabled. Not scheduling handshake task. Set " +
                        "cross_vpc_internode_communication_enabled=true if desired");
            return;
        }
        if (isEnabled())
        {
            logger.info("Cross VPC IP Swapping already enabled and scheduled. Ignoring extra start() call.");
            return;
        }
        logger.info("Started running CrossVpcIpMappingTask at interval of {}",
                    CrossVpcIpMappingHandshaker.scheduledInterval);
        scheduledCVIMTask = executor.scheduleWithFixedDelay(() -> {
            try
            {
                triggerHandshakeWithAllPeers();
            }
            catch (Exception e)
            {
                logger.error("Caught exception trying to run scheduled CrossVpcIpMapping handshake task", e);
            }
        }, 0L, CrossVpcIpMappingHandshaker.scheduledInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean isEnabled()
    {
        return (scheduledCVIMTask != null) && (!scheduledCVIMTask.isCancelled());
    }

    public void stop()
    {
        if (scheduledCVIMTask != null)
        {
            logger.warn("Stopping CrossVpcIpMappingTask at interval after operator request");
            scheduledCVIMTask.cancel(false);
        }
    }

    @VisibleForTesting
    long getLastTriggeredHandshakeMillis()
    {
        return lastTriggeredHandshakeMillis;
    }

    @VisibleForTesting
    void setLastTriggeredHandshakeMillis(long millis)
    {
        lastTriggeredHandshakeMillis = millis;
    }

    @VisibleForTesting
    Map<InetAddressIp, InetAddressIp> getCrossVpcIpMapping()
    {
        return this.privatePublicIpMappings;
    }

    @VisibleForTesting
    Map<InetAddressIp, InetAddressHostname> getCrossVpcIpHostnameMapping()
    {
        return this.ipHostnameMappings;
    }

    @VisibleForTesting
    void clearMappings()
    {
        this.ipHostnameMappings.clear();
        this.privatePublicIpMappings.clear();
    }
}
