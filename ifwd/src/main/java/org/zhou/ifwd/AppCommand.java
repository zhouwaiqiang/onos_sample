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

import org.apache.karaf.shell.commands.Argument;
import org.apache.karaf.shell.commands.Command;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.net.HostId;
import java.util.Map;
/**
 * Sample Apache Karaf CLI command
 */
@Command(scope = "onos", name = "sample",
         description = "Sample Apache Karaf CLI command")
public class AppCommand extends AbstractShellCommand {

    private static final String FMT = "src=%s, dst=%s";

    // the String to hold the optional argument
    @Argument(index = 0, name = "hostId", description = "Host ID of the source", required = false, multiValued = false)
    private String hostId = null;

    //refer to our service
    private ForwardingMapService service;
    //to hold the service's response
    private Map<HostId,HostId> hmap;

    @Override
    protected void execute() {
        //get a referrence to our service
        service = get(ForwardingMapService.class);

        hmap = service.getEndPoints();
        if (hostId != null) {
            HostId host = HostId.hostId(hostId);
            for (Map.Entry<HostId, HostId> el : hmap.entrySet()) {
                if (el.getKey().equals(hostId)) {
                    print(FMT, el.getKey(), el.getValue());
                }
            }
        } else {
            //print everything we have
            for (Map.Entry<HostId, HostId> el : hmap.entrySet()) {
                print(FMT, el.getKey(), el.getValue());
            }
        }
        print("Hello %s", "World");
    }

}
