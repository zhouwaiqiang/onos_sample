package org.zhou.ifwd;
import org.onosproject.net.HostId;
import java.util.Map;
/**
 * Created by sdn on 9/20/17.
 */
public interface ForwardingMapService {
    public Map<HostId, HostId> getEndPoints();
}
