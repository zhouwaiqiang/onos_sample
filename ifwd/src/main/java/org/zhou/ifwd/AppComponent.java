/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zhou.ifwd;

import org.apache.felix.scr.annotations.*;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.IpPrefix;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.*;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.HostToHostIntent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.IntentState;
import org.onosproject.net.intent.Key;
import org.onosproject.net.packet.*;
import org.onosproject.net.topology.TopologyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
@Service
public class AppComponent implements ForwardingMapService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowObjectiveService flowObjectiveService;

    private ReactivePacketProcessor processor = new ReactivePacketProcessor();
    private ApplicationId appId;
    protected final ConcurrentMap<HostId, HostId> endPoints = new ConcurrentHashMap<>();

    // in order to judge the path
    private Map<Map<HostId, HostId>, Integer> map = new HashMap<Map<HostId, HostId>, Integer>();

    //key integer(stand for path) and value is deviceId, the center of the path
    private Map<Integer, DeviceId> mapDevice = new HashMap<Integer, DeviceId>();

    private static final int DROP_RULE_TIMEOUT = 30;
    private static final EnumSet<IntentState> WITHDRAWN_STATES = EnumSet.of(IntentState.WITHDRAWN,
                                                                            IntentState.WITHDRAWING,
                                                                            IntentState.WITHDRAW_REQ);

    @Activate
    protected void activate() {
        //first register the appId
        mapDevice.put(1, DeviceId.deviceId("of:0000000000000002"));
        mapDevice.put(2, DeviceId.deviceId("of:0000000000000003"));
        mapDevice.put(3, DeviceId.deviceId("of:0000000000000004"));
        appId = coreService.registerApplication("org.zhou.ifwd");
        packetService.addProcessor(processor, PacketProcessor.director(2));

        //create the selector of traffic
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        //match the ethtype of ipv4
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
        log.info("Hello, this is the first App");
    }

    @Deactivate
    protected void deactivate() {
        packetService.removeProcessor(processor);
        processor = null;
        map.clear();
        mapDevice.clear();
        log.info("Stopped");
    }

    @Override
    public Map<HostId, HostId> getEndPoints() {
        return Collections.unmodifiableMap(endPoints);
    }

    private class ReactivePacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            IPv4 ipPkt = (IPv4) ethPkt.getPayload();
            if (ethPkt == null) {
                return;
            }

            HostId srcId = HostId.hostId(ethPkt.getSourceMAC());
            HostId dstId = HostId.hostId(ethPkt.getDestinationMAC());

            Host dst = hostService.getHost(dstId);
            endPoints.put(srcId, dstId);
            if (dst == null || ethPkt.getEtherType() == Ethernet.TYPE_ARP) {
                flood(context);
                return;
            }

            //if we are in an edge switch, forward
            if (pkt.receivedFrom().deviceId().equals(dst.location().deviceId())) {
                if (!context.inPacket().receivedFrom().port().equals(dst.location().port())) {
                    //log.info("-*****- install the last flow switch to host -***-");
                    installRule(context, dst.location().port(), srcId, dstId);
                }
                return;
            }

            // get a path that does not lead back to where we came from, if no such path, flood and bail
            Set<Path> paths =  topologyService.getPaths(topologyService.currentTopology(),
                                                        pkt.receivedFrom().deviceId(), dst.location().deviceId());
            if (paths.isEmpty()) {
                log.warn("path is empty !!! no path is available");
                context.block();
                return;
            }

            Path path = pickForwardPathIfPossible(paths, pkt.receivedFrom().port(), srcId, dstId);


            if (path == null) {
                log.warn("we don't know where to go from here {} for {} -> {}",
                         pkt.receivedFrom(), ethPkt.getSourceMAC(), ethPkt.getDestinationMAC());
                return;
                //path = pickDefaultPathIfPossible(paths, pkt.receivedFrom().port());
            }

            log.info("\n --- Zhou Path Info --- \n Src:{}, Dst:{}, Recevied Port{}\n{}",
                     IpPrefix.valueOf(ipPkt.getSourceAddress(), 32).toString(),
                     IpPrefix.valueOf(ipPkt.getDestinationAddress(), 32).toString(),
                     pkt.receivedFrom().port(),
                     path.links().toString().replace("Default", "\n"));
            //installRule(context, path.src().port(), srcId, dstId);
            installRule(context, path, srcId, dstId);
            //or transport to the portnum
            //setUpConnectivity(context, srcId, dstId);
            //forwardPacketToDst(context, dst);
        }
    }

    private DeviceId getPathCenterDeviceId(HostId srcId, HostId dstId) {
        //deviceId:of:0000000000000004
        Map<HostId, HostId> tempMap = new HashMap<HostId, HostId>();
        tempMap.put(srcId, dstId);
        if (map.containsKey(tempMap)) {
            Integer result = map.get(tempMap);
            if (result == 3) {
                //modify the value of the key
                map.put(tempMap, 1);
                return mapDevice.get(1);
            } else {
                map.put(tempMap, result + 1);
                return mapDevice.get(result + 1);
            }
        } else {
            // first time to record the srcId and dstId
            map.put(tempMap, 1);
            return mapDevice.get(1);
        }

    }

    private void mapMinusOne(HostId srcId, HostId dstId) {
        Map<HostId, HostId> tempMap = new HashMap<HostId, HostId>();
        tempMap.put(srcId, dstId);
        Integer result = map.get(tempMap);
        if (result == 1) {
            //go back to last time
            map.put(tempMap, 3);
        } else {
            map.put(tempMap, result - 1);
        }
    }

    private Path pickForwardPathIfPossible(Set<Path> paths, PortNumber notToPort, HostId srcId, HostId dstId) {
        DeviceId matchDeviceId = getPathCenterDeviceId(srcId, dstId);
        log.info("MatchDeviced ID shoud be {}", matchDeviceId);
        for (Path path : paths) {
            log.info("\n *** Link Path Info *** {}",
                     path.links().toString().replace("Default", "\n"));
            if (!path.src().port().equals(notToPort)) {
                for (Link link : path.links()) {
                    if (link.src().deviceId().equals(matchDeviceId)) {
                        return path;
                    }
                }
            }
        }
        this.mapMinusOne(srcId, dstId);
        return null;
    }

    private Path pickDefaultPathIfPossible(Set<Path> paths, PortNumber notToPort) {
        for (Path path : paths) {
            if (!path.src().port().equals(notToPort)) {
                return path;
            }
        }
        return null;
    }

    private void installRule(PacketContext context, Path path, HostId srcId, HostId dstId) {
        Ethernet inPkt = context.inPacket().parsed();
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        Host src = hostService.getHost(srcId);
        Host dst = hostService.getHost(dstId);
        if (src == null || dst == null) {
            return;
        } else {
            //constructure ipv4 packet use payload
            IPv4 ipPkt = (IPv4) inPkt.getPayload();
            selector.matchEthType(Ethernet.TYPE_IPV4).matchIPSrc(IpPrefix.valueOf(ipPkt.getSourceAddress(), IpPrefix.MAX_INET_MASK_LENGTH))
                    .matchIPDst(IpPrefix.valueOf(ipPkt.getDestinationAddress(), IpPrefix.MAX_INET_MASK_LENGTH));
            for (Link link : path.links()) {
                PortNumber portNumber = link.src().port();
                //log.info("\n -***- Device Id -***- \n DeviceId:{}", link.src().deviceId());
                TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(portNumber).build();
                FlowRule flowRule = DefaultFlowRule.builder()
                        .forDevice(link.src().deviceId())
                        .withSelector(selector.build())
                        .withPriority(10)
                        .withTreatment(treatment)
                        .withHardTimeout(30)
                        .fromApp(appId)
                        .build();
                flowRuleService.applyFlowRules(flowRule);
            }
            /*ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                    .withSelector(selector.build())
                    .withPriority(10)
                    .withTreatment(treatment)
                    .withFlag(ForwardingObjective.Flag.VERSATILE)
                    .fromApp(appId)
                    .makeTemporary(DROP_RULE_TIMEOUT)
                    .add();
            flowObjectiveService.forward(context.inPacket().receivedFrom().deviceId(), forwardingObjective);*/
            packetOut(context, path.src().port());
        }
    }

    private void installRule(PacketContext context, PortNumber portNumber, HostId srcId, HostId dstId) {
        Ethernet inPkt = context.inPacket().parsed();
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        Host src = hostService.getHost(srcId);
        Host dst = hostService.getHost(dstId);
        if (src == null || dst == null) {
            return;
        } else {
            //constructure ipv4 packet use payload
            IPv4 ipPkt = (IPv4) inPkt.getPayload();
            selector.matchEthType(Ethernet.TYPE_IPV4).matchIPSrc(IpPrefix.valueOf(ipPkt.getSourceAddress(), IpPrefix.MAX_INET_MASK_LENGTH))
                    .matchIPDst(IpPrefix.valueOf(ipPkt.getDestinationAddress(), IpPrefix.MAX_INET_MASK_LENGTH));

            TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(portNumber).build();
            /*ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                    .withSelector(selector.build())
                    .withPriority(10)
                    .withTreatment(treatment)
                    .withFlag(ForwardingObjective.Flag.VERSATILE)
                    .fromApp(appId)
                    .makeTemporary(DROP_RULE_TIMEOUT)
                    .add();
            flowObjectiveService.forward(context.inPacket().receivedFrom().deviceId(), forwardingObjective);*/
            FlowRule flowRule = DefaultFlowRule.builder()
                    .forDevice(context.inPacket().receivedFrom().deviceId())
                    .withSelector(selector.build())
                    .withPriority(10)
                    .withTreatment(treatment)
                    .withHardTimeout(30)
                    .fromApp(appId)
                    .build();
            flowRuleService.applyFlowRules(flowRule);
            packetOut(context, portNumber);
        }
    }

    private void flood(PacketContext context) {
        if (topologyService.isBroadcastPoint(topologyService.currentTopology(), context.inPacket().receivedFrom())) {
            //test the in_port flood, PortNumber.FLOOD!!!!org.onosporject.net
            packetOut(context, PortNumber.FLOOD);
        } else {
            context.block();
        }
    }

    private void packetOut(PacketContext context, PortNumber portNumber) {
        context.treatmentBuilder().setOutput(portNumber);
        context.send();
    }

    private void forwardPacketToDst(PacketContext context, Host dst) {
        TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(dst.location().port()).build();
        OutboundPacket packet = new DefaultOutboundPacket(dst.location().deviceId(), treatment, context.inPacket().unparsed());
        packetService.emit(packet);
        log.info("sending packet:{}", packet);
    }

    private void setUpConnectivity(PacketContext context, HostId srcId, HostId dstId) {
        log.info("This is zhou start connect");
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        Host dst = hostService.getHost(dstId);
        TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(dst.location().port()).build();

        Ethernet inPkt = context.inPacket().parsed();
        IPv4 ipPkt = (IPv4) inPkt.getPayload();
        selector.matchEthType(Ethernet.TYPE_IPV4).matchIPSrc(IpPrefix.valueOf(ipPkt.getSourceAddress(), 32))
                .matchIPDst(IpPrefix.valueOf(ipPkt.getDestinationAddress(), 32));

        Key key;//what's this
        if (srcId.toString().compareTo(dstId.toString()) < 0) {
            key = Key.of(srcId.toString() + dstId.toString(), appId);
        } else {
            key = Key.of(dstId.toString() + srcId.toString(), appId);
        }

        HostToHostIntent intent = (HostToHostIntent) intentService.getIntent(key);
        if (intent != null) {
            if (WITHDRAWN_STATES.contains(intentService.getIntentState(key))) {
                log.info("intent is not null");
                HostToHostIntent hostIntent = HostToHostIntent.builder().appId(appId)
                        .key(key).one(srcId).two(dstId).treatment(treatment).selector(selector.build()).build();
                intentService.submit(hostIntent);
            } else if (intentService.getIntentState(key) == IntentState.FAILED) {
                log.info("intent failed");
                TrafficSelector objectiveSelector = DefaultTrafficSelector.builder().matchEthSrc(srcId.mac())
                        .matchEthDst(dstId.mac()).build();
                TrafficTreatment dropTreatment = DefaultTrafficTreatment.builder().drop().build();

                ForwardingObjective objective = DefaultForwardingObjective.builder()
                        .withSelector(objectiveSelector)
                        .withTreatment(dropTreatment)
                        .fromApp(appId)
                        .withPriority(intent.priority() - 1)
                        .makeTemporary(DROP_RULE_TIMEOUT)
                        .withFlag(ForwardingObjective.Flag.VERSATILE)
                        .add();
                flowObjectiveService.forward(context.outPacket().sendThrough(), objective);
            }
        } else if (intent == null) {
            log.info("intent is null");
            HostToHostIntent hostIntent = HostToHostIntent.builder().appId(appId)
                    .key(key).one(srcId).two(dstId).treatment(treatment).selector(selector.build()).build();
            intentService.submit(hostIntent);
        }
    }
}
