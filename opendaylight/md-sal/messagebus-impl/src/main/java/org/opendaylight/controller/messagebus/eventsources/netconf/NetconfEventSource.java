/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.controller.messagebus.eventsources.netconf;

import static com.google.common.util.concurrent.Futures.immediateFuture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import javax.xml.stream.XMLStreamException;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;

import org.opendaylight.controller.md.sal.binding.api.DataChangeListener;
import org.opendaylight.controller.md.sal.common.api.data.AsyncDataChangeEvent;
import org.opendaylight.controller.md.sal.dom.api.DOMMountPoint;
import org.opendaylight.controller.md.sal.dom.api.DOMNotification;
import org.opendaylight.controller.md.sal.dom.api.DOMNotificationListener;
import org.opendaylight.controller.md.sal.dom.api.DOMNotificationPublishService;
import org.opendaylight.controller.md.sal.dom.api.DOMNotificationService;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcService;
import org.opendaylight.controller.messagebus.app.impl.TopicDOMNotification;
import org.opendaylight.controller.messagebus.app.impl.Util;
import org.opendaylight.controller.messagebus.spi.EventSource;
import org.opendaylight.controller.netconf.util.xml.XmlUtil;
import org.opendaylight.controller.sal.connect.netconf.util.NetconfMessageTransformUtil;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.messagebus.eventaggregator.rev141202.NotificationPattern;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.messagebus.eventaggregator.rev141202.TopicId;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.messagebus.eventaggregator.rev141202.TopicNotification;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.messagebus.eventsource.rev141202.JoinTopicInput;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.messagebus.eventsource.rev141202.JoinTopicOutput;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.messagebus.eventsource.rev141202.JoinTopicOutputBuilder;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.messagebus.eventsource.rev141202.JoinTopicStatus;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.netconf.notification._1._0.rev080714.CreateSubscriptionInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.inventory.rev140108.NetconfNode;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.NodeKey;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.AnyXmlNode;
import org.opendaylight.yangtools.yang.data.api.schema.ContainerNode;
import org.opendaylight.yangtools.yang.data.impl.schema.Builders;
import org.opendaylight.yangtools.yang.data.impl.schema.ImmutableNodes;
import org.opendaylight.yangtools.yang.model.api.NotificationDefinition;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.SchemaPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;

public class NetconfEventSource implements EventSource, DOMNotificationListener, DataChangeListener {

    private static final Logger LOG = LoggerFactory.getLogger(NetconfEventSource.class);

    private static final NodeIdentifier TOPIC_NOTIFICATION_ARG = new NodeIdentifier(TopicNotification.QNAME);
    private static final NodeIdentifier EVENT_SOURCE_ARG = new NodeIdentifier(QName.create(TopicNotification.QNAME, "node-id"));
    private static final NodeIdentifier TOPIC_ID_ARG = new NodeIdentifier(QName.create(TopicNotification.QNAME, "topic-id"));
    private static final NodeIdentifier PAYLOAD_ARG = new NodeIdentifier(QName.create(TopicNotification.QNAME, "payload"));

    private static final NodeIdentifier STREAM_QNAME = new NodeIdentifier(QName.create(CreateSubscriptionInput.QNAME,"stream"));
    private static final SchemaPath CREATE_SUBSCRIPTION = SchemaPath.create(true, QName.create(CreateSubscriptionInput.QNAME, "create-subscription"));

    private final String nodeId;
    private final Node node;

    private final DOMMountPoint netconfMount;
    private final DOMNotificationPublishService domPublish;

    private final Map<String, String> urnPrefixToStreamMap;

    private final ConcurrentHashMap<String, StreamNotificationTopicRegistration> streamNotifRegistrationMap = new ConcurrentHashMap<>();

    public NetconfEventSource(final Node node, final Map<String, String> streamMap, final DOMMountPoint netconfMount, final DOMNotificationPublishService publishService) {
        this.netconfMount = netconfMount;
        this.node = node;
        this.nodeId = node.getNodeId().getValue();
        this.urnPrefixToStreamMap = streamMap;
        this.domPublish = publishService;
        this.initializeStreamNotifRegistrationMap();
        LOG.info("NetconfEventSource [{}] created.", nodeId);
    }

    private void initializeStreamNotifRegistrationMap(){
        for(String streamName : this.urnPrefixToStreamMap.values()){
            streamNotifRegistrationMap.put(streamName, new StreamNotificationTopicRegistration(streamName, this.nodeId, this.netconfMount, this));
        }
    }

    @Override
    public Future<RpcResult<JoinTopicOutput>> joinTopic(final JoinTopicInput input) {

        final NotificationPattern notificationPattern = input.getNotificationPattern();
        final List<SchemaPath> matchingNotifications = getMatchingNotifications(notificationPattern);
        return registerTopic(input.getTopicId(),matchingNotifications);

    }

    private synchronized Future<RpcResult<JoinTopicOutput>> registerTopic(final TopicId topicId, final List<SchemaPath> notificationsToSubscribe){

        JoinTopicStatus joinTopicStatus = JoinTopicStatus.Down;
        if(notificationsToSubscribe != null && notificationsToSubscribe.isEmpty() == false){
            final Optional<DOMNotificationService> notifyService = netconfMount.getService(DOMNotificationService.class);
            if(notifyService.isPresent()){
                int subscribedStreams = 0;
                for(SchemaPath schemaNotification : notificationsToSubscribe){
                    final Optional<String> streamName = resolveStream(schemaNotification.getLastComponent());
                    if(streamName.isPresent()){
                        LOG.info("Stream {} is activating, TopicId {}", streamName.get(), topicId.getValue() );
                        StreamNotificationTopicRegistration streamReg = streamNotifRegistrationMap.get(streamName.get());
                        streamReg.activateStream();
                        for(SchemaPath notificationPath : notificationsToSubscribe){
                            LOG.info("Notification listener is registering, Notification {}, TopicId {}", notificationPath, topicId.getValue() );
                            streamReg.registerNotificationListenerTopic(notificationPath, topicId);
                        }
                        subscribedStreams = subscribedStreams + 1;
                    }
                }
                if(subscribedStreams > 0){
                    joinTopicStatus = JoinTopicStatus.Up;
                }
            }
        }

        final JoinTopicOutput output = new JoinTopicOutputBuilder().setStatus(joinTopicStatus).build();
        return immediateFuture(RpcResultBuilder.success(output).build());

    }

    private void resubscribeToActiveStreams() {
        for (StreamNotificationTopicRegistration streamReg : streamNotifRegistrationMap.values()){
            streamReg.reActivateStream();
        }
    }

    private Optional<String> resolveStream(final QName qName) {
        String streamName = null;

        for (final Map.Entry<String, String> entry : urnPrefixToStreamMap.entrySet()) {
            final String nameSpace = qName.getNamespace().toString();
            final String urnPrefix = entry.getKey();
            if( nameSpace.startsWith(urnPrefix) ) {
                streamName = entry.getValue();
                break;
            }
        }
        return Optional.fromNullable(streamName);
    }

    @Override
    public void onNotification(final DOMNotification notification) {
        SchemaPath notificationPath = notification.getType();
        LOG.info("Notification {} has come.",notification.getType());
        for(StreamNotificationTopicRegistration streamReg : streamNotifRegistrationMap.values()){
            for(TopicId topicId : streamReg.getNotificationTopicIds(notificationPath)){
                publishNotification(notification, topicId);
                LOG.info("Notification {} has been published for TopicId {}",notification.getType(), topicId.getValue());
            }
        }
    }

    private void publishNotification(final DOMNotification notification, TopicId topicId){
         final ContainerNode topicNotification = Builders.containerBuilder()
                 .withNodeIdentifier(TOPIC_NOTIFICATION_ARG)
                 .withChild(ImmutableNodes.leafNode(TOPIC_ID_ARG, topicId))
                 .withChild(ImmutableNodes.leafNode(EVENT_SOURCE_ARG, nodeId))
                 .withChild(encapsulate(notification))
                 .build();
         try {
             domPublish.putNotification(new TopicDOMNotification(topicNotification));
         } catch (final InterruptedException e) {
             throw Throwables.propagate(e);
         }
    }

    private AnyXmlNode encapsulate(final DOMNotification body) {
        // FIXME: Introduce something like AnyXmlWithNormalizedNodeData in Yangtools
        final Document doc = XmlUtil.newDocument();
        final Optional<String> namespace = Optional.of(PAYLOAD_ARG.getNodeType().getNamespace().toString());
        final Element element = XmlUtil.createElement(doc , "payload", namespace);

        final DOMResult result = new DOMResult(element);

        final SchemaContext context = netconfMount.getSchemaContext();
        final SchemaPath schemaPath = body.getType();
        try {
            NetconfMessageTransformUtil.writeNormalizedNode(body.getBody(), result, schemaPath, context);
            return Builders.anyXmlBuilder().withNodeIdentifier(PAYLOAD_ARG)
                    .withValue(new DOMSource(element))
                    .build();
        } catch (IOException | XMLStreamException e) {
            LOG.error("Unable to encapsulate notification.",e);
            throw Throwables.propagate(e);
        }
    }

    @Override
    public void onDataChanged(final AsyncDataChangeEvent<InstanceIdentifier<?>, DataObject> change) {
        boolean wasConnected = false;
        boolean nowConnected = false;

        for (final Map.Entry<InstanceIdentifier<?>, DataObject> changeEntry : change.getOriginalData().entrySet()) {
            if ( isNetconfNode(changeEntry) ) {
                final NetconfNode nn = (NetconfNode)changeEntry.getValue();
                wasConnected = nn.isConnected();
            }
        }

        for (final Map.Entry<InstanceIdentifier<?>, DataObject> changeEntry : change.getUpdatedData().entrySet()) {
            if ( isNetconfNode(changeEntry) ) {
                final NetconfNode nn = (NetconfNode)changeEntry.getValue();
                nowConnected = nn.isConnected();
            }
        }

        if (wasConnected == false && nowConnected == true) {
            resubscribeToActiveStreams();
        }
    }

    private static boolean isNetconfNode(final Map.Entry<InstanceIdentifier<?>, DataObject> changeEntry )  {
        return NetconfNode.class.equals(changeEntry.getKey().getTargetType());
    }

    private List<SchemaPath> getMatchingNotifications(NotificationPattern notificationPattern){
        // FIXME: default language should already be regex
        final String regex = Util.wildcardToRegex(notificationPattern.getValue());

        final Pattern pattern = Pattern.compile(regex);
        List<SchemaPath> availableNotifications = getAvailableNotifications();
        if(availableNotifications == null || availableNotifications.isEmpty()){
            return null;
        }
        return Util.expandQname(availableNotifications, pattern);
    }

    @Override
    public void close() throws Exception {
        for(StreamNotificationTopicRegistration streamReg : streamNotifRegistrationMap.values()){
            streamReg.deactivateStream();
        }
    }

    @Override
    public NodeKey getSourceNodeKey(){
        return node.getKey();
    }

    @Override
    public List<SchemaPath> getAvailableNotifications() {
        // FIXME: use SchemaContextListener to get changes asynchronously
        final Set<NotificationDefinition> availableNotifications = netconfMount.getSchemaContext().getNotifications();
        final List<SchemaPath> qNs = new ArrayList<>(availableNotifications.size());
        for (final NotificationDefinition nd : availableNotifications) {
            qNs.add(nd.getPath());
        }
        return qNs;
    }

    private class StreamNotificationTopicRegistration{

        final private String streamName;
        final private DOMMountPoint netconfMount;
        final private String nodeId;
        final private NetconfEventSource notificationListener;
        private boolean active;

        private ConcurrentHashMap<SchemaPath, ListenerRegistration<NetconfEventSource>> notificationRegistrationMap = new ConcurrentHashMap<>();
        private ConcurrentHashMap<SchemaPath, ArrayList<TopicId>> notificationTopicMap = new ConcurrentHashMap<>();

        public StreamNotificationTopicRegistration(final String streamName, final String nodeId, final DOMMountPoint netconfMount, NetconfEventSource notificationListener) {
            this.streamName = streamName;
            this.netconfMount = netconfMount;
            this.nodeId = nodeId;
            this.notificationListener = notificationListener;
            this.active = false;
        }

        public boolean isActive() {
            return active;
        }

        public void reActivateStream(){
            if(this.isActive()){
                LOG.info("Stream {} is reactivated active on node {}.", this.streamName, this.nodeId);
                final ContainerNode input = Builders.containerBuilder().withNodeIdentifier(new NodeIdentifier(CreateSubscriptionInput.QNAME))
                        .withChild(ImmutableNodes.leafNode(STREAM_QNAME, this.streamName))
                        .build();
                netconfMount.getService(DOMRpcService.class).get().invokeRpc(CREATE_SUBSCRIPTION, input);
            }
        }

        public void activateStream() {
            if(this.isActive() == false){
                LOG.info("Stream {} is not active on node {}. Will subscribe.", this.streamName, this.nodeId);
                final ContainerNode input = Builders.containerBuilder().withNodeIdentifier(new NodeIdentifier(CreateSubscriptionInput.QNAME))
                        .withChild(ImmutableNodes.leafNode(STREAM_QNAME, this.streamName))
                        .build();
                netconfMount.getService(DOMRpcService.class).get().invokeRpc(CREATE_SUBSCRIPTION, input);
                this.active = true;
            } else {
                LOG.info("Stream {} is now active on node {}", this.streamName, this.nodeId);
            }
        }

        public void deactivateStream() {
            for(ListenerRegistration<NetconfEventSource> reg : notificationRegistrationMap.values()){
                reg.close();
            }
            this.active = false;
        }

        public String getStreamName() {
            return streamName;
        }

        public ArrayList<TopicId> getNotificationTopicIds(SchemaPath notificationPath){
            return notificationTopicMap.get(notificationPath);
        }

        public void registerNotificationListenerTopic(SchemaPath notificationPath, TopicId topicId){
            final Optional<DOMNotificationService> notifyService = netconfMount.getService(DOMNotificationService.class);
            if(notificationPath != null && notifyService.isPresent()){
                ListenerRegistration<NetconfEventSource> registration = notifyService.get().registerNotificationListener(this.notificationListener,notificationPath);
                notificationRegistrationMap.put(notificationPath, registration);
                ArrayList<TopicId> topicIds = getNotificationTopicIds(notificationPath);
                if(topicIds == null){
                    topicIds = new ArrayList<>();
                    topicIds.add(topicId);
                } else {
                    if(topicIds.contains(topicId) == false){
                        topicIds.add(topicId);
                    }
                }
                notificationTopicMap.put(notificationPath, topicIds);
            }
        }

    }
}
