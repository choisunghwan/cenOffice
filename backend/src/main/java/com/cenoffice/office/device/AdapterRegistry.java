package com.cenoffice.office.device;

import com.cenoffice.common.domain.Enums;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AdapterRegistry {
    private final Map<Enums.AdapterType, DeviceAdapter> adapters = new EnumMap<>(Enums.AdapterType.class);

    public AdapterRegistry(List<DeviceAdapter> list) {
        list.forEach(a -> adapters.put(a.type(), a));
    }

    public DeviceAdapter get(Enums.AdapterType type) throws DeviceAdapter.DeviceException {
        DeviceAdapter a = adapters.get(type);
        if (a == null) throw new DeviceAdapter.DeviceException(type + " 어댑터가 구현되어 있지 않습니다 (Phase 4 예정)");
        return a;
    }
}
