/*
 * Copyright 2022-present Open Networking Foundation
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
package nctu.winlab.unicastdhcp;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import java.net.URI;
import java.util.Map;

import org.onlab.packet.Ethernet;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.ElementId;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sample Network Configuration Service Application. **/
@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final DhcpConfigListener cfgListener = new DhcpConfigListener();
    private final ConfigFactory<ApplicationId, DhcpConfig> factory = new ConfigFactory<ApplicationId, DhcpConfig>(
            APP_SUBJECT_FACTORY, DhcpConfig.class, "UnicastDhcpConfig") {
        @Override
        public DhcpConfig createConfig() {
            return new DhcpConfig();
        }
    };

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry netcfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;

    private ApplicationId appId;
    private ConnectPoint DhcpServer;
    private DhcpPacketProcessor processor;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.unicastdhcp");
        netcfgService.addListener(cfgListener);
        netcfgService.registerConfigFactory(factory);

        processor = new DhcpPacketProcessor();
        packetService.addProcessor(processor, PacketProcessor.director(3));

        packetService.requestPackets(
                DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
                PacketPriority.REACTIVE, appId);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        netcfgService.removeListener(cfgListener);
        netcfgService.unregisterConfigFactory(factory);
        packetService.removeProcessor(processor);
        processor = null;
        log.info("Stopped");
    }

    private class DhcpPacketProcessor implements PacketProcessor {
        private void createIntent(FilteredConnectPoint ingress, FilteredConnectPoint egress,
                TrafficSelector.Builder selector) {
            PointToPointIntent intent = PointToPointIntent.builder()
                    .appId(appId)
                    .filteredIngressPoint(ingress)
                    .filteredEgressPoint(egress)
                    .priority(50000)
                    .selector(selector.build())
                    .build();

            intentService.submit(intent);

            log.info("Intent {}, port {} => {}, port {} is submitted.",
                    ingress.connectPoint().deviceId(),
                    ingress.connectPoint().port(),
                    egress.connectPoint().deviceId(),
                    egress.connectPoint().port());
        }

        private void processDhcpPacket(PacketContext context) {
            ConnectPoint ingress = DhcpServer;
            ConnectPoint egress = context.inPacket().receivedFrom();

            FilteredConnectPoint dhcp = new FilteredConnectPoint(ingress);
            FilteredConnectPoint host = new FilteredConnectPoint(egress);

            TrafficSelector.Builder srcSelector = DefaultTrafficSelector.builder();
            srcSelector.matchEthSrc(context.inPacket().parsed().getSourceMAC());
            createIntent(host, dhcp, srcSelector);

            TrafficSelector.Builder dstSelector = DefaultTrafficSelector.builder();
            dstSelector.matchEthDst(context.inPacket().parsed().getSourceMAC());
            createIntent(dhcp, host, dstSelector);
        }

        @Override
        public void process(PacketContext context) {
            Ethernet packet = context.inPacket().parsed();
            if (packet == null || packet.getEtherType() != Ethernet.TYPE_IPV4) {
                return;
            }

            processDhcpPacket(context);
        }
    }

    private class DhcpConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
                    && event.configClass().equals(DhcpConfig.class)) {
                DhcpConfig config = netcfgService.getConfig(appId, DhcpConfig.class);
                if (config != null) {
                    String point = config.serverLocation();
                    ElementId device = DeviceId.deviceId(URI.create(point.substring(0, point.indexOf('/'))));
                    PortNumber port = PortNumber.portNumber(point.substring(point.indexOf('/') + 1, point.length()));
                    DhcpServer = new ConnectPoint(device, port);
                    log.info("DHCP server is connected to {}, port {}", DhcpServer.deviceId(), DhcpServer.port());
                }
            }
        }
    }
}
